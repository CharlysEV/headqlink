"""Android API-level scan of compiled classes (javap -v output) against api-versions.xml.

Usage: python apiscan.py javap_output.txt api-versions.xml [minSdk]
Reports every java/javax/android/org reference whose API level is above minSdk, with the
referencing class, plus super types and implemented interfaces of each compiled class.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

javap_path, api_path = sys.argv[1], sys.argv[2]
min_sdk = int(sys.argv[3]) if len(sys.argv) > 3 else 16

# ---------------------------------------------------------------- api-versions.xml
classes = {}
root = ET.parse(api_path).getroot()
for c in root.findall("class"):
    name = c.get("name")
    entry = {
        "since": int(c.get("since", "1")),
        "removed": c.get("removed"),
        "methods": {},
        "fields": {},
        "supers": [],
    }
    for m in c.findall("method"):
        entry["methods"][m.get("name")] = int(m.get("since", entry["since"]))
    for f in c.findall("field"):
        entry["fields"][f.get("name")] = int(f.get("since", entry["since"]))
    for e in c.findall("extends"):
        entry["supers"].append(e.get("name"))
    for i in c.findall("implements"):
        entry["supers"].append(i.get("name"))
    classes[name] = entry


def find_member(cls, kind, key, seen=None):
    """Returns (declaring class, since) or None, walking super types."""
    if seen is None:
        seen = set()
    if cls in seen or cls not in classes:
        return None
    seen.add(cls)
    e = classes[cls]
    table = e["methods"] if kind == "m" else e["fields"]
    if key in table:
        return cls, table[key]
    for s in e["supers"]:
        r = find_member(s, kind, key, seen)
        if r:
            return r
    return None


def platform(name):
    return name.startswith(("java/", "javax/", "android/", "org/json", "org/w3c", "org/xml", "dalvik/"))


# ---------------------------------------------------------------- javap -v output
ref_re = re.compile(r"=\s+(Methodref|InterfaceMethodref|Fieldref)\s+\S+\s+//\s+(\S+?)\.(\S+?):(\S+)")
cls_re = re.compile(r"=\s+Class\s+\S+\s+//\s+(\S+)")
this_re = re.compile(r"^(?:public |private |protected |final |abstract |static |synthetic |)*(?:class|interface|enum)\s+(\S+)(?:\s+extends\s+([^\s{]+))?(?:\s+implements\s+([^{]+))?")
current = None
uses = defaultdict(set)  # (kind, cls, member) -> set(user class)
supers = defaultdict(set)
with open(javap_path, encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.rstrip("\n")
        if line.startswith("Classfile "):
            current = None
            continue
        if current is None:
            m = this_re.match(line.strip())
            if m and ("class " in line or "interface " in line):
                current = m.group(1).replace(".", "/")
                if m.group(2):
                    for s in m.group(2).split(","):
                        supers[current].add(s.strip().split("<")[0].replace(".", "/"))
                if m.group(3):
                    for s in m.group(3).split(","):
                        s = s.strip().split("<")[0].replace(".", "/")
                        if s:
                            supers[current].add(s)
                continue
        m = ref_re.search(line)
        if m:
            kind, owner, member, desc = m.groups()
            owner = owner.strip('"')
            member = member.strip('"')
            if owner.startswith("["):
                continue
            k = "f" if kind == "Fieldref" else "m"
            key = f"{member}{desc}" if k == "m" else member
            uses[(k, owner, key)].add(current)
            continue
        m = cls_re.search(line)
        if m:
            name = m.group(1).strip('"').split("<")[0]
            if not name.startswith("["):
                uses[("c", name, "")].add(current)

for user, ss in supers.items():
    for s in ss:
        uses[("c", s, "")].add(user)

# ---------------------------------------------------------------- report
problems = []
unknown = []
platform_refs = 0
for (k, owner, key), users in sorted(uses.items()):
    if not platform(owner):
        continue
    platform_refs += 1
    if owner not in classes:
        unknown.append((k, owner, key, users))
        continue
    csince = classes[owner]["since"]
    if k == "c":
        level = csince
        where = owner
    else:
        found = find_member(owner, k, key)
        if not found:
            unknown.append((k, owner, key, users))
            continue
        decl, msince = found
        level = max(csince, msince)
        where = f"{decl}#{key}"
    if level > min_sdk:
        problems.append((level, k, owner, key, where, users))

print(f"platform references checked: {platform_refs}")
print(f"above minSdk {min_sdk}: {len(problems)}")
for level, k, owner, key, where, users in sorted(problems, key=lambda p: (-p[0], p[2], p[3])):
    short_users = sorted({u.split('$')[0].rsplit('/', 1)[-1] for u in users if u})
    print(f"  API {level:>2}  {owner} {key}  (declared {where})  used by {', '.join(short_users)}")
print(f"not found in api-versions.xml: {len(unknown)}")
for k, owner, key, users in unknown:
    short_users = sorted({u.split('$')[0].rsplit('/', 1)[-1] for u in users if u})
    print(f"  ?? {k} {owner} {key} used by {', '.join(short_users)}")

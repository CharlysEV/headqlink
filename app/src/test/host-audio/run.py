#!/usr/bin/env python3
"""Exercise production audio owners with deterministic Android test doubles, without a device.

Run the Gradle unit tests first to populate the existing Kotlin compiler dependencies.
"""
from pathlib import Path
import subprocess
import os

base = Path(__file__).resolve().parent
repo = base.parents[3]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(group, artifact, version="*"):
    matches = sorted((cache / group / artifact).glob(version + "/*/*.jar"))
    if not matches:
        raise SystemExit("Missing cached compiler dependency; run :app:testGithubDebugUnitTest first")
    return matches[0]


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.22")
annotations = jar("org.jetbrains", "annotations", "13.0")
compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.22"), stdlib,
            jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
            jar("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.22"),
            jar("org.jetbrains.intellij.deps", "trove4j"), annotations]
sources = [repo / line for line in (base / "sources.txt").read_text().splitlines() if line]
sources += sorted((base / "stubs").glob("*.kt")) + [base / "Regression.kt"]
output = repo / "build/host-audio"
output.mkdir(parents=True, exist_ok=True)
target = output / "regression.jar"
subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                "-classpath", str(stdlib) + os.pathsep + str(annotations), "-d", str(target),
                *map(str, sources)], check=True)
subprocess.run(["java", "-cp", str(target) + os.pathsep + str(stdlib),
                "com.andrerinas.openheadunit.decoder.audio.RegressionKt"], check=True, timeout=30)

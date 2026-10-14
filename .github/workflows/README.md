# Continuous Integration

## `android-ci.yml` — Android CI

Runs on every pull request, on pushes to `main` (releases) and to `publico` (the working branch), and on demand
(`workflow_dispatch`). One job, **Tests y versión publicada**:

1. `./gradlew :qdcore:test :qdsim:test :app:testGithubDebugUnitTest` — protocol library, car simulator and app unit
   tests; the HTML/XML reports are uploaded as the `test-reports` artifact.
2. `./gradlew :app:assembleGithubRelease` — the real release build (R8, ARM-only native code, 8 locales), **unsigned**:
   the signing key never leaves the maintainer's PC, so the APK it produces cannot be installed and is not uploaded.
3. A job summary with the APK size and the test counts.

The NDK version is read from `ndkVersion` in `app/build.gradle.kts`, so the two cannot drift apart again.

**This repository is a fork:** GitHub keeps push-triggered workflows off in forks until they are enabled once from
the Actions tab («I understand my workflows, go ahead and enable them»). Manual runs work regardless.

### Branch protection

To make CI enforce quality on `main`, add a branch-protection rule requiring
these status checks:

- `Build (github debug)`
- `Unit tests (github debug)`

### Notes

- **`github` flavor only.** The `playstore` flavor is not built because it
  cannot currently compile: `VpnControl.kt` lives only in the `github` source
  set (`app/src/github/...`) but is imported by shared `main` code. Once that
  split is resolved, add a `playstore` build to the `build` job.
- **`main` may show red** on `Build`/`Unit tests` until the known
  `SettingsFragment.kt:767` smart-cast compile error (issue #659) is fixed.
  This is expected, not a CI misconfiguration.
- **No lint job (yet).** Android Lint is intentionally not run in CI:
  `:app:lintAnalyzeGithubDebug` hangs for many minutes on GitHub-hosted runners
  and produces no report (likely choking on the large generated protobuf
  sources). Run it locally instead — `./gradlew :app:lintGithubDebug` — and
  note that the project sets `lint { abortOnError = false }`, so lint findings
  are advisory by design. Re-adding a CI lint job is worthwhile once the hang
  is diagnosed.
- **Toolchain:** Temurin JDK 21 (Gradle 8.13 rejects Java 24+), plus the pinned
  NDK `27.0.12077973` and CMake `3.22.1` installed via `sdkmanager`. The
  `sdkmanager` install is retried up to three times because its downloads
  occasionally arrive corrupt on GitHub-hosted runners.
- **Action pinning:** all actions (GitHub first-party and third-party alike)
  are pinned to major-version tags. Maintainers who want stricter supply-chain
  hygiene can repin them to full commit SHAs.

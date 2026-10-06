# Alpine same-rootfs control — API34 x86_64

Completed 2026-10-04. **Current candidate passes strict package acceptance with
exactly the official v0.8.0 Alpine 3.24.1 tarball**, in both interpreter and
JIT-requested modes. The earlier official APK failed update and actual OpenSSH
installation in both modes; see `docs/original-release-package-results.md`.
This removes the 3.24.1-versus-3.24.2 rootfs version as the **sole** explanation.
It does not identify a single causal engine commit.

## Artifacts and controlled replacement

All values below are SHA256:

| Artifact | Hash |
| --- | --- |
| Official v0.8.0 APK | `ef3b85214c67cfa7f18f67aa983637562d4d8cf1f99ccf129e0b81615d269030` |
| Exact official Alpine 3.24.1 xz | `3de1738196b080c0658e3413199084413c1cff08923ef0bb751395c81bb1a6e5` |
| Baseline Alpine 3.24.2 xz, before and restored | `478378c84a543cad9094231d6e6c6ecf503cd08ad364eeadef91200b99d2fa3f` |
| Baseline candidate APK, before and restored | `fa3559bf62d847f140d34d9167442a042c405829727b2ec5211b55bb1426d40e` |
| Temporary 3.24.1 control APK | `fb0df6237d90261ed69a62b814b17dedb740c3eb03fece37f3d1c4ece8bf4696` |
| Test APK, baseline/control/restored | `e0e0a73a45308c02e9e8195b8af1cb10f3b180fab2058a09a088ac211110f5e7` |

The official APK was rehashed at
`/home/v/dbx-homes/dev/android17-investigation/A-SH_v0.8.0.apk`.
Its `assets/alpine_3.24.1_aarch64_rootfs.tar.xz` entry was extracted without
repacking: **3,199,792 bytes**, hash verified, and byte-compared against the
control APK entry. The original 3.24.2 asset (**3,203,368 bytes**, mode 0644)
was moved outside `UserlandRootfs/`, preserving metadata; Debian remained
unchanged. Thus the temporary APK had exactly one selectable Alpine asset.

Existing `PackageManagerTest` selects the first matching distro from bundled
assets and installs it with a wrapped `Context.getFilesDir()` into a fresh
`files/package-manager-tests/<unique>/userland`. No test or production source
changes were needed. All uncompressed ZIP entries in the control app match the
baseline except removal of 3.24.2 and addition of 3.24.1. Both ABI `libterm.so`
entries are byte-identical to baseline and contain `Version: 1.5.0`:

- arm64-v8a: `d8c11a2384d814ebbe676ad4f91f239d79e569006423f77a2aa9fc4746c364a8`
- x86_64: `0dba2695c1a0646a6ac0fa0f2643c38b509331082cf8981d979f0949145c5f3b`

Main HEAD remains `1c2fad1d56969c4ee6fd8d92b369bd6c59ebcdb4`; engine HEAD
`570ec1e0cd379592ca5d581a37f990618e9c85bf` / v1.5.0 includes proc-fd fix
`2c332ad47754f8d6cef74375a2f5b5ce5c3ad1b5`. The original engine was
`b51064beb7e7dcbb60ff0a90cbb1b91abed48741` / v1.3.0.

## Build, installation and actual package results

JDK21, SDK `/home/v/dbx-homes/dev/Android/Sdk`, both configured ABIs:

```sh
JAVA_HOME=/home/v/.sdkman/candidates/java/21.0.9-tem \
ANDROID_HOME=/home/v/dbx-homes/dev/Android/Sdk \
ANDROID_SDK_ROOT=/home/v/dbx-homes/dev/Android/Sdk \
./gradlew --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Build succeeded in 4m53s. Existing Java/test compilation was up-to-date.
Matching local signing certificate SHA256
`49dcc8af948ef994c9893e188b72a3ca1859d928701f25b8d6853a88061e4143`
allowed **`adb install -r`**, without uninstall or data clearing. Installed
control app/test hashes were independently checked.

Each run used the sole target `localhost:5561`, isolated ADB5038:

```sh
export ADB_SERVER_SOCKET=tcp:localhost:5038
ADB=/home/v/dbx-homes/dev/Android/Sdk/platform-tools/adb
# Execute once with false, once with true:
"$ADB" -s localhost:5561 shell am instrument -w -r \
  -e class 'io.github.sylirre.terminal.PackageManagerTest#alpineRepositoryAndInstall' \
  -e packageManagerDiagnostics true -e packageManagerJit false \
  io.github.sylirre.terminal.test/androidx.test.runner.AndroidJUnitRunner
```

| Actual phase | Interpreter | JIT requested |
| --- | ---: | ---: |
| DNS default / Google / Cloudflare | 0 / 0 / 0 | 0 / 0 / 0 |
| HTTPS / HTTP BusyBox wget diagnostics | 0 / 0 | 0 / 0 |
| `apk -vv update` | **0** | **0** |
| Actual `apk -vv add openssh` | **0** | **0** |
| Four required installed-package checks | **0** | **0** |
| `ssh -V` and `sshd -G` | **0** | **0** |
| ED25519 generation and public-key parsing | **0** | **0** |
| Guest-written aggregate exit / natural guest exit | **0 / 0** | **0 / 0** |
| JUnit cases / passes / failures / skips | **1 / 1 / 0 / 0** | **1 / 1 / 0 / 0** |

Both roots report Alpine **3.24.1**, apk-tools **3.0.6-r0**, configured stock
v3.24 HTTPS repositories, and 28,557 available packages after refresh. Actual
installation selects OpenSSH **10.3_p1-r1**; execution reports
`OpenSSH_10.3p1, OpenSSL 3.5.7`. Both write `pm-done=done`. Runner times:
73.924s interpreter, 37.663s JIT requested; ADB command exit0 and instrumentation
code -1 in each. Counts are independently reconstructed JUnit XML from raw
AndroidJUnitRunner statuses, **not Gradle connected-test reports**.

## Evidence, scope and restoration

Evidence directory:
`/home/v/dbx-homes/dev/.cache/terminal-setup/alpine-same-rootfs-20261004/`.

Primary files: `baseline.json`, `prepare.log`, `build.log`,
`control-artifacts.json`, `results.json`, both `*-instrumentation.log`,
`*-logcat.log`, `*-junit.xml`, and `*-guest/` (full package log, phase files,
script, root metadata, installed database and public key). Raw logcat records
asset, engine/JIT request, root and natural guest exit. The second logcat
snapshot also includes the earlier run; root identity disambiguates them.

Retained isolated roots, relative to the app data directory:

- Interpreter: `files/package-manager-tests/alpine-1791121792315-58884219529504/userland`
- JIT requested: `files/package-manager-tests/alpine-1791121869808-58961713363883/userland`

No DNS/repository override, TLS/signature bypass, package substitution, native
patch, or old-app compile occurred. Successful wget is transport evidence,
**not proof of apk TLS validation**. The original standalone diagnostic script
was not separately repeated: the existing isolated test already exercises
actual apk update/add and stricter binary/key acceptance without a new launch
hook or source changes. Its wget probes fetch the repository directory, not
that script's APKINDEX URL. No SSH service startup/network connection claimed.

This controls compressed rootfs bytes, not the entire historical execution
stack: original onboarding/current installer and engines differ, repository
contents are live, and no failing syscall trace or single-commit A/B was run.
The result supports the **current runtime plus installer** on the same asset;
it does not isolate proc-fd fix 2c332ad from other upstream changes. No DNS-only
or JIT-only causality is established. JIT requested is not backend attestation;
W^X fallback remains possible. Coverage is Android14/API34 **x86_64**, not a
physical ARM64 phone, Android17/API37 or ARM-to-ARM JIT.

`RESTORE-VERIFIED.json` records original asset set/SHA/modes, restored installed
and output APK hashes, unchanged preferences and absent shared `files/userland`.
Runtime permission grants and package flags match. Android's app lifecycle
`notLaunched` changed true→false due to instrumentation; `stopped=true` is
restored, and all other compared user-state fields match. This expected launch
flag was recorded, not reset by wiping data. No full 34k-entry backup/restore
was repeated; only fresh isolated test roots were added. Existing source-file
hashes and git diff/status matched before adding this document.

The exact 3.24.1 tarball/control APK remains in local cache only; it is absent
from restored `UserlandRootfs/` and the final installed/output baseline APK.
Both apps are stopped, no active instrumentation or Gradle daemon remains.
Emulator **PID3488024** is preserved and responsive on **5561/ADB5038**; host
ADB5037 was untouched. Device/Gradle exclusivity is released. No tools-branch
work, source/version change, host/root install, push or commit occurred.

# Next-agent handoff: package investigation, separated features, ARM validation

Verified 2026-10-06 UTC; final metadata check at 17:26 UTC.

## Executive summary and operating contract

This is the entry point for the next **orchestrator**. The user requested investigation of package-manager failures, bundled native BusyBox/OpenSSH tools, and an additional autocorrect-capable terminal input field; then explicitly required **both features on separate branches**, genuine ARM64 Android validation, and a distrobox space audit. Feature separation and local builds are complete; genuine ARM Android runtime acceptance is **not** complete. Main contains neither feature. No new native-engine patch is needed: its existing pin already includes the relevant upstream proc-fd fix.

The parent must delegate implementation/validation to bounded subagents, not implement itself. Give each stage a small scope, exclusive device/build ownership where needed, and a short results handoff. Read this file and the relevant short reports below rather than giant JSONL transcripts. Saved JSONL outputs survive provider-quota/classifier failures and agent-handle reloads; extract result/tool text only, excluding encrypted thinking. Reloaded handles are not evidence that work completed.

All new tools, installs, outputs and writes must stay under **`/home/v/dbx-homes/dev`**, not host/root. Do not change a phone, cloud resource or unrelated host process without explicit allocation. Auto mode is active: respect denials; never retry an equivalent workaround, bypass/reset controls, or relabel a blocked action as executed. This handoff-writing stage performed read-only verification and wrote **only this document**: no builds, tests, cleanup, VM/ADB operations, commits or pushes.

Throughout this file `DEV=/home/v/dbx-homes/dev`, `MAIN=$DEV/projects/ghostty-android-terminal`, and `CACHE=$DEV/.cache/terminal-setup` are path abbreviations, not a request to dump environment variables. Historical reports describe their own completion-time state; current observations below supersede stale process/resource assertions.

## Git/worktrees and preservation

Verified directly while preparing this document:

| Checkout / branch | HEAD | State |
| --- | --- | --- |
| `MAIN`, `main` | `1c2fad1d56969c4ee6fd8d92b369bd6c59ebcdb4` | Empty index; one tracked modification plus 13 untracked files before this document |
| `$DEV/projects/ghostty-android-terminal-shell-tools`, `feature/android-shell-tools` | `b176ce5be65f68b2da1df6b81224c65437ed1703` | Clean; 44 feature paths |
| `$DEV/projects/ghostty-android-terminal-input-field`, `feature/terminal-input-field` | `60e7599303ff650d6be0f3bc46dfac27fe2bab12` | Clean; seven feature paths |

Both feature commits are based on the same main HEAD, not on each other. No feature was merged onto main and no push is recorded. Do not merge either feature or fix deferred tools issues without the user's request.

Main's tracked change is `app/src/androidTest/java/io/github/sylirre/terminal/TerminalUiTest.java` (**21 additions, five deletions**): real-pointer double/triple-tap timing and selection diagnostics, not production UI. Untracked originals are PackageManagerTest, ProcFdPermissionsTest, two probe assets (including temporary `.H96aXx`), five investigation/provenance documents, `build-proc-fd-permissions-probe.sh`, `diagnose-alpine-network.sh`, and the probe C/README files. This handoff adds a fourteenth untracked file. Do not blanket reset/clean or accidentally discard these regression/evidence files.

Main's native gitlink is `570ec1e0cd379592ca5d581a37f990618e9c85bf` (arm64chroot v1.5.0). Extraction preserved native gitlinks, local.properties, ignored rootfs assets and other local artifacts; no VmImages assets were present in the extraction inventory. Standalone input-field builds omit optional ignored rootfs assets, explaining their smaller APK.

Recovery entry points:

- `$CACHE/branch-extraction/snapshot-20261004T101607Z/HANDOFF.md` — tools extraction, exact 44-path allowlist and manifest. Manifest SHA256 `7bbb7b5c13d759ca38a1b98174de93fa5a02afc191edb5c67aad0f91cb278816`.
- `$CACHE/input-field-extraction/snapshot-20261004T182840Z/HANDOFF.md` — input extraction, exact seven-path allowlist and remaining-main inventory. Manifest SHA256 `282914a346539685065e1a62e96436dee95c8c3c9b92f1cb35fcc8c62e685c4c`.

These retain original patches/files, relocated originals and tool scratch/downloads. Restore selectively; never rerun extraction/finalization or overwrite current work wholesale. Prior agents verified backup manifests; this document did not rehash huge backups. Original app-data archives may contain private keys: keep local, never publish.

## Package investigation: established cause versus limits

User report: official v0.8.0 on a Pixel 11 Pro / Tensor G6 / ARMv8 / Android 17. Alpine showed mirror/index errors and “no such package”; Debian systemd postinst reported `failed to copy permissions from /etc/group to /etc/.#groupbb94d1f511496b4e: Bad file descriptor`.

Official APK SHA256 is `ef3b85214c67cfa7f18f67aa983637562d4d8cf1f99ccf129e0b81615d269030`, retained at `$DEV/android17-investigation/A-SH_v0.8.0.apk`. Release app commit `b6c2b6d5814cb0e1fa0788d4978f027f3144fd7c` pins engine `b51064beb7e7dcbb60ff0a90cbb1b91abed48741` / v1.3.0. Recovered old source was byte-verified against that actual pin, not inferred from a directory name. Both APK ABIs default to arm64chroot with JIT requested; optional chroot-ng is a current supported-ARM path, not the old release default.

Debian's relevant fd is **ordinary, not O_PATH**. systemd's permission-copy helper attempts guest `fchmodat2`, receives ENOSYS, and falls back to legacy chmod on `/proc/<pid>/fd/N`. The old resolver dereferences an absolute **host** fd target as though it were a guest path, double-prefixes the rootfs, and gets ENOENT; systemd maps this proc-fd failure to EBADF. Upstream **`2c332ad47754f8d6cef74375a2f5b5ce5c3ad1b5`** adds 18 lines fixing final-link handling while preserving directory traversal containment. Current `570ec1e` already includes it. Host probes using actual old/current path.c demonstrate the defect; current guest probes exercise the real fallback and containment. No extra native patch was authored.

Alpine's original APK genuinely failed on API34 x86 Android in both interpreter and JIT-requested modes: update exit **2**, actual add exit **1**, unavailable indexes/“no such package”, despite successful DNS and HTTP/HTTPS wget probes. A current candidate using the **exact same official Alpine 3.24.1 tarball**, SHA256 `3de1738196b080c0658e3413199084413c1cff08923ef0bb751395c81bb1a6e5`, passed strict update/install/binary/key checks in both modes with isolated roots. This removes rootfs version as the sole explanation and supports the current **runtime plus installer**; it does not isolate an APK syscall or causal commit. Do not claim a DNS-only/JIT-only bug or treat wget as a TLS-validation oracle.

Assets were restored to Alpine 3.24.2 and Debian from the official OCI image. Debian's existing `policy-rc.d` returns 101 to suppress automatic service startup: installation and `sshd -t` passed, not init/daemon operation. Physical ARM and the user's Android 17 remain untested. “JIT requested” is not proof that the backend actually executed; W^X fallback can select the interpreter.

Short repository reports: [release provenance](release-package-fix-provenance.md), [original APK results](original-release-package-results.md), [same-rootfs control](alpine-same-rootfs-control.md), [Android 17 investigation](android17-release-investigation.md), [package investigation](package-manager-investigation.md). Phone-side diagnostic source is `scripts/diagnose-alpine-network.sh`; executing it on a phone requires an allocated device, not implied permission.

## Evidence/results matrix — do not promote historical passes

| Scope / evidence | Actual result | Boundary |
| --- | --- | --- |
| Integrated tools + field, `/tmp/integrated-runtime-api34/HANDOFF.md` | Latest class runs 5+9+14+18 = **46 passes**; combined sweep **45/46**, tab flake; unchanged UI18 rerun passed | API34 x86 only; not either extracted branch's acceptance |
| Main after tools removal, `$CACHE/main-after-tools-split-validation-20261004T103200Z/HANDOFF.md` | Build passed; combined **40/41**, extra-key `/-` mismatch; unchanged UI18 rerun passed | Main still contained input field then; not current feature-free main |
| `$CACHE/main-procfd-package-final-20261004T110832Z/HANDOFF.md` | ProcFd **2 pass / 1 expected skip**; strict packages **2 pass**; separate native opt-in also skipped on x86 | Historical candidate `fa355…`, before input extraction; strict package run JIT requested |
| `$CACHE/original-api34-20261004/RESULTS.json` | Actual official Alpine package failure in both modes; transport diagnostics succeed | Original second mode reused its root; not fresh independent installation |
| `$CACHE/alpine-same-rootfs-20261004/results.json` | Exact old Alpine rootfs passes current candidate in two modes | Controls asset bytes, not complete historical stack or live repositories |
| Current standalone builds, `$CACHE/input-build-verification/` | Main and input-field app/test builds passed, both ABIs | No final device install/restoration or standalone 14-test runtime acceptance evidence |
| Genuine ARM environment | Prepared image/AVD, **no ARM launch or acceptance** | Android 14 / API34 preparation is not Pixel Android 17 reproduction |

Proc-fd coverage includes named/unlinked fd aliases, expected closed-fd failures, legitimate directory children, and unchanged outside-root sentinel. The probe's old O_DIRECTORY constant was corrected to AArch64 **040000** and rebuilt reproducibly. Correct asset: 14,792 bytes, SHA256 `0f0670b104a3cd41c36fc40d99a77c40844a8e371b9d389d8678a9ebba6044be`. Expected native skip must not count as a pass. Direct runner-status-derived JUnit XML is not a Gradle connected-test report.

API37 x86 failed at the Android framework level: repeated GoldfishMapper/readColorBufferDma/SurfaceFlinger crashes prevented APK installation. It supplies **no package-manager verdict**.

## Current artifacts, independently rehashed

Relative paths in each checkout:

- App: `app/build/outputs/apk/debug/A-SH_v0.8.0.apk`
- Tests: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

| Checkout / artifact | Bytes | SHA256 |
| --- | ---: | --- |
| Main app | 39,277,285 | `58a98d00116f62423fa57a5ab0edfa30b138aa20fee762b5b80402e510326e26` |
| Main test | 5,173,208 | `04ff3f2816efb171d8d129f2cfbe0e6aef6748f6db6f4ce2989eea5177d3fb7f` |
| Input-field app | 6,791,981 | `0d87b77a67ed42949a82f5ba6bda82804dc7b0f62f86dd025d268f190e133660` |
| Input-field test | 5,178,555 | `a9974bfb9f63d75221f1af00557688f4ff1a6a8d86b22de91e1c7fd0e1c4bff5` |

`main-build-offline.log`: success **30m20s**, 68 tasks. `input-build.log`: initial AAPT2 link timeout, failure **28m4s**; `input-build-retry.log`: success **48m50s**, 68 tasks. Preserve failure and retry rather than reporting uninterrupted success. These are newer than historical runtime candidate `fa3559bf62d847f140d34d9167442a042c405829727b2ec5211b55bb1426d40e`.

Same version filename does not mean official signing identity. Official certificate SHA256 `cd8b52234173646773b65641acc7fff11976da610039a612f8b4bd5f5e31b6f6` differs from historical local debug `49dcc8af948ef994c9893e188b72a3ca1859d928701f25b8d6853a88061e4143`. Verify current keys before replacement. Never advise phone uninstall without rootfs/app-data backup. Earlier emulator restoration is proven for earlier artifacts, not these new builds.

## Feature review backlog, not release readiness

**Tools:** official BusyBox 1.37.0, OpenSSH 10.5p1, OpenSSL 3.5.9, locally cross-compiled with NDK28/API29 for two ABIs, 16KiB-aligned PIE executables packaged as lib*.so. Installer-nativeLibraryDir execution and private PATH symlinks avoid app-data exec restrictions. Includes busybox/ssh/scp/sftp/keygen clients, **not sshd**; updates via APK, not a full Termux package manager. Read the tools worktree's `docs/android-shell-tools.md`.

**Update 2026-10-07 (`feature/android-shell-tools`, rebased onto main 5a64155):** the deferred items below are closed on the branch — bundle includes the notices helper and is reproducible (BusyBox rebuilt bit-identical from it), NOTICE reworded, GPL modified-file/date notice added, known_hosts rewrites are atomic + fsync'd + flock-serialized, verifier enforces RELRO/BIND_NOW/16 KiB/API-29 symbol versions/NDK note and input/source drift. Real arm64 (Pixel 8 Pro, Android 17) runs found and fixed a BusyBox awk/diff SIGSEGV (clang `ptr_to_globals` hoisting). SSH key auth, remote commands, SCP (both protocols) and SFTP transfers were verified on the phone against a throwaway sshd. A 16 KiB-page runtime and API 29 hardware remain untested. See that branch's `docs/android-shell-tools.md`. Original note:

Deferred: BusyBox source bundle omits `scripts/android-tools-notices.py` (the helper exists in the branch, but not in the bundle); prior exact bundler edit had a scope denial and remains **not explicitly authorized**. NOTICE's completeness wording overstates evidence. Review modified-file/date GPL notices, known_hosts old-then-new rename crash/concurrency gap, verifier RELRO/NOW and future/input-hash checks. Actual SSH authentication/transfers, real ARM and 16KiB runtime remain untested. Denied `.gitignore`/helper edits were not retried.

**Input field:** real EditText, Aa toggle, Send pastes without an added Enter; Run pastes plus Enter. Generation-fenced, per-session volatile drafts, no IME autoexecution or disk persistence. Normal IME may learn text: not password-safe. Multiline Send can execute in a shell lacking bracketed paste. Seven committed paths include four existing production UI files, a new view, the 14-test class and documentation; read its `docs/input-field.md`. Reviewer found the immediate asynchronous Send-negative assertion can false-pass; production looked correct, test strengthening is deferred. Actual autocorrect/swipe still needs a suitable IME, not merely input-flag assertions.

## ARM preparation and resource/disk audit

Prepared under `$DEV/arm64-test`: `launch-arm64.sh`, `verify-image.py`, `provenance/image-verification.json`, `installed-file-hashes.json`, and isolated `arm64-tcg-api34` AVD (two CPUs, 2048MiB RAM, 480×800, 2GiB userdata). Backend is `$DEV/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-aarch64-headless`. Outer emulator wrapper rejects ARM API≥28 on x86; direct backend TCG is an **unsupported experiment**, not proof of boot.

Official `system-images;android-34;default;arm64-v8a`: repository/package revision 4 versus original source.properties revision 2, preserved honestly. Image metadata: Android 14, build 11228894, ARM64-only, native bridge 0, patch 2023-09-05. Archive 705,115,396 bytes; SHA1 `d9f2011131919abe952814e041f16f317242c3fa`; SHA256 `1447958a4c6747c44390ac5f5f4c894be6d1dfce93868a0385a95c5f0ae4c339`; all 22 installed files verified previously. Keep archive for offline verifier.

`logs/boot-gate-audit-20261005T212320Z.json` records **no trial**: RAM 0.931 GiB versus 3 GiB gate; disk 7.07 GiB versus 8 GiB gate. Proposed 7.25 GiB post-install budget was **not applied**: log/temp caps and allocation bounds remain unproven. Wrapper also treats owned ADB5038 as a port conflict; future boot owner must explicitly resolve verified reuse, never blindly stop it/lower gates.

**Current read-only snapshot differs (2026-10-06):** filesystem free **40,995,205,120 bytes (~38.18 GiB)**; MemAvailable **3,989,348,352 bytes (~3.72 GiB)**, swap used 3,566,137,344 bytes. No active QEMU/Gradle observed; old x86 PID 3488024 no longer exists. Private ADB PID 2400278 is present with SDK adb executable; no ADB commands were issued. These transient values exceed historical numerical gates but do not prove resource reservation, boot or cleanup. Recheck at allocation time; never touch host ADB5037. Planned ARM ports 5564/5565, guest endpoint `127.0.0.1:5565`.

Prior disk audit (not rerun): SDK 12.64 GiB, free 7.05 GiB; allocated, nonoverlapping candidates below. **No deletion approved/performed by this task; cleanup remains a user decision, not now demonstrably necessary.** API37 image/AVD and ARM archive still exist.

| Proposed lower-risk candidate | Prior allocated bytes |
| --- | ---: |
| `$DEV/.cache/debian-rootfs/{rootfs,tools}` failed PRoot scratch | 652,832,768 |
| Main + input `app/.cxx`, `app/build/intermediates` only; KEEP outputs | 317,149,184 |
| `$DEV/.local/android-tools-host/rpms`; KEEP extracted usr/etc/var | 165,773,312 |
| `$CACHE/cmdline-tools.zip`; KEEP installed tools | 164,761,600 |

Total 1,300,516,864 bytes (**1.21 GiB**). Optional failed API37 package `system-images;android-37.0;google_apis;x86_64` (4.32 GiB) plus `$DEV/android17-investigation/avd/android17-release-repro.avd` (1.05 GiB) makes **6.58 GiB** combined. Prefer SDK/AVD managers after explicit allowlist approval; preserve official APK/source/logs. Optional additional 1.08 GiB backup/.build pruning needs separate consent and would invalidate complete manifests.

KEEP platform 36, NDK 28.2, CMake 3.22.1, build-tools 35 (actual AGP dependency) and 36 (redundancy unproven), platform-tools/emulator/ARM image+ZIP, API34 x86 fallback image/AVD (prior 7.23 GiB), Git/sources/rootfs/provenance/APKs/backups. No whole-SDK deletion or root package install. Swap is not MemAvailable; disk cleanup is not a RAM solution. Ask user to close apps if the 3 GiB RAM gate fails.

## Exact next-stage sequence and pending decisions

1. **Orchestrator:** obtain any still-needed cleanup allowlist approval and an exclusive resource/device window. Recheck RAM/disk/ports/process ownership. If cleanup is unnecessary now, say so; do not delete just to follow stale recommendations. Delegate approved cleanup only, record before/after and preserve evidence.
2. **ARM boot subagent:** inspect launcher/gate audit, prove bounded allocation/log caps and verified ADB5038 reuse without bypassing controls. Trial direct backend for **≤60 minutes**, at most one evidence-driven GPU retry swiftshader→swangle, Vulkan disabled. No SELinux disabling or ABI spoofing. Stop only owned processes; publish a small result handoff.
3. Require actual Android `arm64-v8a`, kernel `aarch64`, native bridge0, fingerprint, stable package/activity managers and SurfaceFlinger, screenshot and working app UI. Guest synthesized uname alone is insufficient. Then install the hashed main app/test; verify installed hashes, primaryCpuAbi ARM64 and ELF machine 183 for loaded libterm. No data wipe without backup/allocation.
4. On the accepted ARM target, run `ProcFdPermissionsTest#arm64chrootInterpreter` and `#arm64chrootJitRequested` separately, and `#nativeArmEngineOptIn` with `procFdNativeDiagnostics=true`; run `PackageManagerTest` with `packageManagerDiagnostics=true`, separately selecting `packageManagerJit=false/true`. Capture guest phase codes, natural exits, raw runner counts/skips and sentinel checks, not screen echoes. These tests currently live on main, not feature branches. Inspect current methods before invoking. Respect bounded time/disk budgets for rootfs/package installation.
5. Later allocate the input-field worktree separately: run all 14 `TerminalInputFieldUiTest` cases, then appropriate shell/UI suites and interactive IME checks. Verify signing compatibility, preserve data, and restore the fix-only main APK/test afterward; record final installed hashes. Historical integrated tests cannot substitute. Tools branch acceptance and deferred review require their own requested stage.
6. If bounded ARM backend trial fails, consider an official API29 fallback or Cuttlefish cross-TCG **only after** resolving rootless helper/vhost-vsock dependencies and image HTTP 403 gates. No host modules/cloud outside distrobox. Real phone/ARM host requires explicit user allocation. Android 14 ARM success still would not reproduce the user's Android 17 Tensor environment.

Completed: provenance, bounded x86 package/proc-fd evidence, same-rootfs control, separate clean feature commits, current local builds, ARM preparation, disk audit and this handoff. Pending: genuine ARM usable environment, final-build device acceptance/restoration, standalone input14 tests, optional cleanup decision, interactive IME/SSH/16KiB matrices and deferred feature review. Do not mark these pending items complete from prepared files or historical passes.

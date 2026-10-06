# Official v0.8.0 / Android 17 investigation

Related: [package-manager investigation](package-manager-investigation.md).

## Verified release provenance

The tested candidate is the official upstream release, not a newly built APK:

- Upstream: `sylirre/ghostty-android-terminal`, tag `v0.8.0`.
- Release published: `2026-07-27T19:48:38Z`.
- Tag commit: `b6c2b6d5814cb0e1fa0788d4978f027f3144fd7c`.
- `A-SH_v0.8.0.apk` SHA-256:
  `ef3b85214c67cfa7f18f67aa983637562d4d8cf1f99ccf129e0b81615d269030`,
  matching GitHub's asset digest.
- APK manifest: versionName 0.8.0, versionCode 8000, minSdk 29,
  target/compile SDK 36, INTERNET permission, debuggable.
- Bundled `assets/alpine_3.24.1_aarch64_rootfs.tar.xz` SHA-256:
  `3de1738196b080c0658e3413199084413c1cff08923ef0bb751395c81bb1a6e5`.
- Also bundled: `assets/debian_trixie_aarch64_rootfs.tar.xz`.
- Native submodule pin at this release:
  `native/arm64chroot` = `b51064beb7e7dcbb60ff0a90cbb1b91abed48741`.

The release's `UserlandOptions`, `UserlandRootfs.command`, and `pty_jni.c`
use **arm64chroot on both host ABIs**, normally with `--jit` and a 32 MiB
cache. They do not select chroot-ng on ARM. Current source's optional native
engine must not be confused with the release. Likewise, a harness forcing
arm64chroot does not test the native engine.

## Actual Android 17 device and blocker

The prepared user-local AVD uses SDK package
`system-images;android-37.0;google_apis;x86_64`. Successful device queries on
2026-10-03 returned:

```text
ro.build.version.release=17
ro.build.version.sdk=37
ro.product.cpu.abi=x86_64
ro.build.fingerprint=google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys
ro.dalvik.vm.native.bridge=libndk_translation.so
```

Emulator binary: 37.2.12.0, build 16428233. The owned console/ADB ports were
5562/5563 and the isolated ADB server was `tcp:localhost:5038`. Registration
required `adb connect 127.0.0.1:5563`; only that TCP serial was used afterward.
The existing host server on 5037 was not stopped or reconfigured.

**No Alpine acceptance result was obtained on API37.** With
`-gpu swangle -feature -Vulkan`, the OS repeatedly aborted SurfaceFlinger:

```text
Abort message: 'Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma'
/vendor/lib64/hw/mapper.ranchu.so
GoldfishMapper::readFromHost
surfaceflinger / RegionSamplingThread::threadMain
```

APK installation failed before app execution with
`Failure calling service package: Broken pipe (32)`. UI automation also found
framework services absent during restarts. `sys.boot_completed=1` was not
sufficient evidence of a stable Android framework. Adding
`-feature -GLDMA -feature -GLDMA2` still reproduced the same abort.
A separate `-gpu swiftshader -feature -Vulkan` attempt timed out in a device
query and subsequently exited; it did not yield a usable test device. Its
cause is unconfirmed. These are **test-platform failures, not reproduced
Alpine DNS or package errors**. No app or guest DNS/TLS/signature workaround
was applied.

At handoff, no owned emulator was running and isolated ADB5038 listed no
attached devices. Exclusive device access was released for other validation.
The previous API34 AVD had already been stopped with permission. A new
current-source build does not retroactively validate this release.

Evidence is retained under `$HOME/android17-investigation/` in the dev
container: official APK/rootfs, release/tag/tree JSON, release Java/JNI
sources, `api37-getprop.txt`, `emulator-api37-{retry,nodma,swiftshader}.log`,
`api37-crash-log.txt`, `api37-nodma-crash-log.txt` and bounded system logs.
Earlier blocked classifier calls were not executed; the retries described
above did execute. No Gradle build was run by this investigation.

## What x86 can and cannot cover

This AVD matches the Android API, **not** a Pixel/Tensor ARM CPU or kernel.
The app has an x86_64 libterm, so a normal install selects x86_64 code and
executes an AArch64 guest through its x86 backend. It cannot validate the
arm64-to-arm64 JIT, ARM-specific CPU instructions, ARM ABI behavior, or the
arm64-only native engine. `uname -m` inside the guest is virtualized and is
not proof of host ARM coverage. A native bridge on the AVD is likewise not
proof of actual ARM hardware execution.

An ARM system image cannot use x86 KVM acceleration on this Intel host.
An ARM API37 image has not been booted or validated here; no such coverage is
claimed. Practical faithful coverage requires the user's ARM phone, an
ARM-hosted Android emulator, or an ARM physical-device test service. Before
claiming it, record Android `getprop ro.product.cpu.abi`, Build fingerprint,
SDK, installed APK hash/version, active engine, and JIT setting.

## Runnable Alpine diagnostics on the ARM phone

`scripts/diagnose-alpine-network.sh` runs **inside Alpine**, needs no added
package, and records per-phase exit statuses in a new
`/tmp/alpine-network.XXXXXX` directory. Default operation reads config,
queries DNS, contrasts HTTP/HTTPS transport, refreshes APK indexes, and
simulates package selection. It changes APK index caches, but never edits
resolv.conf, repository URLs, CA policy, signatures or engine settings.
`--install` additionally installs OpenSSH, but only after a successful
repository refresh, then probes installed packages, binary execution and
key generation. Do not share the generated private `hostkey`.

Copy it into the installed release's Alpine root with an explicitly chosen
physical-device serial (not the investigation emulator):

```bash
SERIAL='<physical-phone-adb-serial>'
adb -s "$SERIAL" shell getprop ro.product.cpu.abi
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell getprop ro.build.fingerprint
adb -s "$SERIAL" shell \
  "run-as io.github.sylirre.terminal sh -c 'cat > files/userland/tmp/diagnose-alpine-network.sh'" \
  < scripts/diagnose-alpine-network.sh
```

Then, in the app's **Alpine terminal**, not `adb shell`:

```sh
sh /tmp/diagnose-alpine-network.sh
# Optional actual installation after the baseline:
sh /tmp/diagnose-alpine-network.sh --install
```

Copy the printed directory's log back, replacing the suffix:

```bash
adb -s "$SERIAL" exec-out run-as io.github.sylirre.terminal \
  cat files/userland/tmp/alpine-network.XXXXXX/log.txt > alpine-phone.log
```

The existing release is debuggable; if `run-as` is unavailable on another
build, transfer the script through shared storage instead. Do not root the
phone or disable Android security. Reports may contain DNS/network details;
review before sharing.

Interpretation is deliberately staged:

- Default DNS and explicit 8.8.8.8/1.1.1.1 queries failing does not identify an
  emulator bug by itself. Compare Android's resolver and a different network
  before changing persistent settings. VPN/private-DNS/firewall information
  is important, but the diagnostic does not disable any of them.
- Successful DNS plus HTTP failure points past name resolution. HTTP success
  plus HTTPS/APK failure warrants checking date, CA configuration and the
  exact APK error; BusyBox wget alone is not a certificate-validation oracle.
- `apk update` failure followed by missing package candidates may just mean
  there are no usable repository indexes. The later 'could not find' message
  is not evidence that the package is absent upstream.
- Preserve exact error text and each `.rc`; the script's own process exit
  status is not an acceptance verdict.

For a meaningful ARM comparison, repeat on the same phone/network with JIT
on, then with JIT off in app settings and **a newly opened session**. Do not
change DNS or engine at the same time. Use separate diagnostic directories.
If actual installation is tested twice, use separate clean disposable rootfs
installs or note that the second run sees already-installed packages.

For current-source instrumentation, `PackageManagerTest` already provides
isolated clean roots and a `packageManagerJit=false` comparison. Run the
Alpine method on an ABI-verified ARM device, not merely an AArch64 guest on
x86. The current harness explicitly chooses arm64chroot; native-engine
regressions need a separate explicit engine selector and a runtime-supported
ARM test, not a silent fallback. Add deterministic ARM backend regressions
for DNS-style datagram socket send/recv, poll/ppoll readiness/timeouts,
TCP connect/error behavior, and interpreter/JIT result parity alongside the
online package test. Cross-compiled ELF/hash verification is useful packaging
coverage, but is not execution coverage.

## Diagnostic-script validation

`sh -n scripts/diagnose-alpine-network.sh` passed. Command-mock tests verified
independent nonzero DNS statuses, repository-update status capture, no-install
mode, installation gating after a failed refresh, successful-install phase
creation, and rejection of invalid arguments. The mock test is retained at
`$HOME/android17-investigation/test-diagnostic-script.py`. These checks do
not claim real Alpine/BusyBox, network, ARM or API37 execution.

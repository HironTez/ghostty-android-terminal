# Proc-fd permission-copy regression

`proc-fd-permissions.c` is an original Apache-2.0-licensed, freestanding Linux
AArch64 guest probe for `ProcFdPermissionsTest`. Its static ELF is committed as
`app/src/androidTest/assets/probes/proc-fd-permissions.aarch64`, **only in the test
APK**. There is no production asset/build change and no downloaded dependency.

## Why raw guest syscalls

The v0.8.0 engine gitlink `b51064beb7e7dcbb60ff0a90cbb1b91abed48741` (v1.3.0)
followed the final `/proc/<pid>/fd/N` link to an absolute host path and prefixed
the guest rootfs again. Consequently systemd's legacy `fchmodat` fallback
returned ENOENT for a valid ordinary fd; systemd's mounted-proc error translation
reported EBADF. Current engine `570ec1e0cd379592ca5d581a37f990618e9c85bf` (v1.5.0)
already contains upstream fix `2c332ad47754f8d6cef74375a2f5b5ce5c3ad1b5`.
No further engine patch, syscall-452 implementation or fake-EBADF success is
needed. See [release provenance](../../docs/release-package-fix-provenance.md).

The probe has no libc, CRT, PT_INTERP or PT_DYNAMIC. It directly issues Linux
AArch64 syscalls 452 (`fchmodat2`), 53 (legacy `fchmodat`), 52 (`fchmod`) and 80
(`fstat`), so guest/host libc fallbacks cannot conceal the resolver behavior.
Syscall 452 may retain ENOSYS, return EPERM, or eventually succeed (with a real
mode change); syscall 53 is always tested independently.

Coverage, with mode reset before every alias:

- `/proc/<numeric getpid>/fd/N`, `/proc/self/fd/N`, `/dev/fd/N`;
- ordinary named and unlinked-but-open files: actual fd inode mode 0600 → 0644;
- closed fd: all aliases still return raw `-ENOENT`, fd `fstat` returns `-EBADF`;
  no intervening open can reuse the tested fd;
- directory fd plus a legitimate child: chmod succeeds and changes its mode;
- directory fd plus a symlink to an app-owned **outside-rootfs** sentinel:
  open/chmod must return ENOENT, not leak passthrough containment. All three
  aliases are checked. Java independently checks the sentinel content/mode and
  the named/child modes on the host.

The guest writes a small key/value status file and atomically renames it when
complete. Java reads that file, validates every expected key and the real guest
exit code. No assertion relies on shell stdout, echoed input or PTY markers.

## Rebuild (one compiler/linker worker, no Gradle/device)

From the repository root, using the already installed Android NDK:

```sh
ANDROID_NDK="$HOME/Android/Sdk/ndk/28.2.13676358" \
  scripts/build-proc-fd-permissions-probe.sh
```

The script invokes NDK clang with `-target aarch64-linux-android29 -nostdlib
-static`, explicitly limits LLD to one worker and validates ELF machine 183,
ET_EXEC, absence of interpreter/dynamic linkage and a size below 64 KiB. It uses
only shell, Python 3 and NDK clang. An optional first argument writes to another
output path for reproducibility comparison. No QEMU, AArch64 libc, cross GCC,
root access or host execution of the probe is required. The source's embedded
Linux stat layout has compile-time size/offset assertions.

The asset is host-nonexecutable (0644). Instrumentation reads it from
`InstrumentationRegistry.getInstrumentation().getContext().getAssets()`, copies
it inside its private guest root and sets the **guest** executable mode. The
emulator loads it in-process, exactly like other guest ELF programs; Android's
app-data W^X policy is neither bypassed nor weakened.

## Device run — only after the parent allocates it

Bundle either the existing Debian or Alpine rootfs asset with the APK. The test
prefers Debian, otherwise Alpine; it skips when neither is bundled. It installs
a fresh root under `files/proc-fd-permissions-tests/<distro>-<UUID>/userland`
using the same wrapped-Context pattern as `PackageManagerTest`. It never reads
or writes `AppSettings`, uses `SessionManager`, touches `files/userland`, binds
external storage, downloads packages or rewrites maintainer scripts. Successful
roots are removed without following symlinks; failed roots/reports are retained
at their logged paths for diagnosis.

Once Gradle/device ownership is granted, use the supported JDK (17–21):

```sh
./gradlew --max-workers=1 connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.sylirre.terminal.ProcFdPermissionsTest
```

The default methods explicitly choose **arm64chroot**, not the user's engine
preference: interpreter and JIT requested, on arm64-v8a and x86_64 hosts.
`--jit` can safely fall back to the interpreter; a passing test does not prove
which backend executed. To select an asset, add
`-Pandroid.testInstrumentationRunnerArguments.procFdDistro=alpine` (or `debian`).
The separate native-engine method is skipped unless
`-Pandroid.testInstrumentationRunnerArguments.procFdNativeDiagnostics=true`
is supplied and `TerminalNative.hasChrootNg()` confirms availability. Its result
is not evidence about the emulated engine's resolver.

**Validation boundary:** compilation/static checks are not device execution.
x86_64 Android emulation can prove the guest/common resolver fix but cannot prove
ARM-to-ARM JIT or physical-device policy behavior. Physical ARM64 Android 17
interpreter/JIT validation and actual Debian `systemd-sysusers`/apt reproduction
remain pending until separately allocated and run.

# Testing

All meaningful behavior crosses the JNI boundary (PTY, Ghostty VT state),
so the test suite is **instrumented integration tests** under
`app/src/androidTest/` — they run on a device/emulator against the real
native libraries and the real `/system/bin/sh`.

## Suites

| Class | What it proves |
|---|---|
| `EmulatorVtTest` | Ghostty VT correctness through JNI: plain text, SGR colors/attributes, cursor movement, clear/erase, line wrap, resize, scrollback, alt screen, terminal query responses (DA1), key encoding incl. mode-dependent arrows |
| `ShellSessionTest` | End-to-end PTY: spawns `/system/bin/sh`, runs commands, asserts output reaches the screen; verifies `PATH=<files>/android-bin:/system/bin`, working directory, resize delivery (`stty size`), exit reporting |
| `UserlandSessionTest` | Debian-under-arm64chroot: installs the rootfs from the APK asset, spawns `arm64chroot_main()` + bash login, proves guest ELF binaries run emulated, `--fake-id` uid 0, `--link2symlink` hard links, dpkg/apt, exit propagation. **Skipped** (JUnit assumption) when no *Debian* rootfs asset is bundled, or when a different distro is already installed on the device |
| `UserlandAlpineSessionTest` | Alpine-under-arm64chroot smoke — the suite that boots the userland in CI (the workflow bundles only the small Alpine asset there): install, BusyBox ash login, emulated `uname`/`id`, offline `apk info`. **Skipped** when no Alpine asset is bundled, when a Debian asset is bundled too and nothing is installed yet (local runs leave the shared rootfs dir to `UserlandSessionTest`), or when the installed rootfs is not Alpine |
| `ProcFdPermissionsTest` | Regression for systemd's `/proc/<pid>/fd/N` permission-copy fallback (the resolver bug fixed upstream in arm64chroot): a freestanding AArch64 probe (test-APK asset, [scripts/probes/README.md](../scripts/probes/README.md)) issues raw `fchmodat2`/`fchmodat`/`fchmod`/`fstat` through every fd alias, and Java checks the host modes plus an outside-rootfs sentinel. Both default methods force arm64chroot (interpreter, JIT requested). **Runs by default**: each method installs a fresh rootfs (Debian if bundled, else Alpine — so Alpine in CI) under `files/proc-fd-permissions-tests/<distro>-<UUID>/`, never `files/userland`, and deletes it on pass (kept, with its path logged, on failure). Runner args: `procFdDistro=debian\|alpine` picks the asset; `procFdNativeDiagnostics=true` enables the chroot-ng method on supported ARM64 builds. **Skipped** when neither asset is bundled |
| `PackageManagerTest` | Online package-manager acceptance: Alpine `apk update` + OpenSSH install, Debian strict apt update + `openssh-server` install, with phase status and completion read from guest-written files. **Opt-in** via `packageManagerDiagnostics=true` (otherwise skipped) and needs network; each method installs into its own `files/package-manager-tests/<distro>-<run>/userland`, which is **retained** after the run. Optional args: `packageManagerJit=false`, `packageManagerDns`, `packageManagerPackage`, `packageManagerTrace=true` |
| `VmSessionTest` | The guest machine end to end: `arm64emu_main()` boots EDK2 and a real Linux kernel off the bundled ISO, its serial console reaches a tab through a socketpair, a `getty` on `hvc0` lands on that tab's channel and *not* on the console, a control-channel winsize reaches the guest (`stty size` → `30 100`), and closing a tab leaves the guest unhung-up. One machine per class — booting is minutes, not milliseconds. **Skipped** when no machine images are bundled, which is the normal build |
| `TerminalUiTest` | Activity-level: typing via the view's `InputConnection`, extra-keys toolbar (ESC, CTRL+ combo), tab create/switch/close. Launches with `EXTRA_FORCE_SHELL` so it tests `/system/bin/sh` regardless of rootfs presence (and never sees onboarding) |
| `TerminalInputFieldUiTest` | Espresso + real `EditText` InputConnections for the **Aa** draft field ([input-field.md](input-field.md)): local composing/correction/IME actions, Send (no Enter, proved over a bounded window with a positive control) versus Run, per-tab drafts across recreation, stale-editor and hidden-IME fencing, settings toggles. Launches with `EXTRA_FORCE_SHELL`; saves the input-field/keyboard settings it changes and restores them in `@After` |
| `OnboardingActivityTest` | First-run wizard flows that install nothing: welcome → chooser walk-through, bundled-distro cards, shell-only completion persisting `onboardingCompleted`, setup-only mode starting at the chooser. **Skipped** when a rootfs is already installed (the wizard then finishes immediately by design) |
| `HeadlessApiTest` | The headless ADB API in process: a `LocalSocket` client (this process passes the peer check) drives the real server — `status`, the `hello` server proof, error replies, spawn + attach round trip with RESIZE and the EXIT code, detached spawn → attach → kill, attach stealing, `exec` with separate stdout/stderr and exit code, exact argv/env/cwd, stdin to EOF, SIGNAL; plus the output tap's query-reply suppression and deferred UI resize, the peer policy, and reaping without a listener. `execInUserland` is **skipped** when no usable rootfs is installed |
| `AndroidShellToolsTest` | Bundled BusyBox/OpenSSH clients through the app's real PTY and SELinux domain: installer-directory execution and private aliases, applets/pipelines/archives, encrypted default-HOME keys, `ssh -G` config, known_hosts `-R`/`-H`/`-F`, concurrent known_hosts rewrites (atomic, serialized), SCP/SFTP helper + proxy-shell launch, alias refresh/pruning, and the app-data exec denial. No network or server is needed ([android-shell-tools.md](android-shell-tools.md)) |
| `UserlandDistroTest` | Rootfs asset-name parsing (`<id>_<version>_aarch64_rootfs.tar.xz`) behind the distro chooser |

Polling helper: shell output is asynchronous, so assertions use a small
`waitFor(condition, timeout)` spin instead of fixed sleeps.

## CI

`.github/workflows/ci.yml` runs on every push: one job builds both rootfs
tarballs (Debian via mmdebstrap, Alpine via the minirootfs repackage) and
uploads a debug APK bundling them as an artifact; another runs this whole
suite on an API 34 x86_64 emulator (KVM-accelerated, animations disabled).
The emulator job bundles only the small Alpine rootfs, so
`UserlandAlpineSessionTest` and `ProcFdPermissionsTest` boot the userland in
CI while the Debian `UserlandSessionTest` stays local-only; the opt-in
`PackageManagerTest` skips there. Test reports are uploaded as an
artifact on failure.

## Running

```sh
scripts/setup-emulator.sh        # one-time: create AVD (API 34, x86_64)
scripts/run-emulator.sh          # boot headless emulator, wait for boot
./gradlew connectedDebugAndroidTest
```

Results land in `app/build/reports/androidTests/connected/`.

Notes:

- The emulator needs KVM (`/dev/kvm` writable).
- Tests assume an Android image where `/system/bin/sh` exists — i.e. any
  Android image; the suite does not require root.
- `EmulatorVtTest` drives the `TerminalEmulator` directly (no shell), so
  its assertions are deterministic; only `ShellSessionTest`/`TerminalUiTest`
  depend on shell timing, via the polling helper.
- Espresso needs device animations off for reliable clicks:
  `adb shell settings put global window_animation_scale 0` (and the
  `transition_animation_scale`/`animator_duration_scale` equivalents).
- `UserlandSessionTest` needs the Debian rootfs tarball in `UserlandRootfs/`
  at the repo root **at build time** (gitignored — produce it with
  `scripts/build-debian-rootfs.sh`; `scripts/build-alpine-rootfs.sh` builds
  the Alpine one the onboarding chooser offers). The first Debian test of a
  run pays the one-time rootfs extraction on the device (~15 s on an
  emulator); reruns reuse it until the app's data is cleared. Without the
  tarballs the tests are reported as skipped — this is how CI runs.
- `VmSessionTest` needs the guest machine images in `VmImages/` at the repo
  root **at build time** (gitignored — fetch them with
  `scripts/fetch-vm-images.sh`, which takes the EDK2 firmware from the host's
  `qemu-efi-aarch64` if installed and downloads the Alpine `virt` ISO). They
  add ~95 MB to the APK, and the first run copies them out of it into app
  storage, so leave them out of any build that does not need the VM. Booting a
  whole machine is the slow part: on a KVM-accelerated x86_64 emulator the
  class spends about a minute reaching a login prompt before its first
  assertion, and the tests themselves then run in under a second each.

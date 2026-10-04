# Bundled Android shell tools

The native Android session still launches **`/system/bin/sh`** as the app UID.
It now has BusyBox 1.37.0 and OpenSSH portable 10.5p1 clients (`ssh`, `scp`,
`sftp`, `ssh-keygen`) without needing a Linux rootfs. OpenSSH uses OpenSSL
3.5.9 LTS's **static libcrypto**, not Android's private SSL APIs.

## Execution and packaging

`native/android-tools/prebuilt/{arm64-v8a,x86_64}/` contains five **PIE
executables**, named `libbusybox.so`, `libssh.so`, `libscp.so`, `libsftp.so`,
`libssh-keygen.so` to satisfy Android native-library packaging. Despite their
suffix they are not dlopen libraries. Do not link or load them into `libterm`.

`app/build.gradle` adds that directory to `jniLibs` and sets
`packaging.jniLibs.useLegacyPackaging = true`. The installer extracts the
executable code into `ApplicationInfo.nativeLibraryDir`. Extraction also
applies to the app's existing native libraries, increasing installed size.

`AndroidShellTools.prepare(Context)` verifies all executables and prepares
private, absolute symlink aliases under `files/android-bin`. It replaces stale
managed symlinks atomically after updates, but refuses to overwrite regular
files. The executed inode is installer-managed code, **not a writable copy in
app data**. This is important for targetSdk 29+'s app-data W^X restriction.

Every Android shell caller uses the Context-based `SessionCommand` factory:

```text
PATH=<filesDir>/android-bin:/system/bin
SHELL=/system/bin/sh
HOME=<filesDir>
TMPDIR=<cacheDir>
TERM=xterm-256color
```

The curated BusyBox applets cover file, text, archive and editor commands.
There is deliberately no `sh`/`bash` alias, privileged administration, or
server installation. Shell builtins still take precedence, and explicit
`/system/bin/<command>` remains available. `busybox ash` is an explicit
alternative shell. Rootfs/guest-machine sessions keep their own commands and
PATH; no Android executables are inserted into a guest userland.

The bundle gives no additional Android privileges. Storage access, ownership,
network interfaces, device files, and sandbox boundaries still follow app UID
and SELinux rules.

## Rebuilding

Requirements: Linux x86_64 build machine, **NDK 28.2.13676358 (r28c)**, host C
compiler, GNU make, Perl, patch, Python 3, curl, tar, and standard POSIX build
utilities. No sudo or installation into `/usr` is necessary. Use an already
provisioned distrobox or user-local tools; these scripts never provision host
packages. Dependencies and output staging remain under the repository.

```sh
scripts/fetch-android-tools.sh
ANDROID_NDK="$HOME/Android/Sdk/ndk/28.2.13676358" JOBS=2 \
  scripts/build-android-tools.sh
```

`HOSTCC`, `MAKE`, `ANDROID_NDK`/`ANDROID_NDK_HOME` may be overridden. `ABIS`
may select one ABI for development; the default builds both required ABIs.
`JOBS` is limited to 1 or 2 for low-memory machines. Build logs can be
redirected to a user-local file. The script verifies the pinned NDK revision,
uses API-29 Clang, builds from clean source trees, disables optional OpenSSH
server/helper dependencies, and copies only the selected standalone tools.

`sources.lock` records fixed official release URLs and SHA-256 hashes. The
fetcher verifies both downloaded and cached archives, and fails closed on
checksum mismatch. BusyBox's hash is published by busybox.net; OpenSSH's hash
matches the base64 SHA-256 in its official 10.5 release announcement;
OpenSSL's hash is published in the 3.5.9 GitHub release assets. Hash pinning is
not a claim of independently verified PGP release signatures.

BusyBox starts from allnoconfig plus the committed curated config, then
normalizes dependencies through oldconfig. The exact normalized configs are
retained beside the prebuilts. OpenSSL is PIC/static, with modules, dynamic
providers and engines disabled. Its headers and archive are privately staged.
OpenSSH links static libcrypto and public Bionic/platform zlib. No GPL code is
linked into OpenSSH, OpenSSL or the application's JNI library.

The build requires `/system/bin/linker64`, a nonzero entry point, PIE,
full RELRO, no text relocations, and explicit **16 KiB LOAD alignment**.
`scripts/verify-android-tools.py <ndk> <abi>` validates those properties,
architecture and allowed Android dynamic dependencies, then records output
and source/build-input SHA-256 hashes in each ABI's `build.json`. Builds use a
fixed source epoch/locale and prefix-mapped compiler paths. The manifests make
changes auditable; full bit-for-bit reproducibility should be checked with a
second clean build whenever the pinned toolchain/build recipe changes.

Scratch builds are in `native/android-tools/.build/`, downloads in
`native/android-tools/sources/`. Only the BusyBox source archive is needed in
version control; OpenSSH/OpenSSL archives are verified fetch inputs. Do not
commit scratch/staging trees.

## Android-specific OpenSSH patches

Patches are small local modifications, not a Termux filesystem or preload
layer:

- Bionic's synthetic app passwd entry points to `/data` and `/bin/sh`.
  OpenSSH-local `getpwuid`/`getpwnam` wrappers preserve UID/GID/name and use
  absolute `$HOME` and `/system/bin/sh` **only for the current UID**. This fixes
  default identities, `.ssh/config`, known_hosts, and current-user tilde
  expansion without changing Android's libc.
- The compiled fallback shell is `/system/bin/sh`. `SSH_PROGRAM=ssh` makes
  SCP/SFTP find the bundled SSH through PATH instead of `/usr/bin/ssh` or a
  build-machine staging path. Normal `-S` overrides remain supported.
- `ssh-keygen -R`/`-H` back up known_hosts with rename instead of hard-linking
  (Android apps cannot hard-link app data), and attempt to restore the
  original if the final replacement fails.
- Android's `bzero` is a header-inline macro, not an exported function.
  Configure records that supported API; the explicit-zero fallback retains
  upstream's anti-optimization technique using a **volatile memset function
  pointer** instead of taking the address of the unavailable bzero symbol.
  Core Bionic headers are included before upstream compatibility attribute
  macros to avoid the demonstrated NDK header-order compile error.
- SSHFP queries use Bionic's public `res_query`, never private resolver state.
  This build deliberately never marks an Android SSHFP answer DNSSEC-validated:
  Bionic exposes no resolver DNSSEC controls. Normal hostname resolution,
  known_hosts verification and interactive host-key checks remain supported.
- Upstream key/config permission validation, UID/GID privilege dropping and
  SSH protocol security checks are retained unchanged.
- BusyBox recognizes its exact APK packaging name as a dispatcher as well as
  its normal `busybox` alias, for direct-path diagnostics.

There is no `sshd`, `ssh-agent`, `sftp-server`, askpass app, FIDO helper,
PKCS#11 provider or X11 helper in the initial bundle. The remote machine
supplies its SFTP service. Passwords/passphrases can be entered on the app PTY;
private keys and config belong in `$HOME/.ssh` with normal secure permissions.

## Validation

`AndroidShellToolsTest` runs commands through **the app's real PTY and app
SELinux domain**, not adb shell. It checks installer-directory execution,
private aliases, BusyBox dispatch/applets/pipelines/archive round trips,
`ssh -V`, encrypted default-home key generation, user config loading,
known_hosts rewriting/hashing, RSA/ECDSA/Ed25519 keys, SCP/SFTP's SSH helper and
proxy-shell launch, update refresh, preservation of unrelated regular files,
and the expected denial when executable bytes are copied into app data. `ShellSessionTest` also
uses the actual bundled shell PATH. The tests isolate keys/config/known_hosts
in a fresh temporary HOME rather than touching the user's existing files.

Actual integrated instrumentation on API 34 x86_64 (SELinux
Enforcing, animations off) passed **5/5 `AndroidShellToolsTest` tests** and
**9/9 `ShellSessionTest` tests**, zero skipped. These executed the real app PTY
and app UID: installer-directory BusyBox execution and archive/pipeline checks,
app-data copied-code denial (exit 126), encrypted Ed25519/default HOME keys,
RSA/ECDSA keys, user config, known_hosts rewrite/hash, and SSH/SCP/SFTP's actual
SSH/proxy-shell helper launches. The proxy fixture intentionally fails with
exit 255 after emitting its marker and consuming the client's banner. The
original bare-printf proxy closed stdin too early, racing client SIGPIPE and
cleanup; only that test fixture was corrected, not the native executables.
`ssh -V` is not being counted as an authenticated connection or transfer.

These are historical results from the combined development checkout, not a
fresh build or test of the extracted shell-tools branch. Unrelated input-field
UI changes and test-harness fixes remain in the original checkout and are not
part of this feature branch.

The app APK remains `app/build/outputs/apk/debug/A-SH_v0.8.0.apk`, 49,209,266
bytes, SHA-256 `c132d2f964293bb649c24aeec20ff2d94dd6d570df46bdb3af2fe3dc8d8f84fb`.
The final test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
5,367,310 bytes, SHA-256
`ca08f3dcbb5f70a570f0c23746c5e8d8c42e67b5190b546134d615055ff7269b`.
Constrained normal builds/preflight passed (JDK 21, two workers, 1536MiB heap,
two active processors). Reports/raw instrumentation/logcat/build logs live at
`/tmp/integrated-runtime-api34/`; `native-pty-smoke.log` extracts command and
exit-status diagnostics. `summary-latest.json`/`junit-latest-46.xml` aggregate
the latest class runs, while `summary.json` preserves the prior sweep's failure.
`installed-artifacts-verified.json` confirms pulled-back app/test APK hashes
match the stated artifacts. PackageManager and new proc-fd guest tests were
not run in this window.

Further release acceptance needs a controlled SSH fixture for password/public-key auth,
commands/interactive PTY, DNS, ProxyJump, SCP upload/download including `-O`,
and batch/interactive SFTP. Cover API 29, current target-36 Android, x86_64,
a real arm64 device, and a 16 KiB-page device/emulator. ELF alignment checking
alone is not a substitute for the latter runtime test. Network/fixture tests
must not silently depend on public services.

## Licensing and complete corresponding source

License texts under `native/android-tools/licenses/` are APK assets. See the
root `NOTICE` for upstream and local notices. BusyBox is GPLv2 and is kept in
its own executable/process; OpenSSH uses its upstream permissive licenses,
and OpenSSL 3 uses Apache-2.0.

The APK includes `assets/busybox-corresponding-source.tar.xz` with the
unmodified upstream BusyBox archive, local patch, selected and normalized
configs, pinned fetch/build/verification scripts and license texts. **Complete
corresponding-source/rebuild compliance is not yet established:** the runtime-
validated APK's archive omits `scripts/android-tools-notices.py`, a helper
referenced by the build recipe. Completing that bundle/helper change remains
pending explicit user permission; no denied helper edits were retried during
runtime validation. Passing build preflight is not a GPL-completeness claim.
Extract the current asset for review with:

```sh
unzip terminal.apk assets/busybox-corresponding-source.tar.xz
mkdir busybox-source
tar -xJf assets/busybox-corresponding-source.tar.xz -C busybox-source
```

`scripts/android-tools-notices.py` retains per-file upstream copyright/license
headers (a superset of linked objects) as additional APK license assets.
`scripts/bundle-busybox-source.py` regenerates the deterministic source asset;
the build script runs it automatically. Refresh and redistribute it alongside
any changed prebuilts/config/patches/scripts. The same tarball may also be
published as a release sidecar. OpenSSH/OpenSSL full upstream sources remain
available via the pinned verified fetcher.

## Extraction review handoff

The local `feature/android-shell-tools` branch isolates this work for further
review; extraction does not establish release readiness. No Gradle build,
device command, or new runtime regression test was run during the split.
The original files, both ABI prebuilts, complete current source assets, and
uncommitted build/download caches were hash-verified and retained in a separate
user-local backup before removing the feature from the original checkout.
The OpenSSH/OpenSSL download archives and `.build/` scratch tree are not
committed. The BusyBox upstream archive and current corresponding-source asset
are committed.

Unresolved items (no feature fixes were made during extraction):

- The corresponding-source archive omits `scripts/android-tools-notices.py`.
  Editing the bundling helper remains blocked pending explicit user permission;
  neither that denied edit nor the denied `.gitignore` edit was retried.
- Review BusyBox file/date GPL notices and the source-distribution licensing
  details before release; retaining the existing source asset is not a
  completeness/compliance finding.
- The known_hosts replacement uses two renames and still has a crash gap;
  exception-path restoration does not make the update crash-atomic.
- ELF verification still needs review of full RELRO/NOW enforcement and its
  future/input-drift checks. Existing verifier/preflight success must not be
  read as closing those findings.
- Controlled SSH authentication/transfer fixtures and the API/device matrix
  described above remain required for release acceptance.

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
managed symlinks atomically after updates and prunes managed links an update
no longer provides (and pending links a crash left behind). A managed link
is one that points at a bundled `lib*.so`. It never overwrites a user's file,
or a symlink the user pointed elsewhere, at an alias name: that one alias is
skipped and logged, so a conflict can never stop a shell from opening. A missing or
non-executable bundled tool is still a hard error. The executed inode is installer-managed code, **not a writable copy in
app data**. This is important for targetSdk 29+'s app-data W^X restriction.

Every Android shell caller uses the Context-based `SessionCommand` factory —
UI sessions, `TerminalSession`'s convenience constructor and the headless
API's Android-shell `spawn`/`exec` (so `scripts/gterm exec -- busybox …`
works); headless `env` entries still override these defaults:

```text
PATH=<filesDir>/android-bin:/system/bin
SHELL=/system/bin/sh
HOME=<filesDir>
TMPDIR=<cacheDir>
TERM=xterm-256color
```

The curated BusyBox applets cover file, text, archive and editor commands
(plus `uname`). Because the aliases come before `/system/bin` they shadow
toybox, so the config also enables the options common scripts expect
(`head -1`/`-c`, `echo -n/-e`, `sha256sum -c`, `xargs -I/-P`, `find
-empty/-executable/-newer`, `less -R`, `date -I`, `dd status=`, vi undo,
awk GNU extensions). Not every toybox option is matched; use the explicit
`/system/bin/<command>` path when one is missing.
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
utilities. Python 3.12+ (or 3.11.4+/3.10.12+/3.9.17+/3.8.17+) extracts the
source archives with `tarfile`'s `data` filter; an older Python falls back to an
equivalent explicit check (no absolute or `..` paths, no links escaping the
tree, no device/FIFO members). No sudo or installation into `/usr` is necessary. Use an already
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

`COMPONENTS=busybox` builds only BusyBox and prints its SHA-256 instead of
replacing prebuilts — this is how the shipped BusyBox is rebuilt from the
extracted corresponding-source archive, which carries no OpenSSH/OpenSSL
archives. `scripts/fetch-android-tools.sh [component…]` fetches selected
components.

`sources.lock` records fixed official release URLs and SHA-256 hashes. The
fetcher verifies both downloaded and cached archives, and fails closed on
checksum mismatch. BusyBox's hash is published by busybox.net; OpenSSH's hash
matches the base64 SHA-256 in its official 10.5 release announcement;
OpenSSL's hash is published in the 3.5.9 GitHub release assets. Hash pinning is
not a claim of independently verified PGP release signatures.

BusyBox starts from allnoconfig plus the committed curated config, then
normalizes dependencies through oldconfig. Every `patches/busybox/*.patch` is
applied, in bytewise-sorted order (the Gradle gate and the corresponding-source
bundle treat the whole directory as input). Our `CFLAGS`/`LDFLAGS` reach BusyBox's
make through the **environment**, never as `make CFLAGS=…` arguments: a
command-line variable would override every `CFLAGS +=` in upstream
`Makefile.flags` (`-funsigned-char`, `-fno-builtin-printf`/`-strlen`,
`-ffunction-sections`/`-fdata-sections`, `-Oz`, `-fpie`, warnings,
`CONFIG_EXTRA_CFLAGS`), while the environment value is kept and appended to.
`-DBB_GLOBAL_CONST=` stays: clang otherwise hoists `G.x` loads above
`SET_PTR_TO_GLOBALS` (awk/diff SIGSEGV on arm64). The exact normalized configs are
retained beside the prebuilts. OpenSSL is PIC/static, with modules, dynamic
providers and engines disabled. Its headers and archive are privately staged.
OpenSSH links static libcrypto and public Bionic/platform zlib. No GPL code is
linked into OpenSSH, OpenSSL or the application's JNI library.

### Verification and drift checks

`scripts/verify-android-tools.py [--record] <ndk> <abi>` checks every
executable on every run and refuses anything newer or unknown:

- ET_DYN with a nonzero entry, `/system/bin/linker64`, `DF_1_PIE`, the right
  machine;
- **full RELRO**: a `PT_GNU_RELRO` segment *and* `DF_BIND_NOW` *and*
  `DF_1_NOW`; no `TEXTREL`, `RPATH`/`RUNPATH`; a non-executable `PT_GNU_STACK`;
- **16 KiB pages**: every `PT_LOAD` has `p_align` ≥ 0x4000 (a power of two)
  and `p_offset ≡ p_vaddr (mod 0x4000)`;
- only `libc.so`, `libm.so`, `libdl.so`, `libz.so` as `NEEDED`, and no
  symbol version newer than API 29 (`LIBC`…`LIBC_Q`) — a binary needing
  `LIBC_R`+ would fail to link on a minSdk device;
- the `.note.android.ident` names API 29 and NDK r28c build 13676358.

Default (check) mode then compares `build.json` with the binaries, the
pinned NDK/API, the pinned upstream source versions/hashes from
`sources.lock` (and any archive present in `sources/`), and the current hash
of every build input (lock file, curated config, all scripts, all patches).
An edited patch or script without a rebuild therefore fails, and a manifest
with an unknown `schema` is refused. `--record` is only used by the build
script right after building, and requires all three archives. Gradle's
`verifyAndroidShellTools` (preBuild) repeats the cheap parts — schema,
NDK/API/ABI, output hashes, source pins vs `sources.lock`, input drift, and
the corresponding-source archive/manifest — so a stale tree cannot be
packaged.

Builds use a fixed `SOURCE_DATE_EPOCH`, `LC_ALL=C`/`TZ=UTC` and
prefix-mapped compiler paths. Evidence from 2026-10-07: three clean full
builds of both ABIs produced bit-identical `ssh`, `scp`, `sftp` and
`ssh-keygen` once their inputs were fixed (`scp`/`sftp` also matched the
prebuilts committed before this work); the final `libbusybox.so` came out
identical from a BusyBox-only build, the full build, and a rebuild inside
the extracted corresponding-source archive in another directory, for both
ABIs. This is same-host evidence (one NDK install, one host toolchain), not
an independent second-machine rebuild.

Scratch builds are in `native/android-tools/.build/`, downloads in
`native/android-tools/sources/`; both are git-ignored except the BusyBox
upstream archive, which stays committed as corresponding source.

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
- **known_hosts rewrites are crash-safe and serialized**
  (`0003-atomic-known-hosts.patch`, `android-known-hosts.h`). Upstream backs
  up with `link(file, file.old)` and then one `rename(temp, file)`, so the
  live name never disappears; Android app domains cannot hard-link app data.
  The first Android patch used `rename(file, file.old)` + `rename(temp,
  file)`, which left a window with *no* known_hosts: a crash or kill there
  lost every recorded host key (the next connection would treat a changed
  key as new), concurrent readers saw ENOENT, and a concurrent writer could
  rename the other's fresh file away or fail. Now the backup is a copy
  written to a temp file in the same directory, fsync'd and renamed over
  `.old`; the new contents are fsync'd and atomically renamed over the live
  file; the directory is fsync'd. An `flock()` on `<file>.lock` (released on
  process death) is held across each read-modify-write — `ssh-keygen -R/-H`
  and `UpdateHostKeys` replacement — and around `ssh`'s append of a new host,
  so concurrent writers cannot lose each other's changes. The append falls
  back to unlocked when no lock file can be created next to the target
  (e.g. `UserKnownHostsFile=/dev/null`).
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
  its normal `busybox` alias, for direct-path diagnostics. The patch carries a
  description header and adds a modified-file notice with the date of change
  (2026-10-04) to `libbb/appletlib.c`, as GPLv2 §2(a) requires.

There is no `sshd`, `ssh-agent`, `sftp-server`, askpass app, FIDO helper,
PKCS#11 provider or X11 helper in the initial bundle. The remote machine
supplies its SFTP service. Passwords/passphrases can be entered on the app PTY;
private keys and config belong in `$HOME/.ssh` with normal secure permissions.

## Validation

`AndroidShellToolsTest` runs commands through **the app's real PTY and app
SELinux domain**, not adb shell, in a fresh temporary HOME (never the user's
keys/config/known_hosts). It checks installer-directory execution and private
aliases, BusyBox dispatch/applets/pipelines/archive round trips, common
toybox idioms the aliases shadow (`head -1`, `head -c`, `sha256sum -c`,
`xargs -I`, `find -newer`, `less -R`, `date -I`), that **every applet
enters its main() without crashing**, `ssh -V`, encrypted default-HOME key
generation, `ssh -G` user config, known_hosts `-R`/`-H`/`-F`, two
**concurrent `ssh-keygen -R` writers plus a reader** (the live file must
always be complete and no removal may be lost), RSA/ECDSA keys,
SCP/SFTP's SSH helper and proxy-shell launch, alias refresh/pruning and
non-fatal conflicts, and the app-data copied-code exec denial (126).
`ShellSessionTest` asserts the bundled PATH.

### Results after the review round (2026-10-07; Pixel 8 Pro, Android 17, arm64-v8a)

BusyBox rebuilt with upstream's own flags kept (`-Oz`, `-funsigned-char`,
`-ffunction-sections`, ... — they used to be dropped by command-line
`CFLAGS`), plus the review fixes elsewhere in the app. `ssh`/`ssh-keygen` were
rebuilt too; their bytes changed only by the checkout path OpenSSH embeds in
its CFLAGS string. Animation scales at 0 for the UI suites, restored after.

| Suite | Result |
| --- | --- |
| `AndroidShellToolsTest` | 7/7 passed (every-applet loop, `command -v` resolution) |
| `ShellSessionTest` | 9/9 passed |
| `EmulatorVtTest` | 54/54 passed |
| `HeadlessApiTest` | 18/18 passed (final build, with the `signal` op tests) |
| `UserlandAlpineSessionTest` | 4/4 passed (incl. `guestPathHasNoAndroidTools`) |
| `ProcFdPermissionsTest` | 3/3 passed |
| `TerminalUiTest` | 18/18 passed |
| `TerminalInputFieldUiTest` | 14/14 passed |
| `OnboardingActivityTest` | 2/2 (skips itself: a rootfs is installed) |

`gterm` against the device: `awk`, `diff`, `sed`, `tar` from the rebuilt
BusyBox; a 32 MiB `cat` round trip byte-identical in 2 s; Ctrl-C reaching a
command that ignores a flooded stdin; a bad `--cwd` refused. The OpenSSH
server checks below were not repeated for this build.

### Earlier results (2026-10-07, before d5e482e; Pixel 8 Pro, Android 17, arm64-v8a)

Final debug APKs installed with `install -r` over the existing app (same
debug certificate, data preserved). Instrumentation ran detached on the
device; UI suites with the three animation scales at 0 and restored
afterwards (1.0 / 1.0 / unset).

| Suite | Result |
| --- | --- |
| `AndroidShellToolsTest` | 7/7 passed |
| `ShellSessionTest` | 9/9 passed |
| `EmulatorVtTest` | 54/54 passed |
| `HeadlessApiTest` | 15/15 passed |
| `TerminalUiTest` | 18/18 passed |
| `TerminalInputFieldUiTest` | 14/14 passed |

No test was skipped. App APK SHA-256 `bd0efef9c402c66978fe05219e7e8fff8c374b7413d74b01f06f6abad8b01d3a`
(16,789,298 bytes); test APK `f0f64f8c0b3cc39a1f1178f6d70be633fcbbcc9ad9b2e84a25d54dfd35571ee3`.

The first real-ARM run found that BusyBox `awk` and `diff` crashed (SIGSEGV)
on every invocation: clang treats BusyBox's `const` `ptr_to_globals` as
immutable and hoisted the first `G.x` access above `SET_PTR_TO_GLOBALS`, so
the store went through NULL. The x86_64 emulator runs had not exposed it.
The build now passes `-DBB_GLOBAL_CONST=` (the knob `libbb.h` documents for
this), and the every-applet test guards the class of bug.

Scripted on-device checks against a throwaway OpenSSH 10.5p1 `sshd`
(host-built under `$DEV`, unprivileged, port 2222, own host key and
`authorized_keys`, `internal-sftp`), reached from the phone via
`adb reverse tcp:2222 tcp:2222`, with the client in a scratch HOME under the
app's cache dir and the server's host key pinned in `known_hosts`
(`StrictHostKeyChecking=yes`, `BatchMode=yes`):

- `ssh-keygen -t ed25519` on the phone; public-key login; remote command
  output (`uname -s`, `id -un`) returned; `ssh -tt` got a remote PTY
  (`/dev/pts/N`, `stty size` 24 80);
- 1 MiB random files: `scp` upload (SFTP protocol), `scp -O` upload (legacy
  protocol), `scp` download, `sftp -b` put and get — all SHA-256 checksums
  matched on both ends;
- a wrong pinned host key was refused ("REMOTE HOST IDENTIFICATION HAS
  CHANGED").
- `scripts/gterm exec --type shell -- busybox uname -m` → `aarch64`; the
  headless Android shell resolves `awk`, `ssh`, `scp`, `sftp`, `ssh-keygen`
  from `files/android-bin`. (Without `--type shell`, `exec` targets the
  installed userland, which has its own BusyBox.)
- The license/source-extraction snippet below was run from the app's shell.

Static checks: every ELF in the APK (`libterm.so`, `libarm64emu.so` and the
five tools, both ABIs) has 16 KiB `PT_LOAD` alignment and congruent offsets
(llvm-readelf from NDK r28c); the tools also pass the verifier above.

Not yet covered: a 16 KiB-page device or emulator at runtime (this Pixel
runs 4 KiB pages; static alignment is not a runtime test), API 29 hardware,
password/keyboard-interactive auth, DNS/SSHFP and ProxyJump against a real
fixture, and an interactive session typed through the UI rather than the
PTY test harness / headless API.

## Licensing and corresponding source

License texts under `native/android-tools/licenses/` are APK assets. See the
root `NOTICE` for upstream and local notices. BusyBox is GPL-2.0-only and is
kept in its own executable/process; OpenSSH uses its upstream permissive
licenses (plus BSD-2-Clause local patches), and OpenSSL 3 uses Apache-2.0.
`scripts/android-tools-notices.py` retains per-file upstream
copyright/license headers of OpenSSH and OpenSSL (a superset of the linked
objects) as additional assets.

GPLv2 obligations for the shipped BusyBox, and how they are met:

- **§1 license text** — `assets/BusyBox-LICENSE.txt` in every APK.
- **§2(a) modified files** — the only modified file, `libbb/appletlib.c`,
  gets a prominent notice with the author and the date of change from the
  local patch; the patch file itself starts with the same information.
- **§3(a) accompanying source** — every APK carries
  `assets/busybox-corresponding-source.tar.xz` with the unmodified upstream
  archive, the patch, the curated and both exact normalized configs, the
  per-ABI `build.json`, every fetch/build/verify/notice/bundle script, the
  license texts and `BUILDING-BusyBox.txt` (rebuild instructions). The NDK
  and host build tools are not included; they are generally available
  compilers. No written offer is relied on.

`scripts/bundle-busybox-source.py` regenerates the archive reproducibly
(ustar, fixed order/owner/mtime/modes, explicit xz preset) and the build
script runs it automatically; `--check` rebuilds it in memory and fails on
any difference. Rebuilding BusyBox from the extracted archive with
`COMPONENTS=busybox` reproduced the shipped binaries bit-for-bit for both
ABIs (2026-10-07). Refresh and redistribute the archive alongside any changed
prebuilts/config/patches/scripts (Gradle refuses a stale one). It may also
be published as a release sidecar. OpenSSH/OpenSSL full upstream sources
remain available via the pinned, verified fetcher.

This describes what is shipped and what was checked; it is not a legal
review. There is no in-app license viewer yet: the assets are reachable by
unpacking the APK, e.g. from the app's own shell:

```sh
cd "$TMPDIR" && unzip -o "$(dirname "$(readlink "$(command -v ssh)")")/../../base.apk" \
    assets/busybox-corresponding-source.tar.xz assets/BusyBox-LICENSE.txt
```

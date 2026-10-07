# Release package-failure fix provenance

Related: [Android 17 release investigation](android17-release-investigation.md),
[package-manager investigation](package-manager-investigation.md).
Research only; no builds, device runs, engine changes or commits.

## Original APK → actual release tree → old engine

The retained official `A-SH_v0.8.0.apk` was independently rehashed:
`ef3b85214c67cfa7f18f67aa983637562d4d8cf1f99ccf129e0b81615d269030`.
This equals the asset digest in the retained and freshly fetched official
[release API](https://api.github.com/repos/sylirre/ghostty-android-terminal/releases/tags/v0.8.0).
The release notes explicitly announce arm64chroot v1.3.0.

At app release commit **`b6c2b6d5814cb0e1fa0788d4978f027f3144fd7c`**:

- `.gitmodules` (blob `25cbf72b3db91576c0332b280ce611edb45eacc0`) identifies
  `native/arm64chroot` as `https://github.com/sylirre/arm64emu-user`.
- `native` tree is `ec7382063b279db45ded3c6bd5330d6afc2ca2c7`; its
  `arm64chroot` entry is mode **160000**, commit
  **`b51064beb7e7dcbb60ff0a90cbb1b91abed48741`**. Local Git objects and the
  [official native-tree API](https://api.github.com/repos/sylirre/ghostty-android-terminal/git/trees/ec7382063b279db45ded3c6bd5330d6afc2ca2c7)
  agree. This is not an inference from a recovery-directory name.
- That engine commit is titled `Version 1.3.0`, tagged `v1.3.0`, and defines
  `PROGRAM_VERSION "1.3.0"` in `src/main.c`.
- Release `app/src/main/cpp/CMakeLists.txt` compiles the submodule's Makefile
  C source list, including `src/path.c`, into a whole-archive-linked
  `libterm.so`; `pty_jni.c:119` enters `arm64chroot_main` in-process.
  There is no alternative downloaded engine/prebuilt selection in this path.
- Release `.github/workflows/ci.yml` checks out recursive submodules, builds
  `:app:assembleDebug`, and attaches that build artifact to tag releases.

Both APK ABI libraries contain `Version: 1.3.0` and `arm64chroot_main`.
Extracted-library SHA-256 values:

```text
arm64-v8a 70978f2ee4837792497a3ab51fa3487e98037f2717444c0d7468f213e95ed502
x86_64    bc4967ea66a3630f50a43444c4f5fd99d96a4ba837f37863c6eec8c3750956fd
```

The recovered local copy `arm64chroot-v1.3.0/` files
`src/path.c`, `src/sys_file.c`, `src/syscall.c`, `src/sys_net.c`, and
`Makefile` each match **byte-for-byte** the actual release engine pin.
Old `src/path.c` Git blob is `35043a77bdb9cac3df30c5cce5f56654d2d4d3b0`;
its SHA-256 is `fb5a139fbbf1f59b2199a5f637400a57745d407f152ea729b703a50ce00664ea`.

## Exact fix relationship and Debian call chain

Upstream fix **`2c332ad47754f8d6cef74375a2f5b5ce5c3ad1b5`**, dated July 30,
2026, adds exactly 18 lines to that old path blob, producing blob
`04fc8ef82b6ede4b5c888420c10fa3f959fd0e62`.
[Exact upstream patch](https://github.com/sylirre/arm64emu-user/commit/2c332ad47754f8d6cef74375a2f5b5ce5c3ad1b5).

Verified Git ancestry: release pin is an ancestor of the fix; the fix is
**not** an ancestor of the release pin; the fix **is** an ancestor of the
app gitlink at the time of this research,
**`570ec1e0cd379592ca5d581a37f990618e9c85bf`** (`Version 1.5.0`), and of the
later pin `313e1d9` (HironTez/arm64emu-user fork) built on top of it. The
first available containing engine tag is `v1.4.0`. At `570ec1e` the guard was
in `src/path.c:849–869`.

The separately investigated systemd v257.13 chain is:
`write_temporary_group` → `copy_rights_with_fallback` →
`fchmod_and_chown_with_fallback` → `fchmod_opath` → unsupported guest syscall
452 → legacy chmod of `/proc/<pid>/fd/N` via guest syscall 53 → `resolve_at`
→ old `path_resolve`. The old resolver splices the absolute **host** fd-link
target as a guest path and double-prefixes the rootfs. Host ENOENT is mapped
by systemd's `proc_fd_enoent_errno()` to EBADF when `/proc` is mounted.
The fix preserves final proc-fd links for host resolution; nonfinal targets
are translated back to guest paths to retain containment. This is not fake
EBADF success or an unrelated Bionic workaround.

## Scope of justified claims

**Justified:** the official release source pins the buggy engine tested by
the resolver investigation; current source already contains its upstream fix.
No additional engine patch is warranted. The existing isolated old/current
resolver probe supports correction of this specific permission-copy path.

**Still conditional:** “the current APK fixes the user's Debian installation
on Android 17.” That requires the runtime agent's original/candidate APK
comparison and actual sysusers/apt execution, with ARM ABI and JIT coverage.
Version strings corroborate packaging but are not reproducible-build
attestation; no byte-identical native rebuild or binary disassembly was done.

## Alpine: separate, unproven diagnosis

Post-release networking fixes include `61129c053ca601731147baba0509b178f2114d22`
(MSG_TRUNC recvfrom copy bound), `dd30cc745c26774dee1baa42606d9f9e935f697a`
(recvmsg source-address truncation), and
`516c8d931822b502e0414a08edd69aef692fe788` (cmsghdr guest/host conversion),
all present by engine v1.4.0. Commit messages and diffs identify specific
hazards, not evidence that Alpine's mirror/DNS failure exercises them.
The cmsghdr mismatch and `f2abf18f7458ddedb4c9a0dcc04e204c6d0a589c`
(socket-timeout timeval conversion) principally address **32-bit hosts**;
the release hosts are arm64-v8a/x86_64. No same-cause or DNS-ABI-fix claim is
supported. The proc-fd fix also mentions apk O_TMPFILE publishing, but that
is a later file-publication operation, not proof of mirror-resolution repair.

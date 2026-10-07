# Original v0.8.0 Alpine package result — API34 x86_64

Completed 2026-10-04. **The actual official APK reproduces an Alpine package
failure**, with both JIT requested and JIT disabled. This was not a rebuild or
a current-test-APK run against old JNI. DNS and HTTP/HTTPS transport succeeded;
APK index refresh and OpenSSH installation failed.

Related provenance: [release-package-fix-provenance.md](release-package-fix-provenance.md),
[Android 17 investigation](android17-release-investigation.md).

## Exact artifacts and device

- Original `A-SH_v0.8.0.apk`, SHA256
  `ef3b85214c67cfa7f18f67aa983637562d4d8cf1f99ccf129e0b81615d269030`.
  Rehashed locally and again from the installed `base.apk`.
- Manifest: app ID `io.github.sylirre.terminal`, version 0.8.0 / 8000,
  minSdk 29, targetSdk 36, **debuggable**. Verified APK signing-certificate
  SHA256 `cd8b52234173646773b65641acc7fff11976da610039a612f8b4bd5f5e31b6f6`.
- Official app commit `b6c2b6d5814cb0e1fa0788d4978f027f3144fd7c` pins
  engine `b51064beb7e7dcbb60ff0a90cbb1b91abed48741` / v1.3.0.
  Both embedded libraries match the hashes in the provenance document and
  contain `Version: 1.3.0`; this is corroboration, not a reproducible build.
- Original bundled `alpine_3.24.1_aarch64_rootfs.tar.xz`, SHA256
  `3de1738196b080c0658e3413199084413c1cff08923ef0bb751395c81bb1a6e5`.
  Installed **through the original onboarding UI**, from clean app state;
  guest `/etc/alpine-release` reports 3.24.1. BusyBox v1.37.0;
  apk-tools 3.0.6-r0, compiled for AArch64.
- A single disposable emulator on an isolated ADB server, sole command
  target. Android 14 / API34 / x86_64, fingerprint
  `google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.036.A4/12096271:user/release-keys`.
  Guest AArch64 and synthesized uname are not evidence of host ARM execution.

The candidate is a different APK **and a different rootfs**, not a controlled
engine-only comparison:

| Artifact | SHA256 |
| --- | --- |
| Candidate main APK | `fa3559bf62d847f140d34d9167442a042c405829727b2ec5211b55bb1426d40e` |
| Candidate test APK | `e0e0a73a45308c02e9e8195b8af1cb10f3b180fab2058a09a088ac211110f5e7` |
| Candidate bundled Alpine **3.24.2** rootfs | `478378c84a543cad9094231d6e6c6ecf503cd08ad364eeadef91200b99d2fa3f` |

The candidate was built with the arm64chroot engine then pinned at
`570ec1e0cd379592ca5d581a37f990618e9c85bf` / v1.5.0 (since superseded by
`313e1d9`, built on top of it), which already contains proc-fd fix
`2c332ad47754f8d6cef74375a2f5b5ce5c3ad1b5`.
Earlier strict candidate validation on this same emulator passed Alpine
update/install/installed/binaries/key generation, all required phase codes 0.
That result is preserved, not rerun or relabelled as original-release coverage.

## Actual guest phase results

The unchanged `scripts/diagnose-alpine-network.sh --install` was injected with
`run-as` into the onboarded guest `/tmp`, then launched using Android keyboard
input **inside the app PTY**. A guest wrapper wrote started/completed markers
and exit-code files. Results were copied with `exec-out run-as`; shell-command
echo on the terminal screen is not the evidence.

The script gates installation on successful update. Because update failed, a
second guest wrapper explicitly executed `busybox timeout 180 apk -vv add
openssh` and recorded its actual status, followed by the installed-package
check. No DNS, repository, CA/TLS, signature or engine-source changes were made.

| Phase | Original, JIT requested | Original, interpreter |
| --- | ---: | ---: |
| DNS default (8.8.8.8) | 0 | 0 |
| DNS explicit 8.8.8.8 | 0 | 0 |
| DNS explicit 1.1.1.1 | 0 | 0 |
| BusyBox wget HTTPS index → `/dev/null` | 0 / HTTP 200 | 0 / HTTP 200 |
| BusyBox wget HTTP index → `/dev/null` | 0 / HTTP 200 | 0 / HTTP 200 |
| `apk -vv update` | **2** | **2** |
| `apk -vv add --simulate openssh` | **1** | **1** |
| Actual `apk -vv add openssh` | **1** | **1** |
| `apk info -e openssh openssh-client-default openssh-server openssh-keygen` | **4** | **4** |

Both updates report:

```text
WARNING: updating and opening https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz: No such file or directory
WARNING: updating and opening https://dl-cdn.alpinelinux.org/alpine/v3.24/community/aarch64/APKINDEX.tar.gz: No such file or directory
2 unavailable, 0 stale; 16 distinct packages available
```

Both actual installation attempts end with:

```text
ERROR: unable to select packages:
  openssh (no such package):
    required by: world[openssh]
```

The JIT-requested direct add also reports `I/O error` for the main index and
`No such file or directory` for community. Interpreter direct add reports
`No such file or directory` for both. Preserve that difference rather than
silently treating every error string as identical.

Default original preferences omit `userland_jit`; the release Java default
and captured UI switch are true. After the failure, **Use emulator JIT** was
turned off through the original Settings UI, the resulting preference
`userland_jit=false` was copied, and a **new sh:2 tab** was opened for the
interpreter run. It used the same root after the failed package operations,
not an independently fresh second install. JIT requested is not attestation
that the W^X-aware native backend actually executed rather than falling back.
Both diagnostic-script process statuses are 0 by design; that is **not** a
package acceptance pass. No OpenSSH binaries/key-generation acceptance is
claimed for the original APK because installation failed.

## Cause boundaries

- This supplies the previously missing **actual original-APK Alpine failure**
  on working API34 x86_64. It is past the tested DNS and wget transport paths;
  missing package candidates follow unusable APK indexes, not evidence that
  OpenSSH is absent upstream.
- It does **not** reproduce a carrier/private-DNS failure. BusyBox wget is
  not a certificate-validation oracle, and successful wget does not prove
  APK's own fetch/cache/publication implementation works.
- The old resolver defect and upstream fix (including the APK O_TMPFILE
  publication relevance) are established separately by source/proc-fd
  evidence. These package logs contain no syscall trace: **they do not alone
  prove the precise failing APK syscall or causally isolate that fix**.
- Both original modes fail, so a JIT-only diagnosis is unsupported. The
  candidate/rootfs pair passes, but APK tools/rootfs and engine versions both
  differ. Do not assign the difference to speculative DNS/networking fixes.
- No ARM64 Android 17, ARM-to-ARM JIT, physical phone, API37, or native-engine
  runtime coverage is claimed. The phone Alpine script remains useful for
  the ARM/network-specific gap. Original Debian was not rerun in this bounded
  window; its separate resolver/sysusers evidence remains separate.
- No production/source patch is warranted by this run. No Gradle, source
  build, old/current Java-JNI instrumentation mixture, tools-branch change,
  root/host installation, push, or commit occurred.

## Evidence and recovery

Evidence was kept in a local directory outside the repo
(`original-api34-20261004/`). Primary files:

- `RESULTS.json`, `original-embedded.json`, `original-manifest.txt`,
  `original-signature.txt`, `candidate-signature.txt`, `device-getprop.txt`.
- `guest/alpine-network.NBDFiN/log.txt` and sibling `.rc` files:
  original JIT-requested diagnostic, UTC 11:59.
- `guest/original-add.log`, `original-add.rc`, `original-installed.rc`:
  actual first add and installed check.
- `guest/alpine-network.oamPka/log.txt` and sibling `.rc` files:
  original interpreter diagnostic, UTC 12:06.
- `guest/interpreter-add.log`, `interpreter-add.rc`,
  `interpreter-installed.rc`; wrappers and guest completion markers.
- `original-settings.xml`, `interpreter-settings.xml`, UI XML captures,
  `device-commands.log`, and `official-runtime-data.tar` retain exact setup.
- `BACKUP-VERIFIED.txt`, `restore-final.log`, `RESTORE-VERIFIED.txt`, backup
  and restored per-file manifests, and empty `*.restore-differences.json`.

The previous candidate app/test APKs and **all app/test data** were backed up
before any uninstall; APK hashes and archive readability were verified. The
signing certificates differ (candidate certificate SHA256
`49dcc8af948ef994c9893e188b72a3ca1859d928701f25b8d6853a88061e4143`),
so clean uninstall/install was required. Onboarding only affected the disposable
emulator, never another device or host data.

Restoration is complete: installed candidate and test APK hashes match the
table; **34,480 app archive entries and 3 test archive entries** match their
backups by path/type/file SHA256/mode/symlink target. Android assigns the new
app UIDs normally. Non-root tar restoration initially cleared the setgid bit
on the two empty cache directories in each package; these empty directories
were removed and recreated by reinstalling the **same verified APKs with
`install -r`**, restoring Android's correct app-cache group/setgid modes.
Final full verification has no differences. Runtime permission grant lines
match before/after; system animation scales remain 0. Backup archives may
contain private candidate data/keys: keep them local, do not publish them.

Candidate apps are stopped, no instrumentation is active, shared
`files/userland` is again absent, and retained candidate isolated package roots
are restored.

For reproducing the original workflow, use an independently backed-up,
explicitly disposable target; do not paste uninstall commands onto a phone:

```sh
ADB=$ANDROID_HOME/platform-tools/adb
# After original APK install and Alpine onboarding:
"$ADB" -s <serial> shell \
  "run-as io.github.sylirre.terminal sh -c 'cat > files/userland/tmp/diagnose-alpine-network.sh'" \
  < scripts/diagnose-alpine-network.sh
# Within its Alpine PTY (run-as only transfers files):
# sh /tmp/diagnose-alpine-network.sh --install
# busybox timeout 180 apk -vv add openssh > /tmp/direct-add.log 2>&1
# printf '%s\n' "$?" > /tmp/direct-add.rc
"$ADB" -s <serial> exec-out run-as io.github.sylirre.terminal \
  cat files/userland/tmp/alpine-network.NBDFiN/log.txt
```

Previous strict candidate evidence was kept locally
(`main-procfd-package-final-20261004T110832Z/`, with `HANDOFF.md` and
`package-phase-results.json`); it is not in the repo.

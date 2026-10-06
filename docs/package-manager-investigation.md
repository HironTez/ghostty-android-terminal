# Package-manager runtime investigation

Related: [test-suite overview](testing.md), [userland architecture](architecture.md).

## Scope and environment

Investigation performed 2026-10-02 UTC against the real Android API 34 x86_64
emulator, running AArch64 guests inside the app's linked `arm64chroot` engine.
The native pin is `570ec1e0cd379592ca5d581a37f990618e9c85bf`.
The x86_64 emulator cannot validate the arm64-only `chroot-ng` engine or the
arm64-to-arm64 JIT. No native submodule modifications or patches were needed.

The latest Android emulator initially crashed with a real host RenderThread
SIGSEGV (confirmed from the host journal, not an OOM). Boot succeeded using
`-gpu swangle -feature -Vulkan`. The test device is `emulator-5560` on the
isolated adb server `tcp:localhost:5038`; the preexisting port-5037 server was
not stopped or reconfigured. Do not also use the duplicate TCP endpoint
`127.0.0.1:5561` for the same emulator.

App launch and APK installation succeeded; animations were disabled. Each
online test extracts into its own unique
`files/package-manager-tests/<distro>-<run>/userland`, leaving the user's
`files/userland`, installed distro and settings untouched. Diagnostic roots
and logs are intentionally retained and can consume substantial device space.

## Assets and provenance

- Alpine: bundled `alpine_3.24.2_aarch64_rootfs.tar.xz`, apk-tools 3.0.8-r0,
  original HTTPS `/alpine/v3.24/main` and `/alpine/v3.24/community` repositories,
  installed CA certificate bundle and intact `cert.pem` symlink.
- Debian: bundled `debian_trixie_aarch64_rootfs.tar.xz` (Debian 13), made from the
  verified original official Debian OCI layer SHA-256
  `ccd9dba13ae33c050c13176f84743269a9457dcb0cfd091e368aa5131dd9e9c7`.
  This was **not** the earlier partially configured PRoot tree. It has bash,
  dpkg, apt and Debian archive keys; it initially lacks ca-certificates and
  locales. Its HTTP repositories use signed InRelease metadata; no signature
  or certificate check was disabled. No blocked PRoot download was retried.

Important qualification: the original Debian OCI layer includes
`/usr/sbin/policy-rc.d` returning 101, deliberately suppressing daemon starts
in container images. The diagnostic does not install such a policy or alter
maintainer scripts. The successful install therefore demonstrates package
configuration, not service startup under a real init system. The log includes
`policy-rc.d denied execution of start`, which is expected for this asset.

## Stock runtime results

No production workaround was applied: default fake-root identity, stock
repositories, installer-provided `8.8.8.8`/`1.1.1.1` resolver configuration,
original package-manager signatures and normal apt sandbox configuration.

| Mode | Test | Result |
|---|---|---|
| JIT enabled, default | Alpine `apk update` + `apk add openssh` | PASS, 22.097 s |
| JIT enabled, default | Debian strict apt update + download + `openssh-server` install | PASS, 189.013 s |
| JIT disabled, interpreter | Both online tests, freshly extracted roots | PASS, 829.586 s total |

Alpine fetched HTTPS repository indexes (28,553 available packages), installed
10 packages including OpenSSH 10.3_p1-r1, and executed BusyBox triggers.
Guest DNS queries against both public resolvers and cleartext/TLS downloads
also succeeded. The proposed DNS failure was **not reproduced** on this
network; Android's resolver returned different valid CDN addresses, not an
error. Hardcoded public DNS still has portability limitations on other
networks, but these results do not justify attributing this run to DNS.

Debian fetched 10.1 MB of repository metadata and 8.6 MB of archives, then
installed/configured 26 packages including systemd and OpenSSH
`1:10.0p1-7+deb13u4`. The real OpenSSH postinst generated RSA, ECDSA and Ed25519
host keys and created the `sshd` account. Requested OpenSSH packages had `ii`
status and `dpkg --audit` printed no incomplete packages. A separate Ed25519
key-generation probe also succeeded. No apt sandbox override, syscall
workaround, ownership change workaround or ARM instruction patch was needed.

### Other observed limitations (not installation blockers here)

The baseline intentionally probes the fake-root model:

- `chown 123:456` returns success, but stat continues to report guest root
  ownership. Persistent arbitrary guest ownership is not provided by this
  configuration.
- Reading a mode-000 file as guest root returns permission denied. Fake-root
  identity is not a complete Linux DAC override.

Neither blocked these installations. A successful package install does not
prove that every privileged daemon feature, ownership-sensitive workload,
restricted file access, or network works on every Android device. These
observations are retained rather than papered over with speculative native
changes.

## Regression coverage

`app/src/androidTest/java/io/github/sylirre/terminal/PackageManagerTest.java`
is opt-in, online coverage; ordinary CI runs skip it unless explicitly enabled.
The generated script records each phase's exit status in guest-written files.
Completion also comes from a guest-written file, so echoed PTY commands cannot
produce a false-positive pass. Failure messages and logcat include a bounded
log tail; the entire log remains available via `run-as`.

The strengthened acceptance checks cover:

- Alpine repository refresh, real install, all required OpenSSH subpackages,
  client binary execution, server configuration parsing and Ed25519 keygen.
- Debian strict update, archive downloads, unpack/configure, empty dpkg audit
  output (audit can exit zero while reporting problems), exact requested
  package status `install ok installed`, keygen, and (for openssh-server)
  `sshd -t`, all three postinst-generated host keys and the sshd account.

The existing `UserlandSessionTest.aptIsFunctional` remains only an offline
smoke test: its `apt ... | head` status belongs to `head`, not necessarily apt,
and its output marker can match echoed input. An attempted fix to that
preexisting file was blocked by auto-mode scope and **was not applied or
retried**. Do not treat that smoke test as package-install acceptance.

## Reproduction

Build with JDK 17–21 and constrained workers on this host:

```bash
./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Use the isolated adb server and one explicit serial for every command:

```bash
export ADB_SERVER_SOCKET=tcp:localhost:5038
ADB="$HOME/Android/Sdk/platform-tools/adb"
"$ADB" -s emulator-5560 install -r app/build/outputs/apk/debug/A-SH_v0.8.0.apk
"$ADB" -s emulator-5560 install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5560 shell am instrument -w -r \
  -e class io.github.sylirre.terminal.PackageManagerTest \
  -e packageManagerDiagnostics true \
  io.github.sylirre.terminal.test/androidx.test.runner.AndroidJUnitRunner
```

Add `-e packageManagerJit false` for interpreter comparison. Select one method
with `-e class 'io.github.sylirre.terminal.PackageManagerTest#alpineRepositoryAndInstall'`
or `#debianOpenSshInstall`. Optional diagnosis-only arguments:
`packageManagerDns=10.0.2.3` for the emulator DNS comparison,
`packageManagerTrace=true` for potentially large native traces, and
`packageManagerPackage=openssh-client` for the Debian client-only install.
The ordinary run does **not** use these overrides.

Inspect retained logs:

```bash
"$ADB" -s emulator-5560 shell run-as io.github.sylirre.terminal \
  ls files/package-manager-tests
"$ADB" -s emulator-5560 exec-out run-as io.github.sylirre.terminal \
  cat files/package-manager-tests/<run>/userland/tmp/package-manager.log
"$ADB" -s emulator-5560 logcat -d -s PackageManagerTest:I
```

Stock JIT diagnostic roots from this investigation:

- `alpine-1790978584763-12819653297030`
- `debian-1790978634659-12869549301472`

Interpreter roots:

- `alpine-1790979684209-13919099221776`
- `debian-1790978915023-13149913423609`

Host copies are `/tmp/package-manager-{alpine,debian}-full.log` and
`/tmp/package-manager-{alpine,debian}-interpreter-full.log`; runner outputs
are `/tmp/package-manager-{alpine,debian}-stock.txt` and
`/tmp/package-manager-interpreter-stock.txt`.

## Build/validation status

The first strengthened-test build compiled the latest app/UI sources but
failed on unrelated concurrent native-tools tests using nonexistent Android
`Os.unlink(String)` in `AndroidShellToolsTest`. The responsible agent was
notified; package acceptance validation requires a successful test-APK rebuild
and device run after that compile error is fixed.

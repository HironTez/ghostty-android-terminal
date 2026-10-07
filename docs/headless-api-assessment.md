# Headless API over ADB: assessment

> **Superseded.** This is the pre-implementation assessment, written
> 2026-10-06 against `main` @ `1c2fad1`, and kept for its reasoning. The API
> has since been implemented and differs in places (notably the peer policy,
> §6); [headless-api.md](headless-api.md) is the authoritative description.
> Line numbers and "today" statements below refer to that older tree.

Goal: drive everything the app does (Android shell, userland distro, guest
VM, distro install, backup/restore) from a host over ADB, on a phone whose
screen is broken, without the UI ever being launched.

## Verdict

**Medium.** The session engine is already almost free of the UI. The work is
a new exported service that hosts a socket server, a small framed protocol,
a host-side client script, and three targeted refactors: an output tap on
`TerminalSession`, a pipe-based (non-PTY) spawn for `exec`, and moving the
distro install out of `OnboardingActivity`. No native-engine changes are
needed. The arm64chroot, arm64emu and Ghostty layers stay as they are.

| Component | Effort |
| --- | --- |
| Exported FGS + abstract-socket server + peer-uid auth + lifecycle | 1.5–2 d |
| Protocol: session registry ids, spawn / attach / resize / kill / list | 2 d |
| `TerminalSession` output tap, reply suppression while attached, exit reaper | 1 d |
| `exec`: pipe-based spawn in `pty_jni.c`, guest-argv override in `UserlandRootfs.command` | 1.5 d |
| Distro install pulled out of `OnboardingActivity` into `term/` | 0.5 d |
| Host client (`scripts/gterm`, Python stdlib only) | 1 d |
| Instrumented tests + docs | 1.5–2 d |
| **MVP total** | **about 9–10 dev-days** |
| Later: VM start/stop/attach | 1 d |
| Later: backup/restore streaming | 1 d |
| Later: BOOT_COMPLETED autostart + setting | 0.5 d |
| Later: settings get/set | 0.5–1 d |
| Later: re-attach screen replay rendered from `ScreenSnapshot` | 1–2 d |

## 1. What is coupled to the UI and what isn't

### Already UI-free (needs only a `Context`)

- **`TerminalEmulator`** is pure JNI behind `synchronized` methods
  (`term/TerminalEmulator.java:17-291`). It has no Android UI types.
- **`TerminalSession`** takes `cols, rows, cellWidthPx, cellHeightPx,
  scrollbackLines, SessionCommand, Listener` (`term/TerminalSession.java:153-193`).
  It has no View dependency. A fixed size such as 120x40 with any cell pixel
  size works; `pty_jni.c:100-105` just writes `cols*cell_w` into `ws_xpixel`.
  - Main-thread assumption: `mainHandler = new Handler(Looper.getMainLooper())`
    (`TerminalSession.java:72`). Every listener callback is posted there,
    including `onExited` (`:276-279`), and `onUpdate` is coalesced (`:297-303`).
    This works inside a Service, because an app process always has a main
    looper. Callbacks are dropped when `listener == null`.
  - The reader thread always drains the PTY and feeds the emulator
    (`:236-248`), so nothing blocks when no one is watching.
- **`SessionManager`** is a process singleton. `create(Context, cols, rows,
  …, userland, UserlandOptions, …)` (`term/SessionManager.java:42-58`) and
  `attachVm(...)` (`:68-77`) need no Activity. The class comment at `:17`
  ("deliberately no foreground service") is stale. So is
  `docs/architecture.md` §"Sessions and tabs" (line 556): `SessionService`
  now exists.
- **`SessionCommand`** (`term/SessionCommand.java`) is a plain value. **`UserlandRootfs`**
  `install(Context, assetName, InstallListener)` (`term/UserlandRootfs.java:211-254`)
  blocks and is idempotent. `command(Context, UserlandOptions)` (`:353-445`)
  builds the arm64chroot argv. **`UserlandDistro.bundled(Context)`**
  (`term/UserlandDistro.java:76`) lists the APK assets.
- **`VmMachine` / `VmOptions` / `VmImages`**: `VmMachine.start(VmOptions)`
  (`term/VmMachine.java:62-68`), `attach(i)` (`:170-176`, returns a `dup` of
  the channel), `setWinsize` (`:189-204`), and `VmImages.install(Context, …)`
  (`term/VmImages.java:97`). All are UI-free.
- **`RootfsBackup.backup(Context, OutputStream, …)`** (`term/RootfsBackup.java:124`)
  and **`restore(Context, ArchiveSource, …)`** (`:195`) are UI-free.
  `restore` opens its source twice (`:86-95`), so a socket upload has to be
  spooled to a cache file first.
- **`AppSettings`** sits in `ui/` but only wraps `SharedPreferences`
  (`ui/AppSettings.java:106-109`). It works from a Service as-is, though
  moving it to `term/` would be cleaner. The first-run flag is
  `KEY_ONBOARDING_COMPLETED` (`:67`, `:693-699`). **`ThemeStore`** works the
  same way. Themes do not matter headlessly: a remote client renders with its
  own terminal.

### Coupled to Activities/Views (must be re-homed or bypassed)

- **Session spawn policy lives in `MainActivity`.** This covers onboarding
  gating (`ui/MainActivity.java:148-157`), the first-layout spawn
  (`:259-276`), building `UserlandOptions` from settings and calling
  `SessionManager.create` with `terminal.gridCols()` etc. (`:481-501`),
  falling back to sh on failure (`:503-516`), and VM boot (`:638-651`).
- **Exit handling lives in `MainActivity.onExited`** (`:1373-1395`). Without
  an Activity, nobody calls `SessionManager.close`, so dead sessions stay in
  the list. The headless layer needs its own reaper.
- **`TerminalSession` has a single listener.** `MainActivity` overwrites it
  for every session on create (`:255-257`). A headless listener would be
  silently replaced, so add a separate tap rather than share the listener
  slot.
- **Distro install flow is in `OnboardingActivity`.** The worker thread is at
  `ui/OnboardingActivity.java:575-587`. Persisting the outcome
  (`setUserlandDistroAsset`, `setOnboardingCompleted`) happens in
  `onInstallFinished` (`:604-627`). The post-install login shell and home
  derivation is `applyPostInstallDefaults` (`:638-645`); without it, Alpine
  would get the bash default. About 30 lines to lift into a `term/` helper.
- **The battery-optimization and notification prompts are Activity
  requests** (`MainActivity.java:284-298`, `:826`). Headlessly they are
  replaced by `pm grant` and `dumpsys deviceidle` (see §5).

### The mksh SIGWINCH constraint

This only bites when a session is spawned at a guessed size and then resized
(`TerminalSession.java:400-405`, `docs/architecture.md` "Decisions"). A
headless client knows its real size from local `stty size` and passes it in
`spawn`, so the PTY starts final. Later resizes come from real client window
changes and are legitimate SIGWINCHes. **Conclusion: yes, a session can start
at a fixed size with no view.**

## 2. Process lifetime with no Activity

- **A foreground service already exists.** `SessionService`
  (`term/SessionService.java`) uses type `specialUse` (manifest, plus
  `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on 34+, `:134-141`).
  It has `stopWithTask=false` and holds no session state. Its optional
  partial wakelock (`:187-199`) can only be toggled from the notification.
  It is `exported="false"`, so `adb shell am start-foreground-service`
  (uid 2000) **cannot start it today**.
- **FGS type:** keep `specialUse`. Its permission is already declared, and it
  is allowed from BOOT_COMPLETED. Android 15 bars only dataSync, camera,
  mediaPlayback, phoneCall, mediaProjection and microphone there. Unlike
  `dataSync`, which Android 15 caps at about 6 h per 24 h, it has no runtime
  cap. Do not switch to `dataSync`.
- **POST_NOTIFICATIONS (33+):** the FGS runs without it; the notification is
  just hidden. Headlessly: `adb shell pm grant io.github.sylirre.terminal
  android.permission.POST_NOTIFICATIONS` (optional).
- **Background FGS-start restrictions (31+):** a shell-issued
  `am start-foreground-service` is exempt. The app starting its own FGS
  later, from a socket thread or a broadcast, is also exempt once it is on the
  device-idle allowlist. Allowlisting is the robust choice for both.
- **Stopped state:** a freshly installed or force-stopped package gets no
  broadcasts, including BOOT_COMPLETED, until something explicitly starts one
  of its components. An explicit `am start-foreground-service -n pkg/.Cls`
  does that. `am broadcast` would need `--include-stopped-packages`.
- **Doze / standby:** an FGS keeps the process at FGS priority, but the CPU
  still suspends with the screen off unless a partial wakelock is held.
  Allowlist the app (`dumpsys deviceidle whitelist +pkg`). That covers idle
  network restrictions and FGS-start exemptions. Hold the wakelock while
  headless mode is on.
- **API 37 / Android 17:** nothing found in the repo indicates new
  FGS-from-shell rules. The app targets 36. Re-verify on the real device.

## 3. Transports

| | Interactive TTY | exec + exit code | Auth | Verdict |
| --- | --- | --- | --- | --- |
| (a) `am broadcast` to a permission-guarded receiver | no | one-shot only; result via `setResultData`/extras, printed by `am` | `android:permission="android.permission.DUMP"` (shell holds it; normal apps can't, except via an adb `pm grant`) | control-plane shim only. `onReceive` runs on the main thread under the broadcast ANR timeout, can't stream, can't take stdin, and needs `--include-stopped-packages` |
| (b) abstract `LocalServerSocket` + `adb forward tcp:N localabstract:NAME` | **yes**, raw byte stream | **yes**, streamed stdout/stderr, exit code, stdin | `SO_PEERCRED` via `LocalSocket.getPeerCredentials()`: allow uid 0, 2000 and the app's own uid (as implemented: the app's own *process* only, see §6) | **recommended** |
| (c) ContentProvider + `content call/read/write` | no | partial: `content read --uri` can stream a pipe to stdout, but there is no exit code or stdin multiplexing | `readPermission/writePermission=DUMP` + `Binder.getCallingUid()` | usable for one-liners. Providers do start a stopped app's process. Clunky for anything else |
| (d) `run-as` / `am instrument` | yes on debug builds | yes | debuggable only | dev fallback only. Release builds can't `run-as` |

Notes:

- **The shell uid cannot run the userland directly.** The rootfs is under
  `/data/data/io.github.sylirre.terminal/files/userland`
  (`UserlandRootfs.java:69-71`), mode 0700, labeled `app_data_file`, so the
  shell can't even read it. arm64chroot exists only inside `libterm.so`,
  entered by `fork()` + `arm64chroot_main()` (`app/src/main/cpp/pty_jni.c:113-170`).
  An `adb forward localfilesystem:` socket inside app data is equally
  unreachable for adbd. That leaves the abstract namespace for (b).
- **Alternative (e), which bypasses the app:** arm64chroot builds standalone
  (`native/arm64chroot/Makefile`, `docs/android-termux.md`). The shell uid
  may exec from `/data/local/tmp`. So a static aarch64 `arm64chroot` plus a
  rootfs pushed there runs under uid 2000 with no app changes and no FGS. The
  downsides: it is not the app's rootfs, `adb shell` processes die with the
  adb connection unless `nohup`/`setsid`, and it has no notification and no
  app lifecycle. This is the fastest route if "the app's functionality" just
  means "a Linux userland on the phone".
- **A guest sshd is a complement, not a replacement.** Once `exec` works, run
  dropbear/openssh inside the distro on port 2222 and
  `adb forward tcp:2222 tcp:2222`. That gives real ssh/scp/port-forwarding
  with key auth (localhost TCP is reachable by any app, so keys are
  mandatory). Under arm64chroot `--fake-id`, verify that sshd privsep and PTY
  allocation work before relying on it.

## 4. Security

- **Abstract socket:** any process in the device's network namespace can try
  to `connect()`, which in practice means any app. Peer-uid checking is
  therefore mandatory, not optional. Chrome's `chrome_devtools_remote` socket
  is the precedent: abstract socket, `adb forward`, peer uid must be shell or
  root.
- **Filesystem socket in app data:** only the app uid can reach it, so adbd
  can't either. That rules it out for ADB.
- **Localhost TCP:** any app with INTERNET can connect, and `SO_PEERCRED`
  does not exist for TCP. Reject it.
- **Recommended default:**
  1. Headless mode is **off** until enabled from the shell.
  2. The abstract socket name is fixed: `io.github.sylirre.terminal.headless`.
  3. On every accept, read `getPeerCredentials().getUid()`. Allow `0`,
     `2000` (`Process.SHELL_UID`) and `Process.myUid()` (for in-app tests).
     Close the connection otherwise.
     *Correction (as implemented):* allowing the whole app uid is too broad,
     because the userland's guest processes run under that uid too and could
     drive the server. `HeadlessServer.isAllowedPeer` accepts uid 0, uid 2000,
     or the app uid **only from the app's own process**
     (`uid == Process.myUid() && pid == Process.myPid()`), using the peer pid
     from the same credentials.
  4. The exported entry point (service and/or receiver) is protected with
     `android:permission="android.permission.DUMP"`.
  5. Optional defense in depth: a random token, regenerated on every service
     start and returned only through the DUMP-guarded start/broadcast
     result. The client must present it in the hello line.

  Do not rely on a token file read via `run-as`; release builds can't do it.
- **Residual risk:** anyone with ADB access gets full control of the userland.
  That is inherent and matches `adb shell` itself.

## 5. Headless-specific concerns

- **Unattached sessions:** keep feeding the emulator. It must drain the PTY
  anyway, it keeps scrollback for a future `screen`/re-attach, and it answers
  terminal queries when no remote terminal is present.
- **Duplicate query replies:** while a client is attached, its own terminal
  will answer DA/DSR/etc. The in-app Ghostty reply written back at
  `TerminalSession.java:245-246` must then be **suppressed**, or programs
  like vim and fish get two answers on stdin. Restore replies on detach.
- **Re-attach fidelity:** for the MVP, re-attaching re-sends the client size.
  A size change makes full-screen apps redraw, and a plain shell just sees a
  fresh prompt after Enter. For lossless detach/attach, recommend tmux inside
  the distro. Later: replay a rendered viewport from `ScreenSnapshot` (cells
  are already resolved natively).
- **Boot autostart:** add a `BOOT_COMPLETED` receiver (needs
  `RECEIVE_BOOT_COMPLETED`), gated by an `autostart` setting. It starts the
  FGS and optionally spawns configured sessions. **Caveat:** BOOT_COMPLETED
  and credential-encrypted app storage only become available after the first
  unlock. A broken-screen phone must have no lock screen
  (`adb shell locksettings clear --old <PIN>`), or be unlocked blind with
  `adb shell input text <PIN>; adb shell input keyevent 66`. adbd authorization
  itself works before unlock.
- **Wakelocks:** expose `SessionService`'s partial wakelock through the
  protocol, and hold it by default while headless mode is enabled.
- **Battery / killers:**
  `adb shell dumpsys deviceidle whitelist +io.github.sylirre.terminal`,
  `adb shell cmd appops set io.github.sylirre.terminal RUN_ANY_IN_BACKGROUND allow`,
  `adb shell am set-standby-bucket io.github.sylirre.terminal active`.
- **Phantom-process killer (Android 12+): the biggest operational risk.**
  Every guest process is a real host child of the app, and the VM emulator
  is one too. Android kills app child processes beyond a device-wide cap
  (default 32), and can kill CPU-heavy ones. Turn it off:
  - 12L+: `adb shell settings put global settings_enable_monitor_phantom_procs false`
    (or the Developer option "Disable child process restrictions").
  - 12.0: `adb shell device_config set_sync_disabled_for_tests persistent &&
    adb shell device_config put activity_manager max_phantom_processes 2147483647`.

  OEM task killers (Samsung, Xiaomi, …) are separate and device-specific.
- **First-run install headlessly:** an `install` op runs the extracted helper,
  which wraps `UserlandRootfs.install` and then sets `onboardingCompleted`,
  the distro asset, and the derived shell/home. It streams progress lines.
  This requires the distro tarball to be **bundled in the APK**. A `restore`
  op, which spools an uploaded tar.gz and calls `RootfsBackup.restore`, covers
  installing a rootfs that isn't bundled.

## 6. Recommended architecture

```
host                                    phone (app uid, one process)
gterm ──TCP 127.0.0.1:7777──adb──► adbd ──connect──► @io.github.sylirre.terminal.headless
                                                      HeadlessServer (accept thread, SO_PEERCRED check)
                                                        ├─ thread per connection
                                                        ├─ HeadlessRegistry: id → TerminalSession
                                                        │    (wraps SessionManager; reaper on exit)
                                                        ├─ exec: ProcessPipes (no PTY, no emulator)
                                                        └─ UserlandSetup / RootfsBackup / VmMachine
                                                      SessionService (FGS specialUse, wakelock)
```

- **Entry point:** extend `SessionService` with
  `ACTION_HEADLESS_START/STOP`, set `exported="true"` and
  `android:permission="android.permission.DUMP"`. Shell can then start it, and
  the existing "Exit" action stays shell-only. On start it calls
  `startForeground`, then starts the server, then acquires the wakelock.
  `ACTION_EXIT` / `closeAll` also stops the server. Change
  `START_NOT_STICKY` → `START_STICKY` while headless mode is on, so the server
  comes back after a low-memory kill (the shells are lost, but the node stays
  reachable).
- **Session ownership:** headless sessions live in the same `SessionManager`,
  so if the UI is ever opened they show up as tabs. Add an output tap to
  `TerminalSession`: a reader-thread callback
  `void onOutput(byte[] buf, int n)`, plus `setRepliesSuppressed(boolean)`,
  kept apart from `Listener` so `MainActivity` can't clobber it. Move exit
  reaping into a `SessionManager`-level hook. For VM terminals, attach the
  client to the **one** `TerminalSession` that owns the channel. Two readers
  on `dup`s of the same socketpair (`VmMachine.java:170-176`) would split
  each other's bytes.
- **exec:** add `TerminalNative.pipeCreateEmulator(argv, env, cwd, int[] fds,
  int[] pid)` in `pty_jni.c`. It is the existing fork path (`:113-170`) with
  three pipes and no `setsid`/ctty. Add `UserlandOptions.command` (a
  `String[]`) to replace the whitespace-split login shell
  (`UserlandRootfs.java:426-428`) with an exact guest argv, so quoting
  survives. The exit code comes from `processWaitFor`; arm64chroot returns the
  guest's code (`pty_jni.c:168`).
  (Implemented as `TerminalNative.pipeCreate` and
  `UserlandOptions.withCommand(guest, cwd, env)`.)

### Protocol (v1)

One connection = one request. The client first sends a hello and a
single-line JSON request terminated by `\n`. The server answers with one JSON
line (`{"ok":true,...}` or `{"ok":false,"error":"..."}`). For streaming ops,
both sides then switch to **frames**: `type:u8 | len:u32be | payload`.

| Frame | Dir | Payload |
| --- | --- | --- |
| `0 DATA` | ⇄ | raw bytes (stdin up; PTY output or stdout down) |
| `1 STDERR` | ↓ | exec stderr |
| `2 RESIZE` | ↑ | `cols:u16 rows:u16` |
| `3 EOF` | ↑ | close the child's stdin (exec) |
| `4 SIGNAL` | ↑ | `sig:u8` (exec: e.g. INT on ^C) |
| `5 EXIT` | ↓ | `code:i32` (exit status, or −signal) then close |
| `6 PROGRESS` | ↓ | JSON line (install/backup/restore) |

Requests:

```
{"v":1,"op":"status"}                                  → rootfs installed/usable, distros bundled, sessions, vm, wakelock
{"v":1,"op":"list"}                                    → [{id,type,label,title,cols,rows,exit,attached}]
{"v":1,"op":"spawn","type":"userland|shell|vm","cols":120,"rows":40,"attach":true}
{"v":1,"op":"attach","id":3,"cols":120,"rows":40}      (steals any existing attachment)
{"v":1,"op":"kill","id":3}
{"v":1,"op":"exec","argv":["uname","-a"],"cwd":"/root","env":{"K":"V"},"type":"userland"}
{"v":1,"op":"install","distro":"alpine"}               → PROGRESS frames, then EXIT
{"v":1,"op":"wakelock","on":true}
later: vm-start / vm-stop, backup (DATA down = tar.gz), restore (DATA up),
       settings-get / settings-set, screen (text dump of viewport+scrollback)
```

**Raw mode** lets plain `nc`/`socat` work without the client. If the first
line is `RAW <spawn|attach> ...`, the connection becomes unframed bytes both
ways, with no resize or exit frame.

### What the user types

One-time device setup:

```sh
adb install app-debug.apk
adb shell pm grant io.github.sylirre.terminal android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +io.github.sylirre.terminal
adb shell cmd appops set io.github.sylirre.terminal RUN_ANY_IN_BACKGROUND allow
adb shell settings put global settings_enable_monitor_phantom_procs false
```

Each session (or after reboot, until autostart exists):

```sh
adb shell am start-foreground-service \
  -n io.github.sylirre.terminal/.term.SessionService \
  -a io.github.sylirre.terminal.headless.START
adb forward tcp:7777 localabstract:io.github.sylirre.terminal.headless
```

Use (`gterm` does the `adb forward` itself if asked, puts the local tty in
raw mode, maps SIGWINCH to RESIZE, and exits with the remote code):

```sh
scripts/gterm status
scripts/gterm install alpine
scripts/gterm shell                      # interactive userland, real TTY
scripts/gterm shell --android            # /system/bin/sh as the app uid
scripts/gterm ls ; scripts/gterm attach 3 ; scripts/gterm kill 3
scripts/gterm exec -- apk add htop; echo $?
tar c ./proj | scripts/gterm exec -- tar x -C /root   # stdin streaming = file copy
# without the client:
stty raw -echo; (printf 'RAW spawn userland 120 40\n'; cat) | nc 127.0.0.1 7777; stty sane
```

## 7. MVP vs later

**MVP:** headless start/stop via the exported `SessionService`; the socket
server with peer-uid auth; `status`, `list`, `spawn` (shell/userland),
`attach`, `resize`, `kill`, `exec` (pipes, exit code, stdin), `install`
(bundled distro), `wakelock`; the output tap with reply suppression; the
reaper; `scripts/gterm`; and an instrumented `HeadlessApiTest` that connects
as the app's own uid (from within the app process, which the implemented peer
check requires).

**Later:** VM `vm-start`/`vm-stop` and attach to guest terminals; `backup`
and `restore` streaming; `settings-get/set` (identity, JIT, storage binds,
scrollback); BOOT_COMPLETED autostart with configured startup sessions;
`screen` text dump and rendered re-attach replay; multiple read-only viewers
per session; `am broadcast` / `content call` shims for one-liners without the
client; a token on top of the peer uid.

## 8. Risks

1. **Phantom-process killer / OEM killers** reaping guest processes. This
   needs the device settings in §5 and is not fixable in app code.
2. **Reboot with a lock screen:** CE storage and BOOT_COMPLETED stay
   unavailable until unlock. Remove the lock or plan blind unlocks.
3. **Duplicate terminal-query replies** if suppression is missed. That means
   garbled input in vim/fish/tmux when attached.
4. **UI coexistence:** `MainActivity` replaces session listeners and drives
   resizes. If the UI ever opens, it will resize headless sessions to the
   phone's grid. Accept this, or mark headless sessions as externally sized.
5. **Exec semantics:** non-PTY exec means programs that need a TTY (sudo
   prompts, some installers) behave differently. Offer `exec --tty`, which
   spawns a PTY session and merges stdout and stderr.
6. **Low-memory kill of the FGS** loses every shell. `START_STICKY` only
   restores reachability. Guest daemons that `setsid()` also escape
   kill-on-close (`TerminalSession.java:449-456`).
7. **DUMP as the guard permission:** a "development" permission an app could
   be granted via adb `pm grant`. That is acceptable, since it requires adb,
   but it is not signature-level.
8. **Platform drift on Android 17 / API 37:** re-verify FGS-from-shell and
   abstract-socket SELinux reachability on the target device.

## 9. Open decisions for the user

- Socket API (recommended), **or** the zero-app-change route (e): a
  standalone arm64chroot plus rootfs under `/data/local/tmp` as uid 2000.
- Should interactive use ultimately go through the app's PTY stream, or
  through sshd inside the distro (with the app API limited to control and
  `exec`)?
- Does headless mode have to coexist with the UI, or can it be a separate
  mode that refuses UI attach while enabled?
- Client language for `gterm`: Python (stdlib) is assumed. A static Go/C
  binary avoids a host Python dependency.
- Default auth: peer uid only, or peer uid plus token.
- Is VM support (arm64emu) needed in the MVP?

## 10. Files that would change or be added

Added:

- `app/src/main/java/io/github/sylirre/terminal/term/HeadlessServer.java`:
  accept loop, peer-uid check, per-connection threads.
- `app/src/main/java/io/github/sylirre/terminal/term/HeadlessProtocol.java`:
  JSON request parsing and frame codec.
- `app/src/main/java/io/github/sylirre/terminal/term/HeadlessRegistry.java`:
  session ids, attach/steal, reaper.
- `app/src/main/java/io/github/sylirre/terminal/term/ProcessPipes.java`:
  non-PTY exec.
- `app/src/main/java/io/github/sylirre/terminal/term/UserlandSetup.java`:
  install plus post-install defaults, lifted from `OnboardingActivity`.
- `app/src/main/java/io/github/sylirre/terminal/term/BootReceiver.java`
  (later).
- `scripts/gterm`: the host client.
- `app/src/androidTest/java/io/github/sylirre/terminal/HeadlessApiTest.java`.

Changed:

- `app/src/main/AndroidManifest.xml`: `SessionService` becomes exported and
  guarded by DUMP; later `RECEIVE_BOOT_COMPLETED` plus the receiver.
- `term/SessionService.java`: headless actions, server lifecycle, sticky
  mode, wakelock API.
- `term/TerminalSession.java`: output tap, reply suppression, exit hook.
- `term/SessionManager.java`: exit reaping hook; fix the stale comment.
- `term/UserlandOptions.java` and `term/UserlandRootfs.java`: exact guest
  argv override.
- `term/TerminalNative.java` and `app/src/main/cpp/pty_jni.c`:
  `pipeCreateEmulator`.
- `ui/OnboardingActivity.java`: call `UserlandSetup`.
- `ui/AppSettings.java`: headless and autostart keys (optionally move it to
  `term/`).
- `docs/architecture.md`, `docs/testing.md`, `README.md`, `CLAUDE.md`:
  document headless mode, and fix the stale "no foreground service" text.

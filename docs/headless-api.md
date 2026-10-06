# Headless ADB API

Drive the app from a host over ADB with no UI: interactive shells (Android
`sh`, the userland distro, guest-machine terminals), one-shot commands with
real exit codes and separate stdout/stderr, and the first-run distro install.
Built for a phone used as a headless node (for example one with a broken
screen).

## How it works

```
host                                    phone (app uid, one process)
gterm ──TCP 127.0.0.1:7777──adb──► adbd ──connect──► @io.github.sylirre.terminal.headless
                                                      HeadlessServer (accept thread, SO_PEERCRED check)
                                                        ├─ thread per connection (HeadlessConnection)
                                                        ├─ sessions: SessionManager / TerminalSession output tap
                                                        ├─ exec: ProcessPipes (pipes, no PTY, no emulator)
                                                        └─ install: UserlandSetup · VM: VmMachine
                                                      SessionService (foreground service, specialUse, wake lock)
```

- **Entry point.** `SessionService` is exported, guarded by
  `android:permission="android.permission.DUMP"`. The adb shell holds DUMP;
  ordinary apps cannot. `ACTION io.github.sylirre.terminal.headless.START`
  makes it a foreground service, starts the socket server and acquires a
  partial wake lock (`--ez wakelock false` skips the wake lock). While headless
  mode is on the service is sticky: after a low-memory kill the system
  restarts it and the server comes back. The shells that died with the
  process do not.
- **Transport.** An abstract unix socket, `io.github.sylirre.terminal.headless`,
  reached with `adb forward tcp:N localabstract:…`. The app's rootfs and
  arm64chroot are unreachable for the shell uid (mode 0700 app data, no exec
  from app data), so everything runs inside the app process.
- **Auth, client side.** Any process can `connect()` to an abstract socket,
  so every connection's peer is read with `SO_PEERCRED`
  (`LocalSocket.getPeerCredentials`) before a byte is read. Only uid 0
  (root), uid 2000 (adb shell) and the app's own *process* are accepted.
  Other processes of the app's uid are refused, because the userland's guest
  processes run as that uid and must not get an unconfined Android shell this
  way. Everything else is closed at once. Anyone with ADB access gets full
  control of the app's shells. That matches `adb shell` itself.
- **Auth, server side.** Abstract names are first come, first served. While
  headless mode is off, another app could bind the name and collect what a
  client types or sends. Each server start therefore draws a random 256-bit
  key. Only DUMP holders can read it:
  `adb shell dumpsys activity service io.github.sylirre.terminal/.term.SessionService headless-key`
  (a plain dump, as in bug reports, leaves it out). A client opens with
  `{"op":"hello","nonce":HEX}`. The server answers with
  `proof = HMAC-SHA256(key, "gterm-hello-v1:" + nonce)`, and `gterm` sends
  nothing more unless the proof checks out. When the name is already taken the
  server does not start, and the dump says `headless: failed: ...`, which
  `gterm start` reports. Raw mode with `nc` skips this check.
  A filesystem socket in app data would avoid squatting, but `adb forward`
  runs as the shell uid and cannot reach a path inside the app's private
  directory.
- **Sessions are shared with the UI.** Headless sessions live in the same
  `SessionManager`. If the app is opened they appear as tabs, and UI tabs can
  be attached to from the host.

## One-time device setup

```sh
adb install -r app-debug.apk

# Optional: show the foreground-service notification (it runs without it).
adb shell pm grant io.github.sylirre.terminal android.permission.POST_NOTIFICATIONS

# Keep the app out of Doze and app-standby restrictions.
adb shell dumpsys deviceidle whitelist +io.github.sylirre.terminal
adb shell cmd appops set io.github.sylirre.terminal RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket io.github.sylirre.terminal active

# Android 12L+: stop the phantom-process killer from reaping guest processes
# (every userland process and the VM emulator is a child of the app).
adb shell settings put global settings_enable_monitor_phantom_procs false
# Android 12.0 instead:
#   adb shell device_config set_sync_disabled_for_tests persistent
#   adb shell device_config put activity_manager max_phantom_processes 2147483647
```

A phone that should come back after a reboot must have no lock screen
(`adb shell locksettings clear --old <PIN>`): app storage and
`BOOT_COMPLETED` are only available after the first unlock.

## Every boot (or use autostart)

```sh
adb shell am start-foreground-service \
  -n io.github.sylirre.terminal/.term.SessionService \
  -a io.github.sylirre.terminal.headless.START
adb forward tcp:7777 localabstract:io.github.sylirre.terminal.headless
```

`scripts/gterm start` runs the first command and waits until the server is
up, and every other `gterm` command runs the `adb forward` itself.
`gterm autostart on` makes the app start headless mode on `BOOT_COMPLETED`
(off by default), provided headless mode was still on when the phone went
down: after `gterm stop` it stays off until the next `gterm start`. An explicit
`am start-foreground-service` also takes a freshly installed or force-stopped
app out of the stopped state, which a boot broadcast cannot do.

Stop headless mode with `gterm stop` (or the same `am` command with
`-a io.github.sylirre.terminal.headless.STOP`). The notification's "Exit"
action stops it too and closes every session.

## The client: `scripts/gterm`

A single Python 3 script (standard library only; Linux and macOS hosts).
Copy it anywhere on `PATH`. It uses `adb` from `PATH` or `$ADB`, honors
`ANDROID_SERIAL`, forwards local port `$GTERM_PORT` (default 7777), and skips
the forward with `GTERM_NO_FORWARD=1`.

```sh
scripts/gterm start                       # headless mode on (+ wake lock)
scripts/gterm status                      # rootfs, bundled distros, sessions, VM, wake lock
scripts/gterm install alpine              # first-run install of a bundled distro, with progress
scripts/gterm shell                       # interactive userland shell, real TTY
scripts/gterm shell --android             # /system/bin/sh as the app uid
scripts/gterm shell --vm                  # guest-machine console (boots the VM if needed)
scripts/gterm spawn -d userland           # start detached, print the id
scripts/gterm ls                          # sessions: id, type, size, state, attached, UI
scripts/gterm attach 3                    # attach (steals another client's attachment)
scripts/gterm kill 3
scripts/gterm exec -- uname -a            # one-shot command, exit status propagated
scripts/gterm exec -- 'echo hi; exit 3'; echo $?      # prints hi, then 3
scripts/gterm exec --argv -- printf '%s\n' 'a b'      # exact argv, no shell parsing
tar c ./proj | scripts/gterm exec -- tar x -C /root   # stdin streaming = file copy
scripts/gterm exec -t -- top              # on a PTY (merged output) for TTY programs
scripts/gterm wakelock off
scripts/gterm vm-stop
```

In interactive sessions the local terminal is in raw mode, window size
changes are forwarded, and **Ctrl-]** detaches; the session keeps running.
Closing the client also only detaches. The session ends when its shell exits,
or with `gterm kill`. `gterm exec` exits with the remote status (128+N when
the command died of signal N). gterm's own failures (no server, a refused
request, a failed identity check) exit 255, like ssh. Ctrl-C and other signals are forwarded to the
remote process group. Without `--argv`, the words after `--` are joined and
run by the target's `sh -c` (ssh semantics). `exec` runs in the userland when
a usable rootfs is installed, otherwise in `/system/bin/sh`. `--type` picks
explicitly.

Without the client, plain `nc` works in raw mode (no resize, no exit code):

```sh
adb forward tcp:7777 localabstract:io.github.sylirre.terminal.headless
stty raw -echo; (printf 'RAW spawn userland 120 40\n'; cat) | nc 127.0.0.1 7777; stty sane
```

## Protocol (v1)

One connection carries one request. The client sends a single JSON line
terminated by `\n`, optionally preceded by a `hello` line (see Auth) whose
answer comes first. A client has 30 seconds to send its request; at most 32
connections are served at once. The server answers with one JSON line:
`{"ok":true,"v":1,...}` or `{"ok":false,"v":1,"error":"..."}`. For
streaming ops both sides then switch to frames,
`type:u8 | len:u32 big-endian | payload`:

| Frame | Dir | Payload |
| --- | --- | --- |
| `0 DATA` | both | raw bytes (stdin up; PTY output or stdout down) |
| `1 STDERR` | down | exec stderr |
| `2 RESIZE` | up | `cols:u16 rows:u16` (big-endian) |
| `3 EOF` | up | close the child's stdin (exec) |
| `4 SIGNAL` | up | `sig:u8`, sent to the exec'd process group |
| `5 EXIT` | down | `code:i32`: exit status, or −signal; the server then closes |
| `6 PROGRESS` | down | one JSON object (install) |

Requests (`"v":1` may be omitted):

| Request | Response, then |
| --- | --- |
| `{"op":"hello","nonce":"<16..128 hex>"}` | `{proof}`; then the real request follows on the same connection |
| `{"op":"status"}` | `rootfs{installed,usable,distro}`, `distros[{id,version,asset}]`, `sessions`, `vm{running,images_installed,images_bundled,terminals}`, `wakelock`, `autostart`, `onboarding_completed`, `uid`, `version` |
| `{"op":"list"}` | `sessions[{id,type,label,title,cols,rows,exit,attached,ui,vm_terminal?}]` |
| `{"op":"spawn","type":"userland\|shell\|vm","cols":120,"rows":40,"attach":true}` | `{id,type}`, then DATA both ways, RESIZE up, EXIT down when the session ends. Optional `argv`, `cmd`, `cwd`, `env` replace the login shell (`shell`/`userland`); optional `terminal` picks a guest terminal (`vm`). `"attach":false` returns at once |
| `{"op":"attach","id":3,"cols":120,"rows":40}` | as spawn. Steals an existing attachment: the previous client is disconnected without an EXIT |
| `{"op":"kill","id":3}` | `{id}`. An attached client receives EXIT |
| `{"op":"exec","argv":[...]` or `"cmd":"..."`, `"type":"userland\|shell","cwd":"/root","env":{"K":"V"}}` | `{pid,type}`, then DATA/STDERR down, DATA/EOF/SIGNAL up, EXIT down. `"tty":true` runs it as an attached PTY session instead |
| `{"op":"install","distro":"alpine"}` | `{distro,version}`, then PROGRESS frames (`extracting` with `percent`, then `done`/`failed`/`already-installed`), then EXIT 0/1 |
| `{"op":"wakelock","on":true}` | `{wakelock}` |
| `{"op":"autostart","on":true}` | `{autostart}` |
| `{"op":"vm-stop"}` | `{stopped}` |

Raw mode: a first line `RAW spawn [shell|userland|vm] [cols rows]` or
`RAW attach <id> [cols rows]` turns the connection into unframed bytes both
ways, with no response line and no EXIT. The connection closes when the
session ends.

### Semantics worth knowing

- **Terminal queries.** Every session's output also feeds the in-app Ghostty
  emulator, which answers DA/DSR/etc. While a client is attached those
  answers (and OSC 52 clipboard replies) are suppressed, because the client's
  own terminal answers them. Two answers would arrive on the program's stdin
  as garbage. Replies resume on detach.
- **Window size.** While a client is attached it owns the size: resizes from
  the in-app view are deferred and applied when the client detaches, so
  opening the app does not SIGWINCH a program in use over ADB.
- **Attach sees new output only.** Re-attaching does not replay the screen.
  Press Enter or resize to get a fresh prompt; full-screen programs redraw on
  resize. For lossless detach/attach run `tmux` in the distro.
- **Back-pressure.** Output is written to the client synchronously: a client
  that stops reading stalls the program, like a slow terminal.
- **Exec.** It runs on pipes, not a PTY, so programs that insist on a TTY
  (password prompts, some installers) behave differently. Use `exec -t`. In the
  userland, `argv` runs as `/bin/sh -c 'exec "$@"' sh ARGV...` (PATH lookup,
  argv untouched) and `cmd` as `/bin/sh -c CMD`, both with the configured
  identity, home, locale and PATH. A client that disconnects mid-command
  sends it SIGHUP, then SIGKILL two seconds later.
- **Exited sessions** are reaped by `SessionManager` when no Activity is
  listening; with the UI open, the Activity's usual tab logic runs.
- **Guest machine.** `spawn type=vm` boots the VM with the app's VM settings
  if it is not running (extracting bundled images first), then attaches to
  the requested or first free guest terminal. A terminal already open as a tab
  is attached to through that tab's session, because one channel can have
  only one reader. `vm-stop` stops the machine.
- **Install** needs the distro tarball bundled in the APK
  (`UserlandRootfs/`). It runs the same `UserlandSetup.install` as the
  onboarding wizard, persists the same settings (distro, onboarding done,
  derived login shell and home), and never replaces an installed rootfs.

## Troubleshooting

- `server identity check failed`: something other than the app answered on
  the socket name (or `GTERM_KEY` is stale). Check `adb shell dumpsys activity
  service io.github.sylirre.terminal/.term.SessionService` and look for an
  app squatting the name.
- `connection closed before a response`: headless mode is off
  (`gterm start`), or the forward points at a stale socket
  (`adb forward --remove tcp:7777`).
- `am start-foreground-service` says "Requires permission DUMP": it was not
  run from the adb shell.
- Shells die after a while with the screen off: check the deviceidle
  whitelist, the wake lock (`gterm status`), and the phantom-process setting.
- Logs: `adb logcat -s HeadlessServer SessionService`.

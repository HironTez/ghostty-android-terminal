/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.headless;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import io.github.sylirre.terminal.term.ProcessPipes;
import io.github.sylirre.terminal.term.SessionCommand;
import io.github.sylirre.terminal.term.SessionManager;
import io.github.sylirre.terminal.term.SessionService;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandRootfs;
import io.github.sylirre.terminal.term.VmImages;
import io.github.sylirre.terminal.term.VmMachine;
import io.github.sylirre.terminal.term.VmOptions;
import io.github.sylirre.terminal.ui.AppSettings;
import io.github.sylirre.terminal.ui.UserlandSetup;

/**
 * One client connection: one request line, one response line, then — for the
 * streaming ops — frames until the session/process ends or the client leaves.
 * See docs/headless-api.md for the protocol.
 */
final class HeadlessConnection implements Runnable {
    private static final int MAX_LINE = 1 << 16;
    /** How long a client may take to send its request line(s). */
    private static final int REQUEST_TIMEOUT_MS = 30_000;
    private static final int SIGHUP = 1;
    private static final int SIGKILL = 9;
    /** Nominal cell size for the pixel fields of a headless PTY's winsize. */
    private static final int CELL_W = 8, CELL_H = 16;

    private final Context context;
    private final LocalSocket sock;
    private final HeadlessServer server;
    private DataInputStream in;
    private Frames.Writer out;
    private volatile boolean closed;

    HeadlessConnection(Context context, LocalSocket sock, HeadlessServer server) {
        this.context = context;
        this.sock = sock;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 16));
            out = new Frames.Writer(new BufferedOutputStream(sock.getOutputStream(), 1 << 16));
            // A client that connects and says nothing must not pin a thread
            // (and a connection slot) forever; streams run untimed after.
            sock.setSoTimeout(REQUEST_TIMEOUT_MS);
            String line = readLine();
            if (line == null) return;
            JSONObject req = null;
            if (!isRaw(line)) {
                try {
                    req = new JSONObject(line);
                } catch (JSONException e) {
                    error("bad request: " + e.getMessage());
                    return;
                }
                if (req.optString("op", "").equals("hello")) {
                    // The server proves its identity first (HeadlessServer);
                    // the real request follows on the same connection.
                    if (!hello(req)) return;
                    line = readLine();
                    if (line == null) return;
                    req = null;
                    if (!isRaw(line)) {
                        try {
                            req = new JSONObject(line);
                        } catch (JSONException e) {
                            error("bad request: " + e.getMessage());
                            return;
                        }
                    }
                }
            }
            sock.setSoTimeout(0);
            if (req == null) handleRaw(line);
            else handle(req, false);
        } catch (IOException ignored) {
            // Client went away (or timed out before sending a request).
        } catch (JSONException e) {
            try {
                error("bad request: " + e.getMessage());
            } catch (IOException ignored) {
            }
        } catch (RuntimeException | Error e) {
            // A bad request must never take the process down: every session,
            // the UI's included, lives in it. Errors too: org.json recurses
            // per nesting level, so a deeply nested request overflows the stack.
            android.util.Log.e("HeadlessServer", "request failed", e);
            try {
                error("internal error: " + e);
            } catch (Throwable ignored) {
                // Out of memory, or the client is gone: closing is all that is left.
            }
        } finally {
            close();
            server.remove(this);
        }
    }

    /** Closes the socket; unblocks this connection's reader. Idempotent. */
    void close() {
        if (closed) return;
        closed = true;
        try {
            sock.shutdownInput();
        } catch (IOException ignored) {
        }
        try {
            sock.shutdownOutput();
        } catch (IOException ignored) {
        }
        HeadlessServer.closeQuietly(sock);
    }

    // --- request dispatch ---------------------------------------------------

    private static boolean isRaw(String line) {
        return line.startsWith("RAW ") || line.equals("RAW");
    }

    /**
     * {@code {"op":"hello","nonce":HEX}}: answers with the server's proof of
     * key possession. Returns false (after an error reply) for a bad nonce.
     */
    private boolean hello(JSONObject req) throws IOException, JSONException {
        String nonce = req.optString("nonce", "");
        if (nonce.length() < 16 || nonce.length() > 128 || !nonce.matches("[0-9a-f]+")) {
            error("hello needs a nonce of 16..128 lowercase hex digits");
            return false;
        }
        String proof = HeadlessServer.proof(nonce);
        if (proof == null) {
            error("server stopping");
            return false;
        }
        out.line(ok().put("proof", proof).toString());
        return true;
    }

    private void handle(JSONObject req, boolean raw) throws IOException, JSONException {
        int v = req.optInt("v", 1);
        if (v != 1) {
            error("unsupported protocol version " + v + " (server speaks 1)");
            return;
        }
        String op = req.optString("op", "");
        switch (op) {
            case "status": status(); break;
            case "list": list(); break;
            case "spawn": spawn(req, raw); break;
            case "attach": attach(req, raw); break;
            case "kill": kill(req); break;
            case "signal": signal(req); break;
            case "exec":
                if (req.optBoolean("tty", false)) spawn(req, raw);
                else exec(req);
                break;
            case "install": install(req); break;
            case "wakelock": wakelock(req); break;
            case "autostart": autostart(req); break;
            case "vm-stop": vmStop(); break;
            default: error("unknown op: " + op);
        }
    }

    /** {@code RAW spawn [type] [cols rows]} or {@code RAW attach <id> [cols rows]}: unframed bytes. */
    private void handleRaw(String line) throws IOException, JSONException {
        String[] t = line.trim().split("\\s+");
        JSONObject req = new JSONObject().put("v", 1);
        int i = 2;
        if (t.length >= 2 && t[1].equals("spawn")) {
            req.put("op", "spawn");
            if (t.length > i && !isInt(t[i])) req.put("type", t[i++]);
        } else if (t.length >= 3 && t[1].equals("attach") && isInt(t[2])) {
            req.put("op", "attach").put("id", Integer.parseInt(t[2]));
            i = 3;
        } else {
            out.line("error: usage: RAW spawn [shell|userland|vm] [cols rows] | RAW attach <id> [cols rows]");
            return;
        }
        if (t.length >= i + 2 && isInt(t[i]) && isInt(t[i + 1])) {
            req.put("cols", Integer.parseInt(t[i])).put("rows", Integer.parseInt(t[i + 1]));
        }
        handle(req, true);
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // --- simple ops -----------------------------------------------------------

    private void status() throws IOException, JSONException {
        AppSettings settings = new AppSettings(context);
        JSONObject r = ok();
        r.put("version", appVersion());
        r.put("uid", Process.myUid());
        r.put("onboarding_completed", settings.onboardingCompleted());
        JSONObject rootfs = new JSONObject();
        rootfs.put("installed", UserlandRootfs.isInstalled(context));
        rootfs.put("usable", UserlandRootfs.isUsable(context));
        rootfs.put("distro", settings.userlandDistroAsset() == null ? JSONObject.NULL
                : settings.userlandDistroAsset());
        r.put("rootfs", rootfs);
        JSONArray distros = new JSONArray();
        for (UserlandDistro d : UserlandDistro.bundled(context)) {
            distros.put(new JSONObject().put("id", d.id).put("version", d.version)
                    .put("asset", d.assetName));
        }
        r.put("distros", distros);
        r.put("sessions", SessionManager.get().sessions().size());
        JSONObject vm = new JSONObject();
        VmMachine m = VmMachine.get();
        boolean running = VmMachine.isRunning() && m != null;
        vm.put("running", running);
        vm.put("images_installed", VmImages.isInstalled(context));
        vm.put("images_bundled", VmImages.assetsAvailable(context));
        if (running) vm.put("terminals", m.terminalCount());
        r.put("vm", vm);
        r.put("wakelock", SessionService.wakeLockHeld());
        r.put("autostart", SessionService.autostart(context));
        out.line(r.toString());
    }

    private void list() throws IOException, JSONException {
        JSONArray a = new JSONArray();
        for (TerminalSession s : SessionManager.get().sessions()) a.put(describe(s));
        out.line(ok().put("sessions", a).toString());
    }

    private static JSONObject describe(TerminalSession s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("type", typeOf(s));
        o.put("label", s.label());
        o.put("title", s.title() == null ? JSONObject.NULL : s.title());
        o.put("cols", s.cols());
        o.put("rows", s.rows());
        o.put("exit", s.exitCode() == null ? JSONObject.NULL : s.exitCode());
        o.put("attached", s.tap() != null);
        o.put("ui", s.hasListener());
        if (s.isVm()) o.put("vm_terminal", s.vmTerminal());
        return o;
    }

    private static String typeOf(TerminalSession s) {
        return s.isVm() ? "vm" : s.isUserland() ? "userland" : "shell";
    }

    private void kill(JSONObject req) throws IOException, JSONException {
        TerminalSession s = SessionManager.get().byId(req.optInt("id", -1));
        if (s == null) {
            error("no session " + req.opt("id"));
            return;
        }
        SessionManager.get().close(s);
        SessionService.notifyChanged();
        out.line(ok().put("id", s.id).toString());
    }

    /**
     * {@code signal}: a running exec by pid, from another connection. The exec's
     * own SIGNAL frames travel behind its stdin, so once a command stops reading
     * and the stdin backlog fills, Ctrl-C can only get through this way.
     */
    private void signal(JSONObject req) throws IOException, JSONException {
        ProcessPipes p = EXECS.get(req.optInt("pid", -1));
        int sig = req.optInt("sig", -1);
        if (p == null) {
            error("no running exec " + req.opt("pid"));
            return;
        }
        if (sig < 1 || sig > 64) {
            error("bad signal " + req.opt("sig"));
            return;
        }
        p.signal(sig);
        out.line(ok().put("pid", p.pid()).put("sig", sig).toString());
    }

    /** Running execs by pid, for the {@code signal} op. */
    private static final ConcurrentHashMap<Integer, ProcessPipes> EXECS =
            new ConcurrentHashMap<>();

    private void wakelock(JSONObject req) throws IOException, JSONException {
        boolean on = req.optBoolean("on", true);
        SessionService.setWakeLock(context, on);
        out.line(ok().put("wakelock", on).toString());
    }

    private void autostart(JSONObject req) throws IOException, JSONException {
        boolean on = req.optBoolean("on", true);
        SessionService.setAutostart(context, on);
        out.line(ok().put("autostart", on).toString());
    }

    private void vmStop() throws IOException, JSONException {
        boolean was = VmMachine.isRunning();
        VmMachine.stopIfRunning();
        out.line(ok().put("stopped", was).toString());
    }

    // --- interactive sessions -------------------------------------------------

    private String defaultType() {
        return UserlandRootfs.isUsable(context) ? "userland" : "shell";
    }

    private static int dim(JSONObject req, String key, int def) {
        int v = req.optInt(key, def);
        return Math.max(1, Math.min(1000, v));
    }

    private void spawn(JSONObject req, boolean raw) throws IOException, JSONException {
        String type = req.optString("type", defaultType());
        int cols = dim(req, "cols", 80), rows = dim(req, "rows", 24);
        boolean attach = raw || req.optBoolean("attach", true);
        AppSettings settings = new AppSettings(context);
        Attachment att = attach ? new Attachment(raw) : null;
        TerminalSession s;
        try {
            if (type.equals("vm")) {
                VmMachine vm = ensureVm(settings);
                int term = req.has("terminal") ? req.getInt("terminal") : freeVmTerminal(vm);
                if (term < 0 || term >= vm.terminalCount()) {
                    error("no such guest terminal: " + term);
                    return;
                }
                s = SessionManager.get().attachVm(vm, term, cols, rows, CELL_W, CELL_H,
                        settings.scrollbackLines(), null, att);
                if (s.tap() != att) {
                    // Already open (one reader per channel): attach to that tab.
                    attachTo(s, cols, rows, raw);
                    return;
                }
            } else if (type.equals("shell") || type.equals("userland")) {
                SessionCommand cmd = command(type, req, settings);
                s = SessionManager.get().create(cmd, cols, rows, CELL_W, CELL_H,
                        settings.scrollbackLines(), settings.terminateProcessesOnExit(),
                        null, att);
            } else {
                error("unknown session type: " + type);
                return;
            }
        } catch (IOException e) {
            error(type + " spawn failed: " + e.getMessage());
            return;
        }
        SessionManager.get().setContext(context);
        SessionService.notifyChanged();
        if (att == null) {
            out.line(ok().put("id", s.id).put("type", typeOf(s)).toString());
            return;
        }
        att.session = s;
        try {
            if (!raw) out.line(ok().put("id", s.id).put("type", typeOf(s)).toString());
        } catch (IOException e) {
            att.detach(); // or the tap stays held back, buffering, for good
            throw e;
        }
        att.start();
        pumpInput(att);
    }

    private void attach(JSONObject req, boolean raw) throws IOException, JSONException {
        TerminalSession s = SessionManager.get().byId(req.optInt("id", -1));
        if (s == null) {
            error("no session " + req.opt("id"));
            return;
        }
        int cols = req.has("cols") ? dim(req, "cols", 80) : 0;
        int rows = req.has("rows") ? dim(req, "rows", 24) : 0;
        attachTo(s, cols, rows, raw);
    }

    /** Attaches to a live session, stealing any previous attachment. */
    private void attachTo(TerminalSession s, int cols, int rows, boolean raw)
            throws IOException, JSONException {
        Attachment att = new Attachment(raw);
        att.session = s;
        TerminalSession.OutputTap prev = s.setTap(att);
        if (prev instanceof Attachment) ((Attachment) prev).kick();
        if (cols > 0 && rows > 0) s.resizeExternal(cols, rows);
        if (!raw) {
            try {
                out.line(ok().put("id", s.id).put("type", typeOf(s))
                        .put("cols", s.cols()).put("rows", s.rows()).toString());
            } catch (IOException e) {
                // The tap is installed: a client gone before the response would
                // otherwise leave it held back and buffering for good, with the
                // session's query replies suppressed and its resizes deferred.
                att.detach();
                throw e;
            }
        }
        att.start();
        pumpInput(att);
    }

    /** Client → session until the client leaves; then detaches (the session lives on). */
    private void pumpInput(Attachment att) {
        TerminalSession s = att.session;
        try {
            if (att.raw) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) {
                        if (s.tap() != att) break; // kicked: what is left is not ours
                        byte[] b = new byte[n];
                        System.arraycopy(buf, 0, b, 0, n);
                        s.writeBytes(b);
                    }
                }
            } else {
                Frames.Frame f;
                while ((f = Frames.read(in)) != null) {
                    // Kicked by another client: frames still buffered here
                    // must not reach a session that now belongs to it.
                    if (s.tap() != att) break;
                    if (f.type == Frames.DATA) {
                        if (f.payload.length > 0) s.writeBytes(f.payload);
                    } else if (f.type == Frames.RESIZE && f.payload.length >= 4) {
                        int cols = ((f.payload[0] & 0xff) << 8) | (f.payload[1] & 0xff);
                        int rows = ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
                        if (cols > 0 && rows > 0) {
                            // Same bounds as spawn/attach (dim): a grid this
                            // big is allocated per frame on the main thread.
                            s.resizeExternal(Math.min(1000, cols), Math.min(1000, rows));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Client gone or socket closed by the session's end.
        } finally {
            att.detach();
        }
    }

    /**
     * Mirrors one session's output to this connection. Output that arrives
     * before the response line is written (a fresh spawn's first prompt) is
     * held back until {@link #start}.
     */
    private final class Attachment implements TerminalSession.OutputTap {
        /** Output held back before {@link #start}; more means the client is gone. */
        private static final int MAX_PENDING = 1 << 20;
        final boolean raw;
        TerminalSession session;
        private final Object lock = new Object();
        private boolean ready;
        private volatile boolean done; // also read unlocked (onClosing)
        private ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private Integer pendingEnd;

        Attachment(boolean raw) {
            this.raw = raw;
        }

        @Override
        public void onOutput(byte[] buf, int len) {
            synchronized (lock) {
                if (done) return;
                if (!ready) {
                    if (pending.size() + len <= MAX_PENDING) {
                        pending.write(buf, 0, len);
                        return;
                    }
                    done = true; // never went live; don't hold output without bound
                } else {
                    try {
                        send(buf, len);
                    } catch (IOException e) {
                        done = true;
                    }
                }
            }
            if (done) detach();
        }

        @Override
        public void onEnd(int exitCode) {
            synchronized (lock) {
                if (done) return;
                if (!ready) {
                    pendingEnd = exitCode;
                    return;
                }
                finish(exitCode);
            }
            close();
        }

        /** Response line is out: flush what was held back and go live. */
        void start() {
            boolean end;
            synchronized (lock) {
                ready = true;
                if (pending.size() > 0) {
                    byte[] b = pending.toByteArray();
                    try {
                        send(b, b.length);
                    } catch (IOException e) {
                        done = true;
                    }
                }
                pending = null;
                end = pendingEnd != null && !done;
                if (end) finish(pendingEnd);
            }
            if (end) close();
        }

        private void send(byte[] b, int len) throws IOException {
            if (raw) out.raw(b, 0, len);
            else out.frame(Frames.DATA, b, 0, len);
        }

        private void finish(int code) {
            done = true;
            if (raw) return;
            try {
                out.exit(code);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void onClosing() {
            // A client that is reading gets its EXIT well within this; one that
            // stopped reading is cut loose, which fails the blocked write and
            // frees the session's reader thread.
            Thread t = new Thread(() -> {
                SystemClock.sleep(3000);
                // Not under lock: the stuck write is holding it.
                if (!done) kick();
            }, "headless-close-grace");
            t.setDaemon(true);
            t.start();
        }

        /** Another client attached: drop this one without an EXIT. */
        void kick() {
            // Close first: a write blocked on a client that stopped reading
            // holds the lock, and only failing that write releases it.
            close();
            synchronized (lock) {
                done = true;
            }
        }

        void detach() {
            if (session != null) session.clearTap(this);
            close();
            synchronized (lock) {
                done = true;
            }
        }
    }

    private int freeVmTerminal(VmMachine vm) {
        for (int i = 0; i < vm.terminalCount(); i++) {
            if (SessionManager.get().vmSession(i) == null) return i;
        }
        return 0; // all open: join the console's tab
    }

    /** The running guest machine, booting one (and extracting images) if needed. */
    private VmMachine ensureVm(AppSettings settings) throws IOException {
        VmMachine vm = VmMachine.get();
        if (VmMachine.isRunning() && vm != null) return vm;
        if (!VmImages.isInstalled(context)) {
            if (!VmImages.assetsAvailable(context)) {
                throw new IOException("no guest machine images in this build");
            }
            VmImages.install(context, null);
        }
        java.io.File firmware = VmImages.firmware(context);
        java.io.File image = VmImages.image(context);
        if (image == null || !firmware.isFile()) {
            throw new IOException("guest machine images are incomplete");
        }
        return VmMachine.start(new VmOptions(firmware, image, settings.vmMemoryMb(),
                settings.vmTerminals(), settings.vmJitEnabled()));
    }

    // --- commands -------------------------------------------------------------

    /**
     * What to run for {@code spawn}/{@code exec}: {@code argv} (exact, looked
     * up on PATH), {@code cmd} (a shell command line), or neither (the login
     * shell / plain sh). Both forms go through the target's own {@code sh}:
     * the userland engine resolves argv[0] against the working directory, not
     * PATH, and {@code exec "$@"} gives PATH lookup with the argv untouched.
     */
    private SessionCommand command(String type, JSONObject req, AppSettings settings)
            throws IOException, JSONException {
        String[] argv = stringArray(req.optJSONArray("argv"));
        String cmd = req.has("cmd") ? req.getString("cmd") : null;
        String[] env = envArray(req.optJSONObject("env"));
        String cwd = req.has("cwd") ? req.getString("cwd") : null;
        List<String> shArgs = new ArrayList<>();
        if (argv != null && argv.length > 0) {
            shArgs.add("-c");
            shArgs.add("exec \"$@\"");
            shArgs.add("sh");
            for (String a : argv) shArgs.add(a);
        } else if (cmd != null) {
            shArgs.add("-c");
            shArgs.add(cmd);
        }
        if (type.equals("userland")) {
            if (!UserlandRootfs.isUsable(context)) {
                throw new IOException("no usable userland rootfs installed"
                        + " (install one with the install op)");
            }
            // An explicit cwd that does not resolve is refused, not replaced by
            // the home fallback the settings get: a command meant for one
            // directory must not run in another ("rm -rf ./*" in /root).
            if (cwd != null && !UserlandRootfs.isGuestDir(context, cwd)) {
                throw new IOException("cwd is not a directory in the userland: " + cwd);
            }
            String[] guest = null;
            if (!shArgs.isEmpty()) {
                guest = new String[shArgs.size() + 1];
                guest[0] = "/bin/sh";
                for (int i = 0; i < shArgs.size(); i++) guest[i + 1] = shArgs.get(i);
            }
            return UserlandRootfs.command(context,
                    UserlandSetup.options(context, settings).withCommand(guest, cwd, env));
        }
        if (cwd != null && !new File(cwd).isDirectory()) {
            throw new IOException("cwd is not a directory: " + cwd);
        }
        return SessionCommand.androidShell(context,
                shArgs.toArray(new String[0]), env, cwd);
    }

    private static String[] stringArray(JSONArray a) throws JSONException {
        if (a == null) return null;
        String[] r = new String[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.getString(i);
        return r;
    }

    private static String[] envArray(JSONObject o) throws JSONException {
        if (o == null) return new String[0];
        List<String> r = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.isEmpty() || k.indexOf('=') >= 0) continue;
            r.add(k + "=" + o.getString(k));
        }
        return r.toArray(new String[0]);
    }

    // --- exec -----------------------------------------------------------------

    private void exec(JSONObject req) throws IOException, JSONException {
        if (!req.has("argv") && !req.has("cmd")) {
            error("exec needs argv or cmd");
            return;
        }
        String type = req.optString("type", defaultType());
        if (!type.equals("shell") && !type.equals("userland")) {
            error("exec type must be shell or userland");
            return;
        }
        ProcessPipes p;
        try {
            p = ProcessPipes.start(command(type, req, new AppSettings(context)));
        } catch (IOException e) {
            error("exec failed: " + e.getMessage());
            return;
        }
        try {
            out.line(ok().put("pid", p.pid()).put("type", type).toString());
        } catch (IOException e) {
            // Nobody will supervise it: kill it, and reap it so it is no zombie.
            p.signal(SIGKILL);
            Thread reap = new Thread(() -> {
                p.waitFor();
                p.close();
            }, "exec-reap");
            reap.setDaemon(true);
            reap.start();
            throw e;
        }

        EXECS.put(p.pid(), p);
        Pump outPump = new Pump(p.stdout, Frames.DATA, "exec-out");
        Pump errPump = new Pump(p.stderr, Frames.STDERR, "exec-err");
        Thread waiter = new Thread(() -> {
            int code = p.waitFor();
            EXECS.remove(p.pid(), p);
            // Drain what the child wrote before it exited, however slowly the
            // client reads it. Only idleness is bounded: a background process
            // it started can keep the pipes open without writing.
            while (true) {
                Pump busy = outPump.busy() ? outPump : errPump.busy() ? errPump : null;
                if (busy == null) break;
                join(busy.thread, 50);
            }
            synchronized (out) {
                execEnded = true; // and no pump writes past the EXIT
                try {
                    out.exit(code);
                } catch (IOException ignored) {
                }
            }
            close();
            p.close();
        }, "exec-wait");
        waiter.setDaemon(true);
        waiter.start();

        StdinFeeder stdin = new StdinFeeder(p, this::peerHungUp);
        try {
            Frames.Frame f;
            while ((f = Frames.read(in)) != null) {
                if (f.type == Frames.DATA) {
                    if (!stdin.write(f.payload)) break; // the client hung up
                } else if (f.type == Frames.EOF) {
                    stdin.eof();
                } else if (f.type == Frames.SIGNAL && f.payload.length >= 1) {
                    p.signal(f.payload[0] & 0xff);
                }
            }
        } catch (IOException ignored) {
            // Client gone, or closed by the waiter after EXIT.
        } finally {
            stdin.stop();
        }
        if (p.exitCode() == null) {
            // The client left with the command still running: hang it up like
            // a dropped ssh session, and make sure it is gone soon after.
            p.signal(SIGHUP);
            long deadline = SystemClock.uptimeMillis() + 2000;
            while (p.exitCode() == null && SystemClock.uptimeMillis() < deadline) {
                join(waiter, 100);
            }
            p.signal(SIGKILL);
        }
        join(waiter, 5000);
    }

    /**
     * Writes an exec's stdin on its own thread. Written inline, a child that
     * stops reading blocked the frame loop once the pipe filled, and with it
     * the SIGNAL frames (Ctrl-C) and the noticing of a client that left. Data
     * is queued up to {@link #LIMIT}; past that the loop waits — that is the
     * back-pressure — but gives up as soon as the child is gone, or the client:
     * not reading meanwhile, the loop would never see its EOF, and the
     * disconnect's SIGHUP/SIGKILL would never come. After the child closes its
     * stdin, further data is dropped and signals still served.
     */
    private static final class StdinFeeder implements Runnable {
        private static final int LIMIT = 8 << 20;
        private final ProcessPipes p;
        private final BooleanSupplier peerGone;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private int queued;
        private boolean eof;
        private boolean stopped;

        StdinFeeder(ProcessPipes p, BooleanSupplier peerGone) {
            this.p = p;
            this.peerGone = peerGone;
            Thread t = new Thread(this, "exec-in");
            t.setDaemon(true);
            t.start();
        }

        /** Queues {@code b}; false if the client hung up while this waited for room. */
        synchronized boolean write(byte[] b) {
            if (b.length == 0) return true;
            try {
                while (queued >= LIMIT && !stopped && p.exitCode() == null) {
                    wait(100);
                    if (peerGone.getAsBoolean()) return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
            if (stopped || eof) return true;
            queue.add(b);
            queued += b.length;
            notifyAll();
            return true;
        }

        synchronized void eof() {
            eof = true;
            notifyAll();
        }

        /** The frame loop is done: drop what is queued (the child is going away). */
        synchronized void stop() {
            stopped = true;
            queue.clear();
            notifyAll();
        }

        @Override
        public void run() {
            try {
                while (true) {
                    byte[] b;
                    synchronized (this) {
                        while (queue.isEmpty() && !eof && !stopped) wait();
                        if (stopped) return;
                        b = queue.poll();
                        if (b == null) break; // EOF with everything written
                    }
                    try {
                        p.stdin.write(b);
                    } catch (IOException e) {
                        stop(); // the child closed its stdin
                        return;
                    }
                    synchronized (this) {
                        queued -= b.length;
                        notifyAll();
                    }
                }
                p.closeStdin();
            } catch (InterruptedException ignored) {
            }
        }
    }

    /** How long an exec's output pipe may sit silent, once it exited, before EXIT. */
    private static final long DRAIN_IDLE_MS = 2000;

    /** Set (under {@code out}) when the exec's EXIT is sent; the pumps stop there. */
    private boolean execEnded;

    /** Copies one exec output pipe to the client as frames, until EOF or EXIT. */
    private final class Pump implements Runnable {
        final InputStream src;
        final int type;
        final Thread thread;
        /** When the pump last went into read(); 0 while it is not in read(). */
        private volatile long readingSince;

        Pump(InputStream src, int type, String name) {
            this.src = src;
            this.type = type;
            thread = new Thread(this, name);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            byte[] buf = new byte[16384];
            try {
                while (true) {
                    readingSince = SystemClock.uptimeMillis();
                    int n = src.read(buf);
                    readingSince = 0;
                    if (n < 0) return;
                    if (n == 0) continue;
                    synchronized (out) {
                        if (execEnded) return;
                        out.frame(type, buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                readingSince = 0;
            }
        }

        /**
         * Still moving output: alive, and either busy (a write to a client
         * that reads slowly) or not yet silent in read() for the drain bound.
         */
        boolean busy() {
            if (!thread.isAlive()) return false;
            long since = readingSince;
            return since == 0 || SystemClock.uptimeMillis() - since < DRAIN_IDLE_MS;
        }
    }

    /**
     * Whether the client hung up, polled rather than read: the exec frame
     * loop can be waiting for stdin room, with nobody reading the socket.
     */
    private boolean peerHungUp() {
        if (closed) return true;
        FileDescriptor fd = sock.getFileDescriptor();
        if (fd == null) return true;
        StructPollfd pfd = new StructPollfd();
        pfd.fd = fd;
        pfd.events = (short) (OsConstants.POLLHUP | POLLRDHUP);
        try {
            if (Os.poll(new StructPollfd[] {pfd}, 0) <= 0) return false;
        } catch (ErrnoException e) {
            return false;
        }
        return (pfd.revents & (OsConstants.POLLHUP | POLLRDHUP | OsConstants.POLLERR
                | OsConstants.POLLNVAL)) != 0;
    }

    /** Linux's POLLRDHUP (the peer shut its writing side); OsConstants lacks it. */
    private static final int POLLRDHUP = 0x2000;

    private static void join(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // --- install --------------------------------------------------------------

    private void install(JSONObject req) throws IOException, JSONException {
        String want = req.optString("distro", "");
        List<UserlandDistro> bundled = UserlandDistro.bundled(context);
        UserlandDistro pick = null;
        for (UserlandDistro d : bundled) {
            if (want.equals(d.id) || want.equals(d.assetName)
                    || want.equals(d.id + "_" + d.version)) {
                pick = d;
                break;
            }
        }
        if (pick == null && want.isEmpty() && bundled.size() == 1) pick = bundled.get(0);
        if (pick == null) {
            List<String> ids = new ArrayList<>();
            for (UserlandDistro d : bundled) ids.add(d.id);
            error(bundled.isEmpty() ? "this build bundles no userland distro"
                    : "unknown distro '" + want + "'; bundled: " + String.join(", ", ids));
            return;
        }
        out.line(ok().put("distro", pick.id).put("version", pick.version).toString());
        if (UserlandRootfs.isInstalled(context)) {
            // Never replaced behind the user's back: it may hold their data.
            AppSettings settings = new AppSettings(context);
            settings.setOnboardingCompleted(true);
            out.progress(new JSONObject().put("state", "already-installed")
                    .put("distro", settings.userlandDistroAsset() == null
                            ? JSONObject.NULL : settings.userlandDistroAsset())
                    .toString());
            out.exit(0);
            return;
        }
        final long[] last = {0};
        boolean didInstall;
        ProgressSender sender = new ProgressSender();
        try {
            didInstall = UserlandSetup.install(context, pick, (extracted, read, total) -> {
                long now = SystemClock.uptimeMillis();
                if (now - last[0] < 250) return;
                last[0] = now;
                try {
                    JSONObject p = new JSONObject().put("state", "extracting")
                            .put("extracted", extracted).put("read", read)
                            .put("total", total);
                    if (total > 0) p.put("percent", Math.min(99, read * 100 / total));
                    sender.offer(p.toString());
                } catch (JSONException ignored) {
                }
            });
        } catch (IOException e) {
            sender.stop();
            out.progress(new JSONObject().put("state", "failed")
                    .put("error", String.valueOf(e.getMessage())).toString());
            out.exit(1);
            return;
        }
        sender.stop();
        if (!didInstall) {
            // Lost a race with another install (the wizard, or a second client).
            String asset = new AppSettings(context).userlandDistroAsset();
            out.progress(new JSONObject().put("state", "already-installed")
                    .put("distro", asset == null ? JSONObject.NULL : asset).toString());
            out.exit(0);
            return;
        }
        out.progress(new JSONObject().put("state", "done").put("distro", pick.id)
                .put("usable", UserlandRootfs.isUsable(context)).toString());
        out.exit(0);
    }

    /**
     * Sends install progress from its own thread, latest value only. The
     * extract runs holding the install lock (UserlandSetup), and a progress
     * write blocked on a client that stopped reading would hold it too —
     * freezing the wizard's install and any restore behind it.
     */
    private final class ProgressSender implements Runnable {
        private String latest;
        private boolean stopped;

        ProgressSender() {
            Thread t = new Thread(this, "headless-progress");
            t.setDaemon(true);
            t.start();
        }

        synchronized void offer(String p) {
            latest = p;
            notifyAll();
        }

        /**
         * No more progress after this returns, so the final state line cannot
         * be overtaken by a stale one. Waits for a write already under way.
         */
        void stop() {
            synchronized (writeLock) {
                synchronized (this) {
                    stopped = true;
                    notifyAll();
                }
            }
        }

        private final Object writeLock = new Object();

        @Override
        public void run() {
            while (true) {
                String p;
                synchronized (this) {
                    while (latest == null && !stopped) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (stopped) return;
                    p = latest;
                    latest = null;
                }
                synchronized (writeLock) {
                    synchronized (this) {
                        if (stopped) return;
                    }
                    try {
                        out.progress(p);
                    } catch (IOException e) {
                        return; // client gone; the install goes on regardless
                    }
                }
            }
        }
    }

    // --- helpers --------------------------------------------------------------

    private static JSONObject ok() throws JSONException {
        return new JSONObject().put("ok", true).put("v", 1);
    }

    private void error(String message) throws IOException {
        try {
            out.line(new JSONObject().put("ok", false).put("v", 1)
                    .put("error", message).toString());
        } catch (JSONException e) {
            out.line("{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    private String appVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * Reads the request line byte by byte, so no frame bytes are consumed.
     * Null at EOF, or after answering a line over {@link #MAX_LINE} with an error.
     */
    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (b.size() >= MAX_LINE) {
                error("request line too long (max " + MAX_LINE + " bytes)");
                return null;
            }
            b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        String s = b.toString(StandardCharsets.UTF_8.name());
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        return s;
    }
}

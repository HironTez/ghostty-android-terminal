/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.headless;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
            String line = readLine();
            if (line == null) return;
            if (line.startsWith("RAW ") || line.equals("RAW")) {
                handleRaw(line);
                return;
            }
            JSONObject req;
            try {
                req = new JSONObject(line);
            } catch (JSONException e) {
                error("bad request: " + e.getMessage());
                return;
            }
            handle(req, false);
        } catch (IOException ignored) {
            // Client went away.
        } catch (JSONException e) {
            try {
                error("bad request: " + e.getMessage());
            } catch (IOException ignored) {
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
                TerminalSession open = SessionManager.get().vmSession(term);
                if (open != null) {
                    // One reader per channel: attach to the tab that owns it.
                    attachTo(open, cols, rows, raw);
                    return;
                }
                s = SessionManager.get().attachVm(vm, term, cols, rows, CELL_W, CELL_H,
                        settings.scrollbackLines(), null, att);
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
        if (!raw) out.line(ok().put("id", s.id).put("type", typeOf(s)).toString());
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
            out.line(ok().put("id", s.id).put("type", typeOf(s))
                    .put("cols", s.cols()).put("rows", s.rows()).toString());
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
                        byte[] b = new byte[n];
                        System.arraycopy(buf, 0, b, 0, n);
                        s.writeBytes(b);
                    }
                }
            } else {
                Frames.Frame f;
                while ((f = Frames.read(in)) != null) {
                    if (f.type == Frames.DATA) {
                        if (f.payload.length > 0) s.writeBytes(f.payload);
                    } else if (f.type == Frames.RESIZE && f.payload.length >= 4) {
                        int cols = ((f.payload[0] & 0xff) << 8) | (f.payload[1] & 0xff);
                        int rows = ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
                        if (cols > 0 && rows > 0 && s.tap() == att) {
                            s.resizeExternal(cols, rows);
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
        final boolean raw;
        TerminalSession session;
        private final Object lock = new Object();
        private boolean ready;
        private boolean done;
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
                    pending.write(buf, 0, len);
                    return;
                }
                try {
                    send(buf, len);
                } catch (IOException e) {
                    done = true;
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
            String[] guest = null;
            if (!shArgs.isEmpty()) {
                guest = new String[shArgs.size() + 1];
                guest[0] = "/bin/sh";
                for (int i = 0; i < shArgs.size(); i++) guest[i + 1] = shArgs.get(i);
            }
            return UserlandRootfs.command(context,
                    UserlandSetup.options(context, settings).withCommand(guest, cwd, env));
        }
        return SessionCommand.androidShell(context.getFilesDir().getAbsolutePath(),
                context.getCacheDir().getAbsolutePath(),
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
        out.line(ok().put("pid", p.pid()).put("type", type).toString());

        Thread outPump = pump(p.stdout, Frames.DATA, "exec-out");
        Thread errPump = pump(p.stderr, Frames.STDERR, "exec-err");
        Thread waiter = new Thread(() -> {
            int code = p.waitFor();
            // Drain what the child wrote before it exited; bounded, because a
            // background process it started can keep the pipes open.
            join(outPump, 2000);
            join(errPump, 2000);
            try {
                out.exit(code);
            } catch (IOException ignored) {
            }
            close();
            p.close();
        }, "exec-wait");
        waiter.setDaemon(true);
        waiter.start();

        try {
            Frames.Frame f;
            while ((f = Frames.read(in)) != null) {
                if (f.type == Frames.DATA) {
                    try {
                        p.stdin.write(f.payload);
                    } catch (IOException ignored) {
                        // The child closed its stdin; keep serving signals.
                    }
                } else if (f.type == Frames.EOF) {
                    p.closeStdin();
                } else if (f.type == Frames.SIGNAL && f.payload.length >= 1) {
                    p.signal(f.payload[0] & 0xff);
                }
            }
        } catch (IOException ignored) {
            // Client gone, or closed by the waiter after EXIT.
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

    private Thread pump(InputStream src, int type, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                int n;
                while ((n = src.read(buf)) >= 0) {
                    if (n > 0) out.frame(type, buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

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
        try {
            UserlandSetup.install(context, pick, (extracted, read, total) -> {
                long now = SystemClock.uptimeMillis();
                if (now - last[0] < 250) return;
                last[0] = now;
                try {
                    JSONObject p = new JSONObject().put("state", "extracting")
                            .put("extracted", extracted).put("read", read)
                            .put("total", total);
                    if (total > 0) p.put("percent", Math.min(99, read * 100 / total));
                    out.progress(p.toString());
                } catch (IOException | JSONException ignored) {
                    // A client that stopped listening does not stop the install.
                }
            });
        } catch (IOException e) {
            out.progress(new JSONObject().put("state", "failed")
                    .put("error", String.valueOf(e.getMessage())).toString());
            out.exit(1);
            return;
        }
        out.progress(new JSONObject().put("state", "done").put("distro", pick.id)
                .put("usable", UserlandRootfs.isUsable(context)).toString());
        out.exit(0);
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

    /** Reads the request line byte by byte, so no frame bytes are consumed. */
    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (b.size() >= MAX_LINE) throw new IOException("request line too long");
            b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        String s = b.toString(StandardCharsets.UTF_8.name());
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        return s;
    }
}

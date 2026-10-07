/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import io.github.sylirre.terminal.headless.Frames;
import io.github.sylirre.terminal.headless.HeadlessServer;
import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.SessionCommand;
import io.github.sylirre.terminal.term.SessionManager;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * The headless ADB API end to end, minus adb: an in-process LocalSocket client
 * talks to the real server (the app's own uid passes the peer-uid check), and
 * the sessions it drives are real /system/bin/sh processes.
 */
@RunWith(AndroidJUnit4.class)
public class HeadlessApiTest {
    private static final long TIMEOUT_MS = 15_000;

    private static Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @BeforeClass
    public static void startServer() throws IOException {
        HeadlessServer.start(ctx());
    }

    @AfterClass
    public static void stopServer() {
        HeadlessServer.stop();
    }

    /** A protocol client: request line, response line, then frames. */
    private static final class Client implements Closeable {
        final LocalSocket sock = new LocalSocket();
        final DataInputStream in;
        final OutputStream out;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Integer exit;

        Client() throws IOException {
            sock.connect(new LocalSocketAddress(HeadlessServer.SOCKET_NAME));
            sock.setSoTimeout((int) TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            out = sock.getOutputStream();
        }

        JSONObject request(JSONObject req) throws Exception {
            out.write((req.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') line.write(c);
            return new JSONObject(line.toString("UTF-8"));
        }

        void send(int type, byte[] payload) throws IOException {
            out.write(Frames.encode(type, payload));
            out.flush();
        }

        void type(String s) throws IOException {
            send(Frames.DATA, s.getBytes(StandardCharsets.UTF_8));
        }

        /** Reads one frame into data/stderr/exit; false at end of stream. */
        boolean pump() throws IOException {
            Frames.Frame f = Frames.read(in);
            if (f == null) return false;
            if (f.type == Frames.DATA) data.write(f.payload);
            else if (f.type == Frames.STDERR) stderr.write(f.payload);
            else if (f.type == Frames.EXIT) {
                exit = ((f.payload[0] & 0xff) << 24) | ((f.payload[1] & 0xff) << 16)
                        | ((f.payload[2] & 0xff) << 8) | (f.payload[3] & 0xff);
            }
            return true;
        }

        String data() {
            return new String(data.toByteArray(), StandardCharsets.UTF_8);
        }

        void pumpUntilData(String needle) throws IOException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!data().contains(needle)) {
                if (System.currentTimeMillis() > deadline || !pump()) {
                    throw new AssertionError("no \"" + needle + "\" in output:\n" + data());
                }
            }
        }

        int pumpUntilExit() throws IOException {
            while (exit == null) {
                if (!pump()) throw new AssertionError("stream ended without EXIT; got:\n" + data());
            }
            return exit;
        }

        @Override
        public void close() throws IOException {
            sock.close();
        }
    }

    private static JSONObject req(String op) throws Exception {
        return new JSONObject().put("v", 1).put("op", op);
    }

    private static JSONObject call(JSONObject r) throws Exception {
        try (Client c = new Client()) {
            return c.request(r);
        }
    }

    private static JSONObject findSession(int id) throws Exception {
        JSONArray a = call(req("list")).getJSONArray("sessions");
        for (int i = 0; i < a.length(); i++) {
            if (a.getJSONObject(i).getInt("id") == id) return a.getJSONObject(i);
        }
        return null;
    }

    private static boolean sessionListed(int id) {
        try {
            return findSession(id) != null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** A detached session's prompt, read off its in-app emulator. */
    private static void waitForPrompt(int id) {
        TerminalSession s = SessionManager.get().byId(id);
        assertNotNull(s);
        waitFor("prompt", TIMEOUT_MS, () -> screen(s).contains("$"), () -> screen(s));
    }

    // --- control ops ----------------------------------------------------------

    @Test
    public void statusReportsState() throws Exception {
        JSONObject r = call(req("status"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(Process.myUid(), r.getInt("uid"));
        assertTrue(r.has("rootfs"));
        assertTrue(r.has("distros"));
        assertTrue(r.has("vm"));
        assertTrue(r.has("sessions"));
    }

    @Test
    public void unknownOpAndBadJsonAreErrors() throws Exception {
        JSONObject r = call(req("no-such-op"));
        assertFalse(r.getBoolean("ok"));
        assertTrue(r.getString("error").contains("unknown op"));
        try (Client c = new Client()) {
            c.out.write("not json\n".getBytes(StandardCharsets.UTF_8));
            c.out.flush();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int ch;
            while ((ch = c.in.read()) >= 0 && ch != '\n') line.write(ch);
            assertFalse(new JSONObject(line.toString("UTF-8")).getBoolean("ok"));
        }
    }

    @Test
    public void deeplyNestedRequestIsAnErrorNotACrash() throws Exception {
        // org.json recurses per level: this overflows the connection's stack.
        StringBuilder sb = new StringBuilder("{\"op\":");
        for (int i = 0; i < 60_000; i++) sb.append('[');
        try (Client c = new Client()) {
            c.out.write((sb + "\n").getBytes(StandardCharsets.UTF_8));
            c.out.flush();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int ch;
            while ((ch = c.in.read()) >= 0 && ch != '\n') line.write(ch);
            assertFalse(line.toString("UTF-8"),
                    new JSONObject(line.toString("UTF-8")).getBoolean("ok"));
        }
        assertTrue(call(req("status")).getBoolean("ok")); // and the server lives on
    }

    @Test
    public void overlongRequestLineIsAnError() throws Exception {
        byte[] big = new byte[(1 << 16) + 16];
        java.util.Arrays.fill(big, (byte) 'x');
        try (Client c = new Client()) {
            c.out.write(big);
            c.out.flush();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int ch;
            while ((ch = c.in.read()) >= 0 && ch != '\n') line.write(ch);
            JSONObject r = new JSONObject(line.toString("UTF-8"));
            assertFalse(r.getBoolean("ok"));
            assertTrue(r.toString(), r.getString("error").contains("too long"));
        }
    }

    @Test
    public void peerPolicy() {
        int me = Process.myUid(), pid = Process.myPid();
        assertTrue(HeadlessServer.isAllowedPeer(0, 1));
        assertTrue(HeadlessServer.isAllowedPeer(2000, 1234));
        assertTrue(HeadlessServer.isAllowedPeer(me, pid));
        // Same uid, other process: a userland guest process.
        assertFalse(HeadlessServer.isAllowedPeer(me, pid + 1));
        assertFalse(HeadlessServer.isAllowedPeer(1000, pid));     // system
        assertFalse(HeadlessServer.isAllowedPeer(me + 1, pid));
        assertFalse(HeadlessServer.isAllowedPeer(10999, pid));    // another app
    }

    @Test
    public void helloProvesTheServerKeyThenServesTheRequest() throws Exception {
        String key = HeadlessServer.keyHex();
        assertNotNull(key);
        String nonce = "00112233445566778899aabbccddeeff";
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        byte[] k = new byte[key.length() / 2];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) Integer.parseInt(key.substring(2 * i, 2 * i + 2), 16);
        }
        mac.init(new javax.crypto.spec.SecretKeySpec(k, "HmacSHA256"));
        StringBuilder want = new StringBuilder();
        for (byte b : mac.doFinal(("gterm-hello-v1:" + nonce).getBytes(StandardCharsets.UTF_8))) {
            want.append(String.format("%02x", b & 0xff));
        }
        try (Client c = new Client()) {
            JSONObject h = c.request(new JSONObject().put("op", "hello").put("nonce", nonce));
            assertTrue(h.toString(), h.getBoolean("ok"));
            assertEquals(want.toString(), h.getString("proof"));
            JSONObject r = c.request(req("status"));   // same connection
            assertTrue(r.toString(), r.getBoolean("ok"));
            assertTrue(r.has("rootfs"));
        }
        try (Client c = new Client()) {
            JSONObject h = c.request(new JSONObject().put("op", "hello").put("nonce", "xyz"));
            assertFalse(h.getBoolean("ok"));
        }
    }

    // --- interactive sessions -------------------------------------------------

    @Test
    public void spawnAttachedShellRoundTripAndExitCode() throws Exception {
        int id = -1;
        try (Client c = new Client()) {
            JSONObject r = c.request(req("spawn").put("type", "shell")
                    .put("cols", 100).put("rows", 30));
            assertTrue(r.toString(), r.getBoolean("ok"));
            id = r.getInt("id");
            int sid = id;
            assertEquals("shell", r.getString("type"));
            // mksh drops typeahead when its line editor starts: wait for the prompt.
            c.pumpUntilData("$ ");

            c.type("echo hl-$((6*7)); stty size\n");
            c.pumpUntilData("hl-42");
            c.pumpUntilData("30 100");

            JSONObject listed = findSession(id);
            assertNotNull(listed);
            assertTrue(listed.getBoolean("attached"));
            assertEquals(100, listed.getInt("cols"));

            // A RESIZE frame reaches the PTY.
            c.send(Frames.RESIZE, new byte[] {0, 90, 0, 20});
            c.type("stty size\n");
            c.pumpUntilData("20 90");

            c.type("exit 7\n");
            assertEquals(7, c.pumpUntilExit());
            // Nobody else listens to a headless session: the reaper drops it.
            waitFor("session reaped", TIMEOUT_MS, () -> !sessionListed(sid));
        } finally {
            if (id >= 0) call(req("kill").put("id", id)); // a failed run leaves no shell
        }
    }

    /**
     * A command's last output reaches the client although the process exits
     * right after writing it: the reaper must not close the PTY before the
     * reader has drained it.
     */
    @Test
    public void spawnedCommandOutputTailSurvivesExit() throws Exception {
        int id = -1;
        try (Client c = new Client()) {
            JSONObject r = c.request(req("spawn").put("type", "shell")
                    .put("argv", new JSONArray().put("sh").put("-c")
                            .put("seq 1 20000; echo tail-END")));
            assertTrue(r.toString(), r.getBoolean("ok"));
            id = r.getInt("id");
            assertEquals(0, c.pumpUntilExit());
            String out = c.data();
            assertTrue(out.length() > 200 ? out.substring(out.length() - 200) : out,
                    out.contains("19999\r\n20000\r\ntail-END"));
        } finally {
            if (id >= 0) call(req("kill").put("id", id));
        }
    }

    @Test
    public void detachedSpawnThenAttachAndKill() throws Exception {
        JSONObject r = call(req("spawn").put("type", "shell").put("attach", false));
        assertTrue(r.toString(), r.getBoolean("ok"));
        int id = r.getInt("id");
        try {
            assertFalse(findSession(id).getBoolean("attached"));
            waitForPrompt(id);

            try (Client a = new Client()) {
                JSONObject ar = a.request(req("attach").put("id", id)
                        .put("cols", 81).put("rows", 22));
                assertTrue(ar.toString(), ar.getBoolean("ok"));
                assertEquals(81, ar.getInt("cols"));
                assertEquals(22, ar.getInt("rows"));
                a.type("stty size\n");
                a.pumpUntilData("22 81");

                JSONObject k = call(req("kill").put("id", id));
                assertTrue(k.toString(), k.getBoolean("ok"));
                a.pumpUntilExit();
                assertFalse(sessionListed(id));
            }
            assertFalse(call(req("kill").put("id", id)).getBoolean("ok"));
        } finally {
            call(req("kill").put("id", id)); // a no-op unless an assertion failed
        }
    }

    @Test
    public void secondAttachStealsTheFirst() throws Exception {
        int id = call(req("spawn").put("type", "shell").put("attach", false)).getInt("id");
        waitForPrompt(id);
        try (Client first = new Client(); Client second = new Client()) {
            assertTrue(first.request(req("attach").put("id", id)).getBoolean("ok"));
            assertTrue(second.request(req("attach").put("id", id)).getBoolean("ok"));
            // The first connection is dropped without an EXIT.
            while (first.pump()) { /* drain */ }
            assertEquals(null, first.exit);
            second.type("echo st-$((1+1))\n");
            second.pumpUntilData("st-2");
        } finally {
            call(req("kill").put("id", id));
        }
    }

    // --- exec -----------------------------------------------------------------

    @Test
    public void execSeparatesStreamsAndReturnsExitCode() throws Exception {
        try (Client c = new Client()) {
            JSONObject r = c.request(req("exec").put("type", "shell")
                    .put("cmd", "echo out; echo err >&2; exit 3"));
            assertTrue(r.toString(), r.getBoolean("ok"));
            assertEquals(3, c.pumpUntilExit());
            assertEquals("out\n", c.data());
            assertEquals("err\n", new String(c.stderr.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void execPassesArgvExactlyAndEnv() throws Exception {
        try (Client c = new Client()) {
            JSONObject r = c.request(req("exec").put("type", "shell")
                    .put("argv", new JSONArray().put("sh").put("-c")
                            .put("printf '%s|' \"$0\" \"$1\" \"$GT_VAR\"; pwd")
                            .put("a b").put("c'd"))
                    .put("env", new JSONObject().put("GT_VAR", "x y"))
                    .put("cwd", "/system"));
            assertTrue(r.toString(), r.getBoolean("ok"));
            assertEquals(0, c.pumpUntilExit());
            assertEquals("a b|c'd|x y|/system\n", c.data());
        }
    }

    @Test
    public void execStreamsStdinUntilEof() throws Exception {
        try (Client c = new Client()) {
            assertTrue(c.request(req("exec").put("type", "shell")
                    .put("argv", new JSONArray().put("cat"))).getBoolean("ok"));
            c.type("line one\n");
            c.type("line two\n");
            c.send(Frames.EOF, new byte[0]);
            assertEquals(0, c.pumpUntilExit());
            assertEquals("line one\nline two\n", c.data());
        }
    }

    @Test
    public void signalOpReachesExecFromAnotherConnection() throws Exception {
        try (Client c = new Client(); Client side = new Client()) {
            JSONObject r = c.request(req("exec").put("type", "shell")
                    .put("cmd", "echo ready; exec sleep 30"));
            assertTrue(r.toString(), r.getBoolean("ok"));
            c.pumpUntilData("ready");
            JSONObject s = side.request(req("signal").put("pid", r.getInt("pid")).put("sig", 2));
            assertTrue(s.toString(), s.getBoolean("ok"));
            int code = c.pumpUntilExit();
            assertTrue("exit " + code, code == -2 || code == 130);
        }
    }

    @Test
    public void signalOpRefusesUnknownPid() throws Exception {
        // One request per connection, so this is its own client. No exec has pid 1.
        try (Client c = new Client()) {
            JSONObject gone = c.request(req("signal").put("pid", 1).put("sig", 2));
            assertFalse(gone.toString(), gone.getBoolean("ok"));
        }
    }

    @Test
    public void execSignalKillsProcessGroup() throws Exception {
        try (Client c = new Client()) {
            assertTrue(c.request(req("exec").put("type", "shell")
                    .put("cmd", "echo ready; exec sleep 30")).getBoolean("ok"));
            c.pumpUntilData("ready");
            // exec: no sh left waiting on a foreground child (which would hold
            // a SIGINT until sleep ends). Whether it lands on sh just before the
            // exec or on sleep after, the default disposition ends the group.
            c.send(Frames.SIGNAL, new byte[] {2}); // SIGINT
            int code = c.pumpUntilExit();
            assertTrue("exit " + code, code == -2 || code == 130);
        }
    }

    /**
     * Output is drained for as long as it keeps moving, however slowly the
     * client reads: here a background writer holds the pipe after sh exits,
     * and the client reads nothing for longer than the idle bound.
     */
    @Test
    public void execDrainsOutputForASlowClient() throws Exception {
        final int size = 8 << 20;
        try (Client c = new Client()) {
            assertTrue(c.request(req("exec").put("type", "shell")
                    .put("cmd", "head -c " + size + " /dev/zero &")).getBoolean("ok"));
            // A stalled reader, not a timing bet: whatever the scheduling, the
            // pipe stays busy (blocked on this client) the whole time.
            Thread.sleep(4500);
            assertEquals(0, c.pumpUntilExit());
            assertEquals(size, c.data.size());
            assertFalse(c.pump()); // nothing after EXIT; the server closed
        }
    }

    /**
     * A client that leaves while the command ignores its stdin (the 8 MiB
     * queue full, the frame loop waiting for room) still hangs the command up.
     */
    @Test
    public void execClientHangupWhileStdinBackedUpKillsTheCommand() throws Exception {
        Client c = new Client();
        try {
            JSONObject r = c.request(req("exec").put("type", "shell")
                    .put("argv", new JSONArray().put("sleep").put("120")));
            assertTrue(r.toString(), r.getBoolean("ok"));
            int pid = r.getInt("pid");
            final long[] written = {0};
            Thread writer = new Thread(() -> {
                byte[] chunk = Frames.encode(Frames.DATA, new byte[1 << 20]);
                try {
                    for (int i = 0; i < 16; i++) {
                        c.out.write(chunk);
                        c.out.flush();
                        synchronized (written) {
                            written[0] += 1 << 20;
                        }
                    }
                } catch (IOException ignored) {
                    // Expected: the socket is shut down under the blocked write.
                }
            });
            writer.start();
            // Past 9 MiB the server holds a full queue plus one frame it waits
            // to queue: the frame loop is no longer reading the socket.
            waitFor("stdin backed up", TIMEOUT_MS, () -> {
                synchronized (written) {
                    return written[0] >= 9 << 20;
                }
            });
            c.sock.shutdownOutput();
            c.close();
            writer.join(TIMEOUT_MS);
            // SIGHUP ends sleep; the exec is then gone from the signal op.
            waitFor("command hung up", TIMEOUT_MS, () -> {
                try {
                    return !call(req("signal").put("pid", pid).put("sig", 28))
                            .getBoolean("ok");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        } finally {
            c.close();
        }
    }

    @Test
    public void execInUserland() throws Exception {
        assumeTrue("no usable userland rootfs installed",
                UserlandRootfs.isUsable(ctx()));
        try (Client c = new Client()) {
            JSONObject r = c.request(req("exec").put("type", "userland")
                    .put("argv", new JSONArray().put("sh").put("-c")
                            .put("echo guest-$((2+3)); test -e /etc/os-release && echo has-os; exit 4")));
            assertTrue(r.toString(), r.getBoolean("ok"));
            int code = c.pumpUntilExit();
            assertEquals(c.data() + new String(c.stderr.toByteArray(), StandardCharsets.UTF_8),
                    4, code);
            assertTrue(c.data(), c.data().contains("guest-5"));
            assertTrue(c.data(), c.data().contains("has-os"));
        }
    }

    // --- TerminalSession tap semantics ------------------------------------------

    private static String screen(TerminalSession s) {
        ScreenSnapshot snap = new ScreenSnapshot();
        s.emulator.snapshot(snap);
        return snap.text();
    }

    /** The probe's output (its echoed command line reads len[${#reply}]). */
    private static final java.util.regex.Pattern LEN =
            java.util.regex.Pattern.compile("len\\[\\d+\\]");

    /** A cursor report as the tty echoes it back with ECHOCTL. */
    private static final java.util.regex.Pattern ECHOED_REPLY =
            java.util.regex.Pattern.compile("\\^\\[\\[\\d+;\\d+R");

    private static final TerminalSession.OutputTap NULL_TAP = new TerminalSession.OutputTap() {
        @Override public void onOutput(byte[] buf, int len) {}
        @Override public void onEnd(int exitCode) {}
    };

    @Test
    public void queryRepliesSuppressedOnlyWhileTapped() throws Exception {
        Context c = ctx();
        SessionCommand cmd = SessionCommand.androidShell(c);
        TerminalSession s = new TerminalSession(80, 24, 8, 16, 1000, cmd, false, null, NULL_TAP);
        try {
            // Ask for a cursor report and read a line: whatever arrived on stdin
            // in the meantime is the reply. The sleep proves an absence, which no
            // condition can poll for; the newline then ends the read either way.
            String probe = "printf '\\033[6n'; read -r reply; echo \"len[${#reply}]\"\n";
            // mksh drops typeahead when its line editor starts: wait for the prompt.
            waitFor("prompt", TIMEOUT_MS, () -> screen(s).contains("$"), () -> screen(s));
            s.write(probe);
            Thread.sleep(1000);
            s.write("\n");
            waitFor("tapped probe", TIMEOUT_MS, () -> LEN.matcher(screen(s)).find(),
                    () -> screen(s));
            assertTrue(screen(s), screen(s).contains("len[0]"));

            s.setTap(null);
            s.write("clear\n");
            waitFor("cleared", TIMEOUT_MS, () -> !screen(s).contains("len[0]"), () -> screen(s));
            s.write(probe);
            // The reply lands in the tty's line buffer, which echoes it back
            // (ECHOCTL: "^[[24;1R"); only then may the newline end the read.
            waitFor("reply echoed", TIMEOUT_MS, () -> ECHOED_REPLY.matcher(screen(s)).find(),
                    () -> screen(s));
            s.write("\n");
            waitFor("untapped probe", TIMEOUT_MS, () -> LEN.matcher(screen(s)).find(),
                    () -> screen(s));
            assertFalse(screen(s), screen(s).contains("len[0]"));
        } finally {
            s.close();
        }
    }

    @Test
    public void uiResizeDeferredWhileTapped() throws Exception {
        Context c = ctx();
        SessionCommand cmd = SessionCommand.androidShell(c);
        TerminalSession s = new TerminalSession(80, 24, 8, 16, 1000, cmd, false, null, NULL_TAP);
        try {
            s.resize(50, 10, 8, 16);           // the phone's grid: deferred
            assertEquals(80, s.cols());
            s.resizeExternal(120, 40);         // the remote terminal's: applied
            assertEquals(120, s.cols());
            assertEquals(40, s.rows());
            s.setTap(null);                    // detach applies the deferred UI size
            assertEquals(50, s.cols());
            assertEquals(10, s.rows());
        } finally {
            s.close();
        }
    }

    @Test
    public void sessionsCreatedThroughManagerAreReapedWithoutListener() throws Exception {
        Context c = ctx();
        SessionCommand cmd = SessionCommand.androidShell(c);
        TerminalSession s = SessionManager.get().create(cmd, 80, 24, 8, 16, 1000, false,
                null, null);
        waitFor("prompt", TIMEOUT_MS, () -> screen(s).contains("$"), () -> screen(s));
        s.write("exit 0\n");
        waitFor("reaped", TIMEOUT_MS, () -> SessionManager.get().indexOf(s) < 0);
    }
}

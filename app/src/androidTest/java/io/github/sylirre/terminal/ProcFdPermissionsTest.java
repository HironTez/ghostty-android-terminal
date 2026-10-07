/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.SessionCommand;
import io.github.sylirre.terminal.term.TerminalNative;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * Offline regression for systemd's ordinary-fd permission-copy fallback.
 * Uses a fresh Debian or Alpine asset installation in a wrapped Context, never
 * files/userland, SessionManager, AppSettings or the user's selected engine.
 * Both default methods explicitly select arm64chroot on BOTH host ABIs.
 * The raw AArch64 guest is an androidTest asset, interpreted/JITed in-process;
 * nothing copied to app data is exec'd by the host (Android W^X is unchanged).
 *
 * Optional runner argument procFdDistro=debian|alpine selects the bundled asset.
 * chroot-ng is separate and opt-in via procFdNativeDiagnostics=true; its result
 * does not establish coverage of arm64chroot's regressed common C resolver.
 * Physical ARM64 Android 17 interpreter/JIT validation remains necessary.
 */
@RunWith(AndroidJUnit4.class)
public class ProcFdPermissionsTest {
    private static final String TAG = "ProcFdPermissionsTest";
    private static final String PROBE = "probes/proc-fd-permissions.aarch64";
    private static final String SENTINEL = "outside guest root: must remain untouched\n";
    private static final long TIMEOUT_MS = 45_000;
    private static final int REPORT_LIMIT = 8192;
    private TerminalSession session;
    private File files;
    private File root;
    private File status;
    private boolean passed;

    private final TerminalSession.Listener listener = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onExited(TerminalSession s, int code) {
            Log.i(TAG, "guest exited: " + code);
        }
    };

    @Test
    public void arm64chrootInterpreter() throws Exception {
        runProbe(false, false);
    }

    @Test
    public void arm64chrootJitRequested() throws Exception {
        // --jit is a request, not proof of actual JIT use: W^X fallback to the
        // interpreter remains valid. x86_64 tests cannot validate ARM-to-ARM JIT.
        runProbe(true, false);
    }

    @Test
    public void nativeArmEngineOptIn() throws Exception {
        assumeTrue("native-engine diagnostics require explicit opt-in",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("procFdNativeDiagnostics", "false")));
        assumeTrue("chroot-ng is only bundled on supported ARM64 builds",
                TerminalNative.hasChrootNg());
        runProbe(false, true);
    }

    @After
    public void finish() throws Exception {
        if (session != null) session.close();
        if (files == null) return;
        if (passed) {
            // Successful runs do not accumulate entire distro installations.
            // lstat prevents following Alpine's absolute symlinks or escape.
            deleteOwnTree(files);
            // rmdir: removes the shared parent only once no retained
            // diagnostic root (from an earlier failed run) is left in it.
            files.getParentFile().delete();
        } else {
            Log.e(TAG, "retained private diagnostic root: " + root + "\n" + diagnostic());
        }
    }

    private void runProbe(boolean jit, boolean nativeEngine) throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        UserlandDistro distro = selectDistro(app);
        assumeTrue("no bundled Debian or Alpine rootfs asset", distro != null);
        files = new File(app.getFilesDir(), "proc-fd-permissions-tests/"
                + distro.id + "-" + UUID.randomUUID());
        if (!files.mkdirs()) throw new IOException("cannot create " + files);
        Context isolated = new ContextWrapper(app) {
            @Override public File getFilesDir() { return files; }
        };
        UserlandRootfs.install(isolated, distro.assetName, null);
        root = UserlandRootfs.dir(isolated);
        status = new File(root, "tmp/proc-fd-permissions.status");
        File guestProbe = new File(root, "tmp/proc-fd-permissions.aarch64");
        // The probe belongs to the TEST APK, not app.getAssets(). Never package
        // it as a production executable or try host ProcessBuilder/execve.
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(PROBE);
                FileOutputStream out = new FileOutputStream(guestProbe)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        Os.chmod(guestProbe.getAbsolutePath(), 0755);
        File sentinel = new File(files, "host-sentinel");
        write(sentinel, SENTINEL);
        Os.chmod(sentinel.getAbsolutePath(), 0600);
        write(new File(root, "tmp/proc-fd-host-target"), sentinel.getAbsolutePath());
        Log.i(TAG, "asset=" + distro.assetName + "; root=" + root
                + "; jitRequested=" + jit + "; nativeEngine=" + nativeEngine);

        UserlandOptions options = new UserlandOptions("/tmp/proc-fd-permissions.aarch64",
                false, "0:0", "/root", "/root", "C.UTF-8",
                UserlandOptions.DEFAULT_PATH, jit, 32, nativeEngine);
        SessionCommand command = UserlandRootfs.command(isolated, options);
        assertEquals("must not inherit the user's engine preference",
                nativeEngine ? "chroot-ng" : "arm64chroot", command.argv[0]);
        session = new TerminalSession(100, 30, 8, 16, 1000, command, true, listener);
        // Direct guest execution: there is no shell command or PTY echo that
        // could accidentally satisfy an assertion. The probe atomically renames
        // its completed, bounded result file before exiting.
        waitFor("raw guest proc-fd permission results", TIMEOUT_MS,
                () -> status.isFile() || session.exitCode() != null, this::diagnostic);
        assertTrue("guest exited without publishing results\n" + diagnostic(), status.isFile());
        waitFor("guest exit", TIMEOUT_MS, () -> session.exitCode() != null, this::diagnostic);
        String report = readBounded(status, REPORT_LIMIT);
        Map<String, Long> values = parseReport(report);
        String detail = "root=" + root + "\n" + report;
        assertValue(values, "protocol", 1, detail);
        assertValue(values, "complete", 1, detail);
        assertValue(values, "ordinary_fd", 1, detail);
        assertValue(values, "ordinary_unlinked_fd", 1, detail);
        assertTrue("numeric getpid must be valid\n" + detail,
                values.containsKey("pid") && values.get("pid") > 0);
        // ENOSYS is intentionally not patched away. A future syscall 452
        // implementation is allowed, but must really change the fd's mode.
        Long modern = values.get("fchmodat2.rc");
        assertTrue("unexpected raw fchmodat2 result\n" + detail,
                modern != null && (modern == 0 || modern == -38 || modern == -1));
        assertValue(values, "modern.reset", 0, detail);
        assertValue(values, "fchmodat2.mode", modern == 0 ? 0640 : 0600, detail);
        for (String alias : new String[] {"numeric", "self", "dev"}) {
            for (String state : new String[] {"named", "unlinked"}) {
                String prefix = state + "." + alias;
                assertValue(values, prefix + ".reset", 0, detail);
                assertValue(values, prefix + ".before", 0600, detail);
                assertValue(values, prefix + ".rc", 0, detail);
                assertValue(values, prefix + ".mode", 0644, detail);
            }
            assertValue(values, "closed." + alias + ".rc", -2, detail);
            String prefix = "directory." + alias;
            assertValue(values, prefix + ".reset", 0, detail);
            assertValue(values, prefix + ".rc", 0, detail);
            assertValue(values, prefix + ".mode", 0644, detail);
            assertValue(values, prefix + ".escape_open", -2, detail);
            assertValue(values, prefix + ".escape_chmod", -2, detail);
        }
        assertValue(values, "unlinked.unlink", 0, detail);
        assertValue(values, "closed.close", 0, detail);
        assertValue(values, "closed.fstat", -9, detail);
        assertValue(values, "containment.mkdir", 0, detail);
        assertValue(values, "containment.symlink", 0, detail);
        for (String key : new String[] {"containment.child_open", "containment.directory_open"}) {
            assertTrue(key + "\n" + detail, values.containsKey(key) && values.get(key) >= 0);
        }
        assertValue(values, "failures", 0, detail);
        assertEquals(detail, Integer.valueOf(0), session.exitCode());
        // Independent host observations: a guest report cannot fake the chmod
        // effect, unlink or outside-root containment. Unlinked inode modes are
        // necessarily checked by the guest's still-open fd before it closes.
        assertEquals(detail, 0644, Os.stat(new File(root,
                "tmp/proc-fd-ordinary").getAbsolutePath()).st_mode & 07777);
        assertFalse(detail, new File(root, "tmp/proc-fd-unlinked").exists());
        assertEquals(detail, 0644, Os.stat(new File(root,
                "tmp/proc-fd-dir/child").getAbsolutePath()).st_mode & 07777);
        assertEquals(detail, 0600, Os.stat(sentinel.getAbsolutePath()).st_mode & 07777);
        assertEquals(detail, SENTINEL, readBounded(sentinel, 128));
        Log.i(TAG, detail);
        Log.i(TAG, "host assertions passed: ordinary=0644; unlinked absent; child=0644; "
                + "outside-root sentinel=0600 and content unchanged; guest exitCode=" + session.exitCode());
        passed = true;
    }

    private static UserlandDistro selectDistro(Context app) throws IOException {
        String requested = InstrumentationRegistry.getArguments().getString("procFdDistro", "");
        if (!requested.isEmpty() && !requested.equals("debian") && !requested.equals("alpine")) {
            throw new IllegalArgumentException("procFdDistro must be debian or alpine");
        }
        List<UserlandDistro> bundled = UserlandDistro.bundled(app);
        for (String id : requested.isEmpty()
                ? new String[] {"debian", "alpine"} : new String[] {requested}) {
            for (UserlandDistro d : bundled) if (id.equals(d.id)) return d;
        }
        return null;
    }

    private static Map<String, Long> parseReport(String report) {
        Map<String, Long> values = new HashMap<>();
        for (String line : report.split("\n")) {
            assertTrue("invalid guest report line: " + line,
                    line.matches("[a-z0-9_.]+=-?[0-9]+"));
            int eq = line.indexOf('=');
            String key = line.substring(0, eq);
            assertFalse("duplicate guest report key: " + key, values.containsKey(key));
            values.put(key, Long.parseLong(line.substring(eq + 1)));
        }
        return values;
    }

    private static void assertValue(Map<String, Long> values, String key,
            long expected, String detail) {
        assertEquals(key + "\n" + detail, Long.valueOf(expected), values.get(key));
    }

    private String diagnostic() {
        String result = "private root=" + root;
        if (status != null) {
            try {
                File readable = status.isFile() ? status
                        : new File(root, "tmp/proc-fd-permissions.status.tmp");
                result += "\n" + readBounded(readable, REPORT_LIMIT);
            } catch (IOException e) { result += "\n" + e; }
        }
        if (session != null) {
            ScreenSnapshot snapshot = new ScreenSnapshot();
            session.emulator.snapshot(snapshot);
            result += "\nexit=" + session.exitCode() + "\nPTY:\n" + snapshot.text();
        }
        return result;
    }

    private static String readBounded(File file, int limit) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.getChannel().size() > limit) throw new IOException("oversized result: " + file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Called only on this method's UUID directory after a successful guest exit. */
    private static void deleteOwnTree(File file) throws IOException, ErrnoException {
        if (OsConstants.S_ISDIR(Os.lstat(file.getAbsolutePath()).st_mode)) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("cannot list " + file);
            for (File child : children) deleteOwnTree(child);
        }
        if (!file.delete()) throw new IOException("cannot delete " + file);
    }
}

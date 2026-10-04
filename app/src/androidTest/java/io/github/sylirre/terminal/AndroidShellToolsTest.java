/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;
import static org.junit.Assert.*;

import android.content.Context;
import android.system.Os;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.sylirre.terminal.term.AndroidShellTools;
import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.SessionCommand;
import io.github.sylirre.terminal.term.TerminalSession;

/** Run under the app UID/SELinux domain, never adb shell's more privileged UID. */
@RunWith(AndroidJUnit4.class)
public class AndroidShellToolsTest {
    private Context context;
    private TerminalSession session;
    private File scratch;
    private int commandId;
    private static final TerminalSession.Listener LISTENER = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onExited(TerminalSession s, int code) {}
    };

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        scratch = Files.createTempDirectory(context.getCacheDir().toPath(), "tools-test-").toFile();
        session = new TerminalSession(160, 48, 8, 16, 10_000, context, LISTENER);
        run("export HOME=" + quote(scratch.getAbsolutePath()) + "; mkdir -p \"$HOME/.ssh\"; chmod 700 \"$HOME/.ssh\"", 0);
    }

    @After public void tearDown() throws Exception {
        if (session != null) session.close();
        if (scratch != null) {
            try (var paths = Files.walk(scratch.toPath())) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(p);
            }
        }
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private String screen() {
        ScreenSnapshot snap = new ScreenSnapshot();
        session.emulator.snapshot(snap);
        return snap.text();
    }
    /** Output/result files avoid false positives from PTY input echo and wrapping. */
    private String run(String command, int expected) throws Exception {
        int id = ++commandId;
        File output = new File(scratch, "out-" + id);
        File result = new File(scratch, "result-" + id);
        session.write("{ " + command + "; } >" + quote(output.getPath()) + " 2>&1; "
                + "printf '%s' \"$?\" >" + quote(result.getPath()) + "\n");
        waitFor("tool command " + id, 30_000,
                () -> result.isFile() && result.length() > 0, this::screen);
        String text = read(output);
        android.util.Log.i("AndroidShellToolsTest", command + "\nexit=" + read(result) + "\n" + text);
        assertEquals(command + "\n" + text, expected, Integer.parseInt(read(result)));
        return text;
    }

    @Test public void aliasesAndToolboxExecuteFromInstallerDirectory() throws Exception {
        File bin = AndroidShellTools.prepare(context);
        for (String cmd : new String[] {"busybox", "ssh", "scp", "sftp", "ssh-keygen", "awk", "tar"}) {
            String target = Os.readlink(new File(bin, cmd).getPath());
            assertTrue(target, target.startsWith(context.getApplicationInfo().nativeLibraryDir + "/lib"));
            assertTrue(new File(target).canExecute());
        }
        assertFalse(new File(bin, "sh").exists());
        String env = String.join("\n", SessionCommand.androidShell(context).env);
        assertTrue(env, env.contains("PATH=" + bin.getAbsolutePath() + ":/system/bin"));
        assertTrue(env, env.contains("SHELL=/system/bin/sh"));
        assertTrue(run("busybox --list", 0).contains("awk\n"));
        assertTrue(run(quote(context.getApplicationInfo().nativeLibraryDir + "/libbusybox.so") + " --list", 0).contains("tar\n"));
        assertEquals("BETA\n", run("printf 'alpha\\nbeta\\n' | grep beta | awk '{print toupper($0)}'", 0));
        run("printf archive-test >\"$HOME/file\"; tar -czf \"$HOME/test.tgz\" -C \"$HOME\" file; "
                + "mkdir \"$HOME/unpacked\"; tar -xzf \"$HOME/test.tgz\" -C \"$HOME/unpacked\"; "
                + "cmp \"$HOME/file\" \"$HOME/unpacked/file\"", 0);
        assertTrue(run("ssh -V", 0).contains("OpenSSH_10.5p1"));
    }

    @Test public void keysConfigAndKnownHostsUseHomeNotBionicData() throws Exception {
        // No -f override: tests default passwd HOME, not merely an explicit path.
        run("printf '\\n' | ssh-keygen -q -t ed25519 -N 'test-passphrase'", 0);
        File key = new File(scratch, ".ssh/id_ed25519");
        assertTrue(key.isFile());
        assertTrue(new File(scratch, ".ssh/id_ed25519.pub").isFile());
        assertTrue(run("ssh-keygen -y -P 'test-passphrase' -f \"$HOME/.ssh/id_ed25519\"", 0).startsWith("ssh-ed25519 "));
        write(new File(scratch, ".ssh/config"),
                "Host tools-home-test\n HostName localhost\n User fixture-user\n Port 2222\n");
        String config = run("ssh -G tools-home-test", 0);
        assertTrue(config, config.contains("hostname localhost\n"));
        assertTrue(config, config.contains("user fixture-user\n"));
        assertTrue(config, config.contains("port 2222\n"));
        String pub = read(new File(scratch, ".ssh/id_ed25519.pub")).trim();
        File hosts = new File(scratch, ".ssh/known_hosts");
        write(hosts, "remove-test " + pub + "\nkeep-test " + pub + "\n");
        run("ssh-keygen -R remove-test", 0);
        String rewritten = read(hosts);
        assertFalse(rewritten, rewritten.contains("remove-test"));
        assertTrue(rewritten, rewritten.contains("keep-test"));
        assertTrue(new File(scratch, ".ssh/known_hosts.old").isFile());
        run("ssh-keygen -H", 0);
        assertTrue(read(hosts).startsWith("|1|"));
        run("ssh-keygen -F keep-test", 0);
        run("ssh-keygen -q -t rsa -b 2048 -N '' -f \"$HOME/.ssh/id_rsa\"", 0);
        run("ssh-keygen -q -t ecdsa -b 256 -N '' -f \"$HOME/.ssh/id_ecdsa\"", 0);
    }

    @Test public void appDataCopiesRemainNonExecutable() throws Exception {
        File copy = new File(scratch, "copied-ssh");
        Files.copy(new File(context.getApplicationInfo().nativeLibraryDir, "libssh.so").toPath(), copy.toPath());
        Os.chmod(copy.getPath(), 0755);
        // This must fail under the app domain even though the Unix mode has +x.
        String denied = run(quote(copy.getPath()) + " -V", 126);
        assertTrue(denied, denied.toLowerCase(java.util.Locale.ROOT).contains("permission denied"));
        assertTrue(run("ssh -V", 0).contains("OpenSSH_10.5p1"));
    }

    @Test public void clientsResolveSshAndProxyShellWithoutUsrBin() throws Exception {
        // An intentional proxy failure exercises helper exec and /system/bin/sh
        // without relying on any external network or test server.
        write(new File(scratch, "upload"), "fixture-file");
        // OpenSSH prefixes ProxyCommand with exec. A bare printf proxy can
        // close its stdin before the client writes its banner, so SIGPIPE/
        // proxy cleanup races the diagnostic. Consume that banner before
        // closing, and invoke the script via the actual Android shell.
        write(new File(scratch, "proxy.sh"),
                "printf 'ANDROID_PROXY_OK\\n' >&2\nIFS= read -r banner\nexit 1\n");
        String options = "-o 'ProxyCommand=/system/bin/sh \"$HOME/proxy.sh\"' -o ConnectTimeout=2";
        String ssh = run("ssh " + options + " fixture.invalid true", 255);
        assertTrue(ssh, ssh.contains("ANDROID_PROXY_OK"));
        String scp = run("scp " + options + " \"$HOME/upload\" fixture.invalid:/test", 255);
        assertTrue(scp, scp.contains("ANDROID_PROXY_OK"));
        String sftp = run("sftp " + options + " fixture.invalid </dev/null", 255);
        assertTrue(sftp, sftp.contains("ANDROID_PROXY_OK"));
    }

    @Test public void upgradeRefreshesManagedLinksAndPreservesRegularFiles() throws Exception {
        File bin = AndroidShellTools.prepare(context);
        File ssh = new File(bin, "ssh");
        Files.delete(ssh.toPath());
        Os.symlink("/obsolete-install/libssh.so", ssh.getPath());
        AndroidShellTools.prepare(context);
        assertEquals(context.getApplicationInfo().nativeLibraryDir + "/libssh.so", Os.readlink(ssh.getPath()));
        Files.delete(ssh.toPath());
        write(ssh, "user-file");
        try {
            AndroidShellTools.prepare(context);
            fail("must not overwrite a regular file");
        } catch (java.io.IOException expected) {
            assertEquals("user-file", read(ssh));
        } finally {
            Files.delete(ssh.toPath());
            AndroidShellTools.prepare(context);
        }
    }
}

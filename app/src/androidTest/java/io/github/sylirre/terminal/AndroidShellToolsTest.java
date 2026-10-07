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
        // mksh drops typeahead when its line editor starts: wait for the prompt.
        waitFor("shell prompt", 30_000, () -> screen().contains("$"), this::screen);
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
        // The live shell must resolve these through the aliases, not
        // /system/bin: toybox (and Android's awk) would pass the pipeline and
        // idiom checks below just as well, so those alone prove nothing.
        String b = bin.getAbsolutePath() + "/";
        assertEquals(b + "awk\n" + b + "grep\n" + b + "head\n" + b + "tar\n" + b + "ssh\n",
                run("for c in awk grep head tar ssh; do command -v $c; done", 0));
        assertTrue(run("busybox --list", 0).contains("awk\n"));
        assertTrue(run(quote(context.getApplicationInfo().nativeLibraryDir + "/libbusybox.so") + " --list", 0).contains("tar\n"));
        assertEquals("BETA\n", run("printf 'alpha\\nbeta\\n' | grep beta | awk '{print toupper($0)}'", 0));
        // Aliases shadow toybox, so common toybox idioms must keep working.
        assertEquals("one\none\nabc|x", run("printf 'one\\ntwo\\n' | head -1; printf 'one\\ntwo\\n' | head -n 1; "
                + "printf abcdef | head -c 3; env echo -n '|x'", 0));
        run("cd \"$HOME\" && printf x >f && sha256sum f >f.sha && sha256sum -c f.sha && "
                + "echo f | xargs -I{} test -e {} && find . -maxdepth 1 -name f -newer /system | grep -q f && "
                + "printf '\\033[1mB\\033[0m\\n' | less -R >/dev/null && date -I >/dev/null", 0);
        run("printf archive-test >\"$HOME/file\"; tar -czf \"$HOME/test.tgz\" -C \"$HOME\" file; "
                + "mkdir \"$HOME/unpacked\"; tar -xzf \"$HOME/test.tgz\" -C \"$HOME/unpacked\"; "
                + "cmp \"$HOME/file\" \"$HOME/unpacked/file\"", 0);
        assertTrue(run("ssh -V", 0).contains("OpenSSH_10.5p1"));
    }

    /**
     * Every aliased applet enters its main() without crashing. Found on a
     * real arm64 device: clang hoisted awk/diff's G.x loads above
     * SET_PTR_TO_GLOBALS (SIGSEGV), which `--help` alone does not reach.
     */
    @Test public void everyAppletRunsWithoutCrashing() throws Exception {
        String out = run("mkdir \"$HOME/smoke\" && cd \"$HOME/smoke\" && "
                + "for a in $(busybox --list); do case $a in vi|less|yes) continue;; esac; "
                + "busybox $a </dev/null >/dev/null 2>&1; r=$?; [ $r -lt 128 ] || echo \"CRASH $a $r\"; done; "
                + "printf 'a\\nb\\n' >x; printf 'a\\nc\\n' >y; diff x y | tail -n 2 | tr -d '\\n'; echo", 0);
        assertEquals("-b+c\n", out);
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

    /**
     * Two concurrent ssh-keygen -R writers and a reader: the live known_hosts
     * must always exist and be complete (atomic replace, never renamed away),
     * and no writer may resurrect a host the other removed (flock serializes
     * the read-modify-write cycles).
     */
    @Test public void knownHostsRewritesAreAtomicAndSerialized() throws Exception {
        run("cd \"$HOME/.ssh\" && ssh-keygen -q -t ed25519 -N '' -f fixture && pub=$(cat fixture.pub) && "
                + "{ echo \"keep-host $pub\"; for i in $(seq 1 40); do echo \"host$i $pub\"; done; } >known_hosts", 0);
        // A script file, so the interactive shell's job-control notices
        // ("[1] 1234") stay out of the output.
        write(new File(scratch, "race.sh"), "cd \"$HOME/.ssh\"; rm -f stop\n"
                + "( while [ ! -e stop ]; do grep -q '^keep-host ' known_hosts || echo READER-MISSING; done ) & r=$!\n"
                + "( for i in $(seq 1 20); do ssh-keygen -R host$i >/dev/null 2>&1 || echo FAIL-A$i; done ) & a=$!\n"
                + "( for i in $(seq 21 40); do ssh-keygen -R host$i >/dev/null 2>&1 || echo FAIL-B$i; done ) & b=$!\n"
                + "wait $a; wait $b; touch stop; wait $r; echo DONE\n");
        String out = run("/system/bin/sh \"$HOME/race.sh\"", 0);
        assertEquals("DONE\n", out);
        String hosts = read(new File(scratch, ".ssh/known_hosts"));
        assertTrue(hosts, hosts.startsWith("keep-host "));
        assertEquals(hosts, 1, hosts.split("\n").length);
        assertTrue(new File(scratch, ".ssh/known_hosts.old").isFile());
        String[] leftovers = new File(scratch, ".ssh").list((d, n) -> n.startsWith("known_hosts.")
                && !n.equals("known_hosts.old") && !n.equals("known_hosts.lock"));
        assertEquals(java.util.Arrays.toString(leftovers), 0, leftovers.length);
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
        // A dropped applet and a crash-leftover pending link are pruned.
        File dropped = new File(bin, "dropped-applet");
        File pending = new File(bin, ".scp.0000");
        Os.symlink("/obsolete-install/libbusybox.so", dropped.getPath());
        Os.symlink("/obsolete-install/libscp.so", pending.getPath());
        AndroidShellTools.prepare(context);
        assertFalse(new File(bin, "dropped-applet").exists() || isLink(dropped));
        assertFalse(isLink(pending));
        Files.delete(ssh.toPath());
        write(ssh, "user-file");
        try {
            // A user's regular file is preserved and must not block the shell.
            AndroidShellTools.prepare(context);
            assertEquals("user-file", read(ssh));
            assertTrue(isLink(new File(bin, "scp")));
        } finally {
            Files.delete(ssh.toPath());
            AndroidShellTools.prepare(context);
        }
        assertTrue(isLink(ssh));
    }

    private static boolean isLink(File f) {
        try {
            return android.system.OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (android.system.ErrnoException e) {
            return false;
        }
    }
}

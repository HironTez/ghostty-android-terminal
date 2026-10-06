/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal;

import static io.github.sylirre.terminal.TestUtil.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.sylirre.terminal.term.ScreenSnapshot;
import io.github.sylirre.terminal.term.SessionCommand;
import io.github.sylirre.terminal.term.TerminalSession;
import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * Opt-in, online package-manager diagnostics. Unlike the smoke suites, each
 * method installs its selected asset in files/package-manager-tests/<id>-<run>/
 * userland, never touching files/userland or the user's settings. The roots and
 * logs are deliberately retained for inspection after failure (or success).
 *
 * Run with -Pandroid.testInstrumentationRunnerArguments.packageManagerDiagnostics=true
 * and the class or one method. Optional runner arguments:
 * packageManagerJit=false, packageManagerDns=10.0.2.3 (AVD DNS comparison only),
 * packageManagerPackage=openssh-client (default openssh-server),
 * packageManagerTrace=true (--strace-full; potentially LARGE logs).
 *
 * No service-start suppression, apt sandbox override, signature bypass or DNS
 * workaround is applied by default: the first run must reproduce stock behavior.
 * Completion/status are read from guest-written files, not from echoed PTY input.
 */
@RunWith(AndroidJUnit4.class)
public class PackageManagerTest {
    private static final String TAG = "PackageManagerTest";
    private static final long TIMEOUT_MS = 15 * 60_000;
    private static final int LOG_TAIL_BYTES = 32_000;
    private Bundle args;
    private TerminalSession session;
    private File root;
    private File log;

    private final TerminalSession.Listener listener = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onBell(TerminalSession s) {}
        @Override public void onExited(TerminalSession s, int code) {
            Log.i(TAG, "guest exited: " + code);
        }
    };

    @Before
    public void optIn() {
        args = InstrumentationRegistry.getArguments();
        assumeTrue("online package-manager diagnostics are opt-in",
                Boolean.parseBoolean(args.getString("packageManagerDiagnostics", "false")));
    }

    @After
    public void closeSession() {
        if (session != null) session.close();
        if (log != null) {
            Log.i(TAG, "retained log: " + log);
            String tail = logTail();
            for (int start = 0; start < tail.length(); start += 3000) {
                Log.i(TAG, tail.substring(start, Math.min(tail.length(), start + 3000)));
            }
        }
    }

    @Test
    public void alpineRepositoryAndInstall() throws Exception {
        StringBuilder script = baseline();
        step(script, "dns", "busybox timeout 25 busybox nslookup dl-cdn.alpinelinux.org");
        step(script, "dns_google", "busybox timeout 25 busybox nslookup dl-cdn.alpinelinux.org 8.8.8.8");
        step(script, "dns_cloudflare", "busybox timeout 25 busybox nslookup dl-cdn.alpinelinux.org 1.1.1.1");
        step(script, "repo_config", "cat /etc/apk/repositories; apk --version; ls -l /etc/ssl/cert.pem /etc/ssl/certs");
        // Contrast cleartext/TLS transport with apk's own TLS/certificate handling.
        // BusyBox wget is not itself a certificate-validation oracle.
        step(script, "tls", "busybox timeout 30 busybox wget -T 10 -S -O /tmp/alpine-tls https://dl-cdn.alpinelinux.org/alpine/");
        step(script, "cleartext", "busybox timeout 30 busybox wget -T 10 -S -O /tmp/alpine-cleartext http://dl-cdn.alpinelinux.org/alpine/");
        step(script, "update", "apk -vv update");
        step(script, "install", "apk -vv add openssh");
        step(script, "installed", "apk info -e openssh openssh-client-default openssh-server openssh-keygen");
        step(script, "binaries", "ssh -V && /usr/sbin/sshd -G > /tmp/pm-sshd-config");
        step(script, "hostkey", "ssh-keygen -q -t ed25519 -N '' -f /tmp/pm-hostkey && ssh-keygen -lf /tmp/pm-hostkey.pub");
        run("alpine", script, "update", "install", "installed", "binaries", "hostkey");
        assertStep("update");
        assertStep("install");
        assertStep("installed");
        assertStep("binaries");
        assertStep("hostkey");
    }

    @Test
    public void debianOpenSshInstall() throws Exception {
        String pkg = args.getString("packageManagerPackage", "openssh-server");
        if (!pkg.matches("[a-z0-9][a-z0-9+.-]*")) {
            throw new IllegalArgumentException("invalid packageManagerPackage");
        }
        StringBuilder script = baseline();
        step(script, "dns", "getent ahosts deb.debian.org");
        step(script, "repo_config", "apt-get --version; cat /etc/apt/sources.list /etc/apt/sources.list.d/*; apt-config dump");
        String apt = "apt-get -o Acquire::Retries=0 -o Acquire::http::Timeout=15"
                + " -o Acquire::https::Timeout=15 -o Dpkg::Use-Pty=0";
        step(script, "update", apt + " -o APT::Update::Error-Mode=any update");
        // Separate repository/archive failures from dpkg installation failures.
        // The stock apt install handles dependency unpack/configure ordering.
        step(script, "download", "DEBIAN_FRONTEND=noninteractive " + apt
                + " -y --download-only --no-install-recommends install " + pkg);
        step(script, "install", "DEBIAN_FRONTEND=noninteractive " + apt
                + " -o Dpkg::Options::=--debug=2"
                + " -y --no-download --no-install-recommends install " + pkg);
        step(script, "audit", "dpkg --audit > /tmp/pm-dpkg-audit && cat /tmp/pm-dpkg-audit && test ! -s /tmp/pm-dpkg-audit");
        // dpkg --audit can exit zero while reporting incomplete packages. Also
        // assert the requested package's actual configured state, not a pipeline.
        step(script, "installed", "state=$(dpkg-query -W -f='${Status}' " + pkg
                + ") && printf '%s\\n' \"$state\" && test \"$state\" = 'install ok installed'");
        step(script, "package_log", "dpkg-query -W -f='${binary:Package} ${db:Status-Abbrev} ${Version}\\n' 'openssh*'; tail -n 80 /var/log/dpkg.log");
        step(script, "maintainer_scripts", "for f in /var/lib/dpkg/info/openssh*.preinst /var/lib/dpkg/info/openssh*.postinst; do [ ! -f \"$f\" ] || { echo \"--- $f\"; cat \"$f\"; }; done");
        // Run component probes after the unmodified installation attempt, not before.
        step(script, "hostkey", "ssh-keygen -q -t ed25519 -N '' -f /tmp/pm-hostkey && ssh-keygen -lf /tmp/pm-hostkey.pub");
        if ("openssh-server".equals(pkg)) {
            step(script, "server_config", "/usr/sbin/sshd -t && test -s /etc/ssh/ssh_host_rsa_key && test -s /etc/ssh/ssh_host_ecdsa_key && test -s /etc/ssh/ssh_host_ed25519_key && getent passwd sshd");
        }
        step(script, "users", "getent passwd sshd; getent passwd _apt; ls -ld /run/sshd /var/lib/sshd /var/empty; ls -l /etc/ssh");
        run("debian", script, "openssh-server".equals(pkg)
                ? new String[] {"update", "download", "install", "audit", "installed", "hostkey", "server_config"}
                : new String[] {"update", "download", "install", "audit", "installed", "hostkey"});
        assertStep("update");
        assertStep("download");
        assertStep("install");
        assertStep("audit");
        assertStep("installed");
        assertStep("hostkey");
        if ("openssh-server".equals(pkg)) assertStep("server_config");
    }

    private StringBuilder baseline() {
        StringBuilder s = new StringBuilder("#!/bin/sh\n");
        step(s, "environment", "id; uname -a; date -u; pwd; env; cat /etc/os-release /etc/resolv.conf /etc/hosts; cat /proc/self/status");
        step(s, "filesystem", "mkdir -p /tmp/pm-fs; printf x > /tmp/pm-fs/a; ln /tmp/pm-fs/a /tmp/pm-fs/b; echo link_rc=$?; mv /tmp/pm-fs/b /tmp/pm-fs/c; echo rename_rc=$?; chown 123:456 /tmp/pm-fs/a; echo chown_rc=$?; ls -lni /tmp/pm-fs; chmod 000 /tmp/pm-fs/a; cat /tmp/pm-fs/a; echo root_read_rc=$?; chmod 600 /tmp/pm-fs/a; head -c 16 /dev/urandom | od -An -tx1");
        return s;
    }

    /** Keep failed phases visible, but continue so later probes provide context. */
    private static void step(StringBuilder script, String label, String command) {
        script.append("printf '\\n===== ").append(label).append(" =====\\n'\n(")
                .append(command).append(")\nrc=$?\nprintf '%s\\n' \"$rc\" > /tmp/pm-")
                .append(label).append(".rc\nprintf 'phase=").append(label)
                .append(" rc=%s\\n' \"$rc\"\n");
    }

    private void run(String distro, StringBuilder script, String... requiredSteps) throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        String asset = null;
        for (UserlandDistro d : UserlandDistro.bundled(app)) {
            if (distro.equals(d.id)) { asset = d.assetName; break; }
        }
        assumeTrue("no bundled " + distro + " rootfs", asset != null);
        File files = new File(app.getFilesDir(), "package-manager-tests/" + distro
                + "-" + System.currentTimeMillis() + "-" + System.nanoTime());
        if (!files.mkdirs()) throw new IOException("cannot create " + files);
        Context isolated = new ContextWrapper(app) {
            @Override public File getFilesDir() { return files; }
        };
        UserlandRootfs.install(isolated, asset, null);
        root = UserlandRootfs.dir(isolated);
        log = new File(root, "tmp/package-manager.log");
        Log.i(TAG, "diagnostic root: " + root + "; asset=" + asset);
        String dns = args.getString("packageManagerDns", "");
        if (!dns.isEmpty()) {
            if (!dns.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("invalid DNS IP");
            write(new File(root, "etc/resolv.conf"), "nameserver " + dns + "\n");
        }
        // Host Bionic resolver contrast: this uses Android's DNS, not resolv.conf.
        try {
            Log.i(TAG, "Android DNS dl-cdn.alpinelinux.org="
                    + Arrays.toString(InetAddress.getAllByName("dl-cdn.alpinelinux.org")));
            Log.i(TAG, "Android DNS deb.debian.org="
                    + Arrays.toString(InetAddress.getAllByName("deb.debian.org")));
        } catch (IOException e) {
            Log.w(TAG, "Android resolver failed too", e);
        }
        // The guest's natural exit must reflect every required acceptance phase.
        // Diagnostic-only DNS/transport probes remain visible but not gating.
        script.append("pm_rc=0\nfor phase in ").append(String.join(" ", requiredSteps))
                .append("; do\n[ \"$(cat /tmp/pm-$phase.rc)\" = 0 ] || pm_rc=1\ndone\n")
                .append("printf '%s\\n' \"$pm_rc\" > /tmp/pm-exit.rc\n")
                .append("printf done > /tmp/pm-done\nexit \"$pm_rc\"\n");
        write(new File(root, "tmp/package-manager.sh"), script.toString());
        boolean jit = Boolean.parseBoolean(args.getString("packageManagerJit", "true"));
        UserlandOptions options = new UserlandOptions("/bin/sh -l", false, "0:0",
                "/root", "/root", "C.UTF-8", UserlandOptions.DEFAULT_PATH, jit, 32, false);
        SessionCommand command = UserlandRootfs.command(isolated, options);
        assertEquals("must explicitly use the common arm64chroot engine", "arm64chroot", command.argv[0]);
        Log.i(TAG, "engine=" + command.argv[0] + "; jitRequested=" + jit);
        if (Boolean.parseBoolean(args.getString("packageManagerTrace", "false"))) {
            command = withTrace(command);
        }
        session = new TerminalSession(160, 48, 8, 16, 10_000, command, true, listener);
        session.write(". /tmp/package-manager.sh > /tmp/package-manager.log 2>&1\n");
        File done = new File(root, "tmp/pm-done");
        waitFor(distro + " package-manager diagnostics", TIMEOUT_MS,
                () -> "done".equals(readSmall(done)) || session.exitCode() != null,
                () -> logTail() + "\nPTY:\n" + screen());
        assertEquals("guest did not complete; " + log + "\n" + logTail(), "done", readSmall(done));
        waitFor(distro + " package-manager guest exit", 45_000,
                () -> session.exitCode() != null, () -> logTail() + "\nPTY:\n" + screen());
        Log.i(TAG, "completed root=" + root + "; guest exitCode=" + session.exitCode());
        assertStep("exit");
        assertEquals("guest failed; " + log + "\n" + logTail(), Integer.valueOf(0), session.exitCode());
    }

    /** Test-only argv injection; keep the production SessionCommand API unchanged. */
    private static SessionCommand withTrace(SessionCommand original) throws Exception {
        List<String> argv = new ArrayList<>(Arrays.asList(original.argv));
        argv.add(1, "--strace-full");
        Constructor<SessionCommand> constructor = SessionCommand.class.getDeclaredConstructor(
                String.class, String[].class, String[].class, String.class, String.class,
                boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(original.cmd, argv.toArray(new String[0]),
                original.env, original.cwd, original.label, original.userland);
    }

    private void assertStep(String label) {
        assertEquals(label + " failed; " + log + "\n" + logTail(), "0",
                readSmall(new File(root, "tmp/pm-" + label + ".rc")).trim());
    }

    private String screen() {
        ScreenSnapshot snapshot = new ScreenSnapshot();
        session.emulator.snapshot(snapshot);
        return snapshot.text();
    }

    private String logTail() {
        if (log == null) return "log not created";
        try (FileInputStream in = new FileInputStream(log)) {
            in.getChannel().position(Math.max(0, in.getChannel().size() - LOG_TAIL_BYTES));
            return readBounded(in, LOG_TAIL_BYTES);
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static String readSmall(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return readBounded(in, 128);
        } catch (IOException e) {
            return "";
        }
    }

    /** Avoid newer InputStream convenience methods: minSdk is API 29. */
    private static String readBounded(FileInputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[Math.min(maxBytes, 4096)];
        while (out.size() < maxBytes) {
            int n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
            if (n < 0) break;
            if (n > 0) out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

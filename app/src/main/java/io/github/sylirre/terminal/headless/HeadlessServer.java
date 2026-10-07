/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.headless;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The headless control API: an abstract-namespace unix socket that adb can
 * reach with {@code adb forward tcp:N localabstract:}{@value #SOCKET_NAME}.
 *
 * Any process on the device can try to connect to an abstract socket, so every
 * connection's peer is checked with {@code SO_PEERCRED}, before a single byte
 * is read: only root, the adb shell (uid 2000) and this very process get in.
 * Not this app's uid in general — the userland's guest processes run as that
 * uid too, and must not reach an unconfined Android shell through here. Each
 * accepted connection runs one request on its own thread
 * ({@link HeadlessConnection}).
 *
 * The other direction matters as much: abstract names are first come, first
 * served, so another app could bind this name while the server is down and
 * collect whatever a client then types. Each server start therefore draws a
 * random key, which only DUMP holders can read (the service's dump,
 * {@code dumpsys activity service ... headless-key}), and answers a client's
 * {@code hello} nonce with an HMAC under it; {@code gterm} verifies that
 * before sending anything else (docs/headless-api.md, "Auth").
 *
 * A process singleton, started and stopped by {@code SessionService}.
 */
public final class HeadlessServer {
    private static final String TAG = "HeadlessServer";

    /** Abstract socket name (no leading NUL; Android adds it). */
    public static final String SOCKET_NAME = "io.github.sylirre.terminal.headless";
    /** The adb shell's uid ({@code android.os.Process.SHELL_UID}). */
    static final int SHELL_UID = 2000;

    /** Connections served at once; more are closed on accept. */
    static final int MAX_CONNECTIONS = 32;

    private static HeadlessServer instance;
    /** Why the last start failed (e.g. the name is taken), or null. */
    private static String lastError;

    /** Starts the server if it is not running. */
    public static synchronized void start(Context context) throws IOException {
        if (instance != null) return;
        try {
            instance = new HeadlessServer(context.getApplicationContext());
            lastError = null;
        } catch (IOException e) {
            lastError = String.valueOf(e.getMessage());
            throw e;
        }
    }

    /** The reason the last start failed, or null after a successful start. */
    public static synchronized String lastError() {
        return lastError;
    }

    /**
     * The running server's key as hex, or null when it is not running. Only
     * for the DUMP-guarded service dump and in-process tests: it is what lets
     * a client tell this server from an impostor on the same name.
     */
    public static synchronized String keyHex() {
        return instance == null ? null : hex(instance.key);
    }

    /**
     * {@code HMAC-SHA256(key, "gterm-hello-v1:" + nonce)} as hex, the
     * server's answer to a client's {@code hello}; null when not running.
     */
    static String proof(String nonce) {
        byte[] k;
        synchronized (HeadlessServer.class) {
            if (instance == null) return null;
            k = instance.key;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return hex(mac.doFinal(("gterm-hello-v1:" + nonce)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16))
                .append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    /** Stops the server and drops every open connection. Idempotent. */
    public static void stop() {
        HeadlessServer s;
        synchronized (HeadlessServer.class) {
            s = instance;
            instance = null;
        }
        if (s != null) s.shutdown();
    }

    public static synchronized boolean isRunning() {
        return instance != null;
    }

    /**
     * The peer policy: root, the adb shell, or this process itself (in-app
     * clients and the instrumented tests). Other processes of this app's uid
     * are refused: they are the userland's guest processes.
     */
    public static boolean isAllowedPeer(int uid, int pid) {
        return uid == 0 || uid == SHELL_UID
                || (uid == Process.myUid() && pid == Process.myPid());
    }

    private final Context context;
    private final byte[] key = new byte[32];
    private final LocalServerSocket server;
    private final Set<HeadlessConnection> connections = new HashSet<>();
    private volatile boolean stopped;

    private HeadlessServer(Context context) throws IOException {
        this.context = context;
        new SecureRandom().nextBytes(key);
        this.server = new LocalServerSocket(SOCKET_NAME);
        Thread t = new Thread(this::acceptLoop, "headless-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (!stopped) {
            LocalSocket sock;
            try {
                sock = server.accept();
            } catch (IOException e) {
                if (!stopped) Log.w(TAG, "accept failed", e);
                break;
            }
            if (stopped) {
                closeQuietly(sock);
                break;
            }
            int uid, pid;
            try {
                android.net.Credentials cred = sock.getPeerCredentials();
                uid = cred.getUid();
                pid = cred.getPid();
            } catch (IOException e) {
                closeQuietly(sock);
                continue;
            }
            if (!isAllowedPeer(uid, pid)) {
                Log.w(TAG, "rejected connection from uid " + uid + " pid " + pid);
                closeQuietly(sock);
                continue;
            }
            HeadlessConnection c = new HeadlessConnection(context, sock, this);
            synchronized (connections) {
                // Checked again under the lock shutdown() takes to copy the
                // set: a connection added after that copy would outlive stop.
                if (stopped) {
                    closeQuietly(sock);
                    break;
                }
                if (connections.size() >= MAX_CONNECTIONS) {
                    Log.w(TAG, "too many connections; dropping one");
                    closeQuietly(sock);
                    continue;
                }
                connections.add(c);
            }
            Thread t = new Thread(c, "headless-conn");
            t.setDaemon(true);
            t.start();
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    void remove(HeadlessConnection c) {
        synchronized (connections) {
            connections.remove(c);
        }
    }

    private void shutdown() {
        stopped = true;
        // Closing a LocalServerSocket does not reliably unblock accept(), so
        // wake it with a throwaway connection; the loop then sees stopped.
        LocalSocket wake = new LocalSocket();
        try {
            wake.connect(new LocalSocketAddress(SOCKET_NAME));
        } catch (IOException ignored) {
        } finally {
            closeQuietly(wake);
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        HeadlessConnection[] open;
        synchronized (connections) {
            open = connections.toArray(new HeadlessConnection[0]);
            connections.clear();
        }
        for (HeadlessConnection c : open) c.close();
    }

    static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}

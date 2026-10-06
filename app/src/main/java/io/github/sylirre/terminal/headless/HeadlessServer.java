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
import java.util.HashSet;
import java.util.Set;

/**
 * The headless control API: an abstract-namespace unix socket that adb can
 * reach with {@code adb forward tcp:N localabstract:}{@value #SOCKET_NAME}.
 *
 * Any process on the device can try to connect to an abstract socket, so every
 * connection's peer is checked with {@code SO_PEERCRED}: only root, the adb
 * shell (uid 2000) and this app's own uid get in. Each accepted connection
 * runs one request on its own thread ({@link HeadlessConnection}).
 *
 * A process singleton, started and stopped by {@code SessionService}.
 */
public final class HeadlessServer {
    private static final String TAG = "HeadlessServer";

    /** Abstract socket name (no leading NUL; Android adds it). */
    public static final String SOCKET_NAME = "io.github.sylirre.terminal.headless";
    /** The adb shell's uid ({@code android.os.Process.SHELL_UID}). */
    static final int SHELL_UID = 2000;

    private static HeadlessServer instance;

    /** Starts the server if it is not running. */
    public static synchronized void start(Context context) throws IOException {
        if (instance != null) return;
        instance = new HeadlessServer(context.getApplicationContext());
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

    /** The peer-uid policy: root, the adb shell, or this app itself. */
    public static boolean isAllowedUid(int uid) {
        return uid == 0 || uid == SHELL_UID || uid == Process.myUid();
    }

    private final Context context;
    private final LocalServerSocket server;
    private final Set<HeadlessConnection> connections = new HashSet<>();
    private volatile boolean stopped;

    private HeadlessServer(Context context) throws IOException {
        this.context = context;
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
            int uid;
            try {
                uid = sock.getPeerCredentials().getUid();
            } catch (IOException e) {
                closeQuietly(sock);
                continue;
            }
            if (!isAllowedUid(uid)) {
                Log.w(TAG, "rejected connection from uid " + uid);
                closeQuietly(sock);
                continue;
            }
            HeadlessConnection c = new HeadlessConnection(context, sock, this);
            synchronized (connections) {
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

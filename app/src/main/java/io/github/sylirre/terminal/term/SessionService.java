/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.headless.HeadlessServer;

/**
 * Keeps the app's process alive while shells are running.
 *
 * Sessions live in {@link SessionManager} (a process singleton); this
 * service owns no session state. Its only jobs are to (a) hold the process
 * at foreground-service OOM priority so the Low Memory Killer leaves the
 * shells alone, (b) survive a swipe from Recents (manifest
 * {@code stopWithTask="false"} plus the no-op {@link #onTaskRemoved}), and
 * (c) expose a persistent notification with an "Exit" action and an
 * optional CPU wake lock.
 *
 * The Activity drives the lifecycle: {@link #refresh} on every session
 * count change, {@link #stop} when the last tab closes. "Exit" fires
 * {@link #ACTION_EXIT}, which can run with no Activity alive, so the
 * service itself tears the sessions down and then calls the Activity's
 * {@linkplain #setExitListener exit listener}, if one is registered, to
 * finish it.
 *
 * Headless mode: the service is exported, guarded by
 * {@code android.permission.DUMP} (held by the adb shell, never by ordinary
 * apps), so {@code adb shell am start-foreground-service -a}
 * {@link #ACTION_HEADLESS_START} brings it up with no Activity at all. It then
 * runs the {@link HeadlessServer} socket API, holds the CPU wake lock (unless
 * the start intent says {@code --ez wakelock false}), and is sticky: after a
 * low-memory kill the system restarts it and the server comes back (the shells
 * do not). While headless mode is on, {@link #stop} keeps the service running,
 * so closing the last tab in the UI does not cut the remote client off.
 */
public final class SessionService extends Service {
    private static final String TAG = "SessionService";

    /** Starts headless mode: foreground, socket server, wake lock. */
    public static final String ACTION_HEADLESS_START = "io.github.sylirre.terminal.headless.START";
    /** Stops headless mode; the service stays only while sessions remain. */
    public static final String ACTION_HEADLESS_STOP = "io.github.sylirre.terminal.headless.STOP";
    /** Boolean extra of {@link #ACTION_HEADLESS_START}: hold the wake lock (default true). */
    public static final String EXTRA_WAKELOCK = "wakelock";
    /** Explicit wake-lock state (the headless {@code wakelock} op; boolean extra "on"). */
    private static final String ACTION_SET_WAKELOCK = "io.github.sylirre.terminal.service.SET_WAKELOCK";

    private static final String PREFS = "headless";
    private static final String KEY_HEADLESS = "enabled";
    private static final String KEY_AUTOSTART = "autostart";

    private static volatile boolean wakeLockHeld;
    /** The running instance (main thread writes), for in-process updates. */
    private static volatile SessionService running;

    /** Bring the service to (or keep it in) the foreground; refresh the notification. */
    private static final String ACTION_START = "io.github.sylirre.terminal.service.START";
    /**
     * Kill every shell and stop; user tapped "Exit". Package-private rather
     * than private so the instrumented test can fire the same intent the
     * notification action carries.
     */
    static final String ACTION_EXIT = "io.github.sylirre.terminal.service.EXIT";
    /** Flip the CPU wake lock. */
    private static final String ACTION_TOGGLE_WAKELOCK = "io.github.sylirre.terminal.service.TOGGLE_WAKELOCK";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "sessions";

    private PowerManager.WakeLock wakeLock;

    /**
     * Run on the main thread once "Exit" has torn the sessions down, so a live
     * Activity can drop its UI.
     *
     * A plain callback rather than a broadcast: this service declares no
     * {@code android:process}, so it always runs in the Activity's process —
     * the same reason it can reach {@link SessionManager} directly. Sent as a
     * broadcast it needed a context-registered receiver on the Activity side,
     * and below API 33 there is no {@code RECEIVER_NOT_EXPORTED} to register
     * it with: any installed app could fire the action and make the terminal
     * window disappear.
     */
    private static volatile Runnable exitListener;

    /**
     * Registers the Activity's teardown. Pass the same instance to
     * {@link #clearExitListener} when the Activity goes away.
     */
    public static void setExitListener(Runnable listener) {
        exitListener = listener;
    }

    /** Drops {@code listener}, unless a newer Activity has already replaced it. */
    public static void clearExitListener(Runnable listener) {
        if (exitListener == listener) exitListener = null;
    }

    /** Ensures the service is running and its notification reflects the current state. */
    public static void refresh(Context context) {
        context.startForegroundService(intent(context, ACTION_START));
    }

    /**
     * {@link #refresh} from a context that may be in the background (the
     * session reaper): a start the platform refuses there is logged, not
     * thrown — the service is normally already running in that case.
     */
    public static void refreshQuietly(Context context) {
        if (notifyChanged()) return;
        try {
            refresh(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "notification refresh refused", e);
        }
    }

    /**
     * Re-posts the notification of the already running service, in process,
     * with no start intent (which a background app may be refused). Returns
     * false when the service is not running.
     */
    public static boolean notifyChanged() {
        SessionService s = running;
        if (s == null) return false;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (running == s) s.startForeground();
        });
        return true;
    }

    /**
     * Stops the service; the persistent notification disappears with it. In
     * headless mode it only refreshes the notification instead — the remote
     * API must outlive the last tab.
     */
    public static void stop(Context context) {
        if (HeadlessServer.isRunning()) {
            refreshQuietly(context);
            return;
        }
        context.stopService(intent(context, null));
    }

    /** Starts headless mode from inside the app (tests, boot receiver). */
    public static void startHeadless(Context context, boolean wakeLock) {
        context.startForegroundService(intent(context, ACTION_HEADLESS_START)
                .putExtra(EXTRA_WAKELOCK, wakeLock));
    }

    /**
     * Sets the CPU wake lock on or off. Works in process on the running
     * service; otherwise falls back to a start intent, which needs the app to
     * be allowed a background foreground-service start (device-idle allowlist).
     */
    public static void setWakeLock(Context context, boolean on) {
        SessionService s = running;
        if (s != null) {
            wakeLockHeld = on; // the state the caller can read back at once
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (on) s.acquireWakeLock();
                else s.releaseWakeLock();
                s.startForeground();
            });
            return;
        }
        context.startForegroundService(intent(context, ACTION_SET_WAKELOCK)
                .putExtra("on", on));
    }

    /** Whether the service currently holds its partial wake lock. */
    public static boolean wakeLockHeld() {
        return wakeLockHeld;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Whether headless mode should come back by itself: after a sticky restart,
     * and — when {@link #autostart} is also on — after boot.
     */
    public static boolean headlessEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HEADLESS, false);
    }

    /** Opt-in: start headless mode on BOOT_COMPLETED (off by default). */
    public static boolean autostart(Context context) {
        return prefs(context).getBoolean(KEY_AUTOSTART, false);
    }

    public static void setAutostart(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, on).apply();
    }

    private static Intent intent(Context context, String action) {
        Intent i = new Intent(context, SessionService.class);
        if (action != null) i.setAction(action);
        return i;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_EXIT.equals(action)) {
            stopHeadless();
            SessionManager.get().closeAll();
            // Tell a live Activity (if any) to finish and drop its task.
            Runnable onExited = exitListener;
            if (onExited != null) onExited.run();
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // A null intent is a sticky restart after the process was killed: it
        // only happens while headless mode was on, so bring the server back.
        boolean headlessStart = ACTION_HEADLESS_START.equals(action)
                || (intent == null && headlessEnabled(this));
        if (ACTION_HEADLESS_STOP.equals(action)) {
            stopHeadless();
            releaseWakeLock();
            if (SessionManager.get().isEmpty()) {
                // Must still satisfy the startForegroundService contract.
                startForeground();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
            toggleWakeLock();
        }
        if (ACTION_SET_WAKELOCK.equals(action)) {
            if (intent.getBooleanExtra("on", true)) acquireWakeLock();
            else releaseWakeLock();
        }
        startForeground();
        if (headlessStart) {
            try {
                HeadlessServer.start(getApplicationContext());
                prefs(this).edit().putBoolean(KEY_HEADLESS, true).apply();
                if (intent == null || intent.getBooleanExtra(EXTRA_WAKELOCK, true)) {
                    acquireWakeLock();
                }
                startForeground(); // reflect the wake lock in the notification
            } catch (java.io.IOException e) {
                Log.e(TAG, "headless server failed to start", e);
            }
        }
        // Sticky only in headless mode, where a restart restores reachability.
        // Otherwise there is no point resurrecting an empty service: a restart
        // cannot recover the shells that died with the process.
        return HeadlessServer.isRunning() ? START_STICKY : START_NOT_STICKY;
    }

    private void stopHeadless() {
        HeadlessServer.stop();
        prefs(this).edit().putBoolean(KEY_HEADLESS, false).apply();
    }

    /**
     * Swiping the task from Recents does not stop us (manifest
     * {@code stopWithTask="false"}); this override exists to make that intent
     * explicit and to keep the shells running. The Activity is gone, but the
     * singleton and the PTY reader threads ride along on the surviving process.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Intentionally do not stopSelf().
    }

    private void startForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse is a 34+ foreground-service type; older releases
            // take the untyped overload (the manifest attribute is benign there).
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        // IMPORTANCE_LOW: persistent but silent, no heads-up.
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        int count = SessionManager.get().sessions().size();
        boolean held = wakeLock != null && wakeLock.isHeld();
        String text = getResources().getQuantityString(
                R.plurals.notification_sessions_running, count, count)
                + (held ? getString(R.string.notification_wakelock_active) : "")
                + (HeadlessServer.isRunning()
                        ? getString(R.string.notification_headless_active) : "");

        PendingIntent content = PendingIntent.getActivity(this, 0,
                getPackageManager().getLaunchIntentForPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent exit = PendingIntent.getService(this, 1,
                intent(this, ACTION_EXIT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent toggle = PendingIntent.getService(this, 2,
                intent(this, ACTION_TOGGLE_WAKELOCK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null,
                        held ? getString(R.string.notification_action_release_wakelock)
                             : getString(R.string.notification_action_acquire_wakelock),
                        toggle).build())
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_action_exit), exit).build())
                .build();
    }

    private void toggleWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            releaseWakeLock();
            return;
        }
        acquireWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Terminal:sessions");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire();
        wakeLockHeld = true;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLockHeld = false;
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        releaseWakeLock();
        // The server must not outlive the service that keeps the process up;
        // the persisted flag stays, so a sticky restart brings it back.
        HeadlessServer.stop();
    }

    /**
     * {@code adb shell dumpsys activity service <pkg>/.term.SessionService
     * [headless-key]}: the headless server's state and, only when asked by
     * name, its per-start key. dumpsys requires DUMP (the adb shell has it,
     * apps do not), so this is how {@code gterm} learns the key it then checks
     * the server against. Not printed for a plain dump, which bug reports
     * include.
     */
    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter pw, String[] args) {
        String key = HeadlessServer.keyHex();
        String err = HeadlessServer.lastError();
        pw.println("headless: " + (key != null ? "running"
                : err != null ? "failed: " + err : "stopped"));
        pw.println("sessions: " + SessionManager.get().sessions().size());
        pw.println("wakelock: " + wakeLockHeld);
        if (key != null && args != null) {
            for (String a : args) {
                if ("headless-key".equals(a)) pw.println("headless-key=" + key);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // started service only
    }
}

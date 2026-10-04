/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide session list backing the tab strip and the headless API.
 *
 * A singleton (not Activity state) so shells survive rotation and Activity
 * recreation. {@link SessionService}, a foreground service, keeps the process
 * (and so the sessions) alive while backgrounded; sessions still die with the
 * process if the system kills it anyway.
 *
 * Exited sessions are reaped here rather than only by the Activity: every
 * session carries a reaper listener that, when the session exits with no
 * primary listener (no live Activity — a headless session, or the UI was
 * swiped away), removes it from the list and updates or stops the service.
 * With an Activity attached, the Activity's own {@code onExited} decides
 * (it has startup-failure fallbacks and closes the tab), so the UI behaves
 * exactly as before.
 */
public final class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();

    public static SessionManager get() {
        return INSTANCE;
    }

    private final List<TerminalSession> sessions = new ArrayList<>();
    private volatile Context appContext;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Reaps sessions that exit while no Activity is listening (main thread). */
    private final TerminalSession.Listener reaper = new TerminalSession.Listener() {
        @Override public void onUpdate(TerminalSession session) {}
        @Override public void onTitleChanged(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}

        @Override
        public void onExited(TerminalSession session, int exitCode) {
            if (session.hasListener()) return; // the Activity handles it
            if (!close(session)) return;
            Context ctx = appContext;
            if (ctx == null) return;
            if (isEmpty()) SessionService.stop(ctx);
            else SessionService.refreshQuietly(ctx);
        }
    };

    private SessionManager() {}

    /** Remembers the application context the reaper uses to update the service. */
    public void setContext(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    /** Session by {@link TerminalSession#id}, or null. */
    public TerminalSession byId(int id) {
        synchronized (this) {
            for (TerminalSession s : sessions) {
                if (s.id == id) return s;
            }
        }
        return null;
    }

    /** The open session on guest-machine terminal {@code terminal}, or null. */
    public TerminalSession vmSession(int terminal) {
        synchronized (this) {
            for (TerminalSession s : sessions) {
                if (s.isVm() && s.vmTerminal() == terminal) return s;
            }
        }
        return null;
    }

    private void register(TerminalSession s) {
        s.addListener(reaper);
        synchronized (this) {
            sessions.add(s);
        }
        fireChanged();
    }

    /**
     * Runs {@code r} on the main thread after any session is added or removed
     * — including by the headless API or the reaper, which the tab strip
     * would otherwise never hear about.
     */
    public void addChangeListener(Runnable r) {
        changeListeners.add(r);
    }

    public void removeChangeListener(Runnable r) {
        changeListeners.remove(r);
    }

    private void fireChanged() {
        if (changeListeners.isEmpty()) return;
        mainHandler.post(() -> {
            for (Runnable r : changeListeners) r.run();
        });
    }

    /**
     * Spawns a shell at the given grid size. Callers should pass the real
     * view size: spawning at a wrong size triggers a SIGWINCH on first
     * layout, and mksh reacts by wiping its initial prompt.
     *
     * @param userland Login shell under arm64chroot (rootfs must be
     *               installed) instead of /system/bin/sh.
     * @param userlandOptions arm64chroot inputs (login shell, identity, home,
     *                   working directory, /proc isolation, storage binding);
     *                   used only when {@code userland} is true.
     * @param scrollbackLines lines of history the new session keeps.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean userland,
            UserlandOptions userlandOptions, boolean terminateProcessesOnExit,
            TerminalSession.Listener listener) throws IOException {
        return create(context, cols, rows, cellWidthPx, cellHeightPx,
                scrollbackLines, userland, userlandOptions, terminateProcessesOnExit,
                listener, null);
    }

    /**
     * As above, with an output tap attached from the first byte (the headless
     * API's spawn-and-attach). {@code userlandOptions.command}, when set,
     * replaces the login shell.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean userland,
            UserlandOptions userlandOptions, boolean terminateProcessesOnExit,
            TerminalSession.Listener listener, TerminalSession.OutputTap tap)
            throws IOException {
        setContext(context);
        SessionCommand command = userland
                ? UserlandRootfs.command(context, userlandOptions)
                : SessionCommand.androidShell(context);
        return create(command, cols, rows, cellWidthPx, cellHeightPx,
                scrollbackLines, terminateProcessesOnExit, listener, tap);
    }

    /** Spawns an already built command as a session. */
    public TerminalSession create(SessionCommand command, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines,
            boolean terminateProcessesOnExit, TerminalSession.Listener listener,
            TerminalSession.OutputTap tap) throws IOException {
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, command, terminateProcessesOnExit,
                listener, tap);
        register(s);
        return s;
    }

    /**
     * Attaches a tab to one terminal of the running guest machine.
     *
     * Nothing is spawned here — the machine booted its own gettys, and this
     * only opens a view onto one of them, so unlike {@link #create} it cannot
     * fail for want of a rootfs or a shell. {@code terminal} indexes the
     * machine's terminals: 0 is the serial console the guest boots on.
     */
    public TerminalSession attachVm(VmMachine machine, int terminal, int cols,
            int rows, int cellWidthPx, int cellHeightPx, int scrollbackLines,
            TerminalSession.Listener listener) throws IOException {
        return attachVm(machine, terminal, cols, rows, cellWidthPx, cellHeightPx,
                scrollbackLines, listener, null);
    }

    /** As above, with an output tap attached from the first byte. */
    public TerminalSession attachVm(VmMachine machine, int terminal, int cols,
            int rows, int cellWidthPx, int cellHeightPx, int scrollbackLines,
            TerminalSession.Listener listener, TerminalSession.OutputTap tap)
            throws IOException {
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, machine, terminal, listener, tap);
        register(s);
        return s;
    }

    /**
     * The machine terminals a tab is currently open on, so a caller can pick
     * one that is not. Detaching frees an index for reuse: the guest side is
     * untouched by a tab closing, so reattaching lands back in the same shell.
     */
    public boolean isVmTerminalOpen(int terminal) {
        synchronized (this) {
            for (TerminalSession s : sessions) {
                if (s.isVm() && s.vmTerminal() == terminal) return true;
            }
        }
        return false;
    }

    public List<TerminalSession> sessions() {
        synchronized (this) {
            return new ArrayList<>(sessions);
        }
    }

    public int indexOf(TerminalSession s) {
        synchronized (this) {
            return sessions.indexOf(s);
        }
    }

    public boolean close(TerminalSession s) {
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(s);
        }
        if (removed) {
            s.close();
            fireChanged();
        }
        return removed;
    }

    /**
     * Closes every session and empties the list, leaving a running guest
     * machine alone: a VM tab only detaches, so the machine keeps running and
     * a later tab finds its shells where they were. For callers that need the
     * tabs gone but have no business with the machine — replacing the userland
     * rootfs, which the machine does not touch.
     */
    public void closeSessions() {
        List<TerminalSession> copy;
        synchronized (this) {
            copy = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (TerminalSession s : copy) {
            s.close();
        }
        if (!copy.isEmpty()) fireChanged();
    }

    /**
     * Kills every shell and empties the list. Used by the "Exit" action in
     * the foreground-service notification, which can fire while no Activity
     * is alive — so it must leave no dead sessions behind for a later
     * relaunch to re-attach to.
     */
    public void closeAll() {
        closeSessions();
        // Closing a VM tab only detaches it, by design — so the machine would
        // otherwise outlive the app that started it, with no tab left to reach
        // it from and no way to stop it. "Exit" means exit.
        VmMachine.stopIfRunning();
    }

    public boolean isEmpty() {
        synchronized (this) {
            return sessions.isEmpty();
        }
    }
}

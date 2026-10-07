/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A {@link SessionCommand} run on plain pipes instead of a PTY: separate
 * stdout and stderr, a real stdin EOF, and the exit status — what the headless
 * API's {@code exec} needs. No emulator, no session, no tab.
 *
 * The child leads its own process group, so {@link #signal} reaches it and
 * everything it started in that group.
 */
public final class ProcessPipes {
    private final int pid;
    private final ParcelFileDescriptor stdinFd, stdoutFd, stderrFd;
    public final OutputStream stdin;
    public final InputStream stdout;
    public final InputStream stderr;
    private volatile Integer exitCode;

    private ProcessPipes(int pid, int[] fds) {
        this.pid = pid;
        stdinFd = ParcelFileDescriptor.adoptFd(fds[0]);
        stdoutFd = ParcelFileDescriptor.adoptFd(fds[1]);
        stderrFd = ParcelFileDescriptor.adoptFd(fds[2]);
        stdin = new FileOutputStream(stdinFd.getFileDescriptor());
        stdout = new FileInputStream(stdoutFd.getFileDescriptor());
        stderr = new FileInputStream(stderrFd.getFileDescriptor());
    }

    /** Starts {@code command} (Android shell or userland engine argv). */
    public static ProcessPipes start(SessionCommand command) throws IOException {
        int[] fds = new int[3];
        int[] pid = new int[1];
        TerminalNative.pipeCreate(command.cmd, command.argv, command.env,
                command.cwd, fds, pid);
        return new ProcessPipes(pid[0], fds);
    }

    public int pid() {
        return pid;
    }

    /** Closes the child's stdin, so it reads EOF. Idempotent. */
    public void closeStdin() {
        closeQuietly(stdinFd);
    }

    /** Sends {@code sig} to the child's whole process group. */
    public void signal(int sig) {
        if (exitCode == null) TerminalNative.processKill(-pid, sig);
    }

    /** Blocks until the child exits; returns the exit status or -signal. */
    public int waitFor() {
        Integer c = exitCode;
        if (c != null) return c;
        int code = TerminalNative.processWaitFor(pid);
        exitCode = code;
        return code;
    }

    /** Exit status, or null while running. */
    public Integer exitCode() {
        return exitCode;
    }

    /** Releases all three pipe ends. Does not signal the child. */
    public void close() {
        closeQuietly(stdinFd);
        closeQuietly(stdoutFd);
        closeQuietly(stderrFd);
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }
}

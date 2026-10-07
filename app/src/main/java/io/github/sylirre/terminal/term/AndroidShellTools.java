/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
package io.github.sylirre.terminal.term;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Installer-managed Android executables, exposed through private symlinks.
 * No executable bytes are ever written to app data; the target inode remains
 * in nativeLibraryDir, with the installer's executable SELinux label. */
public final class AndroidShellTools {
    private AndroidShellTools() {}

    private static final String TAG = "AndroidShellTools";

    private static final String[] CLIENTS = {"busybox", "ssh", "scp", "sftp", "ssh-keygen"};
    // Must match the curated BusyBox configuration. Deliberately no sh/bash,
    // privileged applets, network administration, or daemon tools.
    private static final String[] APPLETS = {
            "awk", "basename", "bunzip2", "bzcat", "bzip2", "cat", "chmod", "cmp",
            "comm", "cp", "cut", "date", "dd", "df", "diff", "dirname", "du",
            "echo", "env", "expand", "expr", "false", "find", "fold", "grep",
            "gunzip", "gzip", "head", "hexdump", "id", "less", "ln", "ls",
            "lzcat", "lzma", "md5sum", "mkdir", "mkfifo", "mktemp", "mv", "od",
            "paste", "printf", "pwd", "readlink", "realpath", "rm", "rmdir", "sed",
            "seq", "sha1sum", "sha256sum", "sha512sum", "sleep", "sort", "split",
            "stat", "strings", "stty", "tail", "tar", "tee", "test", "touch", "tr",
            "true", "uname", "unexpand", "uniq", "unlzma", "unxz", "unzip", "uudecode",
            "uuencode", "vi", "wc", "whoami", "xargs", "xz", "xzcat", "yes", "zcat"
    };

    /**
     * Prepare all tools or fail visibly: this bundle is not an optional asset.
     * A user's own regular file at an alias name is left alone (that one
     * alias is skipped and logged) so it can never block opening a shell.
     */
    public static synchronized File prepare(Context context) throws IOException {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String client : CLIENTS) {
            File executable = new File(nativeDir, "lib" + client + ".so");
            if (!executable.isFile() || !executable.canExecute()) {
                throw new IOException("Bundled Android tool missing/not executable: " + executable);
            }
        }
        File bin = new File(context.getFilesDir(), "android-bin");
        try {
            if (bin.exists() && OsConstants.S_ISLNK(Os.lstat(bin.getPath()).st_mode)) {
                throw new IOException("Tool alias directory must not be a symlink: " + bin);
            }
            if (!bin.isDirectory() && !bin.mkdir()) {
                throw new IOException("Cannot create tool alias directory: " + bin);
            }
            Os.chmod(bin.getPath(), 0700);
            for (String client : CLIENTS) {
                alias(bin, client, new File(nativeDir, "lib" + client + ".so"));
            }
            File busybox = new File(nativeDir, "libbusybox.so");
            for (String applet : APPLETS) alias(bin, applet, busybox);
            pruneStale(bin);
        } catch (ErrnoException e) {
            throw new IOException("Preparing Android shell tools", e);
        }
        return bin;
    }

    /** Only managed symlinks can be replaced; never clobber a user's regular file. */
    private static void alias(File bin, String name, File target) throws ErrnoException {
        File link = new File(bin, name);
        try {
            if (!OsConstants.S_ISLNK(Os.lstat(link.getPath()).st_mode)) {
                Log.w(TAG, "Not replacing non-symlink " + link + "; alias skipped");
                return;
            }
            if (Os.readlink(link.getPath()).equals(target.getAbsolutePath())) return;
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.ENOENT) throw e;
        }
        // Rename is atomic so another live shell never observes a missing alias
        // while an APK update refreshes its nativeLibraryDir target.
        File pending = new File(bin, "." + name + "." + UUID.randomUUID());
        Os.symlink(target.getAbsolutePath(), pending.getPath());
        try {
            Os.rename(pending.getPath(), link.getPath());
        } finally {
            pending.delete();
        }
    }

    /**
     * Removes managed symlinks an update no longer provides (an applet dropped
     * from the curated list) and pending links left by a crash mid-refresh.
     * Only symlinks that point at one of the bundled executables are touched.
     */
    private static void pruneStale(File bin) {
        Set<String> managed = new HashSet<>(Arrays.asList(CLIENTS));
        managed.addAll(Arrays.asList(APPLETS));
        Set<String> targets = new HashSet<>();
        for (String client : CLIENTS) targets.add("lib" + client + ".so");
        String[] names = bin.list();
        if (names == null) return;
        for (String name : names) {
            if (managed.contains(name)) continue;
            String path = new File(bin, name).getPath();
            try {
                if (!OsConstants.S_ISLNK(Os.lstat(path).st_mode)) continue;
                if (targets.contains(new File(Os.readlink(path)).getName())) Os.remove(path);
            } catch (ErrnoException e) {
                Log.w(TAG, "Pruning " + path, e);
            }
        }
    }
}

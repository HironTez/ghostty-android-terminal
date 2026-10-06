/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import io.github.sylirre.terminal.term.UserlandDistro;
import io.github.sylirre.terminal.term.UserlandIdentity;
import io.github.sylirre.terminal.term.UserlandOptions;
import io.github.sylirre.terminal.term.UserlandRootfs;

/**
 * The userland policy shared by the onboarding wizard, the main screen and
 * the headless API: installing a bundled distro and persisting the outcome,
 * and turning the user's settings into {@link UserlandOptions}.
 *
 * It lives next to {@link AppSettings} (which it reads and writes) rather than
 * in {@code term/}, which stays free of settings and UI types. Nothing here
 * touches a View, so it runs from any thread and with no Activity.
 */
public final class UserlandSetup {
    private UserlandSetup() {}

    /**
     * Extracts {@code distro} and records it as the installed userland:
     * distro asset, onboarding completed, and the login shell / home derived
     * from the new rootfs ({@link #applyPostInstallDefaults}). The outcome is
     * persisted the moment the extract succeeds, so a killed process cannot
     * lose a completed setup. Blocking; call from a background thread.
     * A no-op extract when a rootfs is already installed (see
     * {@link UserlandRootfs#install}).
     */
    public static void install(Context context, UserlandDistro distro,
            UserlandRootfs.InstallListener progress) throws IOException {
        Context app = context.getApplicationContext();
        UserlandRootfs.install(app, distro.assetName, progress);
        AppSettings settings = new AppSettings(app);
        settings.setUserlandDistroAsset(distro.assetName);
        settings.setOnboardingCompleted(true);
        applyPostInstallDefaults(app, settings);
    }

    /**
     * Points the login-shell and home settings at what the freshly installed
     * rootfs actually provides (e.g. {@code /bin/ash -l} on Alpine, whose
     * root user has no bash), mirroring what the Settings identity dialog
     * does when the identity changes.
     */
    public static void applyPostInstallDefaults(Context context, AppSettings settings) {
        File root = UserlandRootfs.dir(context);
        String identity = settings.userlandIdentity();
        String shell = UserlandRootfs.deriveLoginShell(root, identity);
        if (shell != null) settings.setUserlandLoginShell(shell);
        String home = UserlandIdentity.homeForIdentity(root, identity);
        if (home != null && !home.trim().isEmpty()) settings.setUserlandHome(home);
    }

    /**
     * The userland inputs for a new session from the current settings. The
     * storage binding is dropped (and the setting turned off) when the storage
     * permission was revoked, as the main screen always did.
     */
    public static UserlandOptions options(Context context, AppSettings settings) {
        return new UserlandOptions(
                settings.userlandLoginShell(), storageBindingEnabled(context, settings),
                settings.userlandIdentity(), settings.userlandHome(),
                settings.userlandWorkDir(), settings.userlandLocale(),
                settings.userlandPath(),
                settings.userlandJitEnabled(), settings.userlandJitBufferMb(),
                settings.userlandChrootNgEnabled());
    }

    private static boolean storageBindingEnabled(Context context, AppSettings settings) {
        if (!settings.bindExternalStorage()) return false;
        if (StoragePermission.granted(context)) return true;
        settings.setBindExternalStorage(false);
        return false;
    }
}

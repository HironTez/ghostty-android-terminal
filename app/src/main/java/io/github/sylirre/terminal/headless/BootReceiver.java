/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.headless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import io.github.sylirre.terminal.term.SessionService;

/**
 * Starts headless mode after boot — only when opted in with the
 * {@code autostart} op ({@code gterm autostart on}) and headless mode was on
 * when the phone went down (not ended by {@code gterm stop}); otherwise a
 * no-op. BOOT_COMPLETED is a protected broadcast: only the system sends it.
 * BOOT_COMPLETED arrives only after the first unlock (credential-encrypted
 * storage), so a phone with a lock screen stays unreachable until unlocked.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // Opted in, and headless mode was still on when the phone went down:
        // an explicit `gterm stop` is honored across reboots.
        if (!SessionService.autostart(context)) return;
        if (!SessionService.headlessEnabled(context)) return;
        try {
            // specialUse foreground services may start from BOOT_COMPLETED.
            SessionService.startHeadless(context, true);
        } catch (RuntimeException e) {
            Log.e("BootReceiver", "headless autostart refused", e);
        }
    }
}

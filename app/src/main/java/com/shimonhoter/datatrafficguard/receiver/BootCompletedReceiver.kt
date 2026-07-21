package com.shimonhoter.datatrafficguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * No logic of its own — simply being a registered receiver for BOOT_COMPLETED
 * causes the OS to create the app process right after boot, which runs
 * DataTrafficGuardApp.onCreate() and starts the guard service immediately,
 * before the user opens the app.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty.
    }
}

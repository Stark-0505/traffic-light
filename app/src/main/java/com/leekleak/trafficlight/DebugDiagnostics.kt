package com.leekleak.trafficlight

import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy

/**
 * Debug-only StrictMode diagnostics (combines upstream daca619, 839d456 and 03f1feb).
 *
 * Does nothing unless [enabled] - the caller passes `BuildConfig.DEBUG`, so release builds
 * never install a policy and behave exactly as before. Violations are only logged
 * (`penaltyLog`), never fatal.
 *
 * Upstream ended up on `detectAll()` for both policies; this keeps the checks that map onto
 * what this app can plausibly get wrong, so that a report is worth reading:
 *
 * Thread policy - main-thread work that blocks: disk reads/writes, network, unbuffered I/O,
 * and resource mismatches. Not enabled: custom slow calls (nothing here reports any) and
 * explicit GC.
 *
 * VM policy - leaks: activities, closeables, and leaked receivers/observers/service
 * connections (the app registers dynamic receivers and a ContentObserver), plus incorrect
 * Context use on API 31+. Not enabled: SQLite leaks (no database), file/content URI exposure
 * and unsafe intent launches (nothing is shared that way), non-SDK API use (reports mostly
 * originate in libraries).
 *
 * Installed at the very start of `Application.onCreate`, after the known main-thread I/O sources
 * were moved off the main thread, so what remains in the log is new.
 */
internal fun configureStrictMode(enabled: Boolean, sdkInt: Int = Build.VERSION.SDK_INT) {
    if (!enabled) return

    StrictMode.setThreadPolicy(
        ThreadPolicy.Builder(StrictMode.getThreadPolicy())
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectUnbufferedIo()
            .detectResourceMismatches()
            .penaltyLog()
            .build()
    )
    StrictMode.setVmPolicy(
        VmPolicy.Builder(StrictMode.getVmPolicy()).apply {
            detectActivityLeaks()
            detectLeakedClosableObjects()
            detectLeakedRegistrationObjects()
            if (sdkInt >= Build.VERSION_CODES.S) detectIncorrectContextUse()
            penaltyLog()
        }.build()
    )
}

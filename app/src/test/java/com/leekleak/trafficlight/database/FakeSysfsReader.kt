package com.leekleak.trafficlight.database

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [SysfsReader] for tests. Records which threads performed reads so tests can
 * assert that blocking I/O never happens on the caller's (e.g. main) thread.
 */
internal class FakeSysfsReader(
    files: Map<String, String> = emptyMap(),
) : SysfsReader {
    val files: MutableMap<String, String> = files.toMutableMap()

    /** Paths that exist on disk but are reported unreadable (permission denied). */
    val unreadable: MutableSet<String> = mutableSetOf()

    /** If set, thrown from [readText] for every path (simulates I/O or SELinux failure). */
    @Volatile var readFailure: Throwable? = null

    val readThreads: MutableList<Thread> = CopyOnWriteArrayList()
    val probeThreads: MutableList<Thread> = CopyOnWriteArrayList()

    override fun canRead(path: String): Boolean {
        probeThreads += Thread.currentThread()
        return path in files && path !in unreadable
    }

    override fun readText(path: String): String {
        readThreads += Thread.currentThread()
        readFailure?.let { throw it }
        return files.getValue(path)
    }
}

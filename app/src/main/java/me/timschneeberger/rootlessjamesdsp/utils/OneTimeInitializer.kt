package me.timschneeberger.rootlessjamesdsp.utils

/**
 * Runs an initialisation action at most once per process.
 *
 * Used to move one-off setup off [android.app.Application.onCreate] and onto the first code path
 * that actually needs it. The process is frequently created headlessly (notification listener
 * rebind, Shizuku provider access, BOOT_COMPLETED) and those binds must stay cheap: a bind that
 * overruns its deadline is killed with a BIND APPLICATION ANR, which is far more likely while the
 * device is still starved for CPU shortly after boot.
 *
 * If [runOnce] throws, the action is *not* marked as done, so the next caller retries. That keeps a
 * transient failure (for example a system service not yet reachable during boot) from permanently
 * disabling the subsystem for the lifetime of the process.
 */
class OneTimeInitializer {
    @Volatile
    private var done = false

    /** True once [runOnce] has completed an action without throwing. */
    val hasRun: Boolean
        get() = done

    /**
     * Invokes [action] unless a previous call already completed successfully.
     *
     * Concurrent callers are serialised, and only one of them runs [action].
     */
    fun runOnce(action: () -> Unit) {
        if (done) return
        synchronized(this) {
            if (done) return
            action()
            done = true
        }
    }
}

package me.timschneeberger.rootlessjamesdsp.diagnostics

import java.util.concurrent.atomic.AtomicReference

/** Privacy-safe process-local state, never a claim that source PCM is capturable. */
object CaptureSessionStatus {
    private val query = AtomicReference("NOT_QUERIED")
    private val admission = AtomicReference("STOPPED")
    private val convolver = AtomicReference("NOT_EVALUATED")

    fun started() {
        query.set("NOT_QUERIED")
        admission.set("AWAITING_SESSION_QUERY")
        convolver.set("NOT_EVALUATED")
    }

    fun queryCompleted(observable: Boolean, candidates: Int, providers: Int, failures: Int) {
        query.set("${if (!observable) "UNAVAILABLE" else if (candidates == 0) "NO_USABLE_EXTERNAL_SESSION" else "CANDIDATES_OBSERVED"}; candidates=$candidates providers=$providers unavailable=$failures")
    }

    fun sessionsChanged(count: Int) {
        admission.set(if (count == 0) "IDLE_NO_ACCEPTED_SESSION" else "ACCEPTED_SESSIONS=$count; PCM_CAPTURE_UNVERIFIED")
    }

    fun stopped() { admission.set("STOPPED") }
    fun convolver(state: String) { convolver.set(state) }
    fun summary(): String = "sessionQuery=${query.get()}\nsessionAdmission=${admission.get()}\nconvolver=${convolver.get()}"
}

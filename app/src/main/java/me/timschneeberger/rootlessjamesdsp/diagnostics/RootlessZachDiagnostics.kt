package me.timschneeberger.rootlessjamesdsp.diagnostics

import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import java.util.concurrent.atomic.AtomicReference

/** In-memory bridge from the real-time service to compatibility diagnostics. */
object RootlessZachDiagnostics {
    private val snapshot = AtomicReference<AudioTransportTelemetry.Snapshot?>(null)
    fun publish(value: AudioTransportTelemetry.Snapshot) = snapshot.set(value)
    fun latestTransportSnapshot(): AudioTransportTelemetry.Snapshot? = snapshot.get()
    fun clear() = snapshot.set(null)
}

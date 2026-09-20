package me.timschneeberger.rootlessjamesdsp.interop

internal object ImpulseResponseValidation {
    fun valid(samples: FloatArray, channels: Int, frames: Int): Boolean =
        channels > 0 && frames > 0 && channels.toLong() * frames == samples.size.toLong() &&
            samples.all { it.isFinite() }
}

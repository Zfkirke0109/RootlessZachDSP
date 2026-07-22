package me.timschneeberger.rootlessjamesdsp.audio.direct

/** Verifies that a decoder with a declared frame total does not silently end early. */
class LosslessFrameProgress(private val declaredTotalFrames: Long) {
    var decodedFrames: Long = 0
        private set

    fun record(frames: Int) {
        require(frames > 0) { "Decoded frame count must be positive" }
        decodedFrames = Math.addExact(decodedFrames, frames.toLong())
        check(declaredTotalFrames <= 0 || decodedFrames <= declaredTotalFrames) {
            "Lossless decoder exceeded its declared frame count ($decodedFrames > $declaredTotalFrames)"
        }
    }

    fun verifyEndOfStream() {
        check(declaredTotalFrames <= 0 || decodedFrames == declaredTotalFrames) {
            "Lossless decoder ended early after $decodedFrames of $declaredTotalFrames frames"
        }
    }
}

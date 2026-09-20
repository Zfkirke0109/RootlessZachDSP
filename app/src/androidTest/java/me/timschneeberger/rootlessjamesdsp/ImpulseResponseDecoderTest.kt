package me.timschneeberger.rootlessjamesdsp

import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.diagnostics.CaptureSessionStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImpulseResponseDecoderTest {
    private fun wav(frames: Int): ByteArray = ByteBuffer.allocate(44 + frames * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(48000); putInt(96000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { putShort(if (it == 0) 16000 else 0) }
        }.array()

    @Test fun directPlayerConvolverDoesNotOverwriteCaptureDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CaptureSessionStatus.convolver("CAPTURE_SENTINEL")
        JamesDspLocalEngine(context, publishRootlessDiagnostics = false).use { engine ->
            assertTrue(engine.setConvolver(false, "", 0, ""))
            assertTrue(CaptureSessionStatus.summary().contains("convolver=CAPTURE_SENTINEL"))
        }
    }

    @Test fun emptyAndMalformedFilesNeverReachOptimization() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        for (bytes in listOf(wav(0), byteArrayOf(1, 2, 3))) {
            val file = File.createTempFile("invalid-ir", ".wav", dir)
            try {
                file.writeBytes(bytes)
                for (mode in 0..2) {
                    val info = IntArray(4)
                    val result = JdspImpResToolbox.ReadImpulseResponseToFloat(file.path, 48000, info,
                        mode, intArrayOf(-80, -100, 0, 0, 0, 0))
                    assertTrue("Invalid IR must not have decoded samples", result == null || result.isEmpty())
                    assertEquals(0, info[1])
                }
            } finally { file.delete() }
        }
    }

    @Test fun validMonoImpulseDecodesInEveryOptimizationMode() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("valid-ir", ".wav", dir)
        try {
            file.writeBytes(wav(64))
            for (mode in 0..2) {
                val info = IntArray(4)
                val result = JdspImpResToolbox.ReadImpulseResponseToFloat(file.path, 48000, info,
                    mode, intArrayOf(-80, -100, 0, 0, 0, 0))
                assertNotNull(result)
                assertTrue(info[1] > 0)
                assertEquals(info[0] * info[1], result!!.size)
                assertTrue(result.all { it.isFinite() })
            }
        } finally { file.delete() }
    }
}

package me.timschneeberger.rootlessjamesdsp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLeakScannerTest {
    @Test
    fun `technical redacted report is safe`() {
        val report = """
            applicationId=com.zfkirke0109.rootlesszachdsp.debug
            selectedPackages=<redacted>
            selectedRawUids=<redacted>
            outputDevice[0]=type:2,name:<redacted>,sampleRates:48000,channels:2
            token=<redacted>
            deviceSerial=<redacted>
            storage=app-private-rotating-jsonl
        """.trimIndent()

        assertTrue(DiagnosticsLeakScanner.isSafeToShare(report))
        assertTrue(DiagnosticsLeakScanner.scan(report).isEmpty())
    }

    @Test
    fun `scanner identifies uri paths secrets serials and endpoints without echoing values`() {
        val report = """
            source=content://media/external/audio/1
            file=file:///sdcard/Music/private.wav
            private=/data/user/0/app/files/log.txt
            windows=C:\Users\person\Desktop\capture.txt
            token=super-secret-value
            deviceSerial=RANDOMSERIAL
            adb=192.168.1.10:5555
        """.trimIndent()

        val findings = DiagnosticsLeakScanner.scan(report)
        val categories = findings.map { it.category }.toSet()

        assertTrue(DiagnosticsLeakScanner.Category.CONTENT_OR_FILE_URI in categories)
        assertTrue(DiagnosticsLeakScanner.Category.ANDROID_PRIVATE_OR_SHARED_PATH in categories)
        assertTrue(DiagnosticsLeakScanner.Category.WINDOWS_USER_PATH in categories)
        assertTrue(DiagnosticsLeakScanner.Category.SECRET_ASSIGNMENT in categories)
        assertTrue(DiagnosticsLeakScanner.Category.DEVICE_SERIAL in categories)
        assertTrue(DiagnosticsLeakScanner.Category.ADB_OR_NETWORK_ENDPOINT in categories)
        assertFalse(DiagnosticsLeakScanner.isSafeToShare(report))
        assertEquals(findings.size, findings.distinct().size)
    }

    @Test
    fun `redacted and null sensitive values do not trigger`() {
        val report = """
            password=<redacted>
            token=null
            secret=none
            apiKey=false
            serialNumber=<redacted>
        """.trimIndent()

        assertTrue(DiagnosticsLeakScanner.scan(report).isEmpty())
    }
}

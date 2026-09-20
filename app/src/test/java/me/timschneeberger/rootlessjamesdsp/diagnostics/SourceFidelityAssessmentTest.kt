package me.timschneeberger.rootlessjamesdsp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFidelityAssessmentTest {
    @Test
    fun `missing peak is unknown and recommends no gain change`() {
        val result = SourceFidelityAssessment.assess(null)

        assertEquals(SourceFidelityAssessment.HeadroomRisk.UNKNOWN, result.risk)
        assertEquals(0.0, result.recommendedPreampDb, 0.0)
        assertFalse(result.finalSystemMixMeasured)
    }

    @Test
    fun `zero peak is no signal`() {
        val result = SourceFidelityAssessment.assess(0.0)

        assertEquals(SourceFidelityAssessment.HeadroomRisk.NO_SIGNAL, result.risk)
        assertEquals(Double.NEGATIVE_INFINITY, result.outputPeakDbfs ?: 0.0, 0.0)
        assertEquals(0.0, result.recommendedPreampDb, 0.0)
    }

    @Test
    fun `safe peak never recommends positive preamp`() {
        val result = SourceFidelityAssessment.assess(0.5)

        assertEquals(SourceFidelityAssessment.HeadroomRisk.SAFE, result.risk)
        assertEquals(0.0, result.recommendedPreampDb, 0.0)
        assertTrue((result.headroomDb ?: 0.0) > 5.9)
    }

    @Test
    fun `near ceiling peak is critical with conservative preamp recommendation`() {
        val result = SourceFidelityAssessment.assess(0.9772372841835022)

        assertEquals(SourceFidelityAssessment.HeadroomRisk.CRITICAL, result.risk)
        assertEquals(-0.20, result.outputPeakDbfs ?: 0.0, 0.02)
        assertEquals(0.20, result.headroomDb ?: 0.0, 0.02)
        assertEquals(-1.80, result.recommendedPreampDb, 0.02)
        assertTrue(result.compactString().contains("finalSystemMixMeasured=false"))
    }

    @Test
    fun `unity or higher is classified as clipping`() {
        assertEquals(
            SourceFidelityAssessment.HeadroomRisk.CLIPPING,
            SourceFidelityAssessment.assess(1.0).risk,
        )
        assertEquals(
            SourceFidelityAssessment.HeadroomRisk.CLIPPING,
            SourceFidelityAssessment.assess(1.2).risk,
        )
    }

    @Test
    fun `MQA status never claims unlicensed capability`() {
        val status = SourceFidelityAssessment.mqaStatus()

        assertFalse(status.decoderIncluded)
        assertFalse(status.rendererIncluded)
        assertFalse(status.passthroughClaimed)
        assertFalse(status.carrierDetected)
        assertFalse(status.sourceAuthenticationObservable)
        assertEquals("AUTHORIZED_MODULE_NOT_INSTALLED", status.integrationState)
    }
}

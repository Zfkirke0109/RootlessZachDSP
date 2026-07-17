package me.timschneeberger.rootlessjamesdsp.diagnostics

import java.util.Locale
import kotlin.math.log10
import kotlin.math.min

/**
 * Truthful source-fidelity and headroom assessment for the current AudioTrack-input telemetry
 * window. This class never claims to observe Samsung's final system mix or an external DAC.
 */
object SourceFidelityAssessment {
    enum class HeadroomRisk {
        UNKNOWN,
        NO_SIGNAL,
        SAFE,
        CAUTION,
        CRITICAL,
        CLIPPING,
    }

    data class Result(
        val risk: HeadroomRisk,
        val outputPeak: Double?,
        val outputPeakDbfs: Double?,
        val headroomDb: Double?,
        val targetPeakDbfs: Double,
        val recommendedPreampDb: Double,
        val finalSystemMixMeasured: Boolean = false,
    ) {
        fun compactString(): String = buildString {
            append("risk=").append(risk)
            append(" targetPeakDbfs=").append(formatDb(targetPeakDbfs))
            if (outputPeak == null || outputPeakDbfs == null || headroomDb == null) {
                append(" currentWindowPeak=unknown")
            } else {
                append(" currentWindowPeak=").append(formatLinear(outputPeak))
                append(" currentWindowPeakDbfs=").append(formatDb(outputPeakDbfs))
                append(" currentWindowHeadroomDb=").append(formatDb(headroomDb))
            }
            append(" recommendedPreampDb=").append(formatDb(recommendedPreampDb))
            append(" finalSystemMixMeasured=").append(finalSystemMixMeasured)
        }
    }

    data class MqaIntegrationStatus(
        val decoderIncluded: Boolean = false,
        val rendererIncluded: Boolean = false,
        val passthroughClaimed: Boolean = false,
        val carrierDetected: Boolean = false,
        val sourceAuthenticationObservable: Boolean = false,
        val integrationState: String = "AUTHORIZED_MODULE_NOT_INSTALLED",
        val nextLegalStep: String =
            "Obtain a written MQA Labs integration and redistribution agreement before adding " +
                "decoder, renderer, authentication, trademark, or conformance claims",
    )

    fun assess(outputPeak: Double?): Result {
        if (outputPeak == null || !outputPeak.isFinite() || outputPeak < 0.0) {
            return Result(
                risk = HeadroomRisk.UNKNOWN,
                outputPeak = null,
                outputPeakDbfs = null,
                headroomDb = null,
                targetPeakDbfs = TARGET_PEAK_DBFS,
                recommendedPreampDb = 0.0,
            )
        }
        if (outputPeak == 0.0) {
            return Result(
                risk = HeadroomRisk.NO_SIGNAL,
                outputPeak = 0.0,
                outputPeakDbfs = Double.NEGATIVE_INFINITY,
                headroomDb = Double.POSITIVE_INFINITY,
                targetPeakDbfs = TARGET_PEAK_DBFS,
                recommendedPreampDb = 0.0,
            )
        }

        val peakDbfs = 20.0 * log10(outputPeak)
        val headroomDb = -peakDbfs
        val risk = when {
            outputPeak >= 1.0 -> HeadroomRisk.CLIPPING
            headroomDb < CRITICAL_HEADROOM_DB -> HeadroomRisk.CRITICAL
            headroomDb < CAUTION_HEADROOM_DB -> HeadroomRisk.CAUTION
            else -> HeadroomRisk.SAFE
        }
        val recommendedPreamp = min(0.0, TARGET_PEAK_DBFS - peakDbfs)
        return Result(
            risk = risk,
            outputPeak = outputPeak,
            outputPeakDbfs = peakDbfs,
            headroomDb = headroomDb,
            targetPeakDbfs = TARGET_PEAK_DBFS,
            recommendedPreampDb = recommendedPreamp,
        )
    }

    fun mqaStatus(): MqaIntegrationStatus = MqaIntegrationStatus()

    fun renderUserSummary(outputPeak: Double?): String {
        val result = assess(outputPeak)
        val mqa = mqaStatus()
        return buildString {
            appendLine("Source fidelity and headroom")
            appendLine(result.compactString())
            appendLine()
            appendLine("Measurement boundary: RootlessZachDSP AudioTrack input")
            appendLine("This is not the final Samsung system mix or an external DAC measurement.")
            appendLine()
            appendLine("MQA integration status")
            appendLine("decoderIncluded=${mqa.decoderIncluded}")
            appendLine("rendererIncluded=${mqa.rendererIncluded}")
            appendLine("passthroughClaimed=${mqa.passthroughClaimed}")
            appendLine("carrierDetected=${mqa.carrierDetected}")
            appendLine("sourceAuthenticationObservable=${mqa.sourceAuthenticationObservable}")
            appendLine("integrationState=${mqa.integrationState}")
            appendLine("legalGate=${mqa.nextLegalStep}")
            appendLine(
                "compatibilityNote=A lawfully licensed source app may decode before Android " +
                    "playback capture; RootlessZachDSP cannot authenticate that source from " +
                    "post-capture PCM",
            )
        }.trimEnd()
    }

    private fun formatDb(value: Double): String = when {
        value == Double.POSITIVE_INFINITY -> "+inf"
        value == Double.NEGATIVE_INFINITY -> "-inf"
        else -> String.format(Locale.US, "%.2f", value)
    }

    private fun formatLinear(value: Double): String = String.format(Locale.US, "%.6f", value)

    private const val TARGET_PEAK_DBFS = -2.0
    private const val CRITICAL_HEADROOM_DB = 0.5
    private const val CAUTION_HEADROOM_DB = 1.5
}

package me.timschneeberger.rootlessjamesdsp.audio.profile

import me.timschneeberger.rootlessjamesdsp.model.api.AeqSearchResult

/**
 * Conservative local starting templates, not laboratory AutoEQ corrections.
 *
 * Both templates avoid positive gain so they preserve headroom. Users should calibrate by ear or
 * replace them with measured data. They are intentionally labeled unmeasured in the selector.
 */
object LocalEquipmentProfiles {
    const val SOURCE = "RootlessZachDSP local template · unmeasured"

    data class Profile(
        val id: Long,
        val name: String,
        val graphicEq: String,
    )

    private val profiles = listOf(
        Profile(
            id = -10_001L,
            name = "Samsung Galaxy S23 Ultra speakers — safe starting point",
            graphicEq = "GraphicEQ: 20 -12; 31 -10; 50 -8; 80 -4; 125 -1.5; 250 0; 500 -0.5; 1000 0; 2000 -1; 4000 -1.5; 8000 -0.5; 16000 0;",
        ),
        Profile(
            id = -10_002L,
            name = "2024–2025 Jeep Wrangler 4xe premium audio — neutral starting point",
            graphicEq = "GraphicEQ: 20 -2; 31 -1.5; 63 -1; 125 -0.5; 250 -1; 500 0; 1000 0; 2000 -0.5; 4000 -1; 8000 -0.5; 16000 0;",
        ),
    )

    fun allResults(): Array<AeqSearchResult> = profiles.map(::toSearchResult).toTypedArray()

    fun search(query: String): Array<AeqSearchResult> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return allResults()
        return profiles
            .filter { it.name.contains(normalized, ignoreCase = true) }
            .map(::toSearchResult)
            .toTypedArray()
    }

    fun find(id: Long): Profile? = profiles.firstOrNull { it.id == id }

    private fun toSearchResult(profile: Profile) = AeqSearchResult(
        name = profile.name,
        source = SOURCE,
        rank = Int.MAX_VALUE,
        id = profile.id,
    )
}

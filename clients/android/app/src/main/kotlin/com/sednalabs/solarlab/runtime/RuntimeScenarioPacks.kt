package com.sednalabs.solarlab.runtime

data class RuntimeScenarioPack(
    val scenarioId: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val defaultFocusBodyId: String?,
    val defaultObserverMode: RuntimeObserverMode,
    val startPaused: Boolean,
    val simSecondsPerRealSecond: Double,
)

object RuntimeScenarioPacks {
    const val DEFAULT_SCENARIO_ID = "sol-system"

    val all: List<RuntimeScenarioPack> = listOf(
        RuntimeScenarioPack(
            scenarioId = DEFAULT_SCENARIO_ID,
            title = "Canonical solar system",
            description = "Full canonical startup catalog for broad visual and runtime smoke checks.",
            tags = listOf("canonical", "wide", "default"),
            defaultFocusBodyId = "earth",
            defaultObserverMode = RuntimeObserverMode.FollowSelected,
            startPaused = false,
            simSecondsPerRealSecond = 21_600.0,
        ),
        RuntimeScenarioPack(
            scenarioId = "showcase.inner-system",
            title = "Inner system showcase",
            description = "Sun through Mars plus near-Earth small bodies for dense close-range controls.",
            tags = listOf("showcase", "close", "small-bodies"),
            defaultFocusBodyId = "earth",
            defaultObserverMode = RuntimeObserverMode.FollowSelected,
            startPaused = false,
            simSecondsPerRealSecond = 7_200.0,
        ),
        RuntimeScenarioPack(
            scenarioId = "showcase.earth-moon",
            title = "Earth and Moon choreography",
            description = "Close-scale Earth/Moon framing with marker tracers for camera and trail polish.",
            tags = listOf("showcase", "close", "moon"),
            defaultFocusBodyId = "moon",
            defaultObserverMode = RuntimeObserverMode.FollowHost,
            startPaused = true,
            simSecondsPerRealSecond = 3_600.0,
        ),
        RuntimeScenarioPack(
            scenarioId = "showcase.jupiter-system",
            title = "Jupiter moon theatre",
            description = "Jupiter with four bright Galilean moons for high-drama orbit framing.",
            tags = listOf("showcase", "moons", "outer-system"),
            defaultFocusBodyId = "jupiter",
            defaultObserverMode = RuntimeObserverMode.FollowSelected,
            startPaused = false,
            simSecondsPerRealSecond = 14_400.0,
        ),
        RuntimeScenarioPack(
            scenarioId = "showcase.comet-flyby",
            title = "Comet flyby",
            description = "A cinematic small-body pass with planets retained for scale.",
            tags = listOf("showcase", "comet", "flyby"),
            defaultFocusBodyId = "halley",
            defaultObserverMode = RuntimeObserverMode.FollowSelected,
            startPaused = false,
            simSecondsPerRealSecond = 43_200.0,
        ),
        RuntimeScenarioPack(
            scenarioId = "stress.trail-density",
            title = "Trail density stress",
            description = "A denser tracer field for checking beauty, legibility, and render pressure.",
            tags = listOf("stress", "trails", "density"),
            defaultFocusBodyId = "sun",
            defaultObserverMode = RuntimeObserverMode.SystemFrame,
            startPaused = false,
            simSecondsPerRealSecond = 86_400.0,
        ),
        RuntimeScenarioPack(
            scenarioId = "stress.s25-tile-swarm",
            title = "S25 tile swarm",
            description = "A Galaxy S25 Ultra stress pack shaped to exercise Arm64 parallel tiled scheduler telemetry.",
            tags = listOf("stress", "s25", "arm64", "tiles"),
            defaultFocusBodyId = "sun",
            defaultObserverMode = RuntimeObserverMode.SystemFrame,
            startPaused = false,
            simSecondsPerRealSecond = 172_800.0,
        ),
    )

    fun byId(scenarioId: String?): RuntimeScenarioPack? {
        val normalized = scenarioId?.trim().orEmpty()
        return all.firstOrNull { it.scenarioId == normalized }
    }

    fun requireKnown(scenarioId: String): RuntimeScenarioPack =
        requireNotNull(byId(scenarioId)) { "Unknown runtime scenario pack: $scenarioId" }

    val default: RuntimeScenarioPack
        get() = requireNotNull(byId(DEFAULT_SCENARIO_ID))
}

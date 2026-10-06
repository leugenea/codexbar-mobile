package io.github.leugenea.codexbarmobile

/** UI previews only. No state represents a provider connection or authenticated account. */
enum class Preview(val savedKey: String) {
    Disconnected("disconnected"),
    Loading("loading"),
    Error("error"),
    Demo("demo");

    companion object {
        fun restore(savedKey: String?): Preview =
            entries.firstOrNull { it.savedKey == savedKey } ?: Disconnected
    }
}

/** Hand-authored synthetic display data, not a provider DTO or captured account. */
data class DemoUsage(val fiveHourUsedPercent: Int, val weeklyUsedPercent: Int) {
    init {
        require(fiveHourUsedPercent in 0..100)
        require(weeklyUsedPercent in 0..100)
    }
}

object OfflineFixtures {
    val usage = DemoUsage(fiveHourUsedPercent = 32, weeklyUsedPercent = 58)
}

/** Small synchronous UI model: loading is a frozen preview, never a background request. */
data class OfflineShellState(val preview: Preview = Preview.Disconnected) {
    val demoUsage: DemoUsage?
        get() = if (preview == Preview.Demo) OfflineFixtures.usage else null

    fun select(preview: Preview): OfflineShellState = copy(preview = preview)

    fun retryPreview(): OfflineShellState =
        if (preview == Preview.Error) select(Preview.Loading) else this

    fun showFixture(): OfflineShellState =
        if (preview == Preview.Disconnected || preview == Preview.Loading) select(Preview.Demo) else this

    fun resetPreview(): OfflineShellState = OfflineShellState()

    companion object {
        fun restore(savedKey: String?): OfflineShellState = OfflineShellState(Preview.restore(savedKey))
    }
}

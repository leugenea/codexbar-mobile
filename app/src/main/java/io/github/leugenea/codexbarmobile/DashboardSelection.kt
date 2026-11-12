package io.github.leugenea.codexbarmobile

import java.util.UUID

/** One root display lifetime, not an entry/session/storage generation. Never saved or reused. */
internal class DashboardSelection(val id: UUID?, val controller: ConnectionController,
    private val current: () -> DashboardSelection?) {
    fun isCurrent() = current() === this
    fun historyContext() = if (isCurrent()) controller.currentHistoryDisplayContext() else null
}

/** The captured registration releases only itself, including after a newer START. */
internal class DashboardObserver(private val release: suspend () -> Unit) {
    suspend fun stop() = release()
}

/** Management address is independent of whichever entry currently owns the dashboard. */
internal class AccountSettingsHandle(val id: UUID, val controller: ConnectionController)

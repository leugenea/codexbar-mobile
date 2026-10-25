package io.github.leugenea.codexbarmobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics

/** Gesture state is presentation-only; the process owner's cycle remains the refresh authority. */
@Composable
internal fun DashboardRefresh(active: Boolean, state: ConnectionState, refresh: () -> Unit,
    content: @Composable BoxScope.() -> Unit) {
    if (!active) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }
    val enabled = UsagePresentation.refreshEnabled(state)
    val refreshing = UsagePresentation.refreshing(state)
    val pull = rememberPullToRefreshState()
    val label = stringResource(R.string.dashboard_refresh_action)
    Box(Modifier.fillMaxSize().testTag("dashboard-refresh")
        .pullToRefresh(isRefreshing = refreshing, state = pull, enabled = enabled, onRefresh = refresh)
        .semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction(label) { refresh(); true })
            else disabled()
        }) {
        content()
        PullToRefreshDefaults.Indicator(isRefreshing = refreshing, state = pull,
            modifier = Modifier.align(Alignment.TopCenter)
                .then(if (refreshing) Modifier.testTag("dashboard-refresh-indicator") else Modifier))
    }
}

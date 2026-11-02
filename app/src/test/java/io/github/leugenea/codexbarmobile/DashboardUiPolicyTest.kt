package io.github.leugenea.codexbarmobile

import org.junit.Assert.*
import org.junit.Test

class DashboardUiPolicyTest {
    @Test fun releasePolicyRejectsDiagnosticSelectionsAndKeepsOrdinaryDestinations() {
        for ((requested, expected) in listOf(
            DashboardRoute.DASHBOARD to DashboardRoute.DASHBOARD,
            DashboardRoute.LOCAL_HISTORY_DETAILS to DashboardRoute.DASHBOARD,
            DashboardRoute.CONNECTION_DETAILS to DashboardRoute.DASHBOARD,
            DashboardRoute.ACCOUNT_SETTINGS to DashboardRoute.ACCOUNT_SETTINGS,
        )) {
            val allowed = allowedDashboardRoute(requested, diagnosticsEnabled = false)
            assertEquals(expected, allowed)
            assertEquals("A remembered rejected selection remains safe on subsequent passes",
                allowed, allowedDashboardRoute(allowed, diagnosticsEnabled = false))
        }
    }

    @Test fun debugBuildPolicyAndIsolatedIdentityRetainEveryDestination() {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("debug", BuildConfig.BUILD_TYPE)
        assertEquals("io.github.leugenea.codexbarmobile.dev", BuildConfig.APPLICATION_ID)
        for (route in DashboardRoute.entries) assertEquals(route, allowedDashboardRoute(route, BuildConfig.DEBUG))
    }
}

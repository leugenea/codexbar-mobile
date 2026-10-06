package io.github.leugenea.codexbarmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineShellStateTest {
    @Test
    fun newLaunchHasNoUsageAndIsDisconnected() {
        val state = OfflineShellState()
        assertEquals(Preview.Disconnected, state.preview)
        assertNull(state.demoUsage)
    }

    @Test
    fun everySelectionIsExplicitAndOnlyDemoExposesSyntheticUsage() {
        val initial = OfflineShellState()
        Preview.entries.forEach { preview ->
            val selected = initial.select(preview)
            assertEquals(preview, selected.preview)
            if (preview == Preview.Demo) {
                assertSame(OfflineFixtures.usage, selected.demoUsage)
            } else {
                assertNull(selected.demoUsage)
            }
        }
        assertEquals(Preview.Disconnected, initial.preview)
    }

    @Test
    fun retryMovesOnlyAnErrorToFrozenLoadingWithoutExposingUsage() {
        val error = OfflineShellState(Preview.Error)
        val retry = error.retryPreview()
        assertEquals(Preview.Loading, retry.preview)
        assertNull(retry.demoUsage)
        assertEquals(Preview.Error, error.preview)
        Preview.entries.filter { it != Preview.Error }.forEach {
            val state = OfflineShellState(it)
            assertSame(state, state.retryPreview())
        }
    }

    @Test
    fun fixtureActionIsAdmittedOnlyFromDisconnectedOrLoading() {
        listOf(Preview.Disconnected, Preview.Loading).forEach {
            val sample = OfflineShellState(it).showFixture()
            assertEquals(Preview.Demo, sample.preview)
            assertEquals(DemoUsage(32, 58), sample.demoUsage)
        }
        listOf(Preview.Error, Preview.Demo).forEach {
            val state = OfflineShellState(it)
            assertSame(state, state.showFixture())
        }
    }

    @Test
    fun resetAlwaysRemovesTheSampleAndReturnsToDisconnected() {
        Preview.entries.forEach {
            val reset = OfflineShellState(it).resetPreview()
            assertEquals(Preview.Disconnected, reset.preview)
            assertNull(reset.demoUsage)
        }
    }

    @Test
    fun savedSelectionRoundTripsWithoutIntroducingAnAuthenticatedState() {
        assertEquals(listOf("disconnected", "loading", "error", "demo"), Preview.entries.map { it.savedKey })
        Preview.entries.forEach {
            assertEquals(OfflineShellState(it), OfflineShellState.restore(it.savedKey))
        }
    }

    @Test
    fun absentCorruptOrFutureSavedSelectionFailsClosed() {
        listOf(null, "", "connected", "authenticated", "DEMO", " demo ").forEach {
            val restored = OfflineShellState.restore(it)
            assertEquals(Preview.Disconnected, restored.preview)
            assertNull(restored.demoUsage)
        }
    }

    @Test
    fun syntheticUsageRejectsInvalidPercentagesInsteadOfClampingThem() {
        listOf(-1, 101).forEach {
            assertThrows(IllegalArgumentException::class.java) { DemoUsage(it, 58) }
            assertThrows(IllegalArgumentException::class.java) { DemoUsage(32, it) }
        }
        val boundary = DemoUsage(0, 100)
        assertEquals(0, boundary.fiveHourUsedPercent)
        assertEquals(100, boundary.weeklyUsedPercent)
    }
}

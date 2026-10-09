package io.github.leugenea.codexbarmobile

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.filters.SdkSuppress
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Real production Activity, process owner, A9/B2 state and protected store; synthetic data only. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LiveUsageScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalFactory = NativeConnection.factory
    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()
    private lateinit var originalAppLocales: LocaleList
    private val clock = ScreenClock()
    private val fake = ScreenTransport()
    private val owner get() = NativeConnection.get(context)

    @Before fun installSyntheticSession() {
        Locale.setDefault(Locale.US)
        val localeManager = context.getSystemService(LocaleManager::class.java)
        originalAppLocales = localeManager.applicationLocales
        localeManager.applicationLocales = LocaleList(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        NativeConnection.factory = { NativeConnection.create(it, fake, clock) { kotlinx.coroutines.delay(it) } }
        NativeConnection.resetForTests()
        val store = store()
        assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        val generation = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(generation,
            SensitiveValue.copyOf("synthetic-b3-native-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-b3-native-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
    }

    @After fun removeSyntheticSession() {
        try {
            NativeConnection.resetForTests()
            val store = store()
            assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        } finally {
            NativeConnection.factory = originalFactory
            context.getSystemService(LocaleManager::class.java).applicationLocales = originalAppLocales
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun fractionalWeeklyOnlyAndMissingResetHaveExactAccessibleSemantics() {
        fake.usage = response(weekly("12.345678901234567890"))
        launchLive().use {
            read()
            text("live-weekly-percent", "12.345678901234567890% used")
            progress("live-weekly", 0.12345679f, "Weekly window · 12.345678901234567890% used", "Provider-reported usage", "12.345678901234567890% used")
            text("live-five-hour-status", "Window or usage percentage unavailable · not zero")
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            text("live-weekly-reset-relative", "Reset: Time unavailable")
            compose.onNodeWithTag("live-weekly-reset-absolute").assertDoesNotExist()
            compose.onNodeWithTag("live-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
    }

    @Test fun manualRefreshRetainsValuesAndAnnouncesRefreshingThenError() {
        launchLive().use {
            read()
            fake.hold = true
            click("live-refresh")
            waitFor("manual refresh reaches synthetic usage") { fake.held.size == 1 && owner.state.value.refresh.refreshing }
            text("live-refreshing", "Refreshing · last successful values retained")
            compose.onNodeWithTag("live-refreshing").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithTag("live-refresh").assertIsNotEnabled()
            text("live-five-hour-percent", "12.5% used")
            fake.held.single()(response("{}", 403))
            waitFor("manual error settles") { !owner.state.value.refresh.refreshing && owner.state.value.refresh.usage.attempt?.error == ReadError.FORBIDDEN }
            text("live-error", "Usage access forbidden · this does not imply session expiry or zero quota")
            compose.onNodeWithTag("live-error").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            text("live-five-hour-percent", "12.5% used")
            assertEquals(4, fake.gets.get())
        }
    }

    @Test fun staleAndPassedResetReevaluateLocallyWithoutZeroOrNetwork() {
        fake.usage = response(fiveHour("47.25", reset = ",\"reset_after_seconds\":500"))
        launchLive().use {
            read()
            text("live-five-hour-reset-relative", "Reset: <1h remaining")
            val requests = fake.gets.get()
            clock.advance(500_000)
            waitFor("local reset evaluation crosses original instant") {
                owner.state.value.refresh.evaluatedAt == clock.now().wall
            }
            text("live-five-hour-reset-relative", "Reset: Reset passed · awaiting refresh")
            compose.onNodeWithTag("live-five-hour-reset-relative").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            text("live-five-hour-percent", "47.25% used")
            clock.advance(400_000)
            waitFor("B2 local tick marks usage stale") { owner.state.value.refresh.usage.stale }
            text("live-status", "Stale usage · last successful values retained")
            text("live-stale", "Usage has not succeeded for at least 15 minutes. These values may be out of date.")
            text("live-five-hour-percent", "47.25% used")
            assertEquals(requests, fake.gets.get())
        }
    }

    @Test fun exhaustedBarDoesNotOverrideAllowedAndMalformedSiblingsRemainVisible() {
        fake.usage = response(fiveHour("100.00"))
        launchLive().use {
            read()
            progress("live-five-hour", 1f, "Five-hour window · 100.00% used", "Usage bar exhausted · permission is reported separately", "100.00% used")
            text("live-allowed", "Provider permission: allowed")
            text("live-limit", "Provider limit status: limit not reached")
            fake.usage = response(fiveHour("0", allowed = false, reached = true))
            read()
            progress("live-five-hour", 0f, "Five-hour window · 0% used", "Provider-reported usage", "0% used")
            text("live-allowed", "Provider permission: not allowed")
            text("live-limit", "Provider limit status: limit reached")
            fake.usage = response(fiveHour("7.25", reset = ",\"reset_at\":false"))
            read()
            text("live-five-hour-percent", "7.25% used")
            text("live-five-hour-warning", "Some window fields are malformed. Independently known usage and reset information are retained.")
            text("live-five-hour-reset-relative", "Reset: Time unavailable")
            fake.usage = response(fiveHour("false", reset = ",\"reset_after_seconds\":500"))
            read()
            text("live-five-hour-status", "Malformed window or usage percentage · no value inferred")
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            text("live-five-hour-reset-relative", "Reset: <1h remaining")
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertExists()
        }
    }

    @Test fun knownResetShowsSimultaneousLabelsAndRepeatedHourOffset() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        clock.wall = Instant.parse("2026-11-01T05:00:00Z")
        fake.usage = response(fiveHour("12.5", reset = ",\"reset_at\":1793514600"))
        launchLive().use {
            read()
            text("live-five-hour-reset-absolute", "Reset: Nov 1, 2026, 01:00 UTC-05:00 (hour precision)")
            text("live-five-hour-reset-relative", "Reset: 0 days 1 hour remaining")
            compose.onNodeWithTag("live-five-hour-reset-absolute").assertExists()
            compose.onNodeWithTag("live-five-hour-reset-relative").assertExists()
        }
    }

    @Test fun landscapeLongCopyHasNoNativeTextOverflow() {
        fake.usage = response(fiveHour("12.345678901234567890", reset = ",\"reset_after_seconds\":500"))
        launchLive().use { scenario ->
            read()
            val previous = owner.state.value.refresh.usage.attempt
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            var orientation = Configuration.ORIENTATION_UNDEFINED
            waitFor("real landscape configuration") {
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            waitFor("landscape resumed refresh settled") {
                owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing
            }
            listOf("live-identity", "live-rounding", "live-five-hour-status", "live-five-hour-percent",
                "live-five-hour-reset-relative", "live-five-hour-reset-absolute").forEach(::noOverflow)
            fake.usage = response("{}", 403)
            read()
            noOverflow("live-error")
            noOverflow("live-status")
            compose.onNodeWithTag("live-refresh").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun recreationRestoresLiveTabAndOwnerAndOfflinePreviewStaysSeparate() {
        launchLive().use { scenario ->
            read()
            val shared = owner
            val previous = shared.state.value.refresh.usage.attempt
            scenario.recreate()
            waitFor("recreated live root and resumed cycle settle") {
                owner.state.value.refresh.usage.attempt !== previous &&
                owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing &&
                    compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "live-usage")).fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity { assertSame(shared, it.connection) }
            text("live-five-hour-percent", "12.5% used")
            click("offline-tab")
            waitFor("offline root replaces live data") {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "state-disconnected")).fetchSemanticsNodes().isNotEmpty()
            }
            settleOwnerCommands()
            val before = fake.gets.get()
            click("preview-demo")
            text("demo-identity", "Unofficial · offline demo", scroll = false)
            compose.onNodeWithTag("live-usage").assertDoesNotExist()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("state-demo").assertExists()
            assertEquals(before, fake.gets.get())
        }
    }

    @Test fun disconnectedAndReauthorizationHaveAccessibleStatusAndNoInventedUsage() {
        val store = store()
        assertTrue(store.delete(store.openSession()) is CredentialResult.Success)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            waitFor("no synthetic credentials restores disconnected") { owner.state.value.phase == ConnectionPhase.IDLE }
            click("connection-tab")
            text("live-status", "Disconnected · use the connection controls below to sign in")
            compose.onNodeWithTag("live-refresh").assertIsNotEnabled()
            compose.onNodeWithTag("live-five-hour-progress").assertDoesNotExist()
            assertEquals(0, fake.gets.get())
        }
        NativeConnection.resetForTests()
        // A real missing-key restoration exercises the owner's durable fail-closed path.
        val generation = store.openSession()
        assertTrue(store.replace(CredentialEnvelope(generation,
            SensitiveValue.copyOf("synthetic-b3-native-access".toByteArray()),
            SensitiveValue.copyOf("synthetic-b3-native-refresh".toByteArray())), CredentialCancellation()) is CredentialResult.Success)
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(KeystoreCredentialStore.alias(context, NativeConnection.session(context)))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            waitFor("missing key publishes durable reauthorization") { owner.state.value.phase == ConnectionPhase.REAUTH_REQUIRED }
            click("connection-tab")
            text("live-status", "Authorization required · sign in again using the controls below")
            compose.onNodeWithTag("live-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithTag("live-weekly-progress").assertDoesNotExist()
            compose.onNodeWithTag("connect").performScrollTo().assertTextEquals("Sign in again")
            assertEquals(0, fake.gets.get())
        }
    }

    @Test fun bankedAvailableHasSimultaneousExpiryAndSingleViewOnlyAnnouncements() {
        installBanked()
        launchLive().use {
            read()
            text("banked-status", "Provider-reported banked entitlements")
            text("banked-summary-text", "Usage summary for inventory comparison: 1 available banked reset")
            text("banked-inventory-text", "Inventory reports: 1 available banked reset")
            text("banked-item-0-absolute", "Banked entitlement expiry: Oct 8, 2026, 00:00 (hour precision)")
            text("banked-item-0-relative", "Banked entitlement expiry: <1h remaining")
            text("banked-item-0-provider", "Provider status: available")
            assertBankedOwner("banked-item-0", "<1h remaining")
            compose.onNodeWithTag("banked-item-0").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Provider-reported banked entitlements"))
            assertBankedOwner("banked-inventory", "1 available banked reset")
            assertViewOnlyBanked()
            assertEquals(0, fake.posts.get())
            // Keep C2's purchased-balance/raw-subsecond oracle scoped to its own presentation.
            compose.onNodeWithTag("banked-section", useUnmergedTree = true).assertExists()
            val banked = hasTestTag("banked-section") or hasAnyAncestor(hasTestTag("banked-section"))
            val rawInstantOrBalance = hasText("999999", substring = true) or
                hasContentDescription("999999", substring = true) or
                SemanticsMatcher("State description contains raw instant or purchased balance") {
                    it.config.contains(SemanticsProperties.StateDescription) &&
                        it.config[SemanticsProperties.StateDescription].contains("999999")
                }
            compose.onAllNodes(banked and rawInstantOrBalance, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithTag("banked-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
    }

    @Test fun bankedEmptyUnknownUnsupportedAndMalformedRemainExplicit() {
        launchLive().use {
            fake.usage = response(bankedUsage(0))
            read()
            text("banked-status", "Provider reports no available banked entitlements")
            text("banked-inventory-text", "Inventory reports: 0 available banked resets")
            text("banked-container", "Inventory rows: 0 rows")
            compose.onNodeWithTag("banked-item-0").assertDoesNotExist()
            for ((body, reason) in listOf("{\"available_count\":0}" to "unknown · field missing",
                "{\"available_count\":0,\"credits\":null}" to "unknown · provider returned null")) {
                fake.inventory = response(body)
                read()
                text("banked-status", "Banked entitlement information unknown")
                text("banked-container", "Inventory rows: $reason")
            }
            fake.inventory = response("""{"available_count":0,"credits":false}""")
            read()
            text("banked-status", "Malformed banked entitlement information")
            text("banked-container", "Inventory rows: malformed · wrong field type")
            installBanked()
            fake.inventory = response(bankedInventory(rows = bankedRow("\"2026-10-09T00:00:00+00:00\"")))
            read()
            text("banked-status", "Banked entitlement information unsupported")
            text("banked-item-0-knowledge", "Expiry data: unsupported · unsupported format")
            text("banked-item-0-relative", "Banked entitlement expiry: Time unavailable")
            compose.onNodeWithTag("banked-item-0-absolute").assertDoesNotExist()
            fake.inventory = response("""{"credits":[]}""")
            fake.usage = response(bankedUsage(0))
            read()
            text("banked-inventory-text", "Inventory reports: unknown · field missing")
            fake.inventory = response("""{"available_count":false,"credits":[]}""")
            read()
            text("banked-inventory-text", "Inventory reports: malformed · wrong field type")
            assertViewOnlyBanked()
        }
    }

    @Test fun bankedExpiredAndDiscrepantRetainBothCountsAndExpiry() {
        installBanked()
        fake.usage = response(bankedUsage(7))
        launchLive().use {
            read()
            text("banked-status", "Conflicting banked entitlement information · provider counts retained")
            text("banked-summary-text", "Usage summary for inventory comparison: 7 available banked resets")
            text("banked-inventory-text", "Inventory reports: 1 available banked reset")
            text("banked-issue-SUMMARY_COUNT_MISMATCH", "Usage summary and inventory counts disagree. Both provider counts are retained.")
            clock.advance(3_600_000)
            waitFor("local banked expiry crosses original instant") { owner.state.value.refresh.evaluatedAt == clock.now().wall }
            text("banked-item-0-status", "Entitlement expired")
            text("banked-item-0-relative", "Banked entitlement expiry: Entitlement expired")
            text("banked-item-0-provider", "Provider status: available")
            text("banked-item-0-absolute", "Banked entitlement expiry: Oct 8, 2026, 00:00 (hour precision)")
            text("banked-issue-EXPIRED_AVAILABLE_ITEM", "A provider-available item has expired locally. The provider count is retained.")
            text("banked-inventory-text", "Inventory reports: 1 available banked reset")
            assertViewOnlyBanked()
        }
    }

    @Test fun bankedMissingExpiryAndMalformedRowsKeepKnownSiblings() {
        installBanked(2)
        fake.inventory = response(bankedInventory(2, "null," + bankedRow("null")))
        launchLive().use {
            read()
            text("banked-status", "Banked entitlement information unknown")
            text("banked-item-0-row", "Row data: unknown · provider returned null")
            text("banked-item-1-knowledge", "Expiry data: unknown · provider returned null")
            text("banked-item-1-relative", "Banked entitlement expiry: Time unavailable")
            fake.inventory = response(bankedInventory(2, "false," + bankedRow()))
            read()
            text("banked-status", "Malformed banked entitlement information")
            text("banked-item-0-row", "Row data: malformed · wrong field type")
            text("banked-item-1-relative", "Banked entitlement expiry: <1h remaining")
            assertViewOnlyBanked()
        }
    }

    @Test fun bankedInventoryFailureAndUsageFailureKeepIndependentClocks() {
        installBanked()
        launchLive().use {
            read()
            val inventory = owner.state.value.refresh.inventory
            fake.usage = response("{}", 403)
            fake.holdInventory = true
            clock.advance(1_000)
            click("live-refresh")
            waitFor("usage-only failure published before held inventory") {
                fake.heldInventory.size == 1 && owner.state.value.refresh.usage.attempt?.error == ReadError.FORBIDDEN
            }
            assertSame(inventory.success, owner.state.value.refresh.inventory.success)
            assertSame(inventory.attempt, owner.state.value.refresh.inventory.attempt)
            assertEquals(inventory.successfulAtMillis, owner.state.value.refresh.inventory.successfulAtMillis)
            assertEquals(inventory.stale, owner.state.value.refresh.inventory.stale)
            text("banked-status", "Provider-reported banked entitlements")
            text("banked-inventory-clock", "Inventory observed at (UTC): 2026-10-08T00:00:00Z")
            compose.onNodeWithTag("banked-error").assertDoesNotExist()
            fake.heldInventory.single()(response("{}", 403))
            waitFor("inventory error settles separately") { !owner.state.value.refresh.refreshing }
            assertSame(inventory.success, owner.state.value.refresh.inventory.success)
            assertEquals(inventory.successfulAtMillis, owner.state.value.refresh.inventory.successfulAtMillis)
            text("banked-status", "Banked entitlement information inaccessible")
            text("banked-error", "Inventory access forbidden · availability is not zero")
            fake.holdInventory = false
            fake.usage = response(bankedUsage())
            fake.inventory = response("{}", 403)
            read()
            val usage = owner.state.value.refresh.usage
            text("live-status", "Last successful usage observation")
            text("live-five-hour-percent", "12.5% used")
            assertEquals(clock.now().wall, usage.success?.observedAt)
            assertEquals(1_000L, usage.successfulAtMillis)
            text("banked-inventory-clock", "Inventory observed at (UTC): 2026-10-08T00:00:00Z")
            text("banked-inventory-text", "Inventory reports: 1 available banked reset")
        }
    }

    @Test fun bankedStaleAndRefreshCycleAreIndependentFromUsage() {
        installBanked()
        launchLive().use {
            read()
            clock.advance(900_000)
            waitFor("banked inventory stales on its successful clock") { owner.state.value.refresh.inventory.stale }
            fake.holdInventory = true
            click("live-refresh")
            waitFor("fresh usage while inventory remains stale") { fake.heldInventory.size == 1 && !owner.state.value.refresh.usage.stale }
            text("banked-stale", "Inventory has not succeeded for at least 15 minutes. Retained entitlements may be out of date.")
            text("banked-refreshing", "Refresh cycle in progress · retained inventory shown")
            compose.onNodeWithTag("banked-refreshing").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            compose.onNodeWithTag("live-stale").assertDoesNotExist()
            fake.heldInventory.single()(fake.inventory)
            waitFor("inventory success clears only its stale flag") { !owner.state.value.refresh.refreshing && !owner.state.value.refresh.inventory.stale }
            compose.onNodeWithTag("banked-stale").assertDoesNotExist()
            compose.onNodeWithTag("banked-refreshing").assertDoesNotExist()
        }
    }

    @Test fun bankedLogoutDropsItemsBeforeLateInventoryCanReturn() = retiredBankedInventory(replace = false)

    @Test fun bankedReplacementRejectsLateInventoryWhileNewAccountConnects() = retiredBankedInventory(replace = true)

    private fun retiredBankedInventory(replace: Boolean) {
        installBanked()
        launchLive().use {
            read()
            fake.holdInventory = true
            click("live-refresh")
            waitFor("old banked inventory GET held") { fake.heldInventory.size == 1 }
            val old = fake.heldInventory.single()
            click("sign-out")
            waitFor("logout retires inventory and deletes protected credentials") {
                owner.state.value.phase == ConnectionPhase.SIGNED_OUT && fake.cancelledInventory.get() == 1
            }
            assertNoBankedItemsOrClocks()
            if (replace) {
                fake.holdLogin = true
                fake.holdInventory = false
                fake.usage = response(bankedUsage(0))
                fake.inventory = response(bankedInventory(0, ""))
                click("connect")
                waitFor("replacement owns new login admission") { fake.heldLogin.size == 1 }
                assertNoBankedItemsOrClocks()
            }
            val before = fake.gets.get()
            old(response(bankedInventory()))
            settleOwnerCommands()
            assertNoBankedItemsOrClocks()
            assertEquals(before, fake.gets.get())
            if (replace) {
                fake.heldLogin.single()(response(LOGIN))
                waitFor("replacement publishes only its empty inventory") {
                    owner.state.value.phase == ConnectionPhase.OBSERVED && !owner.state.value.refresh.refreshing
                }
                text("banked-status", "Provider reports no available banked entitlements")
                text("banked-inventory-text", "Inventory reports: 0 available banked resets")
                compose.onNodeWithTag("banked-item-0").assertDoesNotExist()
            }
        }
    }

    @Test fun bankedLandscapeLongCopyAndRecreationRemainReadable() {
        installBanked(Long.MAX_VALUE)
        fake.inventory = response(bankedInventory(Long.MAX_VALUE, bankedRow("\"2026-10-09T14:31:56.833553Z\"")))
        launchLive().use { scenario ->
            read()
            val shared = owner
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("banked real landscape configuration") {
                var orientation = Configuration.ORIENTATION_UNDEFINED
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == Configuration.ORIENTATION_LANDSCAPE && !owner.state.value.refresh.refreshing
            }
            listOf("banked-title", "banked-status", "banked-summary-text", "banked-inventory-text", "banked-limitations",
                "banked-item-0-provider", "banked-item-0-absolute", "banked-item-0-relative", "banked-issue-AVAILABLE_ROW_COUNT_MISMATCH").forEach(::noOverflow)
            text("banked-item-0-relative", "Banked entitlement expiry: 1 day 14 hours remaining")
            val previous = shared.state.value.refresh.inventory.attempt
            scenario.recreate()
            waitFor("banked recreated root and resumed inventory settle") {
                owner.state.value.refresh.inventory.attempt !== previous && !owner.state.value.refresh.refreshing &&
                    compose.onAllNodes(hasTestTag("banked-section")).fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity { assertSame(shared, it.connection) }
            text("banked-inventory-text", "Inventory reports: 9223372036854775807 available banked resets")
            assertViewOnlyBanked()
        }
    }

    private fun installBanked(count: Long = 1) {
        fake.usage = response(bankedUsage(count))
        fake.inventory = response(bankedInventory(count))
    }

    private fun assertBankedOwner(tag: String, value: String) {
        val accessible = !SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        val valueMatcher = hasText(value, substring = true) or hasContentDescription(value, substring = true)
        val row = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
        compose.onAllNodes(valueMatcher and accessible and row, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.Text) }.forEach {
                assertTrue("Duplicate banked text must be hidden", it.config.contains(SemanticsProperties.HideFromAccessibility))
            }
    }

    private fun assertViewOnlyBanked() {
        val banked = hasTestTag("banked-section") or hasAnyAncestor(hasTestTag("banked-section"))
        compose.onAllNodes(banked and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(banked and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun assertNoBankedItemsOrClocks() {
        compose.onNodeWithTag("banked-item-0").assertDoesNotExist()
        text("banked-inventory-text", "Inventory reports: unknown")
        text("banked-summary-text", "Usage summary for inventory comparison: unknown")
        text("banked-inventory-clock", "Inventory observed at (UTC): unknown")
        text("banked-summary-clock", "Summary observed at (UTC): unknown")
    }

    private fun launchLive(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        waitFor("effective rendered US resource locale") {
            var locale: Locale? = null
            scenario.onActivity { locale = it.resources.configuration.locales[0] }
            locale == Locale.US
        }
        waitFor("restored dormant synthetic session") { owner.state.value.phase == ConnectionPhase.RESTORED }
        assertEquals(0, fake.gets.get())
        click("connection-tab")
        text("live-status", "Usage unavailable · refresh to request an observation")
        return scenario
    }

    private fun read() {
        val previous = owner.state.value.refresh.usage.attempt
        click("live-refresh")
        waitFor("requested usage cycle settles") {
            owner.state.value.refresh.usage.attempt !== previous && !owner.state.value.refresh.refreshing
        }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("offline-tab", "connection-tab")) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun text(tag: String, expected: String, scroll: Boolean = true) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertTextEquals(expected)
        if (tag != "demo-identity") assertConnectionHasNoDiagnosticDump(compose)
    }

    private fun progress(tag: String, fraction: Float, description: String, status: String, percent: String) {
        compose.onNodeWithTag("$tag-progress").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
        // Visible text remains testable, but only the precise bar description is announced.
        compose.onNodeWithTag("$tag-percent", useUnmergedTree = true)
            .assertTextEquals(percent)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        val percentage = hasText(percent, substring = true) or hasContentDescription(percent, substring = true)
        val accessible = !SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        compose.onAllNodes(percentage and accessible and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun noOverflow(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No native text layout for $tag", layouts.isNotEmpty())
        layouts.forEach { layout ->
            val lines = (0 until layout.lineCount).map {
                "${layout.getLineLeft(it)}..${layout.getLineRight(it)} (ellipsis=${layout.isLineEllipsized(it)})"
            }
            assertTrue("$tag: text=${layout.layoutInput.text}, size=${layout.size}, " +
                "paragraph=${layout.multiParagraph.width} x ${layout.multiParagraph.height}, " +
                "constraints=${layout.layoutInput.constraints}, lines=$lines", textFitsMeasuredBounds(layout))
            // Calibrate both clipping axes against the same native line metrics, not a wider parent.
            assertFalse("$tag: horizontal clipping must fail the oracle",
                textFitsMeasuredBounds(layout.copy(size = IntSize(0, layout.size.height))))
            assertFalse("$tag: vertical clipping must fail the oracle",
                textFitsMeasuredBounds(layout.copy(size = IntSize(layout.size.width, 0))))
        }
    }

    private fun textFitsMeasuredBounds(layout: TextLayoutResult): Boolean {
        // Compose 1.12.1 TextStringSimpleNode rebuilds semantics with the loose parent maxWidth,
        // but retains the measured Text size. Thus didOverflowWidth can mean 479 < 1752 even
        // when every line fits. These Start-aligned labels need actual line extents instead.
        // Keep height/max-line overflow, complete text coverage and ellipsis fail-closed.
        return layout.lineCount > 0 && !layout.didOverflowHeight &&
            layout.getLineEnd(layout.lineCount - 1) == layout.layoutInput.text.length &&
            (0 until layout.lineCount).all {
                !layout.isLineEllipsized(it) && layout.getLineLeft(it) >= 0f &&
                    layout.getLineRight(it) <= layout.size.width
            }
    }

    private fun waitFor(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 10_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, refresh=${owner.state.value.refresh}", error)
        }
        compose.waitForIdle()
        // Check successful, refreshing, error, stale, unknown, malformed and retired live states.
        if (compose.onAllNodes(hasTestTag("live-usage")).fetchSemanticsNodes().isNotEmpty()) {
            assertConnectionHasNoDiagnosticDump(compose)
        }
    }
    private fun settleOwnerCommands() {
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) { owner.commandsSettled() }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Offline foreground-loss command did not settle: ${owner.state.value}", error)
        }
        waitFor("offline cancellation is complete") { !owner.state.value.refresh.refreshing }
    }

    private fun store() = KeystoreCredentialStore(context, NativeConnection.session(context))

    private class ScreenClock : TransportClock {
        @Volatile var wall: Instant = Instant.parse("2026-10-08T00:00:00Z")
        val monotonic = AtomicLong()
        override fun now() = TransportTime(wall, monotonic.get())
        fun advance(millis: Long) { wall = wall.plusMillis(millis); monotonic.addAndGet(millis) }
    }

    private class ScreenTransport : AuthTransport {
        @Volatile var usage: TransportResult = response(fiveHour("12.5"))
        @Volatile var inventory: TransportResult = response("""{"available_count":0,"credits":[]}""")
        @Volatile var hold = false
        @Volatile var holdInventory = false
        @Volatile var holdLogin = false
        val gets = AtomicInteger()
        val posts = AtomicInteger()
        val cancelledInventory = AtomicInteger()
        val held = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        val heldInventory = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        val heldLogin = CopyOnWriteArrayList<(TransportResult) -> Unit>()
        override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline,
            terminal: (TransportResult) -> Unit): CancellationHandle {
            return when (request) {
                is ProviderHttpRequest.Get -> get(request, terminal)
                is ProviderHttpRequest.FormPost, is ProviderHttpRequest.JsonPost -> post(request, terminal)
            }
        }

        private fun post(request: ProviderHttpRequest, terminal: (TransportResult) -> Unit): CancellationHandle {
            posts.incrementAndGet()
            when (request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> {
                    check(request is ProviderHttpRequest.JsonPost)
                    if (holdLogin) heldLogin += terminal else terminal(response(LOGIN))
                }
                "/api/accounts/deviceauth/token" -> {
                    check(request is ProviderHttpRequest.JsonPost)
                    terminal(response("""{"authorization_code":"synthetic-c2-auth","code_verifier":"synthetic-c2-verifier"}"""))
                }
                "/oauth/token" -> {
                    check(request is ProviderHttpRequest.FormPost)
                    terminal(response("""{"access_token":"synthetic-c2-replacement-access","refresh_token":"synthetic-c2-replacement-refresh"}"""))
                }
                else -> error("Unexpected synthetic POST route")
            }
            return CancellationHandle {}
        }

        private fun get(request: ProviderHttpRequest.Get, terminal: (TransportResult) -> Unit): CancellationHandle {
            gets.incrementAndGet()
            val heldDetail = request.url.encodedPath == ReadOperation.RESET_INVENTORY.path && holdInventory
            when (request.url.encodedPath) {
                ReadOperation.USAGE.path -> if (hold) held += terminal else terminal(usage)
                ReadOperation.RESET_INVENTORY.path -> if (heldDetail) heldInventory += terminal else terminal(inventory)
                else -> error("Unexpected synthetic GET route")
            }
            return CancellationHandle { if (heldDetail) cancelledInventory.incrementAndGet() }
        }
    }

    private companion object {
        const val LOGIN = """{"device_auth_id":"synthetic-c2-device","user_code":"SYNTHETIC-C2-CODE","interval":1}"""
        fun bankedRow(expiry: String = "\"2026-10-08T00:59:59.999999Z\"", status: String = "available",
            type: String = "codex_rate_limits", id: String = "synthetic-c2-row") =
            """{"id":"$id","reset_type":"$type","status":"$status","granted_at":"2026-10-01T00:00:00Z","expires_at":$expiry}"""
        fun bankedInventory(count: Long = 1, rows: String = bankedRow()) = """{"available_count":$count,"credits":[$rows]}"""
        fun bankedUsage(count: Long = 1) = """{"rate_limit_reset_credits":{"available_count":$count},"credits":{"balance":"999999","has_credits":true},"rate_limit":{"allowed":true,"primary_window":{"limit_window_seconds":18000,"used_percent":12.5}}}"""
        fun fiveHour(percent: String, allowed: Boolean = true, reached: Boolean = false, reset: String = "") =
            """{"rate_limit":{"allowed":$allowed,"limit_reached":$reached,"primary_window":{"limit_window_seconds":18000,"used_percent":$percent$reset}}}"""
        fun weekly(percent: String) = """{"rate_limit":{"primary_window":{"limit_window_seconds":604800,"used_percent":$percent},"secondary_window":null}}"""
        fun response(body: String, status: Int = 200) = TransportResult.Response.bounded(status, body.toByteArray())
    }
}

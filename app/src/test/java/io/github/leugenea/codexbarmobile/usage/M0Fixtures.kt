package io.github.leugenea.codexbarmobile.usage

import java.time.Instant

/**
 * Kotlin adaptations, not live responses or JSON readers. Source/provenance:
 * docs/research/m0/fixtures/synthetic-vectors.json (17 original synthetic cases);
 * upstream-test-weekly-only.json: Copyright (c) 2026 Peter Steinberger, MIT;
 * upstream-test-reset-details.json: Copyright 2025 OpenAI, Apache-2.0.
 * Modifications: typed Kotlin builders, omit presentation/unused mock fields.
 * Full notices and licenses remain in docs/research/m0/fixtures/ATTRIBUTION.md
 * and licenses/{CodexBar-MIT, Codex-Apache-2.0, Codex-NOTICE}.txt.
 * owner-reported-live.json supplies only the two safe expiry strings and numeric
 * facts; never promote its retrievalDate to either endpoint observation clock.
 */
internal object M0Fixtures {
    val clock: Instant = Instant.ofEpochSecond(1791154800)
    val ownerExpiries = listOf("2026-10-22T20:31:56.833553Z", "2026-10-29T18:58:37.564252Z")

    fun value(value: Any): Input<Any> = Input.Value(value)

    fun window(
        percent: Any = 22, seconds: Any = 18000,
        absolute: Input<Any> = Input.Missing, relative: Input<Any> = Input.Missing,
    ): Input<WindowInput> = Input.Value(WindowInput(value(seconds), value(percent), absolute, relative))

    fun usage(
        primary: Input<WindowInput> = Input.Missing,
        secondary: Input<WindowInput> = Input.Missing,
    ): UsageInput = UsageInput(primary, secondary)

    fun item(
        id: String = "fixture-reset-a", status: Any = "available",
        expiry: Input<Any> = value("2026-10-05T23:00:00Z"),
    ): Input<ResetItemInput> = Input.Value(ResetItemInput(
        value(id), value("codex_rate_limits"), value(status), value("2026-10-03T23:00:00Z"), expiry,
    ))

    fun inventory(count: Any = 2, vararg items: Input<ResetItemInput>): Input<InventoryInput> =
        Input.Value(InventoryInput(value(count), Input.Value(items.toList())))

    data class Case(
        val name: String,
        val input: UsageInput = UsageInput(),
        val inventory: Input<InventoryInput> = Input.Missing,
        val fivePercent: String? = null,
        val weeklyPercent: String? = null,
        val allowed: Boolean? = null,
        val reached: Boolean? = null,
        val evaluatedAt: Instant = clock,
    )

    // Explicit expectations are hand-authored independently of the normalizers.
    // HTTP/session/freshness outcomes in M0 belong to A2/A10/B2, not this leaf.
    fun cases(): List<Case> = listOf(
        Case("absent-windows", UsageInput(planType = value("plus"))),
        Case("null-windows", usage(Input.Null, Input.Null)),
        Case("weekly-in-primary", usage(window(12, 604800, value(1791241200))).copy(
            allowed = value(true), limitReached = value(false), planType = value("free"),
        ), weeklyPercent = "12", allowed = true, reached = false),
        Case("malformed-independent-window", usage(window("oops"), window(22, 604800, value(1791241200))),
            weeklyPercent = "22"),
        Case("exhausted-not-authority", usage(window(100, absolute = value(1791158400))).copy(
            allowed = value(true), limitReached = value(false),
        ), fivePercent = "100", allowed = true, reached = false),
        Case("zero-not-permission", usage(window(0, absolute = value(1791158400))).copy(
            allowed = value(false), limitReached = value(true),
        ), fivePercent = "0", allowed = false, reached = true),
        Case("reset-due-not-zero", usage(window(83, absolute = value(1791154799))), fivePercent = "83"),
        Case("stale-data", usage(window(33, absolute = value(1791158400))), fivePercent = "33",
            evaluatedAt = clock.plusSeconds(901)),
        Case("unsupported-plan", UsageInput(planType = value("future_plan"))),
        Case("session-expiry"),
        Case("unknown-reset", usage(window()), fivePercent = "22"),
        Case("subhour-reset", usage(window(absolute = value(1791154859), relative = value(59))), fivePercent = "22"),
        // Purchased balance "200" is deliberately not an input to reset normalization.
        Case("banked-distinct-from-purchased", UsageInput(bankedAvailableCount = value(2)),
            inventory(2, item(), item("fixture-reset-b", expiry = Input.Null))),
        Case("expiry-reported-count-conflict", UsageInput(bankedAvailableCount = value(1)),
            inventory(1, item("fixture-expired", expiry = value("2026-10-04T23:00:00Z")))),
        Case("banked-malformed-count", inventory = inventory(-1)),
        // Failed/inaccessible read supplies no successful body or observation.
        Case("banked-unavailable-not-zero"),
        Case("unknown-entitlement-status", inventory = inventory(0, item("fixture-future", "future_status"))),
    )

    fun weeklyMock(): UsageInput = usage(window(0, 604800, value(1775468693)), Input.Null)

    fun inventoryMock(): Input<InventoryInput> = Input.Value(InventoryInput(value(2), Input.Value(listOf(
        Input.Value(ResetItemInput(value("credit-1"), value("codex_rate_limits"), value("available"),
            value("2026-06-17T00:00:00Z"), value("2026-07-17T00:00:00Z"))),
        Input.Value(ResetItemInput(value("credit-2"), value("codex_rate_limits"), value("available"),
            value("2026-06-18T00:00:00Z"), Input.Null)),
    ))))
}

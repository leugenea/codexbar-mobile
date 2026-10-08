package io.github.leugenea.codexbarmobile.usage

import java.math.BigDecimal
import java.time.Instant

/** A9 supplies typed objects/primitives, not JSON text. Invalid never retains raw payloads. */
sealed interface Input<out T> {
    data object Missing : Input<Nothing>
    data object Null : Input<Nothing>
    data object Invalid : Input<Nothing>
    data class Value<T>(val value: T) : Input<T>
}

enum class Knowledge { KNOWN, UNAVAILABLE, MALFORMED, UNSUPPORTED }
enum class Reason { MISSING, PROVIDER_NULL, WRONG_TYPE, OUT_OF_RANGE, UNSUPPORTED_FORMAT, CLOCK_REQUIRED }

data class Field<T>(val knowledge: Knowledge, val value: T? = null, val reason: Reason? = null)

data class WindowInput(
    val durationSeconds: Input<Any> = Input.Missing,
    val usedPercent: Input<Any> = Input.Missing,
    val resetAt: Input<Any> = Input.Missing,
    val resetAfterSeconds: Input<Any> = Input.Missing,
)

data class UsageInput(
    val primary: Input<WindowInput> = Input.Missing,
    val secondary: Input<WindowInput> = Input.Missing,
    val allowed: Input<Any> = Input.Missing,
    val limitReached: Input<Any> = Input.Missing,
    val planType: Input<Any> = Input.Missing,
    val bankedAvailableCount: Input<Any> = Input.Missing,
)

enum class Slot { PRIMARY, SECONDARY }
enum class WindowKind { FIVE_HOUR, WEEKLY, UNSUPPORTED, UNKNOWN }
enum class SelectionState { KNOWN, UNAVAILABLE, MALFORMED, AMBIGUOUS }

data class ResetTime(
    val absolute: Field<Instant>,
    val relativeSeconds: Field<Long>,
    val relativeDerived: Field<Instant>,
    val discrepant: Boolean,
    /** null when no trustworthy instant or evaluation clock is available. */
    val due: Boolean?,
)

data class UsageWindow(
    val slot: Slot,
    val kind: WindowKind,
    val durationSeconds: Field<Long>,
    val usedPercent: Field<BigDecimal>,
    val reset: ResetTime,
)

data class WindowSelection(
    val state: SelectionState,
    /** Ambiguous selections retain all candidates; never choose an arbitrary winner. */
    val candidates: List<UsageWindow>,
)

data class SlotResult(val slot: Slot, val window: Field<UsageWindow>)

data class UsageObservation(
    /** Only the usage endpoint's successful observation time; never receipt retrievalDate. */
    val observedAt: Instant?,
    val fiveHour: WindowSelection,
    val weekly: WindowSelection,
    /** Includes unknown durations and malformed/missing slots without guessing their window kind. */
    val slots: List<SlotResult>,
    val allowed: Field<Boolean>,
    val limitReached: Field<Boolean>,
    val planType: Field<String>,
    val bankedAvailableCount: Field<Long>,
)

data class ResetItemInput(
    val id: Input<Any> = Input.Missing,
    val resetType: Input<Any> = Input.Missing,
    val status: Input<Any> = Input.Missing,
    val grantedAt: Input<Any> = Input.Missing,
    val expiresAt: Input<Any> = Input.Missing,
)

data class InventoryInput(
    val availableCount: Input<Any> = Input.Missing,
    val items: Input<List<Input<ResetItemInput>>> = Input.Missing,
)

data class BankedResetItem(
    val id: Field<String>,
    val resetType: Field<String>,
    val providerStatus: Field<String>,
    val grantedAt: Field<Instant>,
    val expiresAt: Field<Instant>,
    val locallyExpired: Boolean?,
)

enum class Completeness { COMPLETE, PARTIAL, UNKNOWN }
enum class InventoryIssue {
    SUMMARY_COUNT_MISMATCH, AVAILABLE_ROW_COUNT_MISMATCH, EXPIRED_AVAILABLE_ITEM,
    DUPLICATE_IDENTITY, UNKNOWN_STATUS, UNKNOWN_RESET_TYPE,
}

data class BankedResetObservation(
    /** Summary and inventory remain separate reads, with separate successful clocks. */
    val summaryObservedAt: Instant?,
    val observedAt: Instant?,
    val summaryAvailableCount: Field<Long>,
    val reportedAvailableCount: Field<Long>,
    /** null for missing/null/invalid inventory; zero only for an explicitly empty list. */
    val inventoryRowCount: Int?,
    val items: List<Field<BankedResetItem>>,
    val completeness: Completeness,
    val issues: Set<InventoryIssue>,
    /** Array size and A1 container knowledge/reason; independent of provider available counts and row validity. */
    val inventoryRowContainer: Field<Int>,
)

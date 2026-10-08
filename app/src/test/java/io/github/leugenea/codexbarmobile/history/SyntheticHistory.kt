package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.usage.Input
import io.github.leugenea.codexbarmobile.usage.UsageInput
import io.github.leugenea.codexbarmobile.usage.UsageNormalizer
import io.github.leugenea.codexbarmobile.usage.UsageObservation
import io.github.leugenea.codexbarmobile.usage.WindowInput
import io.github.leugenea.codexbarmobile.usage.WindowKind
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Original synthetic normalized inputs only; no provider fixtures or identity/account data. */
internal object SyntheticHistory {
    val partition = HistoryPartition(UUID(0, 1))
    val epoch = ClockEpoch(UUID(0, 2))
    val at: Instant = Instant.ofEpochSecond(1_800_000_000)

    fun window(
        percent: Input<Any> = Input.Value(BigDecimal("12.375")),
        duration: Input<Any> = Input.Value(18000L),
        absolute: Input<Any> = Input.Value(1_800_003_600L),
        relative: Input<Any> = Input.Missing,
    ): Input<WindowInput> = Input.Value(WindowInput(duration, percent, absolute, relative))

    fun usage(
        primary: Input<WindowInput> = window(), secondary: Input<WindowInput> = Input.Missing,
        observedAt: Instant? = at,
    ): UsageObservation = UsageNormalizer.normalize(UsageInput(primary, secondary,
        allowed = Input.Value(false), limitReached = Input.Value(true)), observedAt)

    fun admission(
        cursor: HistoryCursor, usage: UsageObservation = usage(), monotonic: Long? = 1000,
        epoch: ClockEpoch = this.epoch, id: ObservationId = cursor.nextId()!!,
    ): HistoryAdmission = HistoryAdmission(cursor.partition, id, HistoryClock(epoch, monotonic), HistoryEvent.Observed(usage))

    fun append(
        cursor: HistoryCursor = HistoryCursor(partition), usage: UsageObservation = usage(),
        monotonic: Long? = 1000, epoch: ClockEpoch = this.epoch,
    ): HistoryReduction.Applied = WindowHistory.append(cursor, admission(cursor, usage, monotonic, epoch)) as HistoryReduction.Applied

    fun five(result: HistoryReduction.Applied): HistoryWindow = result.entry.windows.single { it.kind == WindowKind.FIVE_HOUR }
    fun weekly(result: HistoryReduction.Applied): HistoryWindow = result.entry.windows.single { it.kind == WindowKind.WEEKLY }
}

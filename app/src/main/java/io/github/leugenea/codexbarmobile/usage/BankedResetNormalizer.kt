package io.github.leugenea.codexbarmobile.usage

import java.time.Instant

object BankedResetNormalizer {
    fun normalize(
        input: Input<InventoryInput>,
        usage: UsageObservation? = null,
        observedAt: Instant? = null,
        evaluatedAt: Instant? = null,
    ): BankedResetObservation {
        val inventory = PrimitiveNormalizer.read(input) { PrimitiveNormalizer.known(it) }
        val count = inventory.value?.let { PrimitiveNormalizer.integer(it.availableCount) }
            ?: Field(inventory.knowledge, reason = inventory.reason)
        val rows = inventory.value?.let { value -> PrimitiveNormalizer.read(value.items) { PrimitiveNormalizer.known(it) } }
            ?: Field(inventory.knowledge, reason = inventory.reason)
        val evaluated = PrimitiveNormalizer.positiveEpoch(evaluatedAt)
        val items = rows.value.orEmpty().map { item(it, evaluated) }
        val summary = usage?.bankedAvailableCount ?: Field(Knowledge.UNAVAILABLE, reason = Reason.MISSING)
        val completeness = completeness(rows, items)
        return BankedResetObservation(
            PrimitiveNormalizer.positiveEpoch(usage?.observedAt), PrimitiveNormalizer.positiveEpoch(observedAt),
            summary, count, rows.value?.size, items, completeness,
            issues(summary, count, items, completeness),
        )
    }

    private fun item(input: Input<ResetItemInput>, now: Instant?): Field<BankedResetItem> =
        PrimitiveNormalizer.read(input) {
            val expiry = PrimitiveNormalizer.utc(it.expiresAt)
            PrimitiveNormalizer.known(BankedResetItem(
                PrimitiveNormalizer.text(it.id), PrimitiveNormalizer.text(it.resetType),
                PrimitiveNormalizer.text(it.status), PrimitiveNormalizer.utc(it.grantedAt), expiry,
                expiry.value?.let { instant -> now?.let { instant <= it } },
            ))
        }

    private fun completeness(
        rows: Field<List<Input<ResetItemInput>>>, items: List<Field<BankedResetItem>>,
    ): Completeness {
        if (rows.knowledge != Knowledge.KNOWN) return Completeness.UNKNOWN
        return if (items.any { incomplete(it) }) Completeness.PARTIAL else Completeness.COMPLETE
    }

    private fun incomplete(item: Field<BankedResetItem>): Boolean {
        val value = item.value ?: return true
        val required = listOf(value.id, value.resetType, value.providerStatus, value.grantedAt)
        return required.any { it.knowledge != Knowledge.KNOWN } || value.expiresAt.knowledge == Knowledge.MALFORMED
    }

    private fun issues(
        summary: Field<Long>, count: Field<Long>, items: List<Field<BankedResetItem>>, completeness: Completeness,
    ): Set<InventoryIssue> {
        val issues = mutableSetOf<InventoryIssue>()
        if (summary.value != null && count.value != null && summary.value != count.value) {
            issues += InventoryIssue.SUMMARY_COUNT_MISMATCH
        }
        val values = items.mapNotNull { it.value }
        val ids = values.mapNotNull { it.id.value }
        if (ids.distinct().size != ids.size) issues += InventoryIssue.DUPLICATE_IDENTITY
        issues += itemIssues(values)
        if (completeness == Completeness.COMPLETE && count.value != null && knownStatuses(values)) {
            val available = values.count { it.providerStatus.value == "available" }.toLong()
            if (available != count.value) issues += InventoryIssue.AVAILABLE_ROW_COUNT_MISMATCH
        }
        return issues.toSet()
    }

    /** Only available is evidenced here. A9 can extend this after source-backed wire review. */
    private fun knownStatuses(items: List<BankedResetItem>): Boolean =
        items.all { it.providerStatus.value == "available" }

    private fun itemIssues(items: List<BankedResetItem>): Set<InventoryIssue> {
        val issues = mutableSetOf<InventoryIssue>()
        for (item in items) {
            if (item.providerStatus.value != "available") issues += InventoryIssue.UNKNOWN_STATUS
            if (item.resetType.value != "codex_rate_limits") issues += InventoryIssue.UNKNOWN_RESET_TYPE
            if (item.providerStatus.value == "available" && item.locallyExpired == true) {
                issues += InventoryIssue.EXPIRED_AVAILABLE_ITEM
            }
        }
        return issues
    }
}

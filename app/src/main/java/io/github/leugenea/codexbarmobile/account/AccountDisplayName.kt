package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.credentials.SessionGeneration
import io.github.leugenea.codexbarmobile.history.HistoryPartition

/** Local user text only. Never include it in diagnostics, credentials or provider requests. */
internal class AccountDisplayName private constructor(val text: String) {
    override fun toString() = "AccountDisplayName(redacted)"

    companion object {
        const val MAX_CODE_POINTS = 80
        fun from(input: String): AccountDisplayName? {
            val text = draft(input).trim()
            return text.takeIf { it.isNotEmpty() }?.let(::AccountDisplayName)
        }

        fun draft(input: String): String {
            val points = input.codePoints().map { if (Character.isWhitespace(it) || Character.isSpaceChar(it)) 32 else it }
                .filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() && it !in 0xD800..0xDFFF }
                .limit(MAX_CODE_POINTS.toLong()).toArray()
            return String(points, 0, points.size)
        }
    }
}

/** Opaque runtime edit authority; UUID alone is never permission to rename an account. */
internal class AccountNameEdit internal constructor(
    internal val generation: SessionGeneration,
    internal val partition: HistoryPartition,
) {
    override fun toString() = "AccountNameEdit(redacted)"
}

internal class AccountNameState(
    val edit: AccountNameEdit? = null,
    val name: AccountDisplayName? = null,
    val storageFailed: Boolean = false,
) {
    override fun toString() = "AccountNameState(storageFailed=$storageFailed, redacted)"
}

internal sealed interface AccountNameRead {
    class Ready(val name: AccountDisplayName?) : AccountNameRead {
        override fun toString() = "AccountNameRead.Ready(redacted)"
    }
    data object Unavailable : AccountNameRead
}

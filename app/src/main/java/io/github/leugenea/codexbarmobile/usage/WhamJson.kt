package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.JsonBoundary
import io.github.leugenea.codexbarmobile.transport.JsonResult
import io.github.leugenea.codexbarmobile.transport.ResponseBody
import io.github.leugenea.codexbarmobile.transport.TransportFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** No raw JSON, arbitrary provider text or exception chains in diagnostics. */
sealed interface PayloadResult<out T> {
    class Decoded<T>(val observation: T) : PayloadResult<T> {
        override fun toString(): String = "PayloadDecoded(redacted)"
    }
    data class Failure(val category: TransportFailure) : PayloadResult<Nothing>
}

/** A4 is the only text boundary; A1 is the only semantic/primitive validator. */
internal object WhamJson {
    fun <T> decode(body: ResponseBody, map: (JsonObject) -> T): PayloadResult<T> =
        when (val json = JsonBoundary.parse(body)) {
            is JsonResult.Failure -> PayloadResult.Failure(json.category)
            is JsonResult.Tree -> {
                val root = json.value as? JsonObject
                if (root == null) PayloadResult.Failure(TransportFailure.INVALID_RESPONSE)
                else PayloadResult.Decoded(map(root))
            }
        }

    fun <T> read(value: JsonElement?, map: (JsonElement) -> T?): Input<T> = when (value) {
        null -> Input.Missing
        JsonNull -> Input.Null
        else -> map(value)?.let { Input.Value(it) } ?: Input.Invalid
    }

    /** Invalid parents propagate wrong type without manufacturing missing child fields. */
    fun child(parent: JsonElement?, key: String): JsonElement? = when (parent) {
        null -> null
        JsonNull -> JsonNull
        is JsonObject -> parent[key]
        else -> JsonArray(emptyList()) // Wrong-type marker, never a scalar child.
    }

    fun primitive(value: JsonElement?): Input<Any> = read(value) {
        val primitive = it as? JsonPrimitive ?: return@read null
        if (primitive.isString) primitive.content else scalar(primitive.content)
    }

    private fun scalar(value: String): Any? = when (value) {
        "true" -> true
        "false" -> false
        else -> try { BigDecimal(value) } catch (_: NumberFormatException) { null }
    }
}

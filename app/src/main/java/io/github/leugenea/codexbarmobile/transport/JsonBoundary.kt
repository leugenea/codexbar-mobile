package io.github.leugenea.codexbarmobile.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Tree only: no DTO mapping, coercion, serializer plugin or secret-bearing errors. */
sealed interface JsonResult {
    class Tree(val value: JsonElement) : JsonResult {
        override fun toString(): String = "JsonTree(redacted)"
    }
    data class Failure(val category: TransportFailure = TransportFailure.INVALID_RESPONSE) : JsonResult
}

object JsonBoundary {
    fun parse(body: ResponseBody): JsonResult = parse(body.copyBytes())

    fun parse(bytes: ByteArray): JsonResult {
        if (bytes.size > ResponseBody.MAX_BYTES) return JsonResult.Failure(TransportFailure.BODY_TOO_LARGE)
        return try {
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            JsonSyntax(text).validate()
            JsonResult.Tree(Json.parseToJsonElement(text))
        } catch (_: IllegalArgumentException) {
            JsonResult.Failure()
        } catch (_: java.nio.charset.CharacterCodingException) {
            JsonResult.Failure()
        }
    }
}

/** Validate before the tree parser can discard duplicate keys or accept bare literals. */
private class JsonSyntax(private val text: String) {
    private var position = 0

    fun validate() {
        value(0)
        whitespace()
        require(position == text.length)
    }

    private fun value(depth: Int) {
        require(depth <= 64)
        whitespace()
        when (peek()) {
            '{' -> obj(depth + 1)
            '[' -> array(depth + 1)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private fun obj(depth: Int) {
        require(depth <= 64)
        position++
        val keys = mutableSetOf<String>()
        if (consume('}')) return
        do {
            whitespace()
            val key = string()
            require(keys.add(key))
            require(consume(':'))
            value(depth)
        } while (consume(','))
        require(consume('}'))
    }

    private fun array(depth: Int) {
        require(depth <= 64)
        position++
        if (consume(']')) return
        do { value(depth) } while (consume(','))
        require(consume(']'))
    }

    private fun string(): String {
        val start = position
        require(peek() == '"')
        position++
        while (peek() != '"') {
            val char = peek()
            require(char >= ' ')
            position++
            if (char == '\\') escape()
        }
        position++
        return (Json.parseToJsonElement(text.substring(start, position)) as JsonPrimitive).content
    }

    private fun escape() {
        val char = peek()
        position++
        if (char == 'u') {
            repeat(4) {
                require(peek() in "0123456789abcdefABCDEF")
                position++
            }
        } else require(char in "\"\\/bfnrt")
    }

    private fun number() {
        val start = position
        if (peek() == '-') position++
        if (peek() == '0') position++ else digits()
        if (peek() == '.') {
            position++
            digits()
        }
        exponent()
        val token = text.substring(start, position)
        // A safety boundary, not a conversion: the original numeric lexeme is retained.
        require(token.toDoubleOrNull()?.isFinite() == true)
    }

    private fun exponent() {
        if (peek() !in "eE") return
        position++
        if (peek() in "+-") position++
        digits()
    }

    private fun digits() {
        val start = position
        while (peek() in '0'..'9') position++
        require(position > start)
    }

    private fun literal(value: String) {
        require(text.startsWith(value, position))
        position += value.length
    }

    private fun consume(char: Char): Boolean {
        whitespace()
        if (peek() != char) return false
        position++
        return true
    }

    private fun whitespace() {
        while (peek() in " \t\r\n") position++
    }

    private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
}

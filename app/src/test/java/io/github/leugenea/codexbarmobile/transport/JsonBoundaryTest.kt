package io.github.leugenea.codexbarmobile.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JsonBoundaryTest {
    private fun parse(text: String): JsonResult = JsonBoundary.parse(text.toByteArray())

    @Test fun preservesTypesAndNumericLexemesWithoutCoercion() {
        val text = """{"number":9007199254740993,"decimal":1.20e2,"text":"12","bool":true,"false":false,"nil":null,"array":[0,-0.5,1E+3]}"""
        val tree = (parse(text) as JsonResult.Tree).value as JsonObject
        assertEquals("9007199254740993", (tree.getValue("number") as JsonPrimitive).content)
        assertEquals("1.20e2", (tree.getValue("decimal") as JsonPrimitive).content)
        assertFalse((tree.getValue("number") as JsonPrimitive).isString)
        assertTrue((tree.getValue("text") as JsonPrimitive).isString)
        assertEquals("true", (tree.getValue("bool") as JsonPrimitive).content)
        assertEquals("false", (tree.getValue("false") as JsonPrimitive).content)
        assertEquals(JsonNull, tree["nil"])
        assertEquals(3, (tree.getValue("array") as JsonArray).size)
        assertEquals("JsonTree(redacted)", parse(text).toString())
        assertEquals("{\"a\":{\"x\":1},\"b\":{\"x\":2}}", (parse("""{"a":{"x":1},"b":{"x":2}}""") as JsonResult.Tree).value.toString())
    }

    @Test fun rejectsDuplicatesIncludingDecodedEscapedKeysAtAnyDepth() {
        listOf("""{"a":1,"a":2}""", """{"a":1,"\u0061":2}""",
            """[{"nested":{"k":false,"k":true}}]""",
            """{"a/b":0,"a\/b":1}""",
            """{"😃":0,"\uD83D\uDE03":1}""").forEach { assertInvalid(it) }
    }

    @Test fun rejectsInvalidGrammarAndNonfiniteNumbers() {
        listOf("", " ", "NaN", "Infinity", "-Infinity", "1e999", "-1e999",
            "00", "-01", "01.0", "+1", "1.", ".1", "1e", "1e+", "--1",
            "true false", "tru", "nul", "False", "{key:1}", "[bare]", "[1,]",
            "{\"a\":1,}", "{\"a\" 1}", "{\"a\":}", "[", "{", "\"unterminated",
            "\"bad\\q\"", "\"bad\\u00x0\"", "\"bad\nline\"", "/*no*/1", "[1 2]")
            .forEach { assertInvalid(it) }
    }

    @Test fun boundsBytesDepthAndUtf8BeforeTreeConstruction() {
        assertEquals(JsonResult.Failure(TransportFailure.BODY_TOO_LARGE),
            JsonBoundary.parse(ByteArray(ResponseBody.MAX_BYTES + 1)))
        assertEquals(JsonResult.Failure(), JsonBoundary.parse(byteArrayOf(0xc3.toByte(), 0x28)))
        assertInvalid("[".repeat(65) + "0" + "]".repeat(65))
        assertInvalid("[".repeat(65) + "]".repeat(65))
        assertInvalid("{\"a\":".repeat(65) + "{}" + "}".repeat(65))
        assertTrue(parse("[".repeat(64) + "]".repeat(64)) is JsonResult.Tree)
        assertTrue(parse("[".repeat(64) + "0" + "]".repeat(64)) is JsonResult.Tree)
        val bytes = "{\"ok\":true}".toByteArray()
        val body = (TransportResult.Response.bounded(200, bytes) as TransportResult.Response).body
        assertEquals("{\"ok\":true}", (JsonBoundary.parse(body) as JsonResult.Tree).value.toString())
    }

    @Test fun acceptsWhitespaceEscapesEmptyContainersAndScalarRoots() {
        listOf(" \r\n\t {} ", "[]", "null", "true", "false", "0", "-0", "1e-3",
            "\"quote\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\"", "{\"é\":\"😃\"}")
            .forEach { assertTrue(parse(it) is JsonResult.Tree) }
    }

    private fun assertInvalid(text: String) {
        // Never include rejected source text in a failure diagnostic.
        assertEquals(JsonResult.Failure(), parse(text))
    }
}

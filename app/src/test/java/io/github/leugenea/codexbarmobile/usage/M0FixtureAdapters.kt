package io.github.leugenea.codexbarmobile.usage

import io.github.leugenea.codexbarmobile.transport.JsonBoundary
import io.github.leugenea.codexbarmobile.transport.JsonResult
import io.github.leugenea.codexbarmobile.transport.ResponseBody
import io.github.leugenea.codexbarmobile.transport.TransportResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Read the checked-in mocks/vectors unchanged, retaining their attribution:
 * docs/research/m0/fixtures/ATTRIBUTION.md and licenses/.
 * Weekly mock: Copyright (c) 2026 Peter Steinberger, MIT.
 * Inventory mock: Copyright 2025 OpenAI, Apache-2.0 + NOTICE; sanitized upstream
 * modification is documented there. No copies or relabeled live backend bodies.
 */
internal object M0FixtureAdapters {
    private fun file(name: String): File {
        val relative = "docs/research/m0/fixtures/$name"
        return listOf(File(relative), File("../$relative")).first { it.isFile }
    }

    fun mock(name: String): ResponseBody = body(file(name).readBytes())

    fun vectors(): List<JsonObject> {
        val tree = JsonBoundary.parse(file("synthetic-vectors.json").readBytes()) as JsonResult.Tree
        return ((tree.value as JsonObject).getValue("vectors") as JsonArray).map { it as JsonObject }
    }

    fun body(text: String): ResponseBody = body(text.toByteArray(Charsets.UTF_8))
    fun body(bytes: ByteArray): ResponseBody = (TransportResult.Response.bounded(200, bytes) as TransportResult.Response).body

    fun <T> observation(result: PayloadResult<T>): T = (result as PayloadResult.Decoded).observation
}

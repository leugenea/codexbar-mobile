package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import io.github.leugenea.codexbarmobile.transport.JsonBoundary
import io.github.leugenea.codexbarmobile.transport.JsonResult
import io.github.leugenea.codexbarmobile.transport.ProviderHttpRequest
import io.github.leugenea.codexbarmobile.transport.ResponseBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigInteger

internal class DeviceCode(val id: SensitiveValue, val code: SensitiveValue, val intervalMillis: Long) {
    override fun toString(): String = "DeviceCode(redacted)"
}
internal class AuthorizationCode(val code: SensitiveValue, val verifier: SensitiveValue) {
    override fun toString(): String = "AuthorizationCode(redacted)"
}
internal class AuthTokens(val access: SensitiveValue, val refresh: SensitiveValue) {
    override fun toString(): String = "AuthTokens(redacted)"
}

/** M0 section 3 requests + the response contract on #45, pinned source [87]. */
internal object AuthProtocol {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val VERIFICATION_URL = "https://auth.openai.com/codex/device"
    const val REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"
    const val POLL_BUDGET_MILLIS = 900_000L
    private const val USERCODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private const val POLL_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
    private const val EXCHANGE_URL = "https://auth.openai.com/oauth/token"

    fun usercodeRequest(): ProviderHttpRequest = ProviderHttpRequest.JsonPost(USERCODE_URL.toHttpUrl(),
        buildJsonObject { put("client_id", CLIENT_ID) })

    fun pollRequest(device: DeviceCode): ProviderHttpRequest = ProviderHttpRequest.JsonPost(POLL_URL.toHttpUrl(),
        buildJsonObject {
            put("device_auth_id", text(device.id))
            put("user_code", text(device.code))
        })

    fun exchangeRequest(authorization: AuthorizationCode): ProviderHttpRequest =
        ProviderHttpRequest.FormPost(EXCHANGE_URL.toHttpUrl(), mapOf(
            "grant_type" to sensitive("authorization_code"),
            "code" to authorization.code,
            "code_verifier" to authorization.verifier,
            "client_id" to sensitive(CLIENT_ID),
            "redirect_uri" to sensitive(REDIRECT_URI),
        ))

    fun device(body: ResponseBody): DeviceCode {
        val tree = objectTree(body)
        return DeviceCode(required(tree, "device_auth_id"), required(tree, "user_code"), interval(tree))
    }

    fun authorization(body: ResponseBody): AuthorizationCode {
        val tree = objectTree(body)
        return AuthorizationCode(required(tree, "authorization_code"), required(tree, "code_verifier"))
    }

    fun tokens(body: ResponseBody): AuthTokens {
        val tree = objectTree(body)
        // Both are mandatory for A7. Other metadata is ignored; it proves neither TTL nor identity.
        return AuthTokens(required(tree, "access_token"), required(tree, "refresh_token"))
    }

    private fun objectTree(body: ResponseBody): JsonObject =
        ((JsonBoundary.parse(body) as? JsonResult.Tree)?.value as? JsonObject) ?: malformed()

    private fun required(tree: JsonObject, key: String): SensitiveValue {
        val value = tree[key] as? JsonPrimitive ?: malformed()
        if (!value.isString || value.content.isEmpty()) malformed()
        return sensitive(value.content)
    }

    private fun interval(tree: JsonObject): Long {
        if (!tree.containsKey("interval")) return 5_000L
        val value = tree["interval"] as? JsonPrimitive ?: malformed()
        val grammar = if (value.isString) Regex("[0-9]+") else Regex("-?(0|[1-9][0-9]*)")
        if (!grammar.matches(value.content)) malformed()
        // Preserve arbitrary integer magnitude without overflow. A wait >= budget expires, never polls early.
        return BigInteger(value.content).max(BigInteger.valueOf(3)).min(BigInteger.valueOf(900)).toLong() * 1_000L
    }

    private fun malformed(): Nothing = throw AuthAbort(AuthFailure.MALFORMED)
    private fun sensitive(text: String): SensitiveValue = try {
        val buffer = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        SensitiveValue.copyOf(bytes)
    } catch (_: java.nio.charset.CharacterCodingException) {
        malformed()
    }
    private fun text(value: SensitiveValue): String = value.copyBytes().toString(Charsets.UTF_8)
}

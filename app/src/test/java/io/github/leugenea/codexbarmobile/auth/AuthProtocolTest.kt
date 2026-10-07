package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import io.github.leugenea.codexbarmobile.transport.ProviderHttpRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AuthProtocolTest {
    @Test fun exactSelectedJsonAndFormShapes() {
        val usercode = AuthProtocol.usercodeRequest() as ProviderHttpRequest.JsonPost
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/usercode", usercode.url.toString())
        assertEquals(Json.parseToJsonElement("""{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}"""), usercode.tree)
        val device = AuthProtocol.device(SyntheticAuth.body(SyntheticAuth.DEVICE))
        val poll = AuthProtocol.pollRequest(device) as ProviderHttpRequest.JsonPost
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/token", poll.url.toString())
        assertEquals(Json.parseToJsonElement(SyntheticAuth.DEVICE), poll.tree)
        val authorization = AuthProtocol.authorization(SyntheticAuth.body(SyntheticAuth.AUTHORIZATION))
        val exchange = AuthProtocol.exchangeRequest(authorization) as ProviderHttpRequest.FormPost
        assertEquals("https://auth.openai.com/oauth/token", exchange.url.toString())
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "synthetic-authorization",
            "code_verifier" to "synthetic-verifier", "client_id" to "app_EMoamEEZ73f0CkXaXp7hrann",
            "redirect_uri" to "https://auth.openai.com/deviceauth/callback"), exchange.fields.mapValues { text(it.value) })
        assertEquals("https://auth.openai.com/codex/device", AuthState.AwaitingUser(device.code).verificationUrl)
    }

    @Test fun intervalHasStrictDefaultFloorAndUnboundedIntegerMagnitude() {
        assertEquals(5_000L, AuthProtocol.device(SyntheticAuth.body(SyntheticAuth.DEVICE)).intervalMillis)
        mapOf("7" to 7_000L, "\"7\"" to 7_000L, "\"0007\"" to 7_000L, "0" to 3_000L,
            "-20" to 3_000L, "\"1\"" to 3_000L, "3" to 3_000L,
            "99999999999999999999999999999" to 900_000L,
            "\"99999999999999999999999999999\"" to 900_000L).forEach { (input, expected) ->
            assertEquals(expected, AuthProtocol.device(SyntheticAuth.body(withInterval(input))).intervalMillis)
        }
        listOf("null", "true", "false", "[]", "{}", "3.0", "1e1", "\"\"", "\"garbage\"",
            "\" 5\"", "\"-5\"", "\"+5\"", "\"5.0\"", "\"٥\"", "1e999").forEach { input ->
            malformed { AuthProtocol.device(SyntheticAuth.body(withInterval(input))) }
        }
    }

    @Test fun everyStageRejectsMalformedRootAndDuplicateKeys() {
        val stages = listOf<(String) -> Any>(
            { AuthProtocol.device(SyntheticAuth.body(it)) },
            { AuthProtocol.authorization(SyntheticAuth.body(it)) },
            { AuthProtocol.tokens(SyntheticAuth.body(it)) },
        )
        stages.forEach { parse ->
            listOf("", "{", "[]", "null", "true", "\"root\"", "7", "{\"extra\":1,\"extra\":2}").forEach { input ->
                malformed { parse(input) }
            }
        }
        val fixtures = listOf(SyntheticAuth.DEVICE, SyntheticAuth.AUTHORIZATION, SyntheticAuth.TOKENS)
        val keys = listOf("device_auth_id", "authorization_code", "access_token")
        fixtures.zip(keys).zip(stages).forEach { (fixtureAndKey, parse) ->
            val (fixture, key) = fixtureAndKey
            malformed { parse(fixture.dropLast(1) + ",\"$key\":\"synthetic-duplicate\"}") }
        }
    }

    @Test fun requiredFieldsAtAllStagesRejectMissingNullEmptyAndWrongTypes() {
        val cases = listOf(
            Pair(listOf("device_auth_id", "user_code"), { input: String -> AuthProtocol.device(SyntheticAuth.body(input)) }),
            Pair(listOf("authorization_code", "code_verifier"), { input: String -> AuthProtocol.authorization(SyntheticAuth.body(input)) }),
            Pair(listOf("access_token", "refresh_token"), { input: String -> AuthProtocol.tokens(SyntheticAuth.body(input)) }),
        )
        cases.forEach { (keys, parse) ->
            keys.forEach { key ->
                val sibling = keys.single { it != key }
                malformed { parse("{\"$sibling\":\"synthetic-sibling\"}") }
                listOf("null", "\"\"", "true", "0", "[]", "{}", "\"synthetic-\\ud800\"").forEach { bad ->
                    malformed { parse("{\"$sibling\":\"synthetic-sibling\",\"$key\":$bad}") }
                }
            }
        }
    }

    @Test fun optionalMetadataCannotChangeBrowserRouteTokensIdentityOrTtl() {
        val device = AuthProtocol.device(SyntheticAuth.body(SyntheticAuth.DEVICE.dropLast(1) +
            ",\"verification_uri\":\"https://untrusted.invalid/\"}"))
        assertEquals("https://auth.openai.com/codex/device", AuthState.AwaitingUser(device.code).verificationUrl)
        val tokens = AuthProtocol.tokens(SyntheticAuth.body(SyntheticAuth.TOKENS.dropLast(1) +
            ",\"id_token\":false,\"expires_in\":\"untrusted\",\"account\":[]}"))
        assertEquals("synthetic-access", text(tokens.access))
        assertEquals("synthetic-refresh", text(tokens.refresh))
        val authorization = AuthProtocol.authorization(SyntheticAuth.body(SyntheticAuth.AUTHORIZATION.dropLast(1) + ",\"other\":null}"))
        assertEquals("synthetic-verifier", text(authorization.verifier))
    }

    @Test fun everyPayloadStateRequestAndExceptionHasRedactedDiagnostics() {
        val device = AuthProtocol.device(SyntheticAuth.body(SyntheticAuth.DEVICE))
        val authorization = AuthProtocol.authorization(SyntheticAuth.body(SyntheticAuth.AUTHORIZATION))
        val tokens = AuthProtocol.tokens(SyntheticAuth.body(SyntheticAuth.TOKENS))
        val objects = listOf(device, authorization, tokens, device.id, device.code, authorization.code,
            authorization.verifier, tokens.access, tokens.refresh, AuthState.AwaitingUser(device.code),
            AuthProtocol.usercodeRequest(), AuthProtocol.pollRequest(device), AuthProtocol.exchangeRequest(authorization),
            AuthState.Failed(AuthStage.POLL, AuthFailure.MALFORMED), AuthAbort(AuthFailure.MALFORMED))
        objects.forEach { value -> SyntheticAuth.secrets.forEach { assertFalse(value.toString().contains(it)) } }
        val error = AuthAbort(AuthFailure.MALFORMED)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertTrue(error.stackTrace.isEmpty())
    }

    private fun withInterval(value: String) = SyntheticAuth.DEVICE.dropLast(1) + ",\"interval\":$value}"
    private fun text(value: SensitiveValue) = value.copyBytes().toString(Charsets.UTF_8)
    private fun malformed(parse: () -> Any) {
        val error = assertThrows(AuthAbort::class.java) { parse() }
        assertEquals(AuthFailure.MALFORMED, error.category)
        assertNull(error.cause)
    }
}

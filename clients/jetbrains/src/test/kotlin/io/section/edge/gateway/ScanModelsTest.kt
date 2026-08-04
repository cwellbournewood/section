package io.section.edge.gateway

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Schema-shape tests for the DTOs in [ScanModels]. Tightly coupled to
 * the gateway's Pydantic models — when those change the failures here
 * tell you exactly what drifted.
 */
class ScanModelsTest {

    // Mirrors GatewayClient.json — encodeDefaults must be true so that
    // ScanRequest.client is serialized at its default value.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `ScanRequest defaults match gateway expectations`() {
        val req = ScanRequest(text = "hello")
        val encoded = json.encodeToString(req)
        assertThat(encoded).contains("\"text\":\"hello\"")
        assertThat(encoded).contains("\"client\":\"jetbrains\"")
        // Nullable fields should NOT appear when null — leaner audit
        // entries on the gateway side.
        assertThat(encoded).doesNotContain("\"url\"")
        assertThat(encoded).doesNotContain("\"model\"")
        assertThat(encoded).doesNotContain("\"session_id\"")
    }

    @Test
    fun `ScanResponse parses minimal allow body`() {
        val body = """
            {
              "request_id": "abc",
              "action": "allow",
              "sanitised": "hello",
              "transforms": [],
              "findings": [],
              "decision": {"action":"allow"},
              "bundle_digest": "x"
            }
        """.trimIndent()
        val resp = json.decodeFromString<ScanResponse>(body)
        assertThat(resp.action).isEqualTo("allow")
        assertThat(resp.isAllow).isTrue()
        assertThat(resp.findings).isEmpty()
        assertThat(resp.transforms).isEmpty()
    }

    @Test
    fun `ScanResponse parses block body with reason and severity`() {
        val body = """
            {
              "request_id": "abc",
              "action": "block",
              "sanitised": null,
              "transforms": [],
              "findings": [],
              "decision": {},
              "bundle_digest": "x",
              "reason": "PCI primary account number detected",
              "severity": "high"
            }
        """.trimIndent()
        val resp = json.decodeFromString<ScanResponse>(body)
        assertThat(resp.isBlock).isTrue()
        assertThat(resp.sanitised).isNull()
        assertThat(resp.reason).isEqualTo("PCI primary account number detected")
        assertThat(resp.severity).isEqualTo("high")
    }

    @Test
    fun `ScanResponse parses mask body with transforms`() {
        val body = """
            {
              "request_id": "abc",
              "action": "mask",
              "sanitised": "send to <ACCOUNT_NUMBER_A4F2>",
              "transforms": [
                {"label":"regex.account_number","placeholder":"<ACCOUNT_NUMBER_A4F2>","method":"tokenise","scope":"request"}
              ],
              "findings": [
                {"label":"ACCOUNT_NUMBER","detector":"regex.account_number","confidence":0.95,"start":8,"end":13}
              ],
              "decision": {"effective_action":"transform"},
              "bundle_digest": "x"
            }
        """.trimIndent()
        val resp = json.decodeFromString<ScanResponse>(body)
        assertThat(resp.isMask).isTrue()
        assertThat(resp.transforms.single().method).isEqualTo("tokenise")
        assertThat(resp.findings.single().confidence).isEqualTo(0.95)
    }

    @Test
    fun `RestoreResponse parses gateway response shape`() {
        val body = """
            {
              "request_id": "abc",
              "text": "I'll send 12345 tomorrow.",
              "restored": 1,
              "missing": []
            }
        """.trimIndent()
        val resp = json.decodeFromString<RestoreResponse>(body)
        assertThat(resp.restored).isEqualTo(1)
        assertThat(resp.text).contains("12345")
        assertThat(resp.missing).isEmpty()
    }

    @Test
    fun `GatewayError tolerates either detail or message field`() {
        val detail = json.decodeFromString<GatewayError>("""{"detail":"x"}""")
        assertThat(detail.bestMessage()).isEqualTo("x")
        val msg = json.decodeFromString<GatewayError>("""{"message":"y","code":"abc"}""")
        assertThat(msg.bestMessage()).isEqualTo("y")
        val codeOnly = json.decodeFromString<GatewayError>("""{"code":"abc"}""")
        assertThat(codeOnly.bestMessage()).isEqualTo("abc")
    }

    @Test
    fun `ScanRequest with custom client and session round-trips`() {
        val req = ScanRequest(
            text = "x",
            client = "jetbrains",
            url = "https://example",
            model = "claude-3-5-sonnet",
            sessionId = "tab-1",
        )
        val encoded = json.encodeToString(req)
        val decoded = json.decodeFromString<ScanRequest>(encoded)
        assertThat(decoded).isEqualTo(req)
    }
}

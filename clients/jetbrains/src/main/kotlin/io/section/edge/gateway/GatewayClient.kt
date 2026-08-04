package io.section.edge.gateway

import io.section.edge.util.logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration

/**
 * Thin HTTP client for the gateway's edge endpoints.
 *
 * Threading: every method is **blocking** — callers run it on a
 * background coroutine / progress task / pooled executor; the action
 * layer and inspection layer in this plugin do exactly that. We do NOT
 * use OkHttp's async API because the IntelliJ Platform's
 * `ProgressIndicator` is the cancellation primitive we already wire
 * through, and matching cancellation semantics across both is fiddly.
 *
 * Auth: API key takes precedence over OIDC bearer token; both are
 * supplied by the caller (the [Auth] sealed class). We pass tenant and
 * groups via the standard `X-Section-*` headers so the gateway can
 * fall back to header-derived principal when running in dev mode
 * without an upstream auth proxy.
 */
class GatewayClient(
    private val baseUrl: String,
    private val auth: Auth,
    private val tenant: String? = null,
    private val userId: String? = null,
    private val groups: List<String> = emptyList(),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    /**
     * Serializer used for both directions. Configured to be tolerant of
     * gateway fields the plugin doesn't know about (forwards compat)
     * and to skip nulls on the way out (avoids sending `model: null`
     * which Pydantic would accept but the audit row would clutter).
     *
     * `encodeDefaults` must stay true: `ScanRequest.client` defaults to
     * "jetbrains", and with defaults omitted the field never reached the
     * gateway, which then recorded the request under its own fallback
     * client instead of attributing it to the plugin. Nulls are still
     * dropped via `explicitNulls`, so optional fields stay absent.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    },
) {
    private val log = logger<GatewayClient>()

    /** Authentication strategy. */
    sealed interface Auth {
        /** No credentials — dev mode, gateway must accept header auth. */
        object None : Auth

        /** Static API key, sent as `X-API-Key`. */
        data class ApiKey(val key: String) : Auth

        /** OIDC bearer token, sent as `Authorization: Bearer <token>`. */
        data class Bearer(val token: String) : Auth
    }

    /**
     * Make a `POST /v1/scan` call. Blocks until the gateway responds.
     */
    fun scan(req: ScanRequest): GatewayResult<ScanResponse> =
        postJson("/v1/scan", json.encodeToString(req)) { body ->
            json.decodeFromString<ScanResponse>(body)
        }

    /**
     * Make a `POST /v1/restore` call. Blocks until the gateway responds.
     */
    fun restore(req: RestoreRequest): GatewayResult<RestoreResponse> =
        postJson("/v1/restore", json.encodeToString(req)) { body ->
            json.decodeFromString<RestoreResponse>(body)
        }

    /**
     * Probe the gateway for reachability — used by the Settings panel's
     * "Test connection" button. Returns the raw HTTP status so callers
     * can distinguish "unreachable" (network failure) from "rejected"
     * (4xx). 2xx and 401/403 both count as "reachable".
     */
    fun ping(): GatewayResult<Int> {
        val url = (baseUrl.trimEnd('/') + "/healthz").toHttpUrlOrNull()
            ?: return GatewayResult.Err(0, "invalid gateway URL: $baseUrl")
        val req = Request.Builder()
            .url(url)
            .get()
            .applyAuth()
            .applyContext()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                GatewayResult.Ok(resp.code)
            }
        } catch (ex: IOException) {
            GatewayResult.Err(0, ex.message ?: "I/O error")
        }
    }

    /**
     * Internal POST helper. Caller supplies the parser closure so we
     * don't need a sealed hierarchy of response types — every endpoint
     * has its own DTO.
     */
    private inline fun <T> postJson(
        path: String,
        body: String,
        crossinline parse: (String) -> T,
    ): GatewayResult<T> {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull()
            ?: return GatewayResult.Err(0, "invalid gateway URL: $baseUrl")
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .applyAuth()
            .applyContext()
            .header("Accept", "application/json")
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                handleResponse(resp, parse)
            }
        } catch (ex: IOException) {
            // Network / TLS / DNS failure → surface as an explicit Err
            // so the UI doesn't show an opaque stack trace.
            log.warn("gateway POST $path failed: ${ex.javaClass.simpleName}: ${ex.message}")
            GatewayResult.Err(0, ex.message ?: "I/O error")
        }
    }

    private inline fun <T> handleResponse(
        resp: Response,
        crossinline parse: (String) -> T,
    ): GatewayResult<T> {
        val text = resp.body?.string().orEmpty()
        if (resp.isSuccessful) {
            return try {
                GatewayResult.Ok(parse(text))
            } catch (ex: SerializationException) {
                log.warn("gateway response parse failure at ${resp.code}: ${ex.message}")
                GatewayResult.Err(
                    resp.code,
                    "malformed gateway response: ${ex.message ?: "unknown"}",
                )
            }
        }
        val retryAfter = resp.header("Retry-After")?.toLongOrNull()
        val message = tryParseError(text) ?: "HTTP ${resp.code}"
        return GatewayResult.Err(resp.code, message, retryAfter)
    }

    private fun tryParseError(text: String): String? {
        if (text.isBlank()) return null
        return try {
            json.decodeFromString<GatewayError>(text).bestMessage()
        } catch (_: SerializationException) {
            // Surface the raw text (truncated) when the body isn't JSON
            // — the gateway sometimes returns plain-text from upstream
            // proxies. Truncate aggressively so notification popups
            // stay readable.
            text.take(MAX_RAW_ERROR_CHARS)
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder = apply {
        when (val a = auth) {
            is Auth.None -> Unit
            is Auth.ApiKey -> header("X-API-Key", a.key)
            is Auth.Bearer -> header("Authorization", "Bearer ${a.token}")
        }
    }

    private fun Request.Builder.applyContext(): Request.Builder = apply {
        tenant?.let { header("X-Section-Tenant", it) }
        userId?.let { header("X-Section-User", it) }
        if (groups.isNotEmpty()) {
            header("X-Section-Groups", groups.joinToString(","))
        }
        header("User-Agent", USER_AGENT)
        // Lets the gateway distinguish edge-originated traffic at the
        // network layer without parsing the body.
        header("X-Section-Client", "jetbrains")
    }

    companion object {
        const val USER_AGENT: String = "Section-JetBrains/1.1"
        const val MAX_RAW_ERROR_CHARS: Int = 240
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .build()
    }
}

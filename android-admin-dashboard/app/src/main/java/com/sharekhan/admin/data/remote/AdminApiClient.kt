package com.sharekhan.admin.data.remote

import com.sharekhan.admin.data.model.AppUser
import com.sharekhan.admin.data.model.BrokerDetails
import com.sharekhan.admin.data.model.BrokerSummary
import com.sharekhan.admin.data.model.PageResponse
import com.sharekhan.admin.data.model.PlaceOrderPayload
import com.sharekhan.admin.data.model.TradingRequest
import com.sharekhan.admin.data.model.TriggeredTrade
import com.sharekhan.admin.data.model.UpdateTargetsRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor

class AdminApiClient(
    baseUrl: String,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    data class CsrfToken(val headerName: String, val token: String)

    private val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
    private val baseHttpUrl: HttpUrl =
        normalizedBaseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid baseUrl: $baseUrl")

    private val cookieJar = SessionCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    @Volatile
    private var csrfToken: CsrfToken? = null

    suspend fun login(username: String, password: String) {
        withContext(dispatcher) {
            cookieJar.clear()
            csrfToken = null
            val loginCsrf = fetchLoginCsrf()

            val formBuilder = FormBody.Builder()
                .add("username", username)
                .add("password", password)
            if (loginCsrf != null) {
                formBuilder.add("_csrf", loginCsrf.token)
            }

            val request = Request.Builder()
                .url(buildUrl("admin/login"))
                .post(formBuilder.build())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", buildUrl("admin/login").toString())
                .build()

            client.newCall(request).execute().use { response ->
                // Let Spring redirect; errors will fall through
                if (response.code == 401 || response.code == 403) {
                    val body = response.body?.string().orEmpty()
                    throw AdminHttpException(response.code, "Login failed", body)
                }
            }

            if (!cookieJar.hasSession()) {
                throw AdminHttpException(401, "Login failed: session cookie missing")
            }

            val ok = dashboardPing()
            if (!ok) {
                throw IOException("Login failed: dashboard ping unsuccessful")
            }

            csrfToken = null // Force reload for authenticated calls
        }
    }

    suspend fun logout() {
        runCatching {
            request(
                path = "admin/logout",
                method = HttpMethod.GET,
                requireJson = false,
                enforceSuccess = false
            )
        }
        cookieJar.clear()
        csrfToken = null
    }

    suspend fun fetchUsers(): List<AppUser> {
        val element = requestJson("admin/app-users")
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["content"]?.jsonArray
            else -> null
        }
        return array?.toAppUsers() ?: emptyList()
    }

    suspend fun createUser(username: String, customerId: Long?, notes: String?): AppUser {
        val payload = buildJsonObject {
            put("username", username)
            customerId?.let { put("customerId", it) }
            notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
        }
        val element = requestJson(
            path = "admin/app-users",
            method = HttpMethod.POST,
            body = payload
        )
        return json.decodeFromJsonElement(AppUser.serializer(), element)
    }

    suspend fun fetchTradingRequests(userId: Long?): List<TradingRequest> {
        val element = requestJson(
            path = "api/orders/requests",
            query = mapOf("userId" to userId?.toString())
        )
        return element.jsonArrayOrEmpty().map {
            json.decodeFromJsonElement(TradingRequest.serializer(), it)
        }
    }

    suspend fun triggerRequest(
        requestId: Long,
        brokerCredentialsId: Long?
    ): String {
        val element = requestJson(
            path = "admin/trigger/$requestId",
            method = HttpMethod.POST,
            query = mapOf(
                "brokerCredentialsId" to brokerCredentialsId?.toString()
            ),
            requireJson = false
        )
        return if (element is JsonPrimitive && element.isString) {
            element.content
        } else {
            "triggered"
        }
    }

    suspend fun updateRequest(
        requestId: Long,
        update: UpdateTargetsRequest
    ): TradingRequest {
        val element = requestJson(
            path = "api/trades/request/$requestId",
            method = HttpMethod.PUT,
            body = json.encodeToJsonElement(UpdateTargetsRequest.serializer(), update)
        )
        return json.decodeFromJsonElement(TradingRequest.serializer(), element)
    }

    suspend fun cancelRequest(requestId: Long, userId: Long?): String {
        val element = requestJson(
            path = "api/trades/cancel-request/$requestId",
            method = HttpMethod.POST,
            query = mapOf("userId" to userId?.toString()),
            requireJson = false
        )
        return element.jsonPrimitiveOrNull()?.content ?: "cancelled"
    }

    suspend fun fetchExecutedTrades(
        userId: Long?,
        statuses: List<String>,
        page: Int,
        size: Int
    ): PageResponse<TriggeredTrade> {
        val query = mutableMapOf<String, String>()
        userId?.let { query["userId"] = it.toString() }
        if (statuses.isNotEmpty()) {
            statuses.forEach { status ->
                query.compute("status") { _, existing ->
                    existing?.let { "$it,$status" } ?: status
                }
            }
        }
        query["page"] = page.toString()
        query["size"] = size.toString()
        val element = requestJson(
            path = "api/orders/executed",
            query = query
        )
        val serializer = PageResponse.serializer(TriggeredTrade.serializer())
        return json.decodeFromJsonElement(serializer, element)
    }

    suspend fun updateExecution(
        executionId: Long,
        update: UpdateTargetsRequest
    ): TriggeredTrade {
        val element = requestJson(
            path = "api/trades/execution/$executionId",
            method = HttpMethod.PUT,
            body = json.encodeToJsonElement(UpdateTargetsRequest.serializer(), update)
        )
        return json.decodeFromJsonElement(TriggeredTrade.serializer(), element)
    }

    suspend fun fetchBrokers(userId: Long): List<BrokerSummary> {
        val element = requestJson("admin/app-users/$userId/brokers")
        return element.jsonArrayOrEmpty().map {
            json.decodeFromJsonElement(BrokerSummary.serializer(), it)
        }
    }

    suspend fun fetchBrokerDetails(brokerId: Long): BrokerDetails {
        val element = requestJson("admin/brokers/$brokerId")
        return json.decodeFromJsonElement(BrokerDetails.serializer(), element)
    }

    suspend fun saveBroker(
        brokerId: Long?,
        userId: Long?,
        body: JsonElement
    ): BrokerSummary {
        val path = if (brokerId == null) {
            requireNotNull(userId) { "userId is required when creating broker" }
            "admin/app-users/$userId/brokers"
        } else {
            "admin/brokers/$brokerId"
        }
        val method = if (brokerId == null) HttpMethod.POST else HttpMethod.PUT
        val element = requestJson(
            path = path,
            method = method,
            body = body
        )
        return json.decodeFromJsonElement(BrokerSummary.serializer(), element)
    }

    suspend fun deleteBroker(brokerId: Long) {
        request(
            path = "admin/brokers/$brokerId",
            method = HttpMethod.DELETE,
            requireJson = false
        )
    }

    suspend fun fetchInstruments(exchange: String): List<String> {
        val element = requestJson("api/scripts/instruments/$exchange")
        val list = when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull()?.content }
            is JsonObject -> element["instruments"]?.jsonArray?.mapNotNull { it.jsonPrimitiveOrNull()?.content }
            else -> null
        }

        if (list != null) return list

        val fallback = requestJson(
            path = "api/scripts/instruments",
            query = mapOf("exchange" to exchange)
        )
        return when (fallback) {
            is JsonArray -> fallback.mapNotNull { it.jsonPrimitiveOrNull()?.content }
            is JsonObject -> fallback["instruments"]?.jsonArray?.mapNotNull { it.jsonPrimitiveOrNull()?.content } ?: emptyList()
            else -> emptyList()
        }
    }

    suspend fun fetchStrikes(exchange: String, instrument: String): List<Double> {
        val element = requestJson(
            path = "api/scripts/strikes",
            query = mapOf(
                "exchange" to exchange,
                "instrument" to instrument
            )
        )
        val strikes = when (element) {
            is JsonObject -> element["strikes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.doubleOrNull }
            else -> null
        }
        return strikes?.sorted() ?: emptyList()
    }

    suspend fun fetchExpiries(
        exchange: String,
        instrument: String,
        strikePrice: Double?
    ): List<String> {
        val query = mutableMapOf(
            "exchange" to exchange,
            "instrument" to instrument
        )
        strikePrice?.let { query["strikePrice"] = it.toString() }
        val element = requestJson(
            path = "api/scripts/expiries",
            query = query
        )
        val expiries = when (element) {
            is JsonObject -> element["expiries"]?.jsonArray?.mapNotNull { it.jsonPrimitiveOrNull()?.content }
            is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull()?.content }
            else -> null
        }
        return expiries ?: emptyList()
    }

    suspend fun fetchOptionLtp(qualifiedKey: String): Double? {
        val element = requestJson(
            path = "api/mstock/ltp",
            query = mapOf("i" to qualifiedKey)
        )
        val data = element.jsonObject["data"] ?: return null
        val entry = data.jsonObject[qualifiedKey] ?: return null
        return entry.jsonObject["last_price"]?.jsonPrimitive?.doubleOrNull
    }

    suspend fun placeOrder(
        payload: PlaceOrderPayload,
        alreadyExecuted: Boolean
    ): JsonElement {
        val path = if (alreadyExecuted) {
            "api/trades/manual-execute"
        } else {
            "api/trades/trigger-on-price"
        }
        val element = requestJson(
            path = path,
            method = HttpMethod.POST,
            body = json.encodeToJsonElement(PlaceOrderPayload.serializer(), payload)
        )
        return element
    }

    suspend fun addExecutedTrade(payload: PlaceOrderPayload): JsonElement {
        val element = requestJson(
            path = "admin/add-executed-trade",
            method = HttpMethod.POST,
            body = json.encodeToJsonElement(PlaceOrderPayload.serializer(), payload)
        )
        return element
    }

    suspend fun ensureSpotToken(exchange: String, instrument: String): Double? {
        val qualified = when (exchange.uppercase()) {
            "NF" -> "NSE:$instrument"
            "BF" -> "BSE:$instrument"
            "NC" -> "NSE:$instrument"
            "BC" -> "BSE:$instrument"
            else -> "$exchange:$instrument"
        }
        return fetchOptionLtp(qualified)
    }

    fun openLtpWebSocket(listener: WebSocketListener): WebSocket {
        val httpUrl = buildUrl("ws/ltp")
        val scheme = if (httpUrl.isHttps) "wss" else "ws"
        val wsUrl = httpUrl.newBuilder()
            .scheme(scheme)
            .build()
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        return client.newWebSocket(request, listener)
    }

    suspend fun dashboardPing(): Boolean {
        return runCatching {
            val element = requestJson("admin/dashboard-ping", enforceSuccess = false)
            element.jsonObject["status"]?.jsonPrimitive?.content == "ok"
        }.getOrElse { false }
    }

    private suspend fun fetchLoginCsrf(): CsrfToken? {
        val request = Request.Builder()
            .url(buildUrl("admin/login"))
            .get()
            .header("Accept", "text/html")
            .build()
        return withContext(dispatcher) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val html = response.body?.string().orEmpty()
                loginCsrfRegex.find(html)?.groupValues?.getOrNull(1)?.let { token ->
                    CsrfToken("X-CSRF-TOKEN", token)
                }
            }
        }
    }

    private suspend fun ensureCsrf(): CsrfToken {
        csrfToken?.let { return it }
        val element = requestJson("admin/csrf-token")
        val header = element.jsonObject["header"]?.jsonPrimitive?.content ?: "X-CSRF-TOKEN"
        val token = element.jsonObject["token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("CSRF token missing in response")
        return CsrfToken(header, token).also { csrfToken = it }
    }

    private suspend fun requestJson(
        path: String,
        method: HttpMethod = HttpMethod.GET,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
        requireJson: Boolean = true,
        enforceSuccess: Boolean = true
    ): JsonElement {
        val response = request(
            path = path,
            method = method,
            query = query,
            body = body,
            requireJson = requireJson,
            enforceSuccess = enforceSuccess
        )
        val responseBody = response.body?.string().orEmpty()
        if (responseBody.isBlank()) return JsonNull
        return try {
            json.parseToJsonElement(responseBody)
        } catch (e: Exception) {
             if (requireJson) throw e else JsonNull
        }
    }

    private suspend fun request(
        path: String,
        method: HttpMethod,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
        requireJson: Boolean = true,
        enforceSuccess: Boolean = true
    ): Response {
        val url = buildUrl(path, query)
        val builder = Request.Builder().url(url)
        if (requireJson) {
            builder.header("Accept", "application/json")
        }
        builder.header("X-Requested-With", "XMLHttpRequest")

        when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.DELETE -> {
                val token = ensureCsrf()
                builder.header(token.headerName, token.token)
                builder.header("X-CSRF-TOKEN", token.token)
                builder.delete()
            }
            HttpMethod.POST, HttpMethod.PUT -> {
                val token = ensureCsrf()
                builder.header(token.headerName, token.token)
                builder.header("X-CSRF-TOKEN", token.token)
                val mediaType = JSON_MEDIA_TYPE
                val requestBody = (body ?: JsonNull).toString().toRequestBody(mediaType)
                if (method == HttpMethod.POST) {
                    builder.post(requestBody)
                } else {
                    builder.put(requestBody)
                }
            }
        }

        return withContext(dispatcher) {
            try {
                val response = client.newCall(builder.build()).execute()
                if (enforceSuccess && !response.isSuccessful) {
                    val bodyString = response.body?.string().orEmpty()
                    response.close()
                    throw AdminHttpException(response.code, "Request to $path failed", bodyString)
                }
                response
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                throw io
            }
        }
    }

    private fun buildUrl(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val clean = path.trim('/')
        val builder = baseHttpUrl.newBuilder()
        if (clean.isNotEmpty()) {
            clean.split('/').filter { it.isNotBlank() }.forEach { segment ->
                builder.addPathSegment(segment)
            }
        }
        query.forEach { (key, value) ->
            if (!value.isNullOrBlank()) {
                builder.addQueryParameter(key, value)
            }
        }
        return builder.build()
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun JsonElement?.jsonArrayOrEmpty(): JsonArray =
        (this as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? =
        this as? JsonPrimitive

    private fun JsonArray.toAppUsers(): List<AppUser> =
        mapNotNull {
            runCatching { json.decodeFromJsonElement(AppUser.serializer(), it) }.getOrNull()
        }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val loginCsrfRegex =
            Regex("""name="_csrf"\s+value="([^"]+)"""", RegexOption.IGNORE_CASE)
    }
}

private enum class HttpMethod {
    GET, POST, PUT, DELETE
}

package com.sharekhan.admin.data.repository

import com.sharekhan.admin.data.model.AppUser
import com.sharekhan.admin.data.model.BrokerDetails
import com.sharekhan.admin.data.model.BrokerSummary
import com.sharekhan.admin.data.model.LtpSnapshot
import com.sharekhan.admin.data.model.LtpQuote
import com.sharekhan.admin.data.model.PageResponse
import com.sharekhan.admin.data.model.PlaceOrderPayload
import com.sharekhan.admin.data.model.TradingRequest
import com.sharekhan.admin.data.model.TriggeredTrade
import com.sharekhan.admin.data.model.UpdateTargetsRequest
import com.sharekhan.admin.data.model.RefreshResult
import com.sharekhan.admin.data.model.StrategyStartPayload
import com.sharekhan.admin.data.model.StrategySubscription
import com.sharekhan.admin.data.model.StrategyTemplate
import com.sharekhan.admin.data.model.TradeAnalytics
import com.sharekhan.admin.data.preferences.AdminPreferences
import com.sharekhan.admin.data.remote.AdminApiClient
import com.sharekhan.admin.data.remote.AdminHttpException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

class AdminRepository(
    private val preferences: AdminPreferences,
    private val json: Json = defaultJson,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    data class Session(
        val baseUrl: String,
        val username: String,
        val client: AdminApiClient
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val baseUrl: Flow<String> = preferences.baseUrl
    val lastUsername: Flow<String?> = preferences.lastUsername

    suspend fun login(baseUrl: String, username: String, password: String) {
        withContext(dispatcher) {
            val client = AdminApiClient(baseUrl, json, dispatcher)
            client.login(username, password)
            preferences.saveRememberedLogin(baseUrl, username, password)
            _session.value = Session(baseUrl, username, client)
        }
    }

    suspend fun autoLogin(): Boolean {
        val savedLogin = preferences.rememberedLogin() ?: return false
        return try {
            withContext(dispatcher) {
            val client = AdminApiClient(savedLogin.baseUrl, json, dispatcher)
            client.login(savedLogin.username, savedLogin.password)
            _session.value = Session(savedLogin.baseUrl, savedLogin.username, client)
            true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun logout() {
        withContext(dispatcher) {
            _session.value?.client?.logout()
            _session.value = null
            preferences.clearRememberedLogin()
        }
    }

    fun isLoggedIn(): Boolean = _session.value != null

    suspend fun fetchUsers(): List<AppUser> = withClient { it.fetchUsers() }

    suspend fun createUser(username: String, password: String, customerId: Long?, notes: String?): AppUser =
        withClient { it.createUser(username, password, customerId, notes) }

    suspend fun fetchTradingRequests(userId: Long?, scope: String, page: Int, size: Int): PageResponse<TradingRequest> =
        withClient { it.fetchTradingRequests(userId, scope, page, size) }

    suspend fun triggerRequest(requestId: Long, brokerCredentialsId: Long?): String =
        withClient { it.triggerRequest(requestId, brokerCredentialsId) }

    suspend fun cancelRequest(requestId: Long, userId: Long?): String =
        withClient { it.cancelRequest(requestId, userId) }

    suspend fun updateRequest(requestId: Long, update: UpdateTargetsRequest): TradingRequest =
        withClient { it.updateRequest(requestId, update) }

    suspend fun fetchExecutedTrades(
        userId: Long?,
        statuses: List<String>,
        page: Int,
        size: Int,
        scope: String
    ): PageResponse<TriggeredTrade> =
        withClient { it.fetchExecutedTrades(userId, statuses, page, size, scope) }

    suspend fun updateExecution(executionId: Long, update: UpdateTargetsRequest): TriggeredTrade =
        withClient { it.updateExecution(executionId, update) }

    suspend fun moveStopLossToCost(tradeId: Long) = withClient { it.moveStopLossToCost(tradeId) }
    suspend fun squareOff(tradeId: Long) = withClient { it.squareOff(tradeId) }
    suspend fun modifyExitOrder(tradeId: Long, price: Double?, orderStatus: String?) =
        withClient { it.modifyExitOrder(tradeId, price, orderStatus) }

    suspend fun fetchAnalyticsSources(userId: Long, scope: String): List<String> =
        withClient { it.fetchAnalyticsSources(userId, scope) }
    suspend fun fetchAnalytics(userId: Long, scope: String, from: String?, to: String?, symbol: String?, sources: List<String>, intraday: Boolean, ai: Boolean): TradeAnalytics =
        withClient { it.fetchAnalytics(userId, scope, from, to, symbol, sources, intraday, ai) }
    suspend fun fetchStrategyTemplates(): List<StrategyTemplate> = withClient { it.fetchStrategyTemplates() }
    suspend fun fetchStrategySubscriptions(userId: Long): List<StrategySubscription> = withClient { it.fetchStrategySubscriptions(userId) }
    suspend fun startStrategy(payload: StrategyStartPayload): StrategySubscription = withClient { it.startStrategy(payload) }
    suspend fun cancelStrategy(id: Long) = withClient { it.cancelStrategy(id) }
    suspend fun refreshScriptMaster(mStock: Boolean): RefreshResult = withClient { it.refreshScriptMaster(mStock) }

    suspend fun fetchBrokers(userId: Long): List<BrokerSummary> =
        withClient { it.fetchBrokers(userId) }

    suspend fun fetchBrokerDetails(brokerId: Long): BrokerDetails =
        withClient { it.fetchBrokerDetails(brokerId) }

    suspend fun saveBroker(brokerId: Long?, userId: Long?, body: JsonElement): BrokerSummary =
        withClient { it.saveBroker(brokerId, userId, body) }

    suspend fun deleteBroker(brokerId: Long) =
        withClient { it.deleteBroker(brokerId) }

    suspend fun fetchInstruments(exchange: String): List<String> =
        withClient { it.fetchInstruments(exchange) }

    suspend fun fetchStrikes(exchange: String, instrument: String): List<Double> =
        withClient { it.fetchStrikes(exchange, instrument) }

    suspend fun fetchExpiries(
        exchange: String,
        instrument: String,
        strikePrice: Double?
    ): List<String> = withClient { it.fetchExpiries(exchange, instrument, strikePrice) }

    suspend fun fetchOptionLtp(key: String): Double? =
        withClient { it.fetchOptionLtp(key) }

    suspend fun fetchLtpByScripCode(scripCode: Int): LtpQuote? =
        withClient { it.fetchLtpByScripCode(scripCode) }

    suspend fun placeOrder(payload: PlaceOrderPayload, alreadyExecuted: Boolean): JsonElement =
        withClient { it.placeOrder(payload, alreadyExecuted) }

    suspend fun ensureDashboard(): Boolean =
        withClient { it.dashboardPing() }

    fun observeLtp(): Flow<LtpSnapshot> = callbackFlow {
        val session = _session.value
        if (session == null) {
            close(AdminHttpException(401, "Not authenticated"))
            return@callbackFlow
        }

        val listener = object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val snapshot = parseLtpSnapshot(text)
                if (snapshot != null) {
                    trySend(snapshot)
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(
                webSocket: okhttp3.WebSocket,
                t: Throwable,
                response: okhttp3.Response?
            ) {
                close(t)
            }
        }

        val webSocket = session.client.openLtpWebSocket(listener)
        awaitClose {
            webSocket.close(1000, null)
        }
    }

    private fun parseLtpSnapshot(message: String): LtpSnapshot? {
        val element = runCatching { json.parseToJsonElement(message) }.getOrNull() ?: return null
        val obj = element.jsonObject
        val lastPrice = obj["ltp"]?.jsonPrimitive?.doubleOrNull
            ?: obj["last_price"]?.jsonPrimitive?.doubleOrNull
            ?: return null
        val scripCode = obj["scripCode"]?.jsonPrimitive?.intOrNull()
            ?: obj["scrip"]?.jsonPrimitive?.intOrNull()
            ?: obj["ScripCode"]?.jsonPrimitive?.intOrNull()
        val qualified = obj["i"]?.jsonPrimitive?.contentOrNull()
        val key = qualified ?: scripCode?.toString()
            ?: obj["key"]?.jsonPrimitive?.contentOrNull()
            ?: return null
        return LtpSnapshot(
            key = key,
            lastPrice = lastPrice,
            scripCode = scripCode,
            qualifiedKey = qualified
        )
    }

    private suspend fun <T> withClient(block: suspend (AdminApiClient) -> T): T {
        val session = _session.value ?: throw AdminHttpException(401, "Not authenticated")
        return withContext(dispatcher) { block(session.client) }
    }

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

private fun JsonPrimitive.intOrNull(): Int? = longOrNull?.toIntOrNull()
private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

private fun Long?.toIntOrNull(): Int? = this?.let {
    if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null
}

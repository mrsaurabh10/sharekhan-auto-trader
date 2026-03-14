package com.sharekhan.admin.data.repository

import com.sharekhan.admin.data.model.AppUser
import com.sharekhan.admin.data.model.BrokerDetails
import com.sharekhan.admin.data.model.BrokerSummary
import com.sharekhan.admin.data.model.PageResponse
import com.sharekhan.admin.data.model.PlaceOrderPayload
import com.sharekhan.admin.data.model.TradingRequest
import com.sharekhan.admin.data.model.TriggeredTrade
import com.sharekhan.admin.data.model.UpdateTargetsRequest
import com.sharekhan.admin.data.preferences.AdminPreferences
import com.sharekhan.admin.data.remote.AdminApiClient
import com.sharekhan.admin.data.remote.AdminHttpException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
            preferences.saveBaseUrl(baseUrl)
            preferences.saveLastUsername(username)
            _session.value = Session(baseUrl, username, client)
        }
    }

    suspend fun logout() {
        withContext(dispatcher) {
            _session.value?.client?.logout()
            _session.value = null
        }
    }

    fun isLoggedIn(): Boolean = _session.value != null

    suspend fun fetchUsers(): List<AppUser> = withClient { it.fetchUsers() }

    suspend fun createUser(username: String, customerId: Long?, notes: String?): AppUser =
        withClient { it.createUser(username, customerId, notes) }

    suspend fun fetchTradingRequests(userId: Long?): List<TradingRequest> =
        withClient { it.fetchTradingRequests(userId) }

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
        size: Int
    ): PageResponse<TriggeredTrade> =
        withClient { it.fetchExecutedTrades(userId, statuses, page, size) }

    suspend fun updateExecution(executionId: Long, update: UpdateTargetsRequest): TriggeredTrade =
        withClient { it.updateExecution(executionId, update) }

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

    suspend fun placeOrder(payload: PlaceOrderPayload, alreadyExecuted: Boolean): JsonElement =
        withClient { it.placeOrder(payload, alreadyExecuted) }

    suspend fun ensureDashboard(): Boolean =
        withClient { it.dashboardPing() }

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


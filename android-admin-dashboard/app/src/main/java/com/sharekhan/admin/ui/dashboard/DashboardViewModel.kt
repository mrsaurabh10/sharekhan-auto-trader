package com.sharekhan.admin.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sharekhan.admin.data.model.AppUser
import com.sharekhan.admin.data.model.BrokerDetails
import com.sharekhan.admin.data.model.BrokerSummary
import com.sharekhan.admin.data.model.PageResponse
import com.sharekhan.admin.data.model.PlaceOrderPayload
import com.sharekhan.admin.data.model.TradingRequest
import com.sharekhan.admin.data.model.TriggeredTrade
import com.sharekhan.admin.data.model.UpdateTargetsRequest
import com.sharekhan.admin.data.repository.AdminRepository
import com.sharekhan.admin.ui.state.UiState
import com.sharekhan.admin.ui.state.getOrNull
import java.text.DecimalFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class DashboardTab {
    ORDER, REQUESTS, EXECUTED, BROKERS
}

data class PaginationState(
    val page: Int = 0,
    val totalPages: Int = 0,
    val isFirst: Boolean = true,
    val isLast: Boolean = true
)

data class PlaceOrderFormState(
    val exchange: String = "",
    val instrument: String = "",
    val instrumentOptions: List<String> = emptyList(),
    val isFetchingInstruments: Boolean = false,
    val strike: String = "",
    val strikeOptions: List<String> = emptyList(),
    val isFetchingStrikes: Boolean = false,
    val expiry: String = "",
    val expiryOptions: List<String> = emptyList(),
    val isFetchingExpiries: Boolean = false,
    val optionType: String = "CE",
    val quantity: String = "1",
    val entryPrice: String = "",
    val stopLoss: String = "",
    val target1: String = "",
    val target2: String = "",
    val target3: String = "",
    val intraday: Boolean = false,
    val tslEnabled: Boolean = false,
    val useSpotPrice: Boolean = false,
    val useSpotForEntry: Boolean = false,
    val useSpotForSl: Boolean = false,
    val useSpotForTarget: Boolean = false,
    val spotScripCode: String = "",
    val alreadyExecuted: Boolean = false,
    val isSubmitting: Boolean = false,
    val formError: String? = null,
    val resultMessage: String? = null
)

data class BrokerDialogState(
    val brokerId: Long? = null,
    val brokerName: String = "",
    val customerId: String = "",
    val apiKey: String = "",
    val brokerUsername: String = "",
    val brokerPassword: String = "",
    val clientCode: String = "",
    val totpSecret: String = "",
    val secretKey: String = "",
    val active: Boolean = true,
    val isSaving: Boolean = false
) {
    val isNew: Boolean get() = brokerId == null
}

class DashboardViewModel(
    private val repository: AdminRepository
) : ViewModel() {

    private val _usersState = MutableStateFlow<UiState<List<AppUser>>>(UiState.Idle)
    val usersState: StateFlow<UiState<List<AppUser>>> = _usersState.asStateFlow()

    private val _selectedUser = MutableStateFlow<AppUser?>(null)
    val selectedUser: StateFlow<AppUser?> = _selectedUser.asStateFlow()

    private val _requestsState = MutableStateFlow<UiState<List<TradingRequest>>>(UiState.Idle)
    val requestsState: StateFlow<UiState<List<TradingRequest>>> = _requestsState.asStateFlow()

    private val _executedState =
        MutableStateFlow<UiState<PageResponse<TriggeredTrade>>>(UiState.Idle)
    val executedState: StateFlow<UiState<PageResponse<TriggeredTrade>>> = _executedState.asStateFlow()

    private val _executedPagination = MutableStateFlow(PaginationState())
    val executedPagination: StateFlow<PaginationState> = _executedPagination.asStateFlow()

    private val _brokersState = MutableStateFlow<UiState<List<BrokerSummary>>>(UiState.Idle)
    val brokersState: StateFlow<UiState<List<BrokerSummary>>> = _brokersState.asStateFlow()

    private val _placeOrderState = MutableStateFlow(PlaceOrderFormState())
    val placeOrderState: StateFlow<PlaceOrderFormState> = _placeOrderState.asStateFlow()

    private val _selectedTab = MutableStateFlow(DashboardTab.ORDER)
    val selectedTab: StateFlow<DashboardTab> = _selectedTab.asStateFlow()

    private val _statusFilter = MutableStateFlow(DEFAULT_STATUS_FILTER)
    val statusFilter: StateFlow<Set<String>> = _statusFilter.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages

    private val _brokerDialog = MutableStateFlow<BrokerDialogState?>(null)
    val brokerDialog: StateFlow<BrokerDialogState?> = _brokerDialog.asStateFlow()

    private var instrumentJob: Job? = null
    private var strikeJob: Job? = null
    private var expiryJob: Job? = null

    fun loadInitial() {
        if (_usersState.value is UiState.Loading || _usersState.value is UiState.Success) return
        refreshUsers()
    }

    fun refreshUsers() {
        _usersState.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repository.fetchUsers() }
                .onSuccess { users ->
                    _usersState.value = UiState.Success(users.sortedBy { it.username.lowercase() })
                    if (_selectedUser.value == null && users.isNotEmpty()) {
                        selectUser(users.first())
                    } else if (_selectedUser.value != null) {
                        val refreshed = users.find { it.id == _selectedUser.value?.id }
                        refreshed?.let { selectUser(it) }
                    }
                }
                .onFailure { ex ->
                    _usersState.value = UiState.Error(ex.readableMessage())
                }
        }
    }

    fun createUser(username: String, customerId: String) {
        if (username.isBlank()) {
            viewModelScope.launch { _messages.send("Username is required") }
            return
        }
        val customerIdNumber = customerId.toLongOrNull()
        viewModelScope.launch {
            runCatching { repository.createUser(username, customerIdNumber, null) }
                .onSuccess {
                    _messages.send("User $username created")
                    refreshUsers()
                }
                .onFailure { ex -> _messages.send(ex.readableMessage()) }
        }
    }

    fun selectTab(tab: DashboardTab) {
        _selectedTab.value = tab
    }

    fun selectUser(user: AppUser) {
        _selectedUser.value = user
        // Reset place order form user-specific data
        _placeOrderState.update {
            it.copy(
                resultMessage = null,
                formError = null
            )
        }
        refreshUserScopedData()
    }

    private fun refreshUserScopedData() {
        refreshTradingRequests()
        refreshExecutedTrades(resetPage = true)
        refreshBrokers()
    }

    fun refreshTradingRequests() {
        val userId = _selectedUser.value?.id ?: return
        _requestsState.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repository.fetchTradingRequests(userId) }
                .onSuccess { requests -> _requestsState.value = UiState.Success(requests) }
                .onFailure { ex -> _requestsState.value = UiState.Error(ex.readableMessage()) }
        }
    }

    fun refreshExecutedTrades(resetPage: Boolean = false) {
        val userId = _selectedUser.value?.id ?: return
        val page = if (resetPage) 0 else _executedPagination.value.page
        val statuses = _statusFilter.value.toList()
        _executedState.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repository.fetchExecutedTrades(userId, statuses, page, EXEC_PAGE_SIZE) }
                .onSuccess { response ->
                    _executedState.value = UiState.Success(response)
                    _executedPagination.value = PaginationState(
                        page = response.number,
                        totalPages = response.totalPages,
                        isFirst = response.first,
                        isLast = response.last
                    )
                }
                .onFailure { ex ->
                    _executedState.value = UiState.Error(ex.readableMessage())
                }
        }
    }

    fun loadNextExecutedPage() {
        if (_executedPagination.value.isLast) return
        _executedPagination.update { it.copy(page = it.page + 1) }
        refreshExecutedTrades(resetPage = false)
    }

    fun loadPreviousExecutedPage() {
        if (_executedPagination.value.isFirst) return
        _executedPagination.update { it.copy(page = maxOf(0, it.page - 1)) }
        refreshExecutedTrades(resetPage = false)
    }

    fun updateStatusFilter(status: String, selected: Boolean) {
        val updated = _statusFilter.value.toMutableSet()
        if (selected) updated.add(status) else updated.remove(status)
        if (updated.isEmpty()) {
            // keep at least one status selected
            return
        }
        _statusFilter.value = updated
        refreshExecutedTrades(resetPage = true)
    }

    fun refreshBrokers() {
        val userId = _selectedUser.value?.id ?: return
        _brokersState.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repository.fetchBrokers(userId) }
                .onSuccess { brokers -> _brokersState.value = UiState.Success(brokers) }
                .onFailure { ex -> _brokersState.value = UiState.Error(ex.readableMessage()) }
        }
    }

    fun triggerRequest(request: TradingRequest, brokerId: Long?) {
        viewModelScope.launch {
            runCatching { repository.triggerRequest(request.id, brokerId) }
                .onSuccess {
                    _messages.send("Request #${request.id} triggered")
                    refreshUserScopedData()
                }
                .onFailure { ex -> _messages.send(ex.readableMessage()) }
        }
    }

    fun cancelRequest(request: TradingRequest) {
        val userId = _selectedUser.value?.id ?: return
        viewModelScope.launch {
            runCatching { repository.cancelRequest(request.id, userId) }
                .onSuccess {
                    _messages.send("Request #${request.id} cancelled")
                    refreshTradingRequests()
                }
                .onFailure { ex -> _messages.send(ex.readableMessage()) }
        }
    }

    fun prefillFromRequest(request: TradingRequest) {
        _placeOrderState.update {
            it.copy(
                exchange = request.exchange.orEmpty(),
                instrument = request.symbol.orEmpty(),
                strike = request.strikePrice?.let { strikeFormatter.format(it) } ?: "",
                expiry = request.expiry.orEmpty(),
                optionType = request.optionType.orEmpty().ifBlank { "CE" },
                quantity = request.quantity?.toString() ?: "1",
                entryPrice = request.entryPrice?.toString() ?: "",
                stopLoss = request.stopLoss?.toString() ?: "",
                target1 = request.target1?.toString() ?: "",
                target2 = request.target2?.toString() ?: "",
                target3 = request.target3?.toString() ?: "",
                intraday = request.intraday == true,
                tslEnabled = request.tslEnabled == true,
                useSpotPrice = request.useSpotPrice == true,
                useSpotForEntry = request.useSpotForEntry == true,
                useSpotForSl = request.useSpotForSl == true,
                useSpotForTarget = request.useSpotForTarget == true,
                spotScripCode = request.spotScripCode?.toString() ?: "",
                resultMessage = null,
                formError = null
            )
        }
    }

    fun onExchangeChanged(value: String) {
        _placeOrderState.update {
            it.copy(
                exchange = value,
                instrument = "",
                instrumentOptions = emptyList(),
                strike = "",
                strikeOptions = emptyList(),
                expiry = "",
                expiryOptions = emptyList(),
                isFetchingInstruments = value.isNotBlank()
            )
        }
        if (value.isNotBlank()) {
            instrumentJob?.cancel()
            instrumentJob = viewModelScope.launch {
                runCatching { repository.fetchInstruments(value) }
                    .onSuccess { options ->
                        _placeOrderState.update { state ->
                            state.copy(
                                instrumentOptions = options,
                                isFetchingInstruments = false
                            )
                        }
                    }
                    .onFailure { ex ->
                        _placeOrderState.update {
                            it.copy(
                                isFetchingInstruments = false,
                                formError = ex.readableMessage()
                            )
                        }
                    }
            }
        }
    }

    fun onInstrumentChanged(value: String) {
        _placeOrderState.update {
            it.copy(
                instrument = value,
                strike = "",
                strikeOptions = emptyList(),
                expiry = "",
                expiryOptions = emptyList(),
                isFetchingStrikes = value.isNotBlank() && requiresOptionFlow(it.exchange)
            )
        }
        val exchange = _placeOrderState.value.exchange
        if (value.isNotBlank() && requiresOptionFlow(exchange)) {
            strikeJob?.cancel()
            strikeJob = viewModelScope.launch {
                runCatching { repository.fetchStrikes(exchange, value) }
                    .onSuccess { strikes ->
                        val formatted = strikes.map { strikeFormatter.format(it) }
                        _placeOrderState.update { state ->
                            state.copy(
                                strikeOptions = formatted,
                                isFetchingStrikes = false
                            )
                        }
                    }
                    .onFailure { ex ->
                        _placeOrderState.update {
                            it.copy(
                                isFetchingStrikes = false,
                                formError = ex.readableMessage()
                            )
                        }
                    }
            }
        } else {
            _placeOrderState.update { it.copy(isFetchingStrikes = false) }
        }
    }

    fun onStrikeChanged(value: String) {
        _placeOrderState.update {
            it.copy(
                strike = value,
                expiry = "",
                expiryOptions = emptyList(),
                isFetchingExpiries = value.isNotBlank() && requiresOptionFlow(it.exchange)
            )
        }
        val exchange = _placeOrderState.value.exchange
        val instrument = _placeOrderState.value.instrument
        if (value.isNotBlank() && requiresOptionFlow(exchange)) {
            val strike = value.toDoubleOrNull()
            expiryJob?.cancel()
            expiryJob = viewModelScope.launch {
                runCatching { repository.fetchExpiries(exchange, instrument, strike) }
                    .onSuccess { expiries ->
                        _placeOrderState.update { state ->
                            state.copy(
                                expiryOptions = expiries,
                                isFetchingExpiries = false
                            )
                        }
                    }
                    .onFailure { ex ->
                        _placeOrderState.update {
                            it.copy(
                                isFetchingExpiries = false,
                                formError = ex.readableMessage()
                            )
                        }
                    }
            }
        } else {
            _placeOrderState.update { it.copy(isFetchingExpiries = false) }
        }
    }

    fun updatePlaceOrderField(field: (PlaceOrderFormState) -> PlaceOrderFormState) {
        _placeOrderState.update(field)
    }

    fun placeOrder() {
        val userId = _selectedUser.value?.id
        if (userId == null) {
            viewModelScope.launch { _messages.send("Select a user first") }
            return
        }
        val state = _placeOrderState.value
        val validationError = validatePlaceOrder(state)
        if (validationError != null) {
            _placeOrderState.update { it.copy(formError = validationError) }
            return
        }
        val exchange = state.exchange
        val instrument = state.instrument
        val strikeValue = state.strike.toDoubleOrNull()
        val expiry = state.expiry.ifBlank { null }
        val optionType = if (requiresOptionFlow(exchange)) state.optionType else null
        val quantity = state.quantity.toLongOrNull()
        val entryPrice = state.entryPrice.toDouble()
        val stopLoss = state.stopLoss.toDouble()
        val target1 = state.target1.toDoubleOrNull()
        val target2 = state.target2.toDoubleOrNull()
        val target3 = state.target3.toDoubleOrNull()
        val spotScripCode = state.spotScripCode.toIntOrNull()

        val brokerId = resolvePreferredBrokerId()

        val payload = PlaceOrderPayload(
            exchange = exchange,
            instrument = instrument,
            strikePrice = if (requiresOptionFlow(exchange)) strikeValue else null,
            expiry = if (requiresOptionFlow(exchange)) expiry else null,
            optionType = optionType,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            target3 = target3,
            quantity = quantity,
            intraday = state.intraday,
            tslEnabled = state.tslEnabled,
            useSpotPrice = state.useSpotPrice,
            useSpotForEntry = state.useSpotForEntry,
            useSpotForSl = state.useSpotForSl,
            useSpotForTarget = state.useSpotForTarget,
            spotScripCode = spotScripCode,
            trailingSl = null,
            userId = userId,
            brokerCredentialsId = brokerId
        )

        _placeOrderState.update { it.copy(isSubmitting = true, formError = null, resultMessage = null) }
        viewModelScope.launch {
            runCatching { repository.placeOrder(payload, state.alreadyExecuted) }
                .onSuccess {
                    _placeOrderState.update {
                        it.copy(
                            isSubmitting = false,
                            resultMessage = if (state.alreadyExecuted) "Executed trade recorded" else "Order submitted"
                        )
                    }
                    refreshUserScopedData()
                }
                .onFailure { ex ->
                    _placeOrderState.update {
                        it.copy(
                            isSubmitting = false,
                            formError = ex.readableMessage()
                        )
                    }
                }
        }
    }

    private fun validatePlaceOrder(state: PlaceOrderFormState): String? {
        if (state.exchange.isBlank()) return "Select an exchange"
        if (state.instrument.isBlank()) return "Select an instrument"
        if (state.entryPrice.isBlank() || state.entryPrice.toDoubleOrNull() == null) return "Entry price is required"
        if (state.stopLoss.isBlank() || state.stopLoss.toDoubleOrNull() == null) return "Stop loss is required"
        if (requiresOptionFlow(state.exchange)) {
            if (state.strike.isBlank() || state.strike.toDoubleOrNull() == null) return "Strike price is required"
            if (state.expiry.isBlank()) return "Expiry is required"
        }
        if (state.quantity.isNotBlank() && state.quantity.toLongOrNull() == null) return "Quantity must be numeric"
        if (state.spotScripCode.isNotBlank() && state.spotScripCode.toIntOrNull() == null) return "Spot Scrip code must be numeric"
        return null
    }

    private fun resolvePreferredBrokerId(): Long? {
        val brokers = _brokersState.value.getOrNull().orEmpty()
        val activeSharekhan = brokers.firstOrNull { it.brokerName.equals("sharekhan", ignoreCase = true) && it.active }
        if (activeSharekhan != null) return activeSharekhan.id
        val activeAny = brokers.firstOrNull { it.active }
        if (activeAny != null) return activeAny.id
        val fallbackSharekhan = brokers.firstOrNull { it.brokerName.equals("sharekhan", ignoreCase = true) }
        return fallbackSharekhan?.id ?: brokers.firstOrNull()?.id
    }

    fun openAddBrokerDialog() {
        _brokerDialog.value = BrokerDialogState()
    }

    fun openEditBrokerDialog(summary: BrokerSummary, details: BrokerDetails?) {
        _brokerDialog.value = BrokerDialogState(
            brokerId = summary.id,
            brokerName = summary.brokerName.orEmpty(),
            customerId = (details?.customerId ?: summary.customerId)?.toString() ?: "",
            apiKey = details?.apiKey.orEmpty(),
            brokerUsername = details?.brokerUsername.orEmpty(),
            brokerPassword = details?.brokerPassword.orEmpty(),
            clientCode = details?.clientCode.orEmpty(),
            totpSecret = details?.totpSecret.orEmpty(),
            secretKey = details?.secretKey.orEmpty(),
            active = details?.active ?: summary.active
        )
    }

    fun loadBrokerDetailsAndEdit(summary: BrokerSummary) {
        _brokerDialog.value = BrokerDialogState(
            brokerId = summary.id,
            brokerName = summary.brokerName.orEmpty(),
            customerId = summary.customerId?.toString() ?: "",
            active = summary.active
        )
        viewModelScope.launch {
            runCatching { repository.fetchBrokerDetails(summary.id) }
                .onSuccess { details ->
                    _brokerDialog.value = BrokerDialogState(
                        brokerId = details.id,
                        brokerName = details.brokerName.orEmpty(),
                        customerId = details.customerId?.toString() ?: "",
                        apiKey = details.apiKey.orEmpty(),
                        brokerUsername = details.brokerUsername.orEmpty(),
                        brokerPassword = details.brokerPassword.orEmpty(),
                        clientCode = details.clientCode.orEmpty(),
                        totpSecret = details.totpSecret.orEmpty(),
                        secretKey = details.secretKey.orEmpty(),
                        active = details.active
                    )
                }
                .onFailure { ex ->
                    _messages.send("Failed to load broker: ${ex.readableMessage()}")
                }
        }
    }

    fun updateBrokerDialog(transform: (BrokerDialogState) -> BrokerDialogState) {
        _brokerDialog.update { state -> state?.let(transform) }
    }

    fun closeBrokerDialog() {
        _brokerDialog.value = null
    }

    fun saveBrokerDialog() {
        val dialog = _brokerDialog.value ?: return
        val userId = _selectedUser.value?.id ?: return
        val brokerName = dialog.brokerName.trim()
        if (brokerName.isBlank()) {
            viewModelScope.launch { _messages.send("Broker name is required") }
            return
        }
        val payload = buildJsonObject {
            put("brokerName", brokerName)
            if (dialog.customerId.isNotBlank()) put("customerId", dialog.customerId)
            if (dialog.apiKey.isNotBlank()) put("apiKey", dialog.apiKey)
            if (dialog.brokerUsername.isNotBlank()) put("brokerUsername", dialog.brokerUsername)
            if (dialog.brokerPassword.isNotBlank()) put("brokerPassword", dialog.brokerPassword)
            if (dialog.clientCode.isNotBlank()) put("clientCode", dialog.clientCode)
            if (dialog.totpSecret.isNotBlank()) put("totpSecret", dialog.totpSecret)
            if (dialog.secretKey.isNotBlank()) put("secretKey", dialog.secretKey)
            put("active", dialog.active)
        }
        _brokerDialog.update { it?.copy(isSaving = true) }
        viewModelScope.launch {
            runCatching { repository.saveBroker(dialog.brokerId, if (dialog.isNew) userId else null, payload) }
                .onSuccess {
                    _messages.send("Broker saved")
                    _brokerDialog.value = null
                    refreshBrokers()
                }
                .onFailure { ex ->
                    _brokerDialog.update { it?.copy(isSaving = false) }
                    _messages.send(ex.readableMessage())
                }
        }
    }

    fun deleteBroker(id: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteBroker(id) }
                .onSuccess {
                    _messages.send("Broker deleted")
                    refreshBrokers()
                }
                .onFailure { ex -> _messages.send(ex.readableMessage()) }
        }
    }

    companion object {
        private val DEFAULT_STATUS_FILTER = setOf(
            "EXECUTED",
            "EXIT_ORDER_PLACED",
            "EXITED_SUCCESS"
        )
        private const val EXEC_PAGE_SIZE = 10
        private val strikeFormatter = DecimalFormat("#.##")
        val SUPPORTED_EXCHANGES = listOf("NF", "BF", "MX", "NC", "BC")

        fun factory(repository: AdminRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(repository) as T
                }
            }
    }
}

private fun Throwable.readableMessage(): String =
    message ?: javaClass.simpleName

private fun requiresOptionFlow(exchange: String): Boolean {
    val upper = exchange.uppercase()
    return upper != "NC" && upper != "BC"
}

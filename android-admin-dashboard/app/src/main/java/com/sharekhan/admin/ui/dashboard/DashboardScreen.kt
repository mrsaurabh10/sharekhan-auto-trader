package com.sharekhan.admin.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sharekhan.admin.data.model.AppUser
import com.sharekhan.admin.data.model.BrokerSummary
import com.sharekhan.admin.data.model.PageResponse
import com.sharekhan.admin.data.model.TradingRequest
import com.sharekhan.admin.data.model.TriggeredTrade
import com.sharekhan.admin.data.model.TradeAnalytics
import com.sharekhan.admin.ui.state.UiState
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onSignOut: () -> Unit
) {
    val usersState by viewModel.usersState.collectAsState()
    val selectedUser by viewModel.selectedUser.collectAsState()
    val requestsState by viewModel.requestsState.collectAsState()
    val executedState by viewModel.executedState.collectAsState()
    val brokersState by viewModel.brokersState.collectAsState()
    val placeOrderState by viewModel.placeOrderState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val pagination by viewModel.executedPagination.collectAsState()
    val requestPagination by viewModel.requestPagination.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val brokerDialog by viewModel.brokerDialog.collectAsState()
    val ltpPrices by viewModel.ltpPrices.collectAsState()
    val tradeScope by viewModel.tradeScope.collectAsState()
    val analyticsState by viewModel.analyticsState.collectAsState()
    val analyticsForm by viewModel.analyticsForm.collectAsState()
    val strategyState by viewModel.strategyState.collectAsState()
    val tradeEditDialog by viewModel.tradeEditDialog.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    LaunchedEffect(viewModel.messages) {
        viewModel.messages.consumeAsFlow().collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                UserDrawerContent(
                    usersState = usersState,
                    selectedUser = selectedUser,
                    onSelectUser = { user ->
                        viewModel.selectUser(user)
                        scope.launch { drawerState.close() }
                    },
                    onRefreshUsers = { viewModel.refreshUsers() },
                    onCreateUser = { username, password, customerId ->
                        viewModel.createUser(username, password, customerId)
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val title = selectedUser?.username ?: "Select a user"
                        Text("Admin Dashboard • $title")
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open users drawer")
                        }
                    },
                    actions = {
                        IconButton(onClick = onSignOut) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                        }
                        IconButton(onClick = { viewModel.refreshUsers() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh users")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    DashboardTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { viewModel.selectTab(tab) },
                            label = { Text(tabLabel(tab)) },
                            icon = {
                                Icon(
                                    imageVector = tabIcon(tab),
                                    contentDescription = tabLabel(tab)
                                )
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TradeScope.entries.forEach { item ->
                        ElevatedFilterChip(selected = tradeScope == item, onClick = { viewModel.updateTradeScope(item) }, label = { Text(if (item == TradeScope.OWN) "Own" else "Simulator") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) { when (selectedTab) {
                    DashboardTab.ORDER -> PlaceOrderScreen(
                        state = placeOrderState,
                        onExchangeChanged = viewModel::onExchangeChanged,
                        onInstrumentChanged = viewModel::onInstrumentChanged,
                        onStrikeChanged = viewModel::onStrikeChanged,
                        onFieldChange = viewModel::updatePlaceOrderField,
                        onSubmit = viewModel::placeOrder,
                        onRefreshMStock = { viewModel.refreshScriptMaster(true) },
                        onRefreshSharekhan = { viewModel.refreshScriptMaster(false) }
                    )

                    DashboardTab.REQUESTS -> RequestsScreen(
                        requestsState = requestsState,
                        ltpPrices = ltpPrices,
                        onRefresh = viewModel::refreshTradingRequests,
                        onTrigger = { request -> viewModel.triggerRequest(request, null) },
                        onCancel = viewModel::cancelRequest,
                        onEdit = viewModel::editRequest,
                        onPrefill = viewModel::prefillFromRequest,
                        pagination = requestPagination,
                        onPrev = viewModel::loadPreviousRequestPage,
                        onNext = viewModel::loadNextRequestPage
                    )

                    DashboardTab.EXECUTED -> ExecutedScreen(
                        executedState = executedState,
                        pagination = pagination,
                        ltpPrices = ltpPrices,
                        onRefresh = { viewModel.refreshExecutedTrades(resetPage = true) },
                        onPrev = viewModel::loadPreviousExecutedPage,
                        onNext = viewModel::loadNextExecutedPage,
                        statusFilter = statusFilter,
                        onStatusChanged = viewModel::updateStatusFilter,
                        onSelectAllStatuses = viewModel::selectAllStatuses,
                        onResetStatuses = viewModel::resetStatusFilter,
                        onMoveSl = viewModel::moveStopLossToCost,
                        onSquareOff = viewModel::squareOff,
                        onEdit = viewModel::editExecution
                    )

                    DashboardTab.ANALYTICS -> AnalyticsScreen(
                        state = analyticsState, form = analyticsForm,
                        onFormChange = viewModel::updateAnalyticsForm,
                        onToggleSource = viewModel::toggleAnalyticsSource,
                        onRefresh = { viewModel.refreshAnalytics(false) },
                        onGemini = { viewModel.refreshAnalytics(true) }
                    )

                    DashboardTab.STRATEGIES -> StrategiesScreen(
                        state = strategyState,
                        onChange = viewModel::updateStrategyState,
                        onRefresh = viewModel::refreshStrategies,
                        onStart = viewModel::startStrategy,
                        onCancel = viewModel::cancelStrategy
                    )

                    DashboardTab.BROKERS -> BrokersScreen(
                        brokersState = brokersState,
                        onRefresh = viewModel::refreshBrokers,
                        onAdd = viewModel::openAddBrokerDialog,
                        onEdit = { summary -> viewModel.loadBrokerDetailsAndEdit(summary) },
                        onDelete = viewModel::deleteBroker
                    )
                } }
            }
        }
    }

    brokerDialog?.let { dialogState ->
        BrokerEditorDialog(
            state = dialogState,
            onDismiss = viewModel::closeBrokerDialog,
            onValueChange = viewModel::updateBrokerDialog,
            onSave = viewModel::saveBrokerDialog
        )
    }
    tradeEditDialog?.let { dialogState ->
        TradeEditorDialog(dialogState, viewModel::closeTradeEditDialog, viewModel::updateTradeEditDialog, viewModel::saveTradeEditDialog)
    }
}

@Composable
private fun UserDrawerContent(
    usersState: UiState<List<AppUser>>,
    selectedUser: AppUser?,
    onSelectUser: (AppUser) -> Unit,
    onRefreshUsers: () -> Unit,
    onCreateUser: (String, String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Admin Users",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider()
        AddUserForm(onCreateUser = onCreateUser)
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("All Users", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = onRefreshUsers, label = { Text("Refresh") })
        }
        when (usersState) {
            UiState.Loading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is UiState.Error -> Text(
                text = usersState.message,
                color = MaterialTheme.colorScheme.error
            )

            is UiState.Success -> {
                val users = usersState.data
                if (users.isEmpty()) {
                    Text("No users found.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(users, key = { it.id }) { user ->
                            OutlinedButton(
                                onClick = { onSelectUser(user) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = user.username,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (user.id == selectedUser?.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                    user.customerId?.let {
                                        Text(
                                            text = "Customer ID: $it",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            UiState.Idle -> Unit
        }
    }
}

@Composable
private fun AddUserForm(
    onCreateUser: (String, String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add User", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = customerId,
            onValueChange = { customerId = it },
            label = { Text("Customer ID (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Button(
            onClick = {
                onCreateUser(username.trim(), password, customerId.trim())
                username = ""
                password = ""
                customerId = ""
            },
            enabled = username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create")
        }
    }
}

@Composable
private fun PlaceOrderScreen(
    state: PlaceOrderFormState,
    onExchangeChanged: (String) -> Unit,
    onInstrumentChanged: (String) -> Unit,
    onStrikeChanged: (String) -> Unit,
    onFieldChange: ((PlaceOrderFormState) -> PlaceOrderFormState) -> Unit,
    onSubmit: () -> Unit,
    onRefreshMStock: () -> Unit,
    onRefreshSharekhan: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Place Order", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefreshMStock) { Text("Refresh mStock") }
                OutlinedButton(onClick = onRefreshSharekhan) { Text("Refresh Sharekhan") }
            }
            DropdownField(
                label = "Exchange",
                value = state.exchange,
                options = DashboardViewModel.SUPPORTED_EXCHANGES,
                isLoading = state.isFetchingInstruments,
                onValueChange = onExchangeChanged
            )
            DropdownField(
                label = "Instrument",
                value = state.instrument,
                options = state.instrumentOptions,
                isLoading = state.isFetchingInstruments,
                onValueChange = onInstrumentChanged
            )

            val requiresOptionFlow = state.exchange.uppercase() !in listOf("NC", "BC")

            if (requiresOptionFlow) {
                DropdownField(
                    label = "Strike",
                    value = state.strike,
                    options = state.strikeOptions,
                    isLoading = state.isFetchingStrikes,
                    onValueChange = onStrikeChanged
                )
                DropdownField(
                    label = "Expiry",
                    value = state.expiry,
                    options = state.expiryOptions,
                    isLoading = state.isFetchingExpiries,
                    onValueChange = { value -> onFieldChange { it.copy(expiry = value) } }
                )
                DropdownField(
                    label = "Option Type",
                    value = state.optionType,
                    options = listOf("CE", "PE"),
                    isLoading = false,
                    onValueChange = { value -> onFieldChange { it.copy(optionType = value) } }
                )
            }

            OutlinedTextField(
                value = state.quantity,
                onValueChange = { value -> onFieldChange { it.copy(quantity = value) } },
                label = { Text("Quantity (lots)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.entryPrice,
                onValueChange = { value -> onFieldChange { it.copy(entryPrice = value) } },
                label = { Text("Entry Price") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.stopLoss,
                onValueChange = { value -> onFieldChange { it.copy(stopLoss = value) } },
                label = { Text("Stop Loss") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.target1,
                    onValueChange = { value -> onFieldChange { it.copy(target1 = value) } },
                    label = { Text("Target 1") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.target2,
                    onValueChange = { value -> onFieldChange { it.copy(target2 = value) } },
                    label = { Text("Target 2") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.target3,
                    onValueChange = { value -> onFieldChange { it.copy(target3 = value) } },
                    label = { Text("Target 3") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = state.spotScripCode,
                onValueChange = { value -> onFieldChange { it.copy(spotScripCode = value) } },
                label = { Text("Spot Scrip Code (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            CheckboxRow("Intraday", state.intraday) { checked -> onFieldChange { it.copy(intraday = checked) } }
            CheckboxRow("Enable TSL", state.tslEnabled) { checked -> onFieldChange { it.copy(tslEnabled = checked) } }
            CheckboxRow("Use Spot Price (All)", state.useSpotPrice) { checked -> onFieldChange { it.copy(useSpotPrice = checked) } }
            CheckboxRow("Spot for Entry", state.useSpotForEntry) { checked -> onFieldChange { it.copy(useSpotForEntry = checked) } }
            CheckboxRow("Spot for Stop Loss", state.useSpotForSl) { checked -> onFieldChange { it.copy(useSpotForSl = checked) } }
            CheckboxRow("Spot for Target", state.useSpotForTarget) { checked -> onFieldChange { it.copy(useSpotForTarget = checked) } }
            CheckboxRow("Already Executed?", state.alreadyExecuted) { checked -> onFieldChange { it.copy(alreadyExecuted = checked) } }

            state.formError?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            state.resultMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.tertiary)
            }

            Button(
                onClick = onSubmit,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.alreadyExecuted) "Record Execution" else "Place Order")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    isLoading: Boolean,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (options.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No options available") },
                        onClick = {},
                        enabled = false
                    )
                } else {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .height(16.dp)
                    .width(16.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun RequestsScreen(
    requestsState: UiState<List<TradingRequest>>,
    ltpPrices: Map<String, Double>,
    onRefresh: () -> Unit,
    onTrigger: (TradingRequest) -> Unit,
    onCancel: (TradingRequest) -> Unit,
    onEdit: (TradingRequest) -> Unit,
    onPrefill: (TradingRequest) -> Unit,
    pagination: PaginationState,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trading Requests", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            AssistChip(onClick = onRefresh, label = { Text("Refresh") })
        }
        when (requestsState) {
            UiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is UiState.Error -> Text(
                text = requestsState.message,
                color = MaterialTheme.colorScheme.error
            )

            is UiState.Success -> {
                val requests = requestsState.data
                if (requests.isEmpty()) {
                    Text("No pending requests.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(requests, key = { it.id }) { request ->
                            val liveLtp = ltpPrices.ltpFor(
                                scripCode = request.scripCode,
                                exchange = request.exchange,
                                symbol = request.symbol
                            )
                            RequestCard(
                                request = request,
                                liveLtp = liveLtp,
                                onTrigger = onTrigger,
                                onCancel = onCancel,
                                onEdit = onEdit,
                                onPrefill = onPrefill
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPrev, enabled = !pagination.isFirst) { Text("Previous") }
                    Text("Page ${pagination.page + 1} of ${maxOf(pagination.totalPages, 1)}")
                    OutlinedButton(onClick = onNext, enabled = !pagination.isLast) { Text("Next") }
                }
            }

            UiState.Idle -> Unit
        }
    }
}

@Composable
private fun RequestCard(
    request: TradingRequest,
    liveLtp: Double?,
    onTrigger: (TradingRequest) -> Unit,
    onCancel: (TradingRequest) -> Unit,
    onEdit: (TradingRequest) -> Unit,
    onPrefill: (TradingRequest) -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Request #${request.id}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Symbol: ${request.symbol ?: "-"} (${request.exchange ?: "-"})")
            Text("Entry: ${request.entryPrice ?: "-"}  SL: ${request.stopLoss ?: "-"}  Qty: ${request.quantity ?: "-"}")
            Text("Status: ${request.status ?: "-"}")
            Text("Live LTP: ${formatPrice(liveLtp)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEdit(request) }) { Text("Edit") }
                OutlinedButton(onClick = { onTrigger(request) }) {
                    Text("Trigger")
                }
                OutlinedButton(onClick = { onCancel(request) }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
                TextButton(onClick = { onPrefill(request) }) {
                    Text("Prefill")
                }
            }
        }
    }
}

@Composable
private fun ExecutedScreen(
    executedState: UiState<PageResponse<TriggeredTrade>>,
    pagination: PaginationState,
    ltpPrices: Map<String, Double>,
    onRefresh: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    statusFilter: Set<String>,
    onStatusChanged: (String, Boolean) -> Unit,
    onSelectAllStatuses: () -> Unit,
    onResetStatuses: () -> Unit,
    onMoveSl: (TriggeredTrade) -> Unit,
    onSquareOff: (TriggeredTrade) -> Unit,
    onEdit: (TriggeredTrade) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Executed Trades", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            AssistChip(onClick = onRefresh, label = { Text("Refresh") })
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val allSelected = statusFilter.containsAll(EXECUTED_STATUSES)
            ElevatedFilterChip(
                selected = allSelected,
                onClick = {
                    if (allSelected) {
                        onResetStatuses()
                    } else {
                        onSelectAllStatuses()
                    }
                },
                label = { Text("All") }
            )
            EXECUTED_STATUSES.forEach { status ->
                ElevatedFilterChip(
                    selected = statusFilter.contains(status),
                    onClick = { onStatusChanged(status, !statusFilter.contains(status)) },
                    label = { Text(status) }
                )
            }
        }
        when (executedState) {
            UiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is UiState.Error -> Text(
                text = executedState.message,
                color = MaterialTheme.colorScheme.error
            )

            is UiState.Success -> {
                val trades = executedState.data.content
                if (trades.isEmpty()) {
                    Text("No executed trades for selected filters.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(trades, key = { it.id }) { trade ->
                            val liveLtp = ltpPrices.ltpFor(
                                scripCode = trade.scripCode,
                                exchange = trade.exchange,
                                symbol = trade.symbol
                            )
                            val livePnl = computeLivePnl(trade, liveLtp)
                            ExecutedTradeCard(trade, liveLtp, livePnl, onMoveSl, onSquareOff, onEdit)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = onPrev, enabled = !pagination.isFirst) {
                        Text("Previous")
                    }
                    Text("Page ${pagination.page + 1} of ${maxOf(pagination.totalPages, 1)}")
                    OutlinedButton(onClick = onNext, enabled = !pagination.isLast) {
                        Text("Next")
                    }
                }
            }

            UiState.Idle -> Unit
        }
    }
}

private val EXECUTED_STATUSES = listOf(
    "EXECUTED",
    "EXIT_ORDER_PLACED",
    "TARGET_ORDER_PLACED",
    "EXITED_SUCCESS",
    "EXIT_FAILED",
    "REJECTED"
)

@Composable
private fun ExecutedTradeCard(
    trade: TriggeredTrade,
    liveLtp: Double?,
    livePnl: Double?,
    onMoveSl: (TriggeredTrade) -> Unit,
    onSquareOff: (TriggeredTrade) -> Unit,
    onEdit: (TriggeredTrade) -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Trade #${trade.id} • ${trade.symbol ?: "-"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Exchange: ${trade.exchange ?: "-"}  Qty: ${trade.quantity ?: "-"}  Status: ${trade.status ?: "-"}")
            Text("Scope: ${trade.tradeScope ?: if (trade.simulator) "SIMULATOR" else "OWN"}  Broker: ${trade.brokerName ?: "-"}")
            Text("Entry: ${trade.entryPrice ?: "-"}  Exit: ${trade.exitPrice ?: "-"}")
            trade.pnl?.let { 
                Text("PnL: $it", color = if (it >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)) 
            }
            trade.totalTradeCost?.let { Text("Costs: ${formatPrice(it)}  Effective PnL: ${formatPrice(trade.effectivePnl ?: trade.pnl?.minus(it))}") }
            Text("Live LTP: ${formatPrice(liveLtp)}")
            livePnl?.let { 
                Text("Live PnL: ${formatPrice(it)}", color = if (it >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)) 
            }
            if (trade.status?.uppercase() in setOf("EXECUTED", "EXIT_ORDER_PLACED", "TARGET_ORDER_PLACED")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(trade) }) { Text("Edit") }
                    OutlinedButton(onClick = { onMoveSl(trade) }) { Text("SL to Cost") }
                    Button(onClick = { onSquareOff(trade) }) { Text("Square Off") }
                }
            }
            trade.triggeredAt?.let { Text("Triggered: $it") }
            trade.exitedAt?.let { Text("Exited: $it") }
        }
    }
}

@Composable
private fun AnalyticsScreen(
    state: UiState<TradeAnalytics>,
    form: AnalyticsFormState,
    onFormChange: ((AnalyticsFormState) -> AnalyticsFormState) -> Unit,
    onToggleSource: (String) -> Unit,
    onRefresh: () -> Unit,
    onGemini: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trade Analytics", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.from, { value -> onFormChange { it.copy(from = value) } }, label = { Text("From (YYYY-MM-DD)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(form.to, { value -> onFormChange { it.copy(to = value) } }, label = { Text("To (YYYY-MM-DD)") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(form.symbol, { value -> onFormChange { it.copy(symbol = value) } }, label = { Text("Symbol (optional)") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            form.sourceOptions.forEach { source ->
                ElevatedFilterChip(selected = source in form.selectedSources, onClick = { onToggleSource(source) }, label = { Text(source) })
            }
        }
        CheckboxRow("Intraday only", form.intraday) { checked -> onFormChange { it.copy(intraday = checked) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text("Refresh") }
            OutlinedButton(onClick = onGemini) { Text("Gemini Analysis") }
        }
        when (state) {
            UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is UiState.Success -> {
                val data = state.data
                val s = data.summary
                ElevatedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Summary", fontWeight = FontWeight.Bold)
                    Text("Realized: ${formatPrice(s.realizedPnl)}  Costs: ${formatPrice(s.totalTradeCost)}")
                    Text("Effective PnL: ${formatPrice(s.effectiveRealizedPnl)}", color = pnlColor(s.effectiveRealizedPnl))
                    Text("Closed: ${s.totalClosedTrades}  Won: ${s.winningTrades}  Lost: ${s.losingTrades}")
                    Text("Win rate: ${formatPrice(s.winRate)}%  Profit factor: ${formatPrice(s.profitFactor)}")
                    Text("Max fund use: ${formatPrice(s.maxFundUseAtTime)}  Open: ${s.openTrades}")
                } }
                if (data.byDay.isNotEmpty()) {
                    Text("Equity / Daily PnL", fontWeight = FontWeight.Bold)
                    data.byDay.forEach { day -> Text("${day.date}: ${formatPrice(day.realizedPnl)}  cumulative ${formatPrice(day.cumulativeRealizedPnl)}", color = pnlColor(day.realizedPnl)) }
                }
                if (data.bySymbol.isNotEmpty()) {
                    Text("By Symbol", fontWeight = FontWeight.Bold)
                    data.bySymbol.forEach { row -> Text("${row.symbol}: ${formatPrice(row.realizedPnl)} (${row.closedCount} trades, ${formatPrice(row.winRate)}% win)") }
                }
                data.backtest?.let { bt ->
                    Text("Backtest: 1m vs 5m", fontWeight = FontWeight.Bold)
                    Text("Actual ${formatPrice(bt.summary.actualPnl)} • 1m ${formatPrice(bt.summary.oneMinutePnl)} • 5m ${formatPrice(bt.summary.fiveMinutePnl)} • re-entry ${formatPrice(bt.summary.oneMinuteReentryPnl)}")
                    bt.byDay.forEach { row -> Text("${row.date}: actual ${formatPrice(row.actualPnl)}, 1m ${formatPrice(row.oneMinutePnl)}, 5m ${formatPrice(row.fiveMinutePnl)}") }
                }
                data.aiNarrative?.takeIf { it.isNotBlank() }?.let { Text("Gemini", fontWeight = FontWeight.Bold); Text(it) }
            }
            UiState.Idle -> Text("Choose filters and refresh.")
        }
    }
}

@Composable
private fun StrategiesScreen(
    state: StrategyState,
    onChange: ((StrategyState) -> StrategyState) -> Unit,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onCancel: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Strategies", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp)); AssistChip(onClick = onRefresh, label = { Text("Refresh") })
        }
        DropdownField("Template", state.selectedTemplateId, state.templates.map { it.id }, state.loading) { value -> onChange { it.copy(selectedTemplateId = value) } }
        OutlinedTextField(state.symbol, { value -> onChange { it.copy(symbol = value.uppercase()) } }, label = { Text("Symbol") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.lots, { value -> onChange { it.copy(lots = value) } }, label = { Text("Lots") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        CheckboxRow("Intraday", state.intraday) { checked -> onChange { it.copy(intraday = checked) } }
        Button(onClick = onStart, enabled = !state.loading) { Text("Start Strategy") }
        HorizontalDivider()
        state.subscriptions.forEach { row ->
            ElevatedCard { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${row.id} ${row.templateId} • ${row.symbol}", fontWeight = FontWeight.Bold)
                Text("${row.status} • ${row.lots} lots${row.lastEvaluatedAt?.let { " • $it" } ?: ""}")
                row.lastMessage?.let { Text(it) }
                row.generatedTradeRequestId?.let { Text("Generated request #$it") }
                if (row.status.equals("ACTIVE", true)) OutlinedButton(onClick = { onCancel(row.id) }) { Text("Cancel") }
            } }
        }
    }
}

private fun pnlColor(value: Double?): Color = if (value == null || value == 0.0) Color.Unspecified else if (value > 0) Color(0xFF16804B) else Color(0xFFC53939)

@Composable
private fun BrokersScreen(
    brokersState: UiState<List<BrokerSummary>>,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (BrokerSummary) -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brokers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            AssistChip(onClick = onRefresh, label = { Text("Refresh") })
            Spacer(Modifier.width(12.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add Broker")
            }
        }
        when (brokersState) {
            UiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is UiState.Error -> Text(
                text = brokersState.message,
                color = MaterialTheme.colorScheme.error
            )

            is UiState.Success -> {
                val brokers = brokersState.data
                if (brokers.isEmpty()) {
                    Text("No brokers configured for this user.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(brokers, key = { it.id }) { broker ->
                            BrokerCard(
                                broker = broker,
                                onEdit = { onEdit(broker) },
                                onDelete = { onDelete(broker.id) }
                            )
                        }
                    }
                }
            }

            UiState.Idle -> Unit
        }
    }
}

@Composable
private fun BrokerCard(
    broker: BrokerSummary,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Broker #${broker.id} • ${broker.brokerName ?: "-"}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text("Customer ID: ${broker.customerId ?: "-"}  Active: ${if (broker.active) "Yes" else "No"}")
            broker.clientCode?.let { Text("Client Code: $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Text("Edit")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun BrokerEditorDialog(
    state: BrokerDialogState,
    onDismiss: () -> Unit,
    onValueChange: ((BrokerDialogState) -> BrokerDialogState) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.isSaving) {
                Text(if (state.isNew) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text(if (state.isNew) "Add Broker" else "Edit Broker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.brokerName,
                    onValueChange = { value -> onValueChange { it.copy(brokerName = value) } },
                    label = { Text("Broker Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.customerId,
                    onValueChange = { value -> onValueChange { it.copy(customerId = value) } },
                    label = { Text("Customer ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = { value -> onValueChange { it.copy(apiKey = value) } },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.brokerUsername,
                    onValueChange = { value -> onValueChange { it.copy(brokerUsername = value) } },
                    label = { Text("Broker Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.brokerPassword,
                    onValueChange = { value -> onValueChange { it.copy(brokerPassword = value) } },
                    label = { Text("Broker Password") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.clientCode,
                    onValueChange = { value -> onValueChange { it.copy(clientCode = value) } },
                    label = { Text("Client Code") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.totpSecret,
                    onValueChange = { value -> onValueChange { it.copy(totpSecret = value) } },
                    label = { Text("TOTP Secret") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.secretKey,
                    onValueChange = { value -> onValueChange { it.copy(secretKey = value) } },
                    label = { Text("Secret Key") },
                    modifier = Modifier.fillMaxWidth()
                )
                CheckboxRow(
                    label = "Active",
                    checked = state.active,
                    onCheckedChange = { checked -> onValueChange { it.copy(active = checked) } }
                )
                CheckboxRow(
                    label = "Trading Enabled",
                    checked = state.tradingEnabled,
                    onCheckedChange = { checked -> onValueChange { it.copy(tradingEnabled = checked) } }
                )
                CheckboxRow(
                    label = "Default for Orders",
                    checked = state.defaultForOrders,
                    onCheckedChange = { checked -> onValueChange { it.copy(defaultForOrders = checked, tradingEnabled = if (checked) true else it.tradingEnabled) } }
                )
            }
        }
    )
}

@Composable
private fun TradeEditorDialog(
    state: TradeEditDialogState,
    onDismiss: () -> Unit,
    onChange: ((TradeEditDialogState) -> TradeEditDialogState) -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.execution) "Edit Executed Trade #${state.id}" else "Edit Request #${state.id}") },
        confirmButton = { Button(onClick = onSave, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EditNumberField("Entry Price", state.entryPrice) { value -> onChange { it.copy(entryPrice = value) } }
                EditNumberField("Stop Loss", state.stopLoss) { value -> onChange { it.copy(stopLoss = value) } }
                EditNumberField("Target 1", state.target1) { value -> onChange { it.copy(target1 = value) } }
                EditNumberField("Target 2", state.target2) { value -> onChange { it.copy(target2 = value) } }
                EditNumberField("Target 3", state.target3) { value -> onChange { it.copy(target3 = value) } }
                EditNumberField("Quantity", state.quantity) { value -> onChange { it.copy(quantity = value) } }
                CheckboxRow("Intraday", state.intraday) { checked -> onChange { it.copy(intraday = checked) } }
            }
        }
    )
}

@Composable
private fun EditNumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Preview
@Composable
private fun AddUserPreview() {
    AddUserForm(onCreateUser = { _, _, _ -> })
}

private fun tabLabel(tab: DashboardTab): String = when (tab) {
    DashboardTab.ORDER -> "Place Order"
    DashboardTab.REQUESTS -> "Requests"
    DashboardTab.EXECUTED -> "Executed"
    DashboardTab.ANALYTICS -> "Analytics"
    DashboardTab.STRATEGIES -> "Strategies"
    DashboardTab.BROKERS -> "Brokers"
}

private fun tabIcon(tab: DashboardTab): ImageVector = when (tab) {
    DashboardTab.ORDER -> Icons.Default.ShoppingCart
    DashboardTab.REQUESTS -> Icons.AutoMirrored.Filled.List
    DashboardTab.EXECUTED -> Icons.Default.History
    DashboardTab.ANALYTICS -> Icons.Default.History
    DashboardTab.STRATEGIES -> Icons.AutoMirrored.Filled.Send
    DashboardTab.BROKERS -> Icons.Default.People
}

private fun Map<String, Double>.ltpFor(
    scripCode: Int?,
    exchange: String?,
    symbol: String?
): Double? {
    scripCode?.let { code ->
        val byCode = get(code.toString())
        if (byCode != null) return byCode
    }
    val qualified = buildQualifiedKey(exchange, symbol)?.uppercase() ?: return null
    return get(qualified)
}

private fun buildQualifiedKey(exchange: String?, symbol: String?): String? {
    if (exchange.isNullOrBlank() || symbol.isNullOrBlank()) return null
    val normalizedExchange = when (exchange.uppercase()) {
        "NF" -> "NFO"
        "BF" -> "BFO"
        "NC" -> "NSE"
        "BC" -> "BSE"
        else -> exchange.uppercase()
    }
    return "$normalizedExchange:${symbol.trim().uppercase()}"
}

private fun computeLivePnl(trade: TriggeredTrade, liveLtp: Double?): Double? {
    if (liveLtp == null) return null
    val status = trade.status?.uppercase(Locale.US) ?: return null
    if (status !in setOf("EXECUTED", "EXIT_ORDER_PLACED", "TARGET_ORDER_PLACED")) return null
    val entryPrice = trade.actualEntryPrice ?: trade.entryPrice ?: return null
    val quantity = trade.quantity?.toDouble() ?: return null
    return quantity * (liveLtp - entryPrice)
}

private fun formatPrice(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

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
import com.sharekhan.admin.ui.state.UiState
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel
) {
    val usersState by viewModel.usersState.collectAsState()
    val selectedUser by viewModel.selectedUser.collectAsState()
    val requestsState by viewModel.requestsState.collectAsState()
    val executedState by viewModel.executedState.collectAsState()
    val brokersState by viewModel.brokersState.collectAsState()
    val placeOrderState by viewModel.placeOrderState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val pagination by viewModel.executedPagination.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val brokerDialog by viewModel.brokerDialog.collectAsState()
    val ltpPrices by viewModel.ltpPrices.collectAsState()

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
                    onCreateUser = { username, customerId ->
                        viewModel.createUser(username, customerId)
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (selectedTab) {
                    DashboardTab.ORDER -> PlaceOrderScreen(
                        state = placeOrderState,
                        onExchangeChanged = viewModel::onExchangeChanged,
                        onInstrumentChanged = viewModel::onInstrumentChanged,
                        onStrikeChanged = viewModel::onStrikeChanged,
                        onFieldChange = viewModel::updatePlaceOrderField,
                        onSubmit = viewModel::placeOrder
                    )

                    DashboardTab.REQUESTS -> RequestsScreen(
                        requestsState = requestsState,
                        ltpPrices = ltpPrices,
                        onRefresh = viewModel::refreshTradingRequests,
                        onTrigger = { request -> viewModel.triggerRequest(request, null) },
                        onCancel = viewModel::cancelRequest,
                        onPrefill = viewModel::prefillFromRequest
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
                        onResetStatuses = viewModel::resetStatusFilter
                    )

                    DashboardTab.BROKERS -> BrokersScreen(
                        brokersState = brokersState,
                        onRefresh = viewModel::refreshBrokers,
                        onAdd = viewModel::openAddBrokerDialog,
                        onEdit = { summary -> viewModel.loadBrokerDetailsAndEdit(summary) },
                        onDelete = viewModel::deleteBroker
                    )
                }
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
}

@Composable
private fun UserDrawerContent(
    usersState: UiState<List<AppUser>>,
    selectedUser: AppUser?,
    onSelectUser: (AppUser) -> Unit,
    onRefreshUsers: () -> Unit,
    onCreateUser: (String, String) -> Unit
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
    onCreateUser: (String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add User", style = MaterialTheme.typography.titleSmall)
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
                onCreateUser(username.trim(), customerId.trim())
                username = ""
                customerId = ""
            },
            enabled = username.isNotBlank(),
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
    onSubmit: () -> Unit
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
    onPrefill: (TradingRequest) -> Unit
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
                        modifier = Modifier.fillMaxSize(),
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
                                onPrefill = onPrefill
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
private fun RequestCard(
    request: TradingRequest,
    liveLtp: Double?,
    onTrigger: (TradingRequest) -> Unit,
    onCancel: (TradingRequest) -> Unit,
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
    onResetStatuses: () -> Unit
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
                            ExecutedTradeCard(trade, liveLtp, livePnl)
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
    "EXITED_SUCCESS",
    "EXIT_FAILED",
    "REJECTED"
)

@Composable
private fun ExecutedTradeCard(trade: TriggeredTrade, liveLtp: Double?, livePnl: Double?) {
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
            Text("Entry: ${trade.entryPrice ?: "-"}  Exit: ${trade.exitPrice ?: "-"}")
            trade.pnl?.let { 
                Text("PnL: $it", color = if (it >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)) 
            }
            Text("Live LTP: ${formatPrice(liveLtp)}")
            livePnl?.let { 
                Text("Live PnL: ${formatPrice(it)}", color = if (it >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)) 
            }
            trade.triggeredAt?.let { Text("Triggered: $it") }
            trade.exitedAt?.let { Text("Exited: $it") }
        }
    }
}

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
            }
        }
    )
}

@Preview
@Composable
private fun AddUserPreview() {
    AddUserForm(onCreateUser = { _, _ -> })
}

private fun tabLabel(tab: DashboardTab): String = when (tab) {
    DashboardTab.ORDER -> "Place Order"
    DashboardTab.REQUESTS -> "Requests"
    DashboardTab.EXECUTED -> "Executed"
    DashboardTab.BROKERS -> "Brokers"
}

private fun tabIcon(tab: DashboardTab): ImageVector = when (tab) {
    DashboardTab.ORDER -> Icons.Default.ShoppingCart
    DashboardTab.REQUESTS -> Icons.AutoMirrored.Filled.List
    DashboardTab.EXECUTED -> Icons.Default.History
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
    if (status != "EXECUTED" && status != "EXIT_ORDER_PLACED") return null
    val entryPrice = trade.actualEntryPrice ?: trade.entryPrice ?: return null
    val quantity = trade.quantity?.toDouble() ?: return null
    return quantity * (liveLtp - entryPrice)
}

private fun formatPrice(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

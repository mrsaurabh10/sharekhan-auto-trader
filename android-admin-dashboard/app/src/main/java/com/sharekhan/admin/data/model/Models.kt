package com.sharekhan.admin.data.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppUser(
    val id: Long = 0,
    val username: String = "",
    val customerId: Long? = null,
    val notes: String? = null
)

@Serializable
data class TradingRequest(
    val id: Long = 0,
    val symbol: String? = null,
    val scripCode: Int? = null,
    val exchange: String? = null,
    val instrumentType: String? = null,
    val strikePrice: Double? = null,
    val optionType: String? = null,
    val expiry: String? = null,
    val entryPrice: Double? = null,
    val stopLoss: Double? = null,
    val target1: Double? = null,
    val target2: Double? = null,
    val target3: Double? = null,
    val quantity: Long? = null,
    val trailingSl: Double? = null,
    val tslEnabled: Boolean? = null,
    val useSpotPrice: Boolean? = null,
    val useSpotForEntry: Boolean? = null,
    val useSpotForSl: Boolean? = null,
    val useSpotForTarget: Boolean? = null,
    val spotScripCode: Int? = null,
    val intraday: Boolean? = null,
    val brokerCredentialsId: Long? = null,
    val appUserId: Long? = null,
    val brokerName: String? = null,
    val tradeScope: String? = null,
    val simulator: Boolean = false,
    val source: String? = null,
    @SerialName("status")
    val status: String? = null,
    val createdAt: LocalDateTime? = null
)

@Serializable
data class TriggeredTrade(
    val id: Long = 0,
    val symbol: String? = null,
    val scripCode: Int? = null,
    val exchange: String? = null,
    val instrumentType: String? = null,
    val strikePrice: Double? = null,
    val optionType: String? = null,
    val expiry: String? = null,
    val quantity: Long? = null,
    val lots: Int? = null,
    val originalLots: Int? = null,
    val entryPrice: Double? = null,
    val actualEntryPrice: Double? = null,
    val stopLoss: Double? = null,
    val target1: Double? = null,
    val target2: Double? = null,
    val target3: Double? = null,
    val trailingSl: Double? = null,
    val tslEnabled: Boolean? = null,
    val useSpotPrice: Boolean? = null,
    val useSpotForEntry: Boolean? = null,
    val useSpotForSl: Boolean? = null,
    val useSpotForTarget: Boolean? = null,
    val spotScripCode: Int? = null,
    val orderId: String? = null,
    val exitOrderId: String? = null,
    val exitOrderPlacedAt: LocalDateTime? = null,
    val exitReason: String? = null,
    val intraday: Boolean? = null,
    val status: String? = null,
    val triggeredAt: LocalDateTime? = null,
    val entryAt: LocalDateTime? = null,
    val exitedAt: LocalDateTime? = null,
    val exitPrice: Double? = null,
    val pnl: Double? = null,
    val appUserId: Long? = null,
    val brokerCredentialsId: Long? = null,
    val brokerName: String? = null,
    val tradeScope: String? = null,
    val simulator: Boolean = false,
    val source: String? = null,
    val brokerage: Double? = null,
    val governmentTaxes: Double? = null,
    val totalTradeCost: Double? = null,
    val effectivePnl: Double? = null
)

@Serializable
data class BrokerSummary(
    val id: Long = 0,
    val brokerName: String? = null,
    val customerId: Long? = null,
    val appUserId: Long? = null,
    val clientCode: String? = null,
    val hasApiKey: Boolean = false,
    val active: Boolean = false,
    val tradingEnabled: Boolean = true,
    val defaultForOrders: Boolean = false
)

@Serializable
data class BrokerDetails(
    val id: Long = 0,
    val brokerName: String? = null,
    val customerId: Long? = null,
    val appUserId: Long? = null,
    val apiKey: String? = null,
    val brokerUsername: String? = null,
    val brokerPassword: String? = null,
    val clientCode: String? = null,
    val totpSecret: String? = null,
    val secretKey: String? = null,
    val active: Boolean = false,
    val tradingEnabled: Boolean = true,
    val defaultForOrders: Boolean = false
)

@Serializable
data class PageResponse<T>(
    val content: List<T> = emptyList(),
    val number: Int = 0,
    val size: Int = 0,
    val totalPages: Int = 0,
    val totalElements: Long = 0,
    val first: Boolean = false,
    val last: Boolean = false
)

@Serializable
data class PlaceOrderPayload(
    val exchange: String,
    val instrument: String,
    val strikePrice: Double? = null,
    val expiry: String? = null,
    val optionType: String? = null,
    val entryPrice: Double,
    val stopLoss: Double,
    val target1: Double? = null,
    val target2: Double? = null,
    val target3: Double? = null,
    val quantity: Long? = null,
    val intraday: Boolean = false,
    val tslEnabled: Boolean = false,
    val useSpotPrice: Boolean = false,
    val useSpotForEntry: Boolean = false,
    val useSpotForSl: Boolean = false,
    val useSpotForTarget: Boolean = false,
    val spotScripCode: Int? = null,
    val trailingSl: Double? = null,
    val userId: Long? = null,
    val brokerCredentialsId: Long? = null
)

@Serializable
data class UpdateTargetsRequest(
    val entryPrice: Double? = null,
    val stopLoss: Double? = null,
    val target1: Double? = null,
    val target2: Double? = null,
    val target3: Double? = null,
    val quantity: Long? = null,
    val intraday: Boolean? = null,
    val useSpotPrice: Boolean? = null,
    val useSpotForEntry: Boolean? = null,
    val useSpotForSl: Boolean? = null,
    val useSpotForTarget: Boolean? = null,
    val spotScripCode: Int? = null,
    val userId: Long? = null
)

@Serializable
data class StrategyTemplate(
    val id: String,
    val name: String = id,
    val description: String? = null
)

@Serializable
data class StrategySubscription(
    val id: Long = 0,
    val templateId: String = "",
    val symbol: String = "",
    val lots: Int = 1,
    val intraday: Boolean = true,
    val status: String = "",
    val lastEvaluatedAt: String? = null,
    val lastMessage: String? = null,
    val generatedTradeRequestId: Long? = null
)

@Serializable
data class StrategyStartPayload(
    val templateId: String,
    val symbol: String,
    val lots: Int,
    val intraday: Boolean,
    val userId: Long,
    val brokerCredentialsId: Long? = null,
    val source: String = "strategy:$templateId"
)

@Serializable
data class TradeAnalytics(
    val filters: AnalyticsFilters? = null,
    val summary: AnalyticsSummary = AnalyticsSummary(),
    val bySymbol: List<SymbolAnalytics> = emptyList(),
    val byDay: List<DailyAnalytics> = emptyList(),
    val backtest: BacktestAnalytics? = null,
    val aiNarrative: String? = null
)

@Serializable data class AnalyticsFilters(val from: String? = null, val to: String? = null, val scope: String? = null)
@Serializable data class AnalyticsSummary(
    val realizedPnl: Double? = null,
    val brokerage: Double? = null,
    val governmentTaxes: Double? = null,
    val totalTradeCost: Double? = null,
    val effectiveRealizedPnl: Double? = null,
    val totalClosedTrades: Int = 0,
    val winningTrades: Int = 0,
    val losingTrades: Int = 0,
    val winRate: Double? = null,
    val profitFactor: Double? = null,
    val maxFundUseAtTime: Double? = null,
    val openTrades: Int = 0,
    val rejectedTrades: Int = 0,
    val failedTrades: Int = 0
)
@Serializable data class SymbolAnalytics(val symbol: String = "", val closedCount: Int = 0, val realizedPnl: Double? = null, val winRate: Double? = null, val averagePnl: Double? = null)
@Serializable data class DailyAnalytics(val date: String = "", val closedCount: Int = 0, val realizedPnl: Double? = null, val cumulativeRealizedPnl: Double? = null)
@Serializable data class BacktestAnalytics(val summary: BacktestSummary = BacktestSummary(), val byDay: List<BacktestDailyAnalytics> = emptyList())
@Serializable data class BacktestSummary(
    val totalTrades: Int = 0, val comparableTrades: Int = 0, val actualComparableTrades: Int = 0,
    val failedTrades: Int = 0, val actualPnl: Double? = null, val oneMinutePnl: Double? = null,
    val fiveMinutePnl: Double? = null, val oneMinuteReentryPnl: Double? = null,
    val oneMinuteCloserToActual: Int = 0, val fiveMinuteCloserToActual: Int = 0
)
@Serializable data class BacktestDailyAnalytics(
    val date: String = "", val totalTrades: Int = 0, val comparableTrades: Int = 0,
    val actualPnl: Double? = null, val oneMinutePnl: Double? = null, val fiveMinutePnl: Double? = null,
    val oneMinuteReentryPnl: Double? = null
)

@Serializable
data class RefreshResult(val message: String? = null, val rows: Long? = null)

data class LtpSnapshot(
    val key: String,
    val lastPrice: Double,
    val scripCode: Int? = null,
    val qualifiedKey: String? = null
)

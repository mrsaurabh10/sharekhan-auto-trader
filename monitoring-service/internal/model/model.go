package model

import "fmt"

type Snapshot struct {
	GeneratedAt  string  `json:"generatedAt"`
	Timezone     string  `json:"timezone"`
	ActiveTrades []Trade `json:"activeTrades"`
	ClosedToday  []Trade `json:"closedToday"`
}

type Trade struct {
	ID                      int64    `json:"id"`
	AppUserID               *int64   `json:"appUserId"`
	Symbol                  string   `json:"symbol"`
	ScripCode               *int     `json:"scripCode"`
	SpotScripCode           *int     `json:"spotScripCode"`
	Exchange                string   `json:"exchange"`
	InstrumentType          string   `json:"instrumentType"`
	StrikePrice             *float64 `json:"strikePrice"`
	OptionType              string   `json:"optionType"`
	Expiry                  string   `json:"expiry"`
	Quantity                *int64   `json:"quantity"`
	Lots                    *int     `json:"lots"`
	EntryPrice              *float64 `json:"entryPrice"`
	ActualEntryPrice        *float64 `json:"actualEntryPrice"`
	StopLoss                *float64 `json:"stopLoss"`
	Target1                 *float64 `json:"target1"`
	Target2                 *float64 `json:"target2"`
	Target3                 *float64 `json:"target3"`
	TrailingSL              *float64 `json:"trailingSl"`
	TSLEnabled              *bool    `json:"tslEnabled"`
	UseSpotForEntry         *bool    `json:"useSpotForEntry"`
	UseSpotForSL            *bool    `json:"useSpotForSl"`
	UseSpotForTarget        *bool    `json:"useSpotForTarget"`
	OrderID                 string   `json:"orderId"`
	ExitOrderID             string   `json:"exitOrderId"`
	ExitReason              string   `json:"exitReason"`
	Intraday                *bool    `json:"intraday"`
	Source                  string   `json:"source"`
	Status                  string   `json:"status"`
	TriggeredAt             string   `json:"triggeredAt"`
	EntryAt                 string   `json:"entryAt"`
	ExitOrderPlacedAt       string   `json:"exitOrderPlacedAt"`
	ExitedAt                string   `json:"exitedAt"`
	ExitPrice               *float64 `json:"exitPrice"`
	PnL                     *float64 `json:"pnl"`
	TradeCost               *float64 `json:"tradeCost"`
	EffectivePnL            *float64 `json:"effectivePnl"`
	InstrumentLTP           *float64 `json:"instrumentLtp"`
	InstrumentLTPObservedAt string   `json:"instrumentLtpObservedAt"`
	SpotLTP                 *float64 `json:"spotLtp"`
	SpotLTPObservedAt       string   `json:"spotLtpObservedAt"`
}

func (t Trade) DisplayName() string {
	name := t.Symbol
	if t.StrikePrice != nil {
		name += fmt.Sprintf(" %.2f", *t.StrikePrice)
	}
	if t.OptionType != "" {
		name += " " + t.OptionType
	}
	return name
}

type Advisory struct {
	Key             string
	Type            string
	Severity        string
	Title           string
	Body            string
	CooldownSeconds int64
}

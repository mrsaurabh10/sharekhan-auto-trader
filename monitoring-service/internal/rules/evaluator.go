package rules

import (
	"fmt"
	"math"
	"sort"
	"time"

	"sharekhan-trade-monitor/internal/model"
)

type Config struct {
	StalePriceAfter    time.Duration
	ExitStuckAfter     time.Duration
	ProximityFraction  float64
	MoveToCostFraction float64
}

type Evaluator struct {
	cfg Config
	ist *time.Location
}

func New(cfg Config) *Evaluator {
	return &Evaluator{cfg: cfg, ist: time.FixedZone("IST", 5*60*60+30*60)}
}

func (e *Evaluator) Evaluate(trade model.Trade, now time.Time) []model.Advisory {
	var out []model.Advisory
	if activeForProtection(trade.Status) && (trade.StopLoss == nil || !finitePositive(*trade.StopLoss)) {
		out = append(out, advisory(trade, "missing_protection", "CRITICAL", "🔴 MISSING TRADE PROTECTION",
			"Executed trade has no valid stop-loss. Verify broker protection immediately.", 10*time.Minute))
	}
	price, observedAt := referencePrice(trade, false)
	if price == nil || observedAt.IsZero() {
		if marketOpen(now.In(e.ist)) {
			out = append(out, advisory(trade, "missing_market_data", "CRITICAL", "🔴 MARKET PRICE UNAVAILABLE",
				"No current price is available for this active trade. Price-based advisories are suspended.", 10*time.Minute))
		}
		return append(out, e.exitStuck(trade, now)...)
	}
	if marketOpen(now.In(e.ist)) && now.Sub(observedAt) > e.cfg.StalePriceAfter {
		out = append(out, advisory(trade, "stale_market_data", "CRITICAL", "🔴 STALE MARKET DATA",
			fmt.Sprintf("Latest relevant price %.2f is %s old. Price-based advisories are suspended.", *price, now.Sub(observedAt).Round(time.Second)), 5*time.Minute))
		return append(out, e.exitStuck(trade, now)...)
	}

	if trade.StopLoss != nil && finitePositive(*trade.StopLoss) {
		stop, current := *trade.StopLoss, *price
		if current <= stop {
			out = append(out, advisory(trade, "stop_breached", "CRITICAL", "🔴 STOP-LOSS BREACHED",
				fmt.Sprintf("Current price %.2f is at or below stop-loss %.2f, but trade status is %s. Verify the exit immediately.", current, stop, trade.Status), 3*time.Minute))
		} else if anchor, ok := stopAnchor(trade); ok && current <= stop+(anchor-stop)*e.cfg.ProximityFraction {
			remaining := current - stop
			remainingPercent := remaining / (anchor - stop) * 100
			out = append(out, advisory(trade, "stop_proximity", "HIGH", "🟠 STOP-LOSS NEAR",
				fmt.Sprintf("Current: %.2f\nStop-loss: %.2f\nRemaining: %.2f (%.1f%% of monitoring range)\nAdvisory: Watch closely; the stop-loss is near.",
					current, stop, remaining, remainingPercent), 15*time.Minute))
		}
	}

	targetPrice, targetObservedAt := referencePrice(trade, true)
	if targetPrice != nil && marketOpen(now.In(e.ist)) && (targetObservedAt.IsZero() || now.Sub(targetObservedAt) > e.cfg.StalePriceAfter) {
		targetPrice = nil
	}
	if targetPrice != nil {
		if target := nextTarget(trade, *targetPrice); target != nil {
			if anchor, ok := targetAnchor(trade, *target); ok {
				trigger := target.Price - (target.Price-anchor)*e.cfg.ProximityFraction
				if *targetPrice >= trigger && *targetPrice < target.Price {
					remaining := target.Price - *targetPrice
					coveredPercent := (*targetPrice - anchor) / (target.Price - anchor) * 100
					out = append(out, advisory(trade, fmt.Sprintf("target_%d_proximity", target.Number), "HIGH",
						fmt.Sprintf("🟢 TARGET %d NEAR", target.Number),
						fmt.Sprintf("Current: %.2f\nT%d: %.2f\nRemaining: %.2f\nProgress through monitoring range: %.1f%%\nAdvisory: Target %d is near.",
							*targetPrice, target.Number, target.Price, remaining, coveredPercent, target.Number), 20*time.Minute))
				}
			}
		}
		if trade.EntryPrice != nil &&
			samePriceBasis(trade.UseSpotForEntry, trade.UseSpotForTarget) &&
			samePriceBasis(trade.UseSpotForEntry, trade.UseSpotForSL) &&
			trade.Target1 != nil && trade.StopLoss != nil && *trade.StopLoss < *trade.EntryPrice && *trade.Target1 > *trade.EntryPrice {
			moveTrigger := *trade.EntryPrice + (*trade.Target1-*trade.EntryPrice)*e.cfg.MoveToCostFraction
			if *targetPrice >= moveTrigger {
				out = append(out, advisory(trade, "move_sl_to_cost", "HIGH", "🟠 CONSIDER SL AT COST",
					fmt.Sprintf("Price %.2f has completed %.0f%% of the path to Target 1. Consider moving SL from %.2f to entry %.2f.", *targetPrice, e.cfg.MoveToCostFraction*100, *trade.StopLoss, *trade.EntryPrice), 30*time.Minute))
			}
		}
	}
	return append(out, e.exitStuck(trade, now)...)
}

func (e *Evaluator) exitStuck(trade model.Trade, now time.Time) []model.Advisory {
	if trade.Status != "EXIT_ORDER_PLACED" && trade.Status != "EXIT_TRIGGERED" {
		return nil
	}
	placed := parseLocalTime(trade.ExitOrderPlacedAt, e.ist)
	if placed.IsZero() || now.Sub(placed) < e.cfg.ExitStuckAfter {
		return nil
	}
	return []model.Advisory{advisory(trade, "exit_stuck", "CRITICAL", "🔴 EXIT ORDER STUCK",
		fmt.Sprintf("Exit has remained in %s for %s. Verify broker order status immediately.", trade.Status, now.Sub(placed).Round(time.Second)), 3*time.Minute)}
}

func DailySummary(closed []model.Trade, date string) model.Advisory {
	var gross, costs, net float64
	wins, losses := 0, 0
	for _, t := range closed {
		if t.PnL != nil {
			gross += *t.PnL
			if *t.PnL >= 0 {
				wins++
			} else {
				losses++
			}
		}
		if t.TradeCost != nil {
			costs += *t.TradeCost
		}
		if t.EffectivePnL != nil {
			net += *t.EffectivePnL
		} else if t.PnL != nil {
			net += *t.PnL
			if t.TradeCost != nil {
				net -= *t.TradeCost
			}
		}
	}
	return model.Advisory{
		Key: "daily_summary:" + date, Type: "daily_summary", Severity: "INFO", Title: "📊 DAILY TRADE SUMMARY",
		Body:            fmt.Sprintf("Date: %s\nClosed trades: %d\nWins: %d | Losses: %d\nGross P&L: ₹%.2f\nCosts: ₹%.2f\nNet P&L: ₹%.2f", date, len(closed), wins, losses, gross, costs, net),
		CooldownSeconds: int64(48 * time.Hour / time.Second),
	}
}

func advisory(t model.Trade, kind, severity, title, detail string, cooldown time.Duration) model.Advisory {
	return model.Advisory{
		Key: fmt.Sprintf("trade:%d:%s", t.ID, kind), Type: kind, Severity: severity, Title: title,
		Body:            fmt.Sprintf("%s | Trade #%d\nStatus: %s\n%s\nObserved: %s", t.DisplayName(), t.ID, t.Status, detail, time.Now().In(time.FixedZone("IST", 19800)).Format("15:04:05 IST")),
		CooldownSeconds: int64(cooldown / time.Second),
	}
}

func activeForProtection(status string) bool {
	return status == "EXECUTED" || status == "TARGET_ORDER_PLACED"
}
func finitePositive(v float64) bool         { return !math.IsNaN(v) && !math.IsInf(v, 0) && v > 0 }
func boolValue(v *bool) bool                { return v != nil && *v }
func samePriceBasis(left, right *bool) bool { return boolValue(left) == boolValue(right) }

func referencePrice(t model.Trade, target bool) (*float64, time.Time) {
	useSpot := boolValue(t.UseSpotForSL)
	if target {
		useSpot = boolValue(t.UseSpotForTarget)
	}
	if useSpot {
		return t.SpotLTP, parseLocalTime(t.SpotLTPObservedAt, time.FixedZone("IST", 19800))
	}
	return t.InstrumentLTP, parseLocalTime(t.InstrumentLTPObservedAt, time.FixedZone("IST", 19800))
}

type targetLevel struct {
	Number int
	Price  float64
}

func nextTarget(t model.Trade, current float64) *targetLevel {
	for _, target := range configuredTargets(t) {
		if target.Price > current {
			value := target
			return &value
		}
	}
	return nil
}

func targetAnchor(t model.Trade, target targetLevel) (float64, bool) {
	if t.EntryPrice != nil && finitePositive(*t.EntryPrice) &&
		samePriceBasis(t.UseSpotForEntry, t.UseSpotForTarget) && *t.EntryPrice < target.Price {
		return *t.EntryPrice, true
	}

	targets := configuredTargets(t)
	for index, candidate := range targets {
		if candidate.Number != target.Number {
			continue
		}
		if index > 0 && targets[index-1].Price < target.Price {
			return targets[index-1].Price, true
		}
		if index+1 < len(targets) && targets[index+1].Price > target.Price {
			return target.Price - (targets[index+1].Price - target.Price), true
		}
	}
	if t.StopLoss != nil && finitePositive(*t.StopLoss) && boolValue(t.UseSpotForSL) == boolValue(t.UseSpotForTarget) && *t.StopLoss < target.Price {
		return *t.StopLoss, true
	}
	return 0, false
}

func stopAnchor(t model.Trade) (float64, bool) {
	if t.StopLoss == nil {
		return 0, false
	}
	if t.EntryPrice != nil && finitePositive(*t.EntryPrice) &&
		samePriceBasis(t.UseSpotForEntry, t.UseSpotForSL) && *t.EntryPrice > *t.StopLoss {
		return *t.EntryPrice, true
	}
	if boolValue(t.UseSpotForSL) == boolValue(t.UseSpotForTarget) {
		targets := configuredTargets(t)
		if len(targets) >= 2 {
			inferredEntry := targets[0].Price - (targets[1].Price - targets[0].Price)
			if inferredEntry > *t.StopLoss {
				return inferredEntry, true
			}
		}
	}
	return 0, false
}

func configuredTargets(t model.Trade) []targetLevel {
	targets := make([]targetLevel, 0, 3)
	for index, price := range []*float64{t.Target1, t.Target2, t.Target3} {
		if price != nil && finitePositive(*price) {
			targets = append(targets, targetLevel{Number: index + 1, Price: *price})
		}
	}
	sort.Slice(targets, func(left, right int) bool { return targets[left].Price < targets[right].Price })
	return targets
}

func parseLocalTime(value string, location *time.Location) time.Time {
	if value == "" {
		return time.Time{}
	}
	for _, layout := range []string{"2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05"} {
		if parsed, err := time.ParseInLocation(layout, value, location); err == nil {
			return parsed
		}
	}
	return time.Time{}
}

func marketOpen(now time.Time) bool {
	if now.Weekday() == time.Saturday || now.Weekday() == time.Sunday {
		return false
	}
	minutes := now.Hour()*60 + now.Minute()
	return minutes >= 9*60+15 && minutes <= 15*60+30
}

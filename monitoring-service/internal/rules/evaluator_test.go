package rules

import (
	"testing"
	"time"

	"sharekhan-trade-monitor/internal/model"
)

func TestStopBreachAndMoveToCost(t *testing.T) {
	now := time.Date(2026, 7, 7, 11, 0, 0, 0, time.FixedZone("IST", 19800))
	entry, stop, target, current := 100.0, 90.0, 120.0, 89.0
	trade := model.Trade{ID: 1, Symbol: "NIFTY", Status: "EXECUTED", EntryPrice: &entry, StopLoss: &stop, Target1: &target, InstrumentLTP: &current, InstrumentLTPObservedAt: "2026-07-07T11:00:00"}
	e := New(Config{StalePriceAfter: 30 * time.Second, ExitStuckAfter: time.Minute, ProximityFraction: .1, MoveToCostFraction: .6})
	items := e.Evaluate(trade, now)
	if !contains(items, "stop_breached") {
		t.Fatalf("expected stop_breached, got %#v", items)
	}

	current = 113
	items = e.Evaluate(trade, now)
	if !contains(items, "move_sl_to_cost") {
		t.Fatalf("expected move_sl_to_cost, got %#v", items)
	}
}

func TestStalePriceSuppressesPriceRules(t *testing.T) {
	now := time.Date(2026, 7, 7, 11, 0, 0, 0, time.FixedZone("IST", 19800))
	entry, stop, current := 100.0, 90.0, 89.0
	trade := model.Trade{ID: 2, Symbol: "NIFTY", Status: "EXECUTED", EntryPrice: &entry, StopLoss: &stop, InstrumentLTP: &current, InstrumentLTPObservedAt: "2026-07-07T10:58:00"}
	e := New(Config{StalePriceAfter: 30 * time.Second, ExitStuckAfter: time.Minute, ProximityFraction: .1, MoveToCostFraction: .6})
	items := e.Evaluate(trade, now)
	if !contains(items, "stale_market_data") || contains(items, "stop_breached") {
		t.Fatalf("unexpected advisories: %#v", items)
	}
}

func TestMissingProtection(t *testing.T) {
	now := time.Date(2026, 7, 7, 11, 0, 0, 0, time.FixedZone("IST", 19800))
	current := 100.0
	trade := model.Trade{ID: 3, Symbol: "NIFTY", Status: "EXECUTED", InstrumentLTP: &current, InstrumentLTPObservedAt: "2026-07-07T11:00:00"}
	e := New(Config{StalePriceAfter: time.Minute, ExitStuckAfter: time.Minute, ProximityFraction: .1, MoveToCostFraction: .6})
	if items := e.Evaluate(trade, now); !contains(items, "missing_protection") {
		t.Fatalf("expected missing protection: %#v", items)
	}
}

func contains(items []model.Advisory, kind string) bool {
	for _, item := range items {
		if item.Type == kind {
			return true
		}
	}
	return false
}

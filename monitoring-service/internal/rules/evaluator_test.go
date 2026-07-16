package rules

import (
	"fmt"
	"strings"
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

func TestSendsExplicitAdvisoryWhenEachTargetIsNear(t *testing.T) {
	now := time.Date(2026, 7, 7, 11, 0, 0, 0, time.FixedZone("IST", 19800))
	entry, stop := 100.0, 90.0
	t1, t2, t3 := 120.0, 140.0, 160.0
	e := New(Config{StalePriceAfter: time.Minute, ExitStuckAfter: time.Minute, ProximityFraction: .1, MoveToCostFraction: .6})

	tests := []struct {
		name       string
		current    float64
		advisory   string
		title      string
		bodyMarker string
	}{
		{name: "T1", current: 118.5, advisory: "target_1_proximity", title: "TARGET 1 NEAR", bodyMarker: "T1: 120.00"},
		{name: "T2", current: 138.5, advisory: "target_2_proximity", title: "TARGET 2 NEAR", bodyMarker: "T2: 140.00"},
		{name: "T3", current: 158.5, advisory: "target_3_proximity", title: "TARGET 3 NEAR", bodyMarker: "T3: 160.00"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			current := test.current
			trade := model.Trade{
				ID: 10, Symbol: "NIFTY", Status: "EXECUTED", EntryPrice: &entry, StopLoss: &stop,
				Target1: &t1, Target2: &t2, Target3: &t3, InstrumentLTP: &current,
				InstrumentLTPObservedAt: "2026-07-07T11:00:00",
			}
			item, ok := findAdvisory(e.Evaluate(trade, now), test.advisory)
			if !ok {
				t.Fatalf("expected %s advisory", test.advisory)
			}
			if !strings.Contains(item.Title, test.title) || !strings.Contains(item.Body, test.bodyMarker) {
				t.Fatalf("unexpected advisory message: title=%q body=%q", item.Title, item.Body)
			}
		})
	}
}

func TestSendsStopLossNearAdvisoryWithDistance(t *testing.T) {
	now := time.Date(2026, 7, 7, 11, 0, 0, 0, time.FixedZone("IST", 19800))
	entry, stop, current := 100.0, 90.0, 90.5
	trade := model.Trade{ID: 11, Symbol: "NIFTY", Status: "EXECUTED", EntryPrice: &entry, StopLoss: &stop, InstrumentLTP: &current, InstrumentLTPObservedAt: "2026-07-07T11:00:00"}
	e := New(Config{StalePriceAfter: time.Minute, ExitStuckAfter: time.Minute, ProximityFraction: .1, MoveToCostFraction: .6})

	item, ok := findAdvisory(e.Evaluate(trade, now), "stop_proximity")
	if !ok {
		t.Fatal("expected stop_proximity advisory")
	}
	if !strings.Contains(item.Title, "STOP-LOSS NEAR") || !strings.Contains(item.Body, fmt.Sprintf("Stop-loss: %.2f", stop)) {
		t.Fatalf("unexpected stop advisory message: title=%q body=%q", item.Title, item.Body)
	}
}

func TestProximityAdvisoriesSupportSpotLevelsWithOptionEntry(t *testing.T) {
	now := time.Date(2026, 7, 7, 11, 0, 0, 0, time.FixedZone("IST", 19800))
	entry, stop := 100.0, 24800.0
	t1, t2, t3 := 25000.0, 25100.0, 25200.0
	spotCurrent := 24992.0
	useSpot := true
	trade := model.Trade{
		ID: 12, Symbol: "NIFTY", Status: "EXECUTED", EntryPrice: &entry, StopLoss: &stop,
		Target1: &t1, Target2: &t2, Target3: &t3, SpotLTP: &spotCurrent,
		SpotLTPObservedAt: "2026-07-07T11:00:00", UseSpotForSL: &useSpot, UseSpotForTarget: &useSpot,
	}
	e := New(Config{StalePriceAfter: time.Minute, ExitStuckAfter: time.Minute, ProximityFraction: .1, MoveToCostFraction: .6})

	if items := e.Evaluate(trade, now); !contains(items, "target_1_proximity") {
		t.Fatalf("expected spot-based T1 proximity advisory, got %#v", items)
	}

	spotCurrent = 24805.0
	if items := e.Evaluate(trade, now); !contains(items, "stop_proximity") {
		t.Fatalf("expected spot-based stop proximity advisory, got %#v", items)
	}
}

func contains(items []model.Advisory, kind string) bool {
	_, ok := findAdvisory(items, kind)
	return ok
}

func findAdvisory(items []model.Advisory, kind string) (model.Advisory, bool) {
	for _, item := range items {
		if item.Type == kind {
			return item, true
		}
	}
	return model.Advisory{}, false
}

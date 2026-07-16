package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"sharekhan-trade-monitor/internal/config"
	"sharekhan-trade-monitor/internal/model"
	"sharekhan-trade-monitor/internal/rules"
	"sharekhan-trade-monitor/internal/state"
	"sharekhan-trade-monitor/internal/telegram"
	"sharekhan-trade-monitor/internal/trader"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	store, err := state.Load(cfg.StateFile)
	if err != nil {
		log.Fatalf("load state: %v", err)
	}
	httpClient := &http.Client{Timeout: cfg.HTTPTimeout}
	traderClient := trader.New(cfg.TraderURL, cfg.TraderToken, httpClient)
	telegramClient := telegram.New(cfg.TelegramBotToken, cfg.TelegramChatID, httpClient)
	evaluator := rules.New(rules.Config{
		StalePriceAfter: cfg.StalePriceAfter, ExitStuckAfter: cfg.ExitStuckAfter,
		ProximityFraction: cfg.ProximityFraction, MoveToCostFraction: cfg.MoveToCostFraction,
	})

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	var lastSuccess atomic.Int64
	go healthServer(cfg.HealthAddress, &lastSuccess)
	log.Printf("trade monitor started shadow_mode=%t poll_interval=%s", cfg.ShadowMode, cfg.PollInterval)

	run := func() {
		snapshotCtx, stop := context.WithTimeout(ctx, cfg.HTTPTimeout)
		snapshot, err := traderClient.Snapshot(snapshotCtx)
		stop()
		if err != nil {
			log.Printf("snapshot failed: %v", err)
			return
		}
		now := time.Now()
		for _, trade := range snapshot.ActiveTrades {
			for _, item := range evaluator.Evaluate(trade, now) {
				deliver(ctx, item, now, cfg.ShadowMode, store, telegramClient)
			}
		}
		ist := now.In(time.FixedZone("IST", 19800))
		if ist.Hour() > cfg.SummaryHour || (ist.Hour() == cfg.SummaryHour && ist.Minute() >= cfg.SummaryMinute) {
			deliver(ctx, rules.DailySummary(snapshot.ClosedToday, ist.Format("2006-01-02")), now, cfg.ShadowMode, store, telegramClient)
		}
		lastSuccess.Store(now.Unix())
		log.Printf("snapshot processed active=%d closed_today=%d", len(snapshot.ActiveTrades), len(snapshot.ClosedToday))
	}

	run()
	ticker := time.NewTicker(cfg.PollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Print("trade monitor stopped")
			return
		case <-ticker.C:
			run()
		}
	}
}

type sender interface {
	Send(context.Context, string, string) error
}

func deliver(ctx context.Context, item model.Advisory, now time.Time, shadow bool, store *state.Store, sender sender) {
	key := item.Key
	if shadow {
		key = "shadow:" + key
	}
	if !store.Due(key, now, time.Duration(item.CooldownSeconds)*time.Second) {
		return
	}
	if shadow {
		encoded, _ := json.Marshal(map[string]string{"key": item.Key, "severity": item.Severity, "title": item.Title, "body": item.Body})
		log.Printf("SHADOW advisory=%s", encoded)
	} else if err := sender.Send(ctx, item.Title, item.Body); err != nil {
		log.Printf("telegram send failed key=%s: %v", item.Key, err)
		return
	}
	if err := store.Mark(key, now); err != nil {
		log.Printf("persist advisory state failed key=%s: %v", item.Key, err)
	}
}

func healthServer(address string, lastSuccess *atomic.Int64) {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"status": "up", "lastSuccessfulSnapshotUnix": lastSuccess.Load()})
	})
	server := &http.Server{Addr: address, Handler: mux, ReadHeaderTimeout: 2 * time.Second}
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Printf("health server failed: %v", err)
	}
}

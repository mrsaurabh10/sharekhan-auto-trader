package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	TraderURL          string
	TraderToken        string
	TelegramBotToken   string
	TelegramChatID     string
	PollInterval       time.Duration
	HTTPTimeout        time.Duration
	StateFile          string
	ShadowMode         bool
	HealthAddress      string
	StalePriceAfter    time.Duration
	ExitStuckAfter     time.Duration
	ProximityFraction  float64
	MoveToCostFraction float64
	SummaryHour        int
	SummaryMinute      int
}

func Load() (Config, error) {
	cfg := Config{
		TraderURL:          strings.TrimRight(os.Getenv("TRADER_API_URL"), "/"),
		TraderToken:        os.Getenv("TRADER_API_TOKEN"),
		TelegramBotToken:   os.Getenv("TELEGRAM_BOT_TOKEN"),
		TelegramChatID:     os.Getenv("TELEGRAM_CHAT_ID"),
		PollInterval:       duration("POLL_INTERVAL", 10*time.Second),
		HTTPTimeout:        duration("HTTP_TIMEOUT", 5*time.Second),
		StateFile:          value("STATE_FILE", "/app/data/advisory-state.json"),
		ShadowMode:         boolean("SHADOW_MODE", true),
		HealthAddress:      value("HEALTH_ADDRESS", ":8090"),
		StalePriceAfter:    duration("STALE_PRICE_AFTER", 30*time.Second),
		ExitStuckAfter:     duration("EXIT_STUCK_AFTER", 60*time.Second),
		ProximityFraction:  decimal("PROXIMITY_FRACTION", 0.10),
		MoveToCostFraction: decimal("MOVE_TO_COST_FRACTION", 0.60),
		SummaryHour:        integer("SUMMARY_HOUR", 15),
		SummaryMinute:      integer("SUMMARY_MINUTE", 40),
	}
	if cfg.TraderURL == "" || cfg.TraderToken == "" {
		return Config{}, fmt.Errorf("TRADER_API_URL and TRADER_API_TOKEN are required")
	}
	if !cfg.ShadowMode && (cfg.TelegramBotToken == "" || cfg.TelegramChatID == "") {
		return Config{}, fmt.Errorf("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are required when SHADOW_MODE=false")
	}
	if cfg.PollInterval <= 0 || cfg.HTTPTimeout <= 0 || cfg.StalePriceAfter <= 0 || cfg.ExitStuckAfter <= 0 {
		return Config{}, fmt.Errorf("poll, HTTP, stale-price, and exit-stuck durations must be positive")
	}
	if cfg.ProximityFraction <= 0 || cfg.ProximityFraction >= 1 || cfg.MoveToCostFraction <= 0 || cfg.MoveToCostFraction >= 1 {
		return Config{}, fmt.Errorf("PROXIMITY_FRACTION and MOVE_TO_COST_FRACTION must be between 0 and 1")
	}
	if cfg.SummaryHour < 0 || cfg.SummaryHour > 23 || cfg.SummaryMinute < 0 || cfg.SummaryMinute > 59 {
		return Config{}, fmt.Errorf("summary time is invalid")
	}
	return cfg, nil
}

func value(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
func boolean(key string, fallback bool) bool {
	v, err := strconv.ParseBool(os.Getenv(key))
	if err == nil {
		return v
	}
	return fallback
}
func integer(key string, fallback int) int {
	v, err := strconv.Atoi(os.Getenv(key))
	if err == nil {
		return v
	}
	return fallback
}
func decimal(key string, fallback float64) float64 {
	v, err := strconv.ParseFloat(os.Getenv(key), 64)
	if err == nil {
		return v
	}
	return fallback
}
func duration(key string, fallback time.Duration) time.Duration {
	v, err := time.ParseDuration(os.Getenv(key))
	if err == nil {
		return v
	}
	return fallback
}

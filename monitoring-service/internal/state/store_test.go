package state

import (
	"path/filepath"
	"testing"
	"time"
)

func TestMarkPersistsAndHonorsCooldown(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	now := time.Date(2026, 7, 7, 10, 0, 0, 0, time.UTC)
	store, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if !store.Due("trade:1:stop", now, time.Minute) {
		t.Fatal("new advisory should be due")
	}
	if err := store.Mark("trade:1:stop", now); err != nil {
		t.Fatal(err)
	}
	reloaded, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.Due("trade:1:stop", now.Add(30*time.Second), time.Minute) {
		t.Fatal("advisory should still be cooling down")
	}
	if !reloaded.Due("trade:1:stop", now.Add(time.Minute), time.Minute) {
		t.Fatal("advisory should be due after cooldown")
	}
}

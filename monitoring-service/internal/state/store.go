package state

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type Store struct {
	path string
	mu   sync.Mutex
	Sent map[string]time.Time `json:"sent"`
}

func Load(path string) (*Store, error) {
	s := &Store{path: path, Sent: map[string]time.Time{}}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, s); err != nil {
		return nil, err
	}
	if s.Sent == nil {
		s.Sent = map[string]time.Time{}
	}
	return s, nil
}

func (s *Store) Due(key string, now time.Time, cooldown time.Duration) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	last, ok := s.Sent[key]
	return !ok || now.Sub(last) >= cooldown
}

func (s *Store) Mark(key string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Sent[key] = now
	if err := os.MkdirAll(filepath.Dir(s.path), 0o750); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

package trader

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"testing"
)

func TestSnapshotSendsTokenAndDecodesResponse(t *testing.T) {
	httpClient := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path != "/internal/monitoring/snapshot" {
			t.Fatalf("unexpected path %s", r.URL.Path)
		}
		if token := r.Header.Get("X-Monitoring-Token"); token != "secret" {
			t.Fatalf("unexpected token %q", token)
		}
		body := `{"generatedAt":"2026-07-07T10:00:00","timezone":"Asia/Kolkata","activeTrades":[{"id":7,"symbol":"NIFTY","status":"EXECUTED"}],"closedToday":[]}`
		return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header), Body: io.NopCloser(bytes.NewBufferString(body))}, nil
	})}

	snapshot, err := New("https://trader.example", "secret", httpClient).Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshot.ActiveTrades) != 1 || snapshot.ActiveTrades[0].ID != 7 {
		t.Fatalf("unexpected snapshot %#v", snapshot)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) { return f(request) }

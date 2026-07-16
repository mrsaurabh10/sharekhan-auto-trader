package trader

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"sharekhan-trade-monitor/internal/model"
)

type Client struct {
	url   string
	token string
	http  *http.Client
}

func New(url, token string, client *http.Client) *Client {
	return &Client{url: url, token: token, http: client}
}

func (c *Client) Snapshot(ctx context.Context) (model.Snapshot, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url+"/internal/monitoring/snapshot", nil)
	if err != nil {
		return model.Snapshot{}, err
	}
	req.Header.Set("X-Monitoring-Token", c.token)
	resp, err := c.http.Do(req)
	if err != nil {
		return model.Snapshot{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return model.Snapshot{}, fmt.Errorf("trader snapshot returned HTTP %d", resp.StatusCode)
	}
	var snapshot model.Snapshot
	if err := json.NewDecoder(resp.Body).Decode(&snapshot); err != nil {
		return model.Snapshot{}, fmt.Errorf("decode trader snapshot: %w", err)
	}
	return snapshot, nil
}

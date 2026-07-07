package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

type Client struct {
	botToken, chatID string
	http             *http.Client
}

func New(botToken, chatID string, client *http.Client) *Client {
	return &Client{botToken: botToken, chatID: chatID, http: client}
}

func (c *Client) Send(ctx context.Context, title, body string) error {
	payload, err := json.Marshal(map[string]any{
		"chat_id":                  c.chatID,
		"text":                     title + "\n\n" + body,
		"disable_web_page_preview": true,
	})
	if err != nil {
		return err
	}
	url := "https://api.telegram.org/bot" + c.botToken + "/sendMessage"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("telegram returned HTTP %d", resp.StatusCode)
	}
	return nil
}

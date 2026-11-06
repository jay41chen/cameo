// Package vendor 是呼叫廠商（或模擬廠商）的 HTTP client。
package vendor

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// callTimeout 是單次呼叫的逾時。
const callTimeout = 5 * time.Second

// Client 實作 usecase.VendorClient。
type Client struct {
	baseURL string
	http    *http.Client
}

// NewClient 以廠商的 base URL（例如 http://vendor-sim:9000）建立 client。
func NewClient(baseURL string) *Client {
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), http: &http.Client{Timeout: callTimeout}}
}

// Dispatch 對 POST {baseURL}/jobs 送 {"job_id": jobID}；只有 2xx 算成功。
func (c *Client) Dispatch(ctx context.Context, jobID string) error {
	body, err := json.Marshal(map[string]string{"job_id": jobID})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/jobs", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("vendor dispatch: unexpected status %d", resp.StatusCode)
	}
	return nil
}

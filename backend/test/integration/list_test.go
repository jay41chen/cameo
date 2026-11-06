package integration_test

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"testing"
	"time"

	apihttp "cameo/backend/internal/adapter/http"
	"cameo/backend/internal/adapter/postgres"
	"cameo/backend/internal/usecase"
	"cameo/backend/test/internal/testdb"

	"github.com/jackc/pgx/v5/pgxpool"
)

func newListHandler(t *testing.T) (http.Handler, *pgxpool.Pool) {
	t.Helper()
	pool := testdb.New(t)
	clock := testdb.NewClock(time.Date(2026, 11, 6, 2, 0, 0, 0, time.UTC))
	svc := usecase.NewService(postgres.NewQueries(pool), postgres.NewTxRunner(pool), clock,
		usecase.Config{DailyLimit: 1000, UploadTTL: time.Hour}, newFiles(t))
	return apihttp.Handler(svc), pool
}

// insertListCameo 直接用 SQL 建立訂單；reason 為空字串表示 NULL。
func insertListCameo(t *testing.T, pool *pgxpool.Pool, id, status, reason string, createdAt time.Time) {
	t.Helper()
	var r *string
	if reason != "" {
		r = &reason
	}
	_, err := pool.Exec(context.Background(), `INSERT INTO cameos
		(id, user_id, idempotency_key, clip_id, cost, status, points_state, failure_reason, upload_expires_at, created_at, updated_at)
		VALUES ($1, 1, $1, 'c1', 10, $2, 'CHARGED', $3, $4, $4, $4)`, id, status, r, createdAt)
	if err != nil {
		t.Fatalf("insert %s: %v", id, err)
	}
}

func listItems(t *testing.T, r resp) []map[string]any {
	t.Helper()
	raw, ok := r.body["items"].([]any)
	if !ok {
		t.Fatalf("items is not an array: %v", r.body)
	}
	out := make([]map[string]any, 0, len(raw))
	for _, v := range raw {
		out = append(out, v.(map[string]any))
	}
	return out
}

func TestListCameosPaginatesTiesWithoutDuplicatesOrGaps(t *testing.T) {
	h, pool := newListHandler(t)
	at := time.Date(2026, 11, 6, 1, 0, 0, 123456000, time.UTC)
	const total = 45
	for i := 0; i < total; i++ {
		insertListCameo(t, pool, fmt.Sprintf("id-%03d", i), "SUCCEEDED", "", at)
	}
	var got []string
	path := "/v1/cameos"
	for pages := 0; ; pages++ {
		if pages > 5 {
			t.Fatal("too many pages")
		}
		r := call(t, h, http.MethodGet, path, "", "")
		if r.Code != 200 {
			t.Fatalf("%s: %d %v", path, r.Code, r.body)
		}
		for _, it := range listItems(t, r) {
			got = append(got, it["id"].(string))
		}
		next := r.body["next_cursor"]
		if next == nil {
			if pages != 2 {
				t.Fatalf("pages = %d, want 3", pages+1)
			}
			break
		}
		path = "/v1/cameos?cursor=" + url.QueryEscape(next.(string))
	}
	want := make([]string, 0, total)
	for i := total - 1; i >= 0; i-- {
		want = append(want, fmt.Sprintf("id-%03d", i))
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ids = %v\nwant %v", got, want)
	}
}

func TestListCameosOnlyListsSubmittedStatuses(t *testing.T) {
	h, pool := newListHandler(t)
	at := time.Date(2026, 11, 6, 1, 0, 0, 0, time.UTC)
	statuses := []string{"AWAITING_UPLOAD", "UPLOADED", "QUEUED", "GENERATING", "SUCCEEDED", "FAILED", "EXPIRED"}
	for i, s := range statuses {
		insertListCameo(t, pool, "s-"+s, s, "", at.Add(time.Duration(i)*time.Second))
	}
	r := call(t, h, http.MethodGet, "/v1/cameos", "", "")
	var got []string
	for _, it := range listItems(t, r) {
		got = append(got, it["status"].(string))
	}
	want := []string{"FAILED", "SUCCEEDED", "GENERATING", "QUEUED"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("statuses = %v, want %v", got, want)
	}
}

func TestListCameosNextCursorOnlyWhenMoreExist(t *testing.T) {
	h, pool := newListHandler(t)
	at := time.Date(2026, 11, 6, 1, 0, 0, 0, time.UTC)
	for i := 0; i < 20; i++ {
		insertListCameo(t, pool, fmt.Sprintf("n-%02d", i), "QUEUED", "", at.Add(time.Duration(i)*time.Second))
	}
	r := call(t, h, http.MethodGet, "/v1/cameos", "", "")
	if n := len(listItems(t, r)); n != 20 || r.body["next_cursor"] != nil {
		t.Fatalf("20 rows: items = %d, next_cursor = %v, want 20 and nil", n, r.body["next_cursor"])
	}
	insertListCameo(t, pool, "n-20", "QUEUED", "", at.Add(20*time.Second))
	r = call(t, h, http.MethodGet, "/v1/cameos", "", "")
	next, ok := r.body["next_cursor"].(string)
	if n := len(listItems(t, r)); n != 20 || !ok || next == "" {
		t.Fatalf("21 rows: items = %d, next_cursor = %v, want 20 and a cursor", n, r.body["next_cursor"])
	}
	r = call(t, h, http.MethodGet, "/v1/cameos?cursor="+url.QueryEscape(next), "", "")
	items := listItems(t, r)
	if len(items) != 1 || items[0]["id"] != "n-00" || r.body["next_cursor"] != nil {
		t.Fatalf("page 2 = %v", r.body)
	}
}

func TestListCameosItemsEqualGetByID(t *testing.T) {
	h, pool := newListHandler(t)
	at := time.Date(2026, 11, 6, 1, 0, 0, 0, time.UTC)
	insertListCameo(t, pool, "x-failed", "FAILED", "CONTENT_REJECTED", at)
	insertListCameo(t, pool, "x-ok", "SUCCEEDED", "", at.Add(time.Second))
	insertListCameo(t, pool, "x-queued", "QUEUED", "", at.Add(2*time.Second))

	r := call(t, h, http.MethodGet, "/v1/cameos", "", "")
	if !reflect.DeepEqual(keys(r.body), []string{"items", "next_cursor"}) {
		t.Fatalf("keys = %v", keys(r.body))
	}
	items := listItems(t, r)
	if len(items) != 3 {
		t.Fatalf("items = %v", items)
	}
	for _, it := range items {
		id := it["id"].(string)
		one := call(t, h, http.MethodGet, "/v1/cameos/"+id, "", "")
		if one.Code != 200 {
			t.Fatalf("get %s: %d", id, one.Code)
		}
		if !reflect.DeepEqual(it, one.body["cameo"]) {
			t.Errorf("%s: list item %v\n != get cameo %v", id, it, one.body["cameo"])
		}
		if _, has := it["upload"]; has {
			t.Errorf("%s: list item has upload", id)
		}
	}
	for _, it := range items {
		if it["id"] == "x-failed" && it["failure_reason"] != "CONTENT_REJECTED" {
			t.Errorf("failure_reason = %v", it["failure_reason"])
		}
		d, _ := it["display"].(map[string]any)
		if d == nil || d["title"] == "" || d["message"] == "" {
			t.Errorf("%v: display = %v", it["id"], it["display"])
		}
	}
}

func TestListCameosEdgeCases(t *testing.T) {
	h, _ := newListHandler(t)

	r := call(t, h, http.MethodGet, "/v1/cameos", "", "")
	if r.Code != 200 || r.body["next_cursor"] != nil || !reflect.DeepEqual(r.body["items"], []any{}) {
		t.Fatalf("empty: %d %v", r.Code, r.body)
	}
	if raw := r.Body.String(); raw != "{\"items\":[],\"next_cursor\":null}\n" {
		t.Errorf("empty body = %q", raw)
	}

	ledger := call(t, h, http.MethodGet, "/v1/points/ledger?cursor=!!!", "", "")
	wantError(t, ledger, 400, "INVALID_CURSOR")
	for _, c := range []string{"!!!", "bm90LWpzb24", "e30", "eyJ0Ijoibm9wZSIsImkiOiJ4In0"} {
		got := call(t, h, http.MethodGet, "/v1/cameos?cursor="+c, "", "")
		wantError(t, got, 400, "INVALID_CURSOR")
		l := call(t, h, http.MethodGet, "/v1/points/ledger?cursor="+c, "", "")
		if !reflect.DeepEqual(got.body, l.body) || got.Code != l.Code {
			t.Errorf("cursor %q: cameos %d %v != ledger %d %v", c, got.Code, got.body, l.Code, l.body)
		}
	}

	req, _ := http.NewRequest(http.MethodGet, "/v1/cameos", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 401 {
		t.Errorf("no token: %d", rec.Code)
	}

	// POST 行為不變。
	c := call(t, h, http.MethodPost, "/v1/cameos", "k1", `{"clip_id":"c1"}`)
	if c.Code != 201 || c.body["upload"] == nil {
		t.Fatalf("post: %d %v", c.Code, c.body)
	}
	// AWAITING_UPLOAD 不出現在列表。
	r = call(t, h, http.MethodGet, "/v1/cameos", "", "")
	if len(listItems(t, r)) != 0 {
		t.Errorf("awaiting upload listed: %v", r.body)
	}
	if put := call(t, h, http.MethodPut, "/v1/cameos", "", ""); put.Code != 405 || put.Header().Get("Allow") != "GET, HEAD, POST" {
		t.Errorf("PUT: %d Allow=%q", put.Code, put.Header().Get("Allow"))
	}
}

package vendor_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"cameo/backend/internal/adapter/vendor"
)

func TestDispatchPostsJobID(t *testing.T) {
	var gotPath, gotMethod string
	var gotBody map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.Path, r.Method
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &gotBody)
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()
	if err := vendor.NewClient(srv.URL+"/").Dispatch(context.Background(), "job-1"); err != nil {
		t.Fatal(err)
	}
	if gotMethod != http.MethodPost || gotPath != "/jobs" || gotBody["job_id"] != "job-1" {
		t.Fatalf("got %s %s %v", gotMethod, gotPath, gotBody)
	}
}

func TestDispatchOnlyTwoXXIsSuccess(t *testing.T) {
	for code, wantErr := range map[int]bool{200: false, 202: false, 204: false, 301: true, 400: true, 404: true, 500: true, 503: true} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(code) }))
		err := vendor.NewClient(srv.URL).Dispatch(context.Background(), "j")
		srv.Close()
		if (err != nil) != wantErr {
			t.Errorf("status %d: err = %v, wantErr %v", code, err, wantErr)
		}
	}
}

func TestDispatchConnectionError(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close()
	if err := vendor.NewClient(url).Dispatch(context.Background(), "j"); err == nil {
		t.Fatal("want error")
	}
}

package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"cameo/backend/internal/domain"
)

func TestShareCameoRules(t *testing.T) {
	ctx := context.Background()
	for _, st := range []string{domain.StatusAwaitingUpload, domain.StatusUploaded, domain.StatusQueued, domain.StatusGenerating, domain.StatusFailed, domain.StatusExpired} {
		e := newEnv(st)
		if _, err := e.svc.ShareCameo(ctx, "c-1"); !errors.Is(err, domain.ErrNotShareable) || e.db.cameo.ShareToken != nil {
			t.Errorf("%s: err=%v token=%v", st, err, e.db.cameo.ShareToken)
		}
	}
	e := newEnv(domain.StatusSucceeded)
	if _, err := e.svc.ShareCameo(ctx, "nope"); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Errorf("unknown id: %v", err)
	}
	if _, err := e.svc.ShareCameo(ctx, "a\x00b"); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Errorf("NUL id: %v", err)
	}
	e.clock.t = base.Add(time.Minute)
	c, err := e.svc.ShareCameo(ctx, "c-1")
	if err != nil || c.ShareToken == nil || *c.ShareToken == "" || !c.UpdatedAt.Equal(e.clock.t) {
		t.Fatalf("share = %+v err=%v", c, err)
	}
	// 已分享：回原訂單，不改 token 與 updated_at。
	e.clock.t = base.Add(time.Hour)
	again, err := e.svc.ShareCameo(ctx, "c-1")
	if err != nil || *again.ShareToken != *c.ShareToken || !again.UpdatedAt.Equal(c.UpdatedAt) {
		t.Fatalf("second share = %+v err=%v", again, err)
	}
}

func TestUnshareCameoRules(t *testing.T) {
	ctx := context.Background()
	e := newEnv(domain.StatusSucceeded)
	if err := e.svc.UnshareCameo(ctx, "nope"); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Errorf("unknown id: %v", err)
	}
	if err := e.svc.UnshareCameo(ctx, "a\x00b"); !errors.Is(err, domain.ErrCameoNotFound) {
		t.Errorf("NUL id: %v", err)
	}
	// 未分享：不寫入（updated_at 不變）。
	before := e.db.cameo.UpdatedAt
	if err := e.svc.UnshareCameo(ctx, "c-1"); err != nil || !e.db.cameo.UpdatedAt.Equal(before) {
		t.Errorf("unshare when not shared: err=%v updated=%v", err, e.db.cameo.UpdatedAt)
	}
	tok := "t"
	e.db.cameo.ShareToken = &tok
	e.clock.t = base.Add(time.Minute)
	if err := e.svc.UnshareCameo(ctx, "c-1"); err != nil || e.db.cameo.ShareToken != nil || !e.db.cameo.UpdatedAt.Equal(e.clock.t) {
		t.Errorf("unshare: err=%v token=%v", err, e.db.cameo.ShareToken)
	}
}

func TestSharedVideoAvailableRejectsInvalidText(t *testing.T) {
	e := newEnv(domain.StatusSucceeded)
	for _, tok := range []string{"a\x00b", "\xff"} {
		if ok, err := e.svc.SharedVideoAvailable(context.Background(), tok); ok || err != nil {
			t.Errorf("%q: ok=%v err=%v", tok, ok, err)
		}
	}
}

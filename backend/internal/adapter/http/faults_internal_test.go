package http

import (
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
)

func sp(s string) *string { return &s }

func entry(name string, target *string, times int) faultEntry {
	return faultEntry{Name: name, Target: target, Times: times, Params: map[string]any{}}
}

func TestFaultTakeTimesOnce(t *testing.T) {
	var r faultRegistry
	r.add(entry("upload_expired", nil, 1))
	if _, ok := r.take("upload_expired", ""); !ok {
		t.Fatal("first take should fire")
	}
	if _, ok := r.take("upload_expired", ""); ok {
		t.Fatal("second take should not fire")
	}
	if len(r.list()) != 0 {
		t.Fatalf("list = %v, want empty after times reaches 0", r.list())
	}
}

func TestFaultTakeTimesTwice(t *testing.T) {
	var r faultRegistry
	r.add(entry("upload_expired", nil, 2))
	for i := 0; i < 2; i++ {
		if _, ok := r.take("upload_expired", ""); !ok {
			t.Fatalf("take %d should fire", i+1)
		}
	}
	if _, ok := r.take("upload_expired", ""); ok {
		t.Fatal("third take should not fire")
	}
}

func TestFaultTakeTargetMismatchDoesNotConsume(t *testing.T) {
	var r faultRegistry
	r.add(entry("response_lost_after_commit", sp("create"), 1))
	if _, ok := r.take("response_lost_after_commit", "submit"); ok {
		t.Fatal("wrong target fired")
	}
	if _, ok := r.take("response_lost_after_commit", ""); ok {
		t.Fatal("empty target fired")
	}
	if l := r.list(); len(l) != 1 || l[0].Times != 1 {
		t.Fatalf("list = %+v, times must not change", l)
	}
	if _, ok := r.take("response_lost_after_commit", "create"); !ok {
		t.Fatal("matching target should fire")
	}
	// 無 target 的異常，帶 target 比對不到。
	r.add(entry("upload_expired", nil, 1))
	if _, ok := r.take("upload_expired", "create"); ok {
		t.Fatal("untargeted fault fired for a target")
	}
}

func TestFaultSameNameOverwritesAndMovesToEnd(t *testing.T) {
	var r faultRegistry
	r.add(entry("response_lost_after_commit", sp("create"), 3))
	r.add(entry("upload_expired", nil, 1))
	r.add(entry("response_lost_after_commit", sp("submit"), 1)) // 不同 target 也算同名
	l := r.list()
	if len(l) != 2 || l[0].Name != "upload_expired" || l[1].Name != "response_lost_after_commit" ||
		*l[1].Target != "submit" || l[1].Times != 1 {
		t.Fatalf("list = %+v", l)
	}
	if _, ok := r.take("response_lost_after_commit", "create"); ok {
		t.Fatal("overwritten target still fires")
	}
	if _, ok := r.take("response_lost_after_commit", "submit"); !ok {
		t.Fatal("new target should fire")
	}
}

func TestFaultClear(t *testing.T) {
	var r faultRegistry
	r.add(entry("upload_expired", nil, 1))
	r.add(entry("vendor_no_callback", nil, 1))
	r.clear()
	r.clear()
	if len(r.list()) != 0 {
		t.Fatal("not cleared")
	}
	if _, ok := r.take("upload_expired", ""); ok {
		t.Fatal("fired after clear")
	}
}

func TestFaultListIsDeepCopy(t *testing.T) {
	var r faultRegistry
	e := faultEntry{Name: "vendor_failed", Target: sp("x"), Times: 2, Params: map[string]any{"reason": "CONTENT_REJECTED"}}
	r.add(e)
	// 改呼叫端傳入的東西，不影響內部。
	e.Params["reason"] = "changed"
	*e.Target = "changed"
	l := r.list()
	if l[0].Params["reason"] != "CONTENT_REJECTED" || *l[0].Target != "x" {
		t.Fatalf("add did not copy: %+v", l[0])
	}
	l[0].Params["reason"] = "mutated"
	*l[0].Target = "mutated"
	l[0].Times = 99
	l[0].Name = "mutated"
	again := r.list()
	if !reflect.DeepEqual(again[0], faultEntry{Name: "vendor_failed", Target: sp("x"), Times: 2, Params: map[string]any{"reason": "CONTENT_REJECTED"}}) {
		t.Fatalf("list leaked internal state: %+v", again[0])
	}
}

func TestFaultConcurrentAddTake(t *testing.T) {
	const n = 200
	var r faultRegistry
	r.add(entry("upload_expired", nil, n))
	var fired atomic.Int64
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < n*2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if _, ok := r.take("upload_expired", ""); ok {
				fired.Add(1)
			}
			_ = r.list()
		}()
	}
	// 同時有人登記別的名稱，不能影響上面的次數。
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			r.add(entry("vendor_no_callback", nil, 1))
		}()
	}
	close(start)
	wg.Wait()
	if fired.Load() != n {
		t.Fatalf("fired = %d, want exactly %d", fired.Load(), n)
	}
}

func TestFaultOnlyOneVendorKind(t *testing.T) {
	var r faultRegistry
	if err := r.add(entry("vendor_failed", nil, 1)); err != nil {
		t.Fatal(err)
	}
	if err := r.add(entry("vendor_no_callback", nil, 1)); err == nil {
		t.Fatal("second vendor kind accepted")
	}
	if l := r.list(); len(l) != 1 || l[0].Name != "vendor_failed" {
		t.Fatalf("list changed: %+v", l)
	}
	if err := r.add(entry("vendor_failed", nil, 2)); err != nil || r.list()[0].Times != 2 {
		t.Fatalf("same-name overwrite: %v %+v", err, r.list())
	}
	if err := r.add(entry("upload_expired", nil, 1)); err != nil || len(r.list()) != 2 {
		t.Fatalf("coexist with upload_expired: %v %+v", err, r.list())
	}
	r.clear()
	if err := r.add(entry("vendor_no_callback", nil, 1)); err != nil {
		t.Fatalf("after clear: %v", err)
	}
}

func TestFaultConcurrentDifferentVendorKindsLeaveOne(t *testing.T) {
	for round := 0; round < 50; round++ {
		var r faultRegistry
		names := []string{"vendor_failed", "vendor_duplicate_callback", "vendor_no_callback"}
		var ok atomic.Int64
		var wg sync.WaitGroup
		start := make(chan struct{})
		for i := 0; i < 30; i++ {
			wg.Add(1)
			go func(n string) {
				defer wg.Done()
				<-start
				if r.add(entry(n, nil, 1)) == nil {
					ok.Add(1)
				}
			}(names[i%3])
		}
		close(start)
		wg.Wait()
		l := r.list()
		if len(l) != 1 {
			t.Fatalf("round %d: list = %+v, want exactly one vendor fault", round, l)
		}
	}
}

package http

import (
	"encoding/json"
	"io"
	"reflect"
	"strings"
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

func TestFaultPeekDoesNotConsumeAndReturnsCopy(t *testing.T) {
	var r faultRegistry
	r.add(faultEntry{Name: "upload_interrupted", Times: 1, Params: map[string]any{"after_bytes": 5}})
	for i := 0; i < 3; i++ {
		p, ok := r.peek("upload_interrupted")
		if !ok || p["after_bytes"] != 5 {
			t.Fatalf("peek %d = %v %v", i, p, ok)
		}
		p["after_bytes"] = 99 // 改拷貝不影響登記
	}
	if got := r.list(); len(got) != 1 || got[0].Times != 1 || got[0].Params["after_bytes"] != 5 {
		t.Fatalf("list = %v", got)
	}
	if _, ok := r.peek("upload_expired"); ok {
		t.Fatal("peek of unregistered name should be false")
	}
}

func readAll(t *testing.T, ir *interruptingReader) (string, error) {
	t.Helper()
	var out []byte
	buf := make([]byte, 4)
	for {
		n, err := ir.Read(buf)
		out = append(out, buf[:n]...)
		if err != nil {
			return string(out), err
		}
	}
}

// 注意：內容長度剛好等於限制時，strings.Reader 先回 (n, nil) 再回 (0, EOF)，會觸發中斷；
// 真實 server 的固定長度 body 在最後一次 Read 同時回 EOF，不會中斷，以整合測試為準。
func TestInterruptingReaderCutsAfterBytesAndConsumesOnce(t *testing.T) {
	var r faultRegistry
	r.add(entry("upload_interrupted", nil, 1))
	ir := &interruptingReader{r: strings.NewReader("0123456789"), remaining: 6, faults: &r}
	got, err := readAll(t, ir)
	if got != "012345" || err != errUploadInterrupted || !ir.tripped {
		t.Fatalf("got %q err %v tripped %v", got, err, ir.tripped)
	}
	if len(r.list()) != 0 {
		t.Fatalf("not consumed: %v", r.list())
	}
}

func TestInterruptingReaderShortContentDoesNotConsume(t *testing.T) {
	var r faultRegistry
	r.add(entry("upload_interrupted", nil, 1))
	ir := &interruptingReader{r: strings.NewReader("0123"), remaining: 6, faults: &r}
	got, err := readAll(t, ir)
	if got != "0123" || err != io.EOF || ir.tripped {
		t.Fatalf("got %q err %v tripped %v", got, err, ir.tripped)
	}
	if got := r.list(); len(got) != 1 || got[0].Times != 1 {
		t.Fatalf("consumed: %v", got)
	}
}

func TestInterruptingReaderFallsBackWhenFaultCleared(t *testing.T) {
	var r faultRegistry // 途中被清掉：take 失敗，改回正常讀取
	ir := &interruptingReader{r: strings.NewReader("0123456789"), remaining: 6, faults: &r}
	got, err := readAll(t, ir)
	if got != "0123456789" || err != io.EOF || ir.tripped {
		t.Fatalf("got %q err %v tripped %v", got, err, ir.tripped)
	}
}

// eofWithDataReader 讀完最後的資料時同時回 (n, io.EOF)，之後再讀回 (0, io.EOF)。
type eofWithDataReader struct{ data []byte }

func (e *eofWithDataReader) Read(p []byte) (int, error) {
	if len(e.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, e.data)
	e.data = e.data[n:]
	if len(e.data) == 0 {
		return n, io.EOF
	}
	return n, nil
}

func TestInterruptingReaderRemembersEOFAndDoesNotConsume(t *testing.T) {
	var r faultRegistry
	r.add(entry("upload_interrupted", nil, 1))
	ir := &interruptingReader{r: &eofWithDataReader{data: []byte("abc")}, remaining: 3, faults: &r}
	buf := make([]byte, 3)
	n, err := ir.Read(buf)
	if n != 3 || err != io.EOF {
		t.Fatalf("first read = %d %v, want 3 EOF", n, err)
	}
	for i := 0; i < 2; i++ {
		if n, err := ir.Read(buf); n != 0 || err != io.EOF {
			t.Fatalf("read after EOF = %d %v, want 0 EOF", n, err)
		}
	}
	if ir.tripped {
		t.Fatal("tripped after EOF")
	}
	if got := r.list(); len(got) != 1 || got[0].Times != 1 {
		t.Fatalf("consumed: %v", got)
	}
}

func TestVendorFailedReasonStoredAsPlainValues(t *testing.T) {
	p, err := vendorFailedParams(map[string]json.RawMessage{"reason": json.RawMessage(`null`)})
	if err != nil || p["reason"] != nil {
		t.Fatalf("null reason = %#v, %v (want untyped nil)", p["reason"], err)
	}
	p, err = vendorFailedParams(map[string]json.RawMessage{})
	if err != nil || p["reason"] != nil {
		t.Fatalf("omitted reason = %#v, %v", p["reason"], err)
	}
	p, err = vendorFailedParams(map[string]json.RawMessage{"reason": json.RawMessage(`"CONTENT_REJECTED"`)})
	if err != nil || p["reason"] != "CONTENT_REJECTED" {
		t.Fatalf("reason = %#v, %v (want string)", p["reason"], err)
	}
}

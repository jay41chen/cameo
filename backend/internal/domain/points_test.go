package domain

import "testing"

func TestNewPoints(t *testing.T) {
	cases := []struct{ balance, held, available int }{
		{100, 0, 100},
		{100, 10, 90},
		{10, 10, 0},
	}
	for _, c := range cases {
		p := NewPoints(c.balance, c.held)
		if p.Available != c.available || p.Held != c.held {
			t.Errorf("NewPoints(%d,%d) = %+v, want available=%d held=%d", c.balance, c.held, p, c.available, c.held)
		}
	}
}

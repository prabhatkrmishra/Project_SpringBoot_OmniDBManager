package main

import (
	"encoding/binary"
	"strings"
	"testing"
)

// Rewrite must preserve unrelated option bytes exactly, including
// repeated whitespace inside quoted values.
func TestRewritePreservesQuotedWhitespace(t *testing.T) {
	pkt := buildStartup(t, [][2]string{
		{"user", "u"},
		{"options", "-c myopt=\"a  b\" -c omnidb.mode=direct"},
	})
	params, err := parseStartupParams(pkt[8:])
	if err != nil {
		t.Fatal(err)
	}
	out, err := rewriteStartup(pkt, params, "abc123def456")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), "-c myopt=\"a  b\"") {
		t.Fatalf("quoted whitespace corrupted: %q", string(out))
	}
	if strings.Contains(string(out), "omnidb.mode") {
		t.Fatal("routing token leaked")
	}
	if binary.BigEndian.Uint32(out[0:4]) != uint32(len(out)) {
		t.Fatal("length mismatch")
	}
}

// Routing token at beginning/end/middle all strip cleanly.
func TestStripRoutingTokenPositions(t *testing.T) {
	cases := map[string]string{
		"-c omnidb.mode=direct":                          "",
		"-c omnidb.mode=pooled -c search_path=foo":       "-c search_path=foo",
		"-c search_path=foo -c omnidb.mode=direct":       "-c search_path=foo",
		"-c a=1 -c omnidb.mode=pooled -c b=2":            "-c a=1 -c b=2",
		"  -c omnidb.mode=direct   -c search_path=foo  ": "-c search_path=foo",
		"-c myopt=\"a  b\" -c omnidb.mode=direct":        "-c myopt=\"a  b\"",
		"-c omnidb.mode=direct -c myopt=\"x   y\"":       "-c myopt=\"x   y\"",
	}
	for in, want := range cases {
		got, ok := stripRoutingToken(in)
		if want == "" {
			if ok {
				t.Errorf("%q: expected empty, got %q", in, got)
			}
			continue
		}
		if !ok || got != want {
			t.Errorf("%q: got %q,%v want %q", in, got, ok, want)
		}
	}
}

// Scan registers keys AND returns them so relay can unregister on close.
func TestScanReturnsKeysForCleanup(t *testing.T) {
	cancels = &cancelRouter{}
	msg := []byte{'K', 0, 0, 0, 12, 0, 0, 0x11, 0x22, 0, 0, 0x33, 0x44}
	keys := scanBackendKeyData(msg, "direct")
	if len(keys) != 1 || keys[0][0] != 0x1122 || keys[0][1] != 0x3344 {
		t.Fatalf("unexpected keys: %v", keys)
	}
	if _, ok := cancels.get(0x1122, 0x3344); !ok {
		t.Fatal("mapping not registered")
	}
	cancels.del(0x1122, 0x3344)
	if _, ok := cancels.get(0x1122, 0x3344); ok {
		t.Fatal("mapping not cleaned up (P1: del never called)")
	}
}

// ReadyForQuery gate recognises session-ready.
func TestReadyForQueryGate(t *testing.T) {
	if !hasReadyForQuery([]byte{'Z', 0, 0, 0, 5}) {
		t.Fatal("missed ReadyForQuery")
	}
	if hasReadyForQuery([]byte{'K', 0, 0, 0, 12, 0, 0, 0, 1, 0, 0, 0, 2}) {
		t.Fatal("false positive on BackendKeyData")
	}
	if hasReadyForQuery([]byte("select 1")) {
		t.Fatal("false positive on plain bytes")
	}
}

// Cancel map stays bounded under flood.
func TestCancelMapBounded(t *testing.T) {
	cancels = &cancelRouter{}
	for i := uint32(0); i < 200000; i++ {
		cancels.put(i, i^0x9e3779b9, "direct")
	}
	if cancels.n > 100001 {
		t.Fatalf("map unbounded: n=%d", cancels.n)
	}
	cancels = &cancelRouter{}
}

package main

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestExtractModeDirect(t *testing.T) {
	m, err := extractMode("-c omnidb.mode=direct")
	if err != nil || m != "direct" {
		t.Fatalf("got %q,%v", m, err)
	}
}

func TestExtractModePooledWithOthers(t *testing.T) {
	m, err := extractMode("-c search_path=foo -c omnidb.mode=pooled")
	if err != nil || m != "pooled" {
		t.Fatalf("got %q,%v", m, err)
	}
}

func TestExtractModeMissingFails(t *testing.T) {
	if _, err := extractMode("-c search_path=foo"); err == nil {
		t.Fatal("expected error for missing mode")
	}
	if _, err := extractMode(""); err == nil {
		t.Fatal("expected error for empty options")
	}
}

func TestExtractModeInvalidFails(t *testing.T) {
	if _, err := extractMode("-c omnidb.mode=banana"); err == nil {
		t.Fatal("expected error for invalid mode")
	}
}

func TestExtractModeDuplicateFails(t *testing.T) {
	if _, err := extractMode("-c omnidb.mode=direct -c omnidb.mode=pooled"); err == nil {
		t.Fatal("expected error for duplicate mode")
	}
}

func TestExtractModeBareTokenFails(t *testing.T) {
	if _, err := extractMode("omnidb.mode=direct"); err == nil {
		t.Fatal("expected error for bare token without -c")
	}
}

func buildStartup(t *testing.T, params [][2]string) []byte {
	t.Helper()
	body := []byte{}
	tmp := make([]byte, 8)
	binary.BigEndian.PutUint32(tmp[0:4], 0) // len placeholder
	binary.BigEndian.PutUint32(tmp[4:8], pgProtocolV3)
	body = append(body, tmp...)
	for _, kv := range params {
		body = append(body, []byte(kv[0])...)
		body = append(body, 0)
		body = append(body, []byte(kv[1])...)
		body = append(body, 0)
	}
	body = append(body, 0)
	binary.BigEndian.PutUint32(body[0:4], uint32(len(body)))
	return body
}

func parseAll(t *testing.T, pkt []byte) [][2]string {
	t.Helper()
	if binary.BigEndian.Uint32(pkt[0:4]) != uint32(len(pkt)) {
		t.Fatal("bad len")
	}
	p, err := parseStartupParams(pkt[8:])
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestRewriteStripsOnlyRoutingToken(t *testing.T) {
	pkt := buildStartup(t, [][2]string{
		{"user", "u"},
		{"database", "d"},
		{"options", "-c omnidb.mode=pooled -c search_path=foo"},
		{"application_name", "omnidb"},
	})
	params := parseAll(t, pkt)
	out, err := rewriteStartup(pkt, params)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "omnidb.mode") {
		t.Fatal("routing token leaked downstream")
	}
	if !strings.Contains(string(out), "search_path=foo") {
		t.Fatal("unrelated options token lost")
	}
	if !strings.Contains(string(out), "application_name") {
		t.Fatal("unrelated param lost")
	}
	if binary.BigEndian.Uint32(out[0:4]) != uint32(len(out)) {
		t.Fatal("length not recalculated")
	}
}

func TestRewriteDropsEmptyOptions(t *testing.T) {
	pkt := buildStartup(t, [][2]string{
		{"user", "u"},
		{"options", "-c omnidb.mode=direct"},
	})
	params := parseAll(t, pkt)
	out, err := rewriteStartup(pkt, params)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "options") {
		t.Fatal("empty options key should be dropped")
	}
}

// Fail-safe rotation: a corrupt replacement must not displace the
// last-good certificate and must not cause plaintext fallback.
func TestCertStoreKeepsLastGoodOnBadReload(t *testing.T) {
	dir := t.TempDir()
	certFile := filepath.Join(dir, "server.crt")
	keyFile := filepath.Join(dir, "server.key")
	// Use the live GOOD test certs as the initial pair (present on the
	// S-06 live rig; skip — do not fake — when absent).
	goodCrt, err := os.ReadFile("/tmp/s06live2/certs/public.crt")
	if err != nil {
		t.Skip("no live GOOD certs; refusing to fake rotation")
	}
	goodKey, err := os.ReadFile("/tmp/s06live2/certs/public.key")
	if err != nil {
		t.Skip("no live GOOD key; refusing to fake rotation")
	}
	if err := os.WriteFile(certFile, goodCrt, 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, goodKey, 0600); err != nil {
		t.Fatal(err)
	}
	store, err := newCertStore(certFile, keyFile)
	if err != nil {
		t.Fatal(err)
	}
	before := store.get()
	if before == nil {
		t.Fatal("expected initial cert")
	}
	// Corrupt the cert file (bad replacement) and force a reload check.
	if err := os.WriteFile(certFile, []byte("not a certificate"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := store.maybeReload(); err == nil {
		t.Fatal("expected reload error for corrupt cert")
	}
	after := store.get()
	if after == nil {
		t.Fatal("last-good certificate lost after failed reload")
	}
	if string(after.Certificate[0]) != string(before.Certificate[0]) {
		t.Fatal("failed reload displaced the last-good certificate")
	}
}

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
	out, err := rewriteStartup(pkt, params, "abc123def456")
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
	out, err := rewriteStartup(pkt, params, "abc123def456")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "options") {
		t.Fatal("empty options key should be dropped")
	}
}

// Downstream application_name is always server-generated omnidb:<sid>.
// Client-supplied values (including spoofed SIDs) are overwritten, never
// trusted; missing keys are added. Routing tokens still stripped.
func TestRewriteForcesDownstreamAppName(t *testing.T) {
	cases := [][][2]string{
		{
			{"user", "u"},
			{"options", "-c omnidb.mode=pooled"},
			{"application_name", "attacker-chosen"},
		},
		{
			{"user", "u"},
			{"options", "-c omnidb.mode=direct"},
			{"application_name", "omnidb:ffffffffffff"},
		},
		{
			{"user", "u"},
			{"options", "-c omnidb.mode=pooled -c omnidb.pool_profile=high_concurrency"},
		},
	}
	for i, p := range cases {
		pkt := buildStartup(t, p)
		params := parseAll(t, pkt)
		out, err := rewriteStartup(pkt, params, "abc123def456")
		if err != nil {
			t.Fatal(err)
		}
		got := parseAll(t, out)
		found := ""
		for _, kv := range got {
			if kv[0] == "application_name" {
				found = kv[1]
			}
			if kv[0] == "options" {
				if strings.Contains(kv[1], "omnidb.mode") || strings.Contains(kv[1], "omnidb.pool_profile") {
					t.Fatalf("case %d: routing leaked: %q", i, kv[1])
				}
			}
		}
		if found != "omnidb:abc123def456" {
			t.Fatalf("case %d: application_name = %q, want omnidb:abc123def456", i, found)
		}
	}
}

// Invalid SIDs fail closed: no packet is produced.
func TestRewriteRejectsBadSid(t *testing.T) {
	pkt := buildStartup(t, [][2]string{
		{"user", "u"},
		{"options", "-c omnidb.mode=direct"},
	})
	params := parseAll(t, pkt)
	for _, bad := range []string{"", "short", "ABC123DEF456", "has space", "omnidb:x"} {
		if _, err := rewriteStartup(pkt, params, bad); err == nil {
			t.Fatalf("expected error for sid %q", bad)
		}
	}
}

// Fail-safe rotation: a corrupt replacement must not displace the
// last-good certificate and must not cause plaintext fallback.
func TestCertStoreKeepsLastGoodOnBadReload(t *testing.T) {
	dir := t.TempDir()
	certFile := filepath.Join(dir, "server.crt")
	keyFile := filepath.Join(dir, "server.key")
	// Use the live GOOD test certs as the initial pair (present on the
	// Live rig; skip — do not fake — when absent).
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

// Profile contract — valid mode/profile combinations.
func TestExtractRouteValid(t *testing.T) {
	cases := []struct {
		in      string
		mode    string
		profile string
	}{
		{"-c omnidb.mode=direct", "direct", ""},
		{"-c omnidb.mode=pooled", "pooled", ""},
		{"-c omnidb.mode=pooled -c omnidb.pool_profile=standard", "pooled", "standard"},
		{"-c omnidb.mode=pooled -c omnidb.pool_profile=high_concurrency", "pooled", "high_concurrency"},
		{"-c omnidb.pool_profile=standard -c omnidb.mode=pooled", "pooled", "standard"},
		{"-c search_path=foo -c omnidb.mode=pooled -c omnidb.pool_profile=standard", "pooled", "standard"},
	}
	for _, c := range cases {
		m, p, err := extractRoute(c.in)
		if err != nil || m != c.mode || p != c.profile {
			t.Errorf("%q: got %q,%q,%v want %q,%q", c.in, m, p, err, c.mode, c.profile)
		}
	}
}

func TestExtractRouteRejects(t *testing.T) {
	bad := []string{
		"-c omnidb.mode=direct -c omnidb.pool_profile=standard",   // profile on direct
		"-c omnidb.mode=direct -c omnidb.pool_profile=high_concurrency",
		"-c omnidb.mode=pooled -c omnidb.pool_profile=banana",     // unknown
		"-c omnidb.mode=pooled -c omnidb.pool_profile=Standard",   // case-sensitive
		"-c omnidb.mode=pooled -c omnidb.pool_profile=STANDARD",
		"-c omnidb.mode=pooled -c omnidb.pool_profile=",           // empty
		"-c omnidb.mode=pooled -c omnidb.pool_profile=standard -c omnidb.pool_profile=standard", // duplicate same
		"-c omnidb.mode=pooled -c omnidb.pool_profile=standard -c omnidb.pool_profile=high_concurrency", // duplicate different
		"omnidb.pool_profile=standard -c omnidb.mode=pooled",      // bare profile without -c
		"-c omnidb.mode=pooled omnidb.pool_profile=standard",      // bare second token
		"-c omnidb.mode=pooled -c omnidb.pool_profile=a_very_long_profile_name_over_32_chars_xx",
		"-c omnidb.mode=pooled -c omnidb.pool_profile=has-dash",
		"-c omnidb.mode=pooled -c omnidb.pool_profile=has space",
		"-c omnidb.mode=direct -c omnidb.mode=pooled",             // duplicate mode still rejected
	}
	for _, in := range bad {
		if _, _, err := extractRoute(in); err == nil {
			t.Errorf("%q: expected rejection", in)
		}
	}
}

func TestStripBothRoutingTokens(t *testing.T) {
	cases := map[string]string{
		"-c omnidb.mode=pooled -c omnidb.pool_profile=standard":            "",
		"-c omnidb.pool_profile=standard -c omnidb.mode=pooled":            "",
		"-c omnidb.mode=pooled -c omnidb.pool_profile=high_concurrency":    "",
		"-c search_path=foo -c omnidb.mode=pooled -c omnidb.pool_profile=standard": "-c search_path=foo",
		"-c a=1 -c omnidb.mode=pooled -c omnidb.pool_profile=standard -c b=2":      "-c a=1 -c b=2",
		"-c myopt=\"a  b\" -c omnidb.mode=pooled -c omnidb.pool_profile=standard":  "-c myopt=\"a  b\"",
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

func TestRewriteStripsBothTokensNoLeak(t *testing.T) {
	pkt := buildStartup(t, [][2]string{
		{"user", "u"},
		{"options", "-c omnidb.mode=pooled -c omnidb.pool_profile=high_concurrency -c search_path=foo"},
	})
	params := parseAll(t, pkt)
	out, err := rewriteStartup(pkt, params, "abc123def456")
	if err != nil {
		t.Fatal(err)
	}
	s := string(out)
	if strings.Contains(s, "omnidb.mode") || strings.Contains(s, "omnidb.pool_profile") {
		t.Fatalf("routing leaked: %q", s)
	}
	if !strings.Contains(s, "search_path=foo") {
		t.Fatal("unrelated option lost")
	}
}

func TestCancelLabelIsolation(t *testing.T) {
	cancels = &cancelRouter{}
	cancels.put(11, 22, backendPooled)
	cancels.put(33, 44, backendPooledHc)
	cancels.put(55, 66, backendDirect)
	if v, _ := cancels.get(11, 22); v != backendPooled {
		t.Fatalf("standard label lost: %q", v)
	}
	if v, _ := cancels.get(33, 44); v != backendPooledHc {
		t.Fatalf("hc label lost: %q", v)
	}
	if v, _ := cancels.get(55, 66); v != backendDirect {
		t.Fatalf("direct label lost: %q", v)
	}
	// Unknown key fails closed.
	if _, ok := cancels.get(99, 99); ok {
		t.Fatal("unknown cancel should fail closed")
	}
	cancels = &cancelRouter{}
}

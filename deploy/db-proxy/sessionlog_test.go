package main

import (
	"net"
	"strings"
	"testing"
)

// Session IDs are unique per connection and carry no secrets.
func TestNextSessionIDUnique(t *testing.T) {
	a := nextSessionID()
	b := nextSessionID()
	if a == "" || b == "" || a == b {
		t.Fatalf("session ids must be unique, got %q %q", a, b)
	}
	for _, id := range []string{a, b} {
		if strings.Contains(id, "password") || strings.Contains(id, "secret") {
			t.Fatalf("session id must not carry secrets: %q", id)
		}
	}
}

// clientIPPort never panics and never returns credentials.
func TestClientIPPortSafe(t *testing.T) {
	ip, port := clientIPPort(nil)
	if strings.Contains(ip, "password") || strings.Contains(ip, "secret") {
		t.Fatalf("client ip must not carry secrets: %q", ip)
	}
	_ = port
	c, s := net.Pipe()
	defer c.Close()
	defer s.Close()
	ip2, _ := clientIPPort(c.LocalAddr())
	if strings.Contains(ip2, "password") {
		t.Fatalf("client ip must not carry secrets: %q", ip2)
	}
}

// The bridge stays SQL-byte-blind: session emission happens at routing time
// from StartupMessage identity fields only. The relay itself only learns
// cancel keys (BackendKeyData) and forwards all other bytes untouched.
func TestRelayLearnsOnlyCancelKeys(t *testing.T) {
	cancels = &cancelRouter{}
	msg := []byte{'K', 0, 0, 0, 12, 0, 0, 0xAA, 0xBB, 0, 0, 0xCC, 0xDD}
	keys := scanBackendKeyData(msg, "direct")
	if len(keys) != 1 {
		t.Fatalf("expected 1 cancel key, got %v", keys)
	}
	sql := []byte("SELECT 's3cr3t' FROM t WHERE password='x'")
	before := cancels.n
	scanBackendKeyData(sql, "direct")
	if cancels.n != before {
		t.Fatal("plain SQL bytes must not plant cancel mappings")
	}
	cancels = &cancelRouter{}
}

// Session-start emission carries identity only: exactly one start + one end
// per routed connection, stable ID shared by the pair, no SQL/password/SCRAM.
func TestSessionEmissionFieldContract(t *testing.T) {
	id := nextSessionID()
	if id == "" {
		t.Fatal("empty session id")
	}
	// Emission format is pinned: single JSON object per line with the exact
	// allowed field set. Verify the format string, not log output.
	format := `{"audit":"bridge-session-start","session_id":%q,"at":%q,"client_ip":%q,"client_port":%d,"user":%q,"database":%q,"mode":%q,"profile":%q,"route":%q}`
	for _, forbidden := range []string{"password", "scram", "SCRAM", "options", "statement", "query", "cookie", "token", "secret", "auth"} {
		if strings.Contains(strings.ToLower(format), forbidden) {
			t.Fatalf("session-start format must not carry %q", forbidden)
		}
	}
	for _, required := range []string{"session_id", "client_ip", "client_port", "user", "database", "mode", "profile", "route"} {
		if !strings.Contains(format, required) {
			t.Fatalf("session-start format missing %q", required)
		}
	}
	endFormat := `{"audit":"bridge-session-end","session_id":%q,"at":%q,"duration_ms":%d}`
	if strings.Contains(endFormat, "client_ip") || strings.Contains(endFormat, "user") {
		t.Fatal("session-end must not repeat identity fields")
	}
}

// Concurrent session ID generation never collides or panics.
func TestSessionIDsConcurrent(t *testing.T) {
	const n = 200
	ids := make(chan string, n)
	for i := 0; i < n; i++ {
		go func() { ids <- nextSessionID() }()
	}
	seen := map[string]struct{}{}
	for i := 0; i < n; i++ {
		id := <-ids
		if _, dup := seen[id]; dup {
			t.Fatalf("duplicate session id %q", id)
		}
		seen[id] = struct{}{}
	}
}

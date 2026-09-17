package main

import (
	"crypto/rand"
	"fmt"
	"log"
	"net"
	"os"
	"sync/atomic"
	"time"
)

// Structured bridge session-context events.
//
// The bridge stays SQL-byte-blind: this file emits exactly one JSON line per
// accepted session at routing time (session-start) and one at close
// (session-end). Allowed fields only: timestamp, session id, client IP/port,
// StartupMessage username, database, mode, pool profile, downstream route,
// and lifecycle timestamps. No passwords, no SCRAM material, no raw
// StartupMessage bytes, no SQL, no results.
//
// Consumers (the query-audit collector) join session-start identity with
// downstream database-native telemetry. For pooled legs the joined identity
// is ingress context, never per-statement authority.
//
// Exact correlation uses the short SID carried downstream as
// application_name=omnidb:<sid> (see protocol.go rewriteStartup). The long
// session_id stays for operator/log correlation; the short sid is the
// join key the collector uses for deterministic per-statement IP lookup.
// The sid carries no IP, username, database, password, or secret — it is a
// random opaque identifier only.

var sessionSeq uint64

// ShortSIDLen is the fixed length of the downstream correlation token.
const shortSIDLen = 12

func nextSessionID() string {
	n := atomic.AddUint64(&sessionSeq, 1)
	host, _ := os.Hostname()
	if host == "" {
		host = "bridge"
	}
	return fmt.Sprintf("%s-%d-%d", host, time.Now().Unix(), n)
}

// nextShortSid generates a 12-char opaque correlation token ([0-9a-f]).
// Crypto-random primarily (negligible collision across restarts); falls back
// to time+atomic hex when randomness is unavailable so a SID is always
// produced. Contains no IP, user, database, or secret.
func nextShortSid() string {
	var b [6]byte
	if _, err := rand.Read(b[:]); err == nil {
		const hexd = "0123456789abcdef"
		out := make([]byte, shortSIDLen)
		for i := 0; i < 6; i++ {
			out[2*i] = hexd[b[i]>>4]
			out[2*i+1] = hexd[b[i]&0x0f]
		}
		return string(out)
	}
	n := atomic.AddUint64(&sessionSeq, 1)
	t := uint64(time.Now().UnixNano())
	return fmt.Sprintf("%06x%06x", t&0xffffff, n&0xffffff)
}

// isValidSid reports whether s is a well-formed short SID ([a-z0-9]{8,32}).
// The collector enforces the same rule; client-supplied values are never
// trusted (the bridge always overwrites downstream application_name).
func isValidSid(s string) bool {
	if len(s) < 8 || len(s) > 32 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') {
			continue
		}
		return false
	}
	return true
}

func clientIPPort(a net.Addr) (string, int) {
	if ta, ok := a.(*net.TCPAddr); ok && ta != nil {
		return ta.IP.String(), ta.Port
	}
	return "", 0
}

// emitSessionStart logs one JSON session-start line to stderr (captured by
// the container json-file driver). Field values come from the already-parsed
// routing decision; secrets are never in scope here.
func emitSessionStart(sessionID, sid string, raw net.Conn, user, database, mode, profile, route string) time.Time {
	now := time.Now().UTC()
	ip, port := clientIPPort(raw.RemoteAddr())
	log.Printf(`{"audit":"bridge-session-start","session_id":%q,"sid":%q,"at":%q,"client_ip":%q,"client_port":%d,"user":%q,"database":%q,"mode":%q,"profile":%q,"route":%q}`,
		sessionID, sid, now.Format(time.RFC3339Nano), ip, port, user, database, mode, profile, route)
	return now
}

func emitSessionEnd(sessionID, sid string, started time.Time) {
	now := time.Now().UTC()
	log.Printf(`{"audit":"bridge-session-end","session_id":%q,"sid":%q,"at":%q,"duration_ms":%d}`,
		sessionID, sid, now.Format(time.RFC3339Nano), now.Sub(started).Milliseconds())
}

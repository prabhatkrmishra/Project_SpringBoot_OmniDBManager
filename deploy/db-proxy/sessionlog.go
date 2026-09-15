package main

import (
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

var sessionSeq uint64

func nextSessionID() string {
	n := atomic.AddUint64(&sessionSeq, 1)
	host, _ := os.Hostname()
	if host == "" {
		host = "bridge"
	}
	return fmt.Sprintf("%s-%d-%d", host, time.Now().Unix(), n)
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
func emitSessionStart(sessionID string, raw net.Conn, user, database, mode, profile, route string) time.Time {
	now := time.Now().UTC()
	ip, port := clientIPPort(raw.RemoteAddr())
	log.Printf(`{"audit":"bridge-session-start","session_id":%q,"at":%q,"client_ip":%q,"client_port":%d,"user":%q,"database":%q,"mode":%q,"profile":%q,"route":%q}`,
		sessionID, now.Format(time.RFC3339Nano), ip, port, user, database, mode, profile, route)
	return now
}

func emitSessionEnd(sessionID string, started time.Time) {
	now := time.Now().UTC()
	log.Printf(`{"audit":"bridge-session-end","session_id":%q,"at":%q,"duration_ms":%d}`,
		sessionID, now.Format(time.RFC3339Nano), now.Sub(started).Milliseconds())
}

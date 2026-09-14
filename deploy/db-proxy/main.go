// Single-host TLS-bridge Database Proxy.
//
// Public contract: db.example.com:15432 serves BOTH modes. The mode comes
// from the StartupMessage `options` field AFTER client TLS termination:
// `-c omnidb.mode=direct` -> PostgreSQL, `-c omnidb.mode=pooled` ->
// PgBouncer. SNI alone cannot split one hostname.
//
// State machine per connection:
//
//	TCP accept -> PG SSL negotiation (SSLRequest->S, GSSENC->N,
//	CancelRequest->dedicated path, cleartext startup->reject) -> TLS server
//	handshake (SNI must equal the public host) -> bounded StartupMessage
//	read -> extract exactly one omnidb.mode -> backend TLS (verify-full) ->
//	forward rewritten StartupMessage (routing token consumed) -> blind relay.
//
// The proxy parses ONLY negotiation bytes, ClientHello/SNI, StartupMessage
// routing options, and BackendKeyData (for cancel routing). Passwords, SCRAM,
// SQL and results are never parsed, logged, or persisted.
package main

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	pgSSLRequest     = 80877103
	pgGSSENCRequest  = 80877104
	pgCancelRequest  = 80877102
	pgProtocolV3     = 196608
	maxStartupBytes  = 16 * 1024
	handshakeTimeout = 10 * time.Second
	backendTimeout   = 5 * time.Second
	firstByteTimeout = 10 * time.Second
)

type config struct {
	listenAddr   string
	publicHost   string
	directAddr   string
	pooledAddr   string
	pooledHcAddr string
	certFile     string
	keyFile      string
	backendCA    string
	maxConns     int
	directSAN    string
	pooledSAN    string
	pooledHcSAN  string
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func loadConfig() config {
	port := getenv("PROXY_PORT", "15432")
	maxConns := 2000
	if v := getenv("PROXY_MAX_CONNS", ""); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			maxConns = n
		}
	}
	return config{
		listenAddr:   ":" + port,
		publicHost:   strings.ToLower(getenv("PROXY_PUBLIC_HOST", "db.example.com")),
		directAddr:   getenv("PROXY_DIRECT_ADDR", "postgres:5432"),
		pooledAddr:   getenv("PROXY_POOLED_ADDR", "pgbouncer:6432"),
		pooledHcAddr: getenv("PROXY_POOLED_HC_ADDR", "pgbouncer-hc:6433"),
		certFile:     getenv("PROXY_CERT_FILE", "/certs/server.crt"),
		keyFile:      getenv("PROXY_KEY_FILE", "/certs/server.key"),
		backendCA:    getenv("PROXY_BACKEND_CA", "/certs/ca.crt"),
		maxConns:     maxConns,
		directSAN:    getenv("PROXY_DIRECT_SAN", "postgres"),
		pooledSAN:    getenv("PROXY_POOLED_SAN", "pgbouncer"),
		pooledHcSAN:  getenv("PROXY_POOLED_HC_SAN", "pgbouncer-hc"),
	}
}

// cancelRoute remembers which backend owns a session so a CancelRequest on a
// fresh TCP connection reaches the exact backend. Bounded, in-memory only,
// secret keys never logged; entries die with the owning connection.
type cancelRouter struct {
	mu sync.Mutex
	m  map[string]string // "pid:secret" -> "direct"|"pooled"
	n  int
}

func (c *cancelRouter) put(pid, secret uint32, backend string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = make(map[string]string)
	}
	if c.n > 100000 {
		return // bounded: drop tracking under absurd load, cancel may fail closed
	}
	k := fmt.Sprintf("%d:%d", pid, secret)
	if _, ok := c.m[k]; !ok {
		c.n++
	}
	c.m[k] = backend
}

func (c *cancelRouter) get(pid, secret uint32) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	v, ok := c.m[fmt.Sprintf("%d:%d", pid, secret)]
	return v, ok
}

func (c *cancelRouter) del(pid, secret uint32) {
	c.mu.Lock()
	defer c.mu.Unlock()
	k := fmt.Sprintf("%d:%d", pid, secret)
	if _, ok := c.m[k]; ok {
		delete(c.m, k)
		c.n--
	}
}

var cancels = &cancelRouter{}

func main() {
	cfg := loadConfig()
	// Fail-safe public identity: the initial cert/key must load or the
	// proxy refuses to start (fail closed, never plaintext). Later
	// rotations are hot-reloaded in the background; a bad replacement
	// keeps serving the last-good certificate (see certStore).
	store, err := newCertStore(cfg.certFile, cfg.keyFile)
	if err != nil {
		log.Fatalf("proxy: load public cert/key: %v", err)
	}
	poll := getenv("PROXY_CERT_POLL_SECONDS", "30")
	pollSecs, err := strconv.Atoi(poll)
	if err != nil || pollSecs < 1 {
		pollSecs = 30
	}
	go store.watch(time.Duration(pollSecs) * time.Second)
	caPEM, err := os.ReadFile(cfg.backendCA)
	if err != nil {
		log.Fatalf("proxy: read backend CA: %v", err)
	}
	caPool := x509.NewCertPool()
	if !caPool.AppendCertsFromPEM(caPEM) {
		log.Fatalf("proxy: invalid backend CA PEM")
	}

	ln, err := net.Listen("tcp", cfg.listenAddr)
	if err != nil {
		log.Fatalf("proxy: listen %s: %v", cfg.listenAddr, err)
	}
	sem := make(chan struct{}, cfg.maxConns)
	log.Printf("proxy: single-host TLS-bridge listening %s public=%s direct=%s pooled=%s pooled-hc=%s",
		cfg.listenAddr, cfg.publicHost, cfg.directAddr, cfg.pooledAddr, cfg.pooledHcAddr)
	for {
		raw, err := ln.Accept()
		if err != nil {
			log.Printf("proxy: accept: %v", err)
			continue
		}
		select {
		case sem <- struct{}{}:
		default:
			raw.Close()
			continue // max conns: fail closed, no backend contact
		}
		go func(c net.Conn) {
			defer func() { <-sem }()
			handleConn(c, cfg, store, caPool)
		}(raw)
	}
}

// certStore holds the public TLS identity with fail-safe hot-reload.
//
// A corrupt/invalid replacement never displaces the last-good certificate
// and never causes plaintext fallback: failed reloads are logged and new
// handshakes keep using the previous certificate. Existing connections are
// unaffected (they already completed their handshake).
type certStore struct {
	certFile string
	keyFile  string
	mu       sync.RWMutex
	cert     *tls.Certificate
	last     [2]time.Time // modtimes of cert,key at the last successful load
}

func newCertStore(certFile, keyFile string) (*certStore, error) {
	c, mt, err := loadCertPair(certFile, keyFile)
	if err != nil {
		return nil, err
	}
	return &certStore{certFile: certFile, keyFile: keyFile, cert: c, last: mt}, nil
}

func loadCertPair(certFile, keyFile string) (*tls.Certificate, [2]time.Time, error) {
	var mt [2]time.Time
	c, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, mt, err
	}
	if len(c.Certificate) == 0 {
		return nil, mt, fmt.Errorf("empty certificate chain")
	}
	// Parse the leaf now so a corrupt cert fails fast instead of failing
	// every future handshake after a swap.
	if _, err := x509.ParseCertificate(c.Certificate[0]); err != nil {
		return nil, mt, fmt.Errorf("parse leaf certificate: %w", err)
	}
	if fi, err := os.Stat(certFile); err == nil {
		mt[0] = fi.ModTime()
	}
	if fi, err := os.Stat(keyFile); err == nil {
		mt[1] = fi.ModTime()
	}
	return &c, mt, nil
}

func (s *certStore) get() *tls.Certificate {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cert
}

// maybeReload reloads the pair when either file changed since the last
// successful load. It reports whether a reload happened; a failed reload
// keeps the last-good certificate (caller only logs the error).
func (s *certStore) maybeReload() (bool, error) {
	var cur [2]time.Time
	if fi, err := os.Stat(s.certFile); err == nil {
		cur[0] = fi.ModTime()
	}
	if fi, err := os.Stat(s.keyFile); err == nil {
		cur[1] = fi.ModTime()
	}
	s.mu.RLock()
	unchanged := cur == s.last && s.cert != nil
	s.mu.RUnlock()
	if unchanged {
		return false, nil
	}
	c, mt, err := loadCertPair(s.certFile, s.keyFile)
	if err != nil {
		return false, err
	}
	s.mu.Lock()
	s.cert = c
	s.last = mt
	s.mu.Unlock()
	return true, nil
}

func (s *certStore) watch(poll time.Duration) {
	if poll <= 0 {
		poll = 30 * time.Second
	}
	t := time.NewTicker(poll)
	defer t.Stop()
	for range t.C {
		reloaded, err := s.maybeReload()
		if err != nil {
			// Fail-safe: keep serving the last-good certificate.
			// The error carries no key material (file/parse errors only).
			log.Printf("proxy: public cert reload failed, keeping last good: %v", err)
			continue
		}
		if reloaded {
			log.Printf("proxy: public certificate reloaded")
		}
	}
}

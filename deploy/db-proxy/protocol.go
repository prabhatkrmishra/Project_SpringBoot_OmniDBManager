package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"time"
)

// readInitPacket reads the 8-byte PG init header (len + code) plus the
// StartupMessage body when present. Returns code, body, isStartup.
func readInitPacket(c net.Conn) (uint32, []byte, bool, error) {
	hdr := make([]byte, 8)
	c.SetReadDeadline(time.Now().Add(handshakeTimeout))
	if _, err := io.ReadFull(c, hdr); err != nil {
		return 0, nil, false, err
	}
	length := binary.BigEndian.Uint32(hdr[0:4])
	code := binary.BigEndian.Uint32(hdr[4:8])
	if length < 8 || length > maxStartupBytes+8 {
		return 0, nil, false, fmt.Errorf("bad init length %d", length)
	}
	rest := make([]byte, length-8)
	if _, err := io.ReadFull(c, rest); err != nil {
		return 0, nil, false, err
	}
	// SSLRequest/GSSENC/Cancel carry no body beyond the 8 bytes.
	if code == pgSSLRequest || code == pgGSSENCRequest || code == pgCancelRequest {
		// SSLRequest/GSSENC are 8 bytes (len+code); Cancel is 16
		// (len+code+pid+key).
		if code == pgCancelRequest {
			if length != 16 {
				return 0, nil, false, fmt.Errorf("bad cancel length %d", length)
			}
		} else if length != 8 {
			return 0, nil, false, fmt.Errorf("bad preamble length %d", length)
		}
		// For cancel, rest holds pid+key (8 bytes).
		return code, rest, false, nil
	}
	// Otherwise it is a cleartext StartupMessage -> reject at call site.
	full := append(hdr, rest...)
	return code, full, true, nil
}

// parseStartup splits a StartupMessage body (after len+proto) into ordered
// key/value pairs without interpreting credentials.
func parseStartupParams(body []byte) ([][2]string, error) {
	var out [][2]string
	parts := bytes.Split(body, []byte{0})
	// parts alternate key,value,key,value,...,final empty
	for i := 0; i+1 < len(parts); i += 2 {
		k := string(parts[i])
		if k == "" {
			break
		}
		if strings.ContainsRune(k, '\x00') || len(k) > 256 {
			return nil, fmt.Errorf("bad startup key")
		}
		v := string(parts[i+1])
		if len(v) > maxStartupBytes {
			return nil, fmt.Errorf("bad startup value")
		}
		out = append(out, [2]string{k, v})
	}
	return out, nil
}

// extractMode finds exactly one "-c omnidb.mode=<direct|pooled>" token in the
// options value. Anything else -> fail closed.
func extractMode(options string) (string, error) {
	toks := strings.Fields(options)
	found := ""
	count := 0
	for i := 0; i < len(toks); i++ {
		if toks[i] == "-c" && i+1 < len(toks) {
			kv := toks[i+1]
			if strings.HasPrefix(kv, "omnidb.mode=") {
				count++
				found = strings.TrimPrefix(kv, "omnidb.mode=")
			}
			i++
			continue
		}
		if strings.HasPrefix(toks[i], "omnidb.mode=") {
			// bare token without -c is not a valid carrier
			return "", fmt.Errorf("malformed mode token")
		}
	}
	if count != 1 {
		return "", fmt.Errorf("mode count=%d", count)
	}
	if found != "direct" && found != "pooled" {
		return "", fmt.Errorf("bad mode")
	}
	return found, nil
}

// rewriteStartup removes only the routing "-c omnidb.mode=<v>" token pair
// from options, preserving every other byte of the options value exactly
// (whitespace, quoting, order) and every other param byte-identically. If
// options becomes empty, the key is dropped. Returns the new full packet.
// S-06.1 P2 fix: the previous Fields+Join re-encoding collapsed repeated
// whitespace (e.g. '-c myopt="a  b"' became '"a b"'); the routing span is
// now excised from the original string instead.
func rewriteStartup(full []byte, params [][2]string) ([]byte, error) {
	var rebuilt [][2]string
	for _, kv := range params {
		if kv[0] != "options" {
			rebuilt = append(rebuilt, kv)
			continue
		}
		stripped, ok := stripRoutingToken(kv[1])
		if !ok {
			continue // drop empty options key
		}
		rebuilt = append(rebuilt, [2]string{"options", stripped})
	}
	buf := new(bytes.Buffer)
	// placeholder len + proto
	binary.Write(buf, binary.BigEndian, uint32(0))
	binary.Write(buf, binary.BigEndian, uint32(pgProtocolV3))
	for _, kv := range rebuilt {
		buf.WriteString(kv[0])
		buf.WriteByte(0)
		buf.WriteString(kv[1])
		buf.WriteByte(0)
	}
	buf.WriteByte(0)
	b := buf.Bytes()
	binary.BigEndian.PutUint32(b[0:4], uint32(len(b)))
	return b, nil
}

// stripRoutingToken excises exactly the "-c omnidb.mode=<v>" token pair
// from an options value, returning the remainder byte-identically (aside
// from the excision point, where surrounding whitespace collapses to at
// most one space, trimmed at the ends). ok=false means nothing remains.
func stripRoutingToken(opt string) (rest string, ok bool) {
	type span struct{ s, e int }
	var toks []span
	i, n := 0, len(opt)
	for i < n {
		// skip whitespace
		for i < n && (opt[i] == ' ' || opt[i] == '\t' || opt[i] == '\n' || opt[i] == '\r' || opt[i] == '\v' || opt[i] == '\f') {
			i++
		}
		if i >= n {
			break
		}
		s := i
		for i < n && opt[i] != ' ' && opt[i] != '\t' && opt[i] != '\n' && opt[i] != '\r' && opt[i] != '\v' && opt[i] != '\f' {
			i++
		}
		toks = append(toks, span{s, i})
	}
	cut := -1
	for j := 0; j+1 < len(toks); j++ {
		if opt[toks[j].s:toks[j].e] == "-c" && strings.HasPrefix(opt[toks[j+1].s:toks[j+1].e], "omnidb.mode=") {
			cut = j
			break
		}
	}
	if cut < 0 {
		// No routing pair: return the value as-is (caller only invokes
		// this for options values already known to carry routing, but
		// stay total for direct unit use).
		t := strings.Trim(opt, " \t\n\r\v\f")
		if t == "" {
			return "", false
		}
		return opt, true
	}
	s, e := toks[cut].s, toks[cut+1].e
	left, right := opt[:s], opt[e:]
	// Collapse the joint to at most one space when both sides continue.
	lSpace := left != "" && isOptSpace(left[len(left)-1])
	rSpace := right != "" && isOptSpace(right[0])
	switch {
	case lSpace && rSpace:
		rest = left + right[1:]
	case !lSpace && !rSpace && left != "" && right != "":
		rest = left + " " + right
	default:
		rest = left + right
	}
	rest = strings.Trim(rest, " \t\n\r\v\f")
	if rest == "" {
		return "", false
	}
	return rest, true
}

func isOptSpace(c byte) bool {
	return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f'
}

func sendError(c net.Conn, msg string) {
	// Minimal PG ErrorResponse (S FATAL, V FATAL, C 08004, M msg).
	var body bytes.Buffer
	body.WriteByte('S')
	body.WriteString("FATAL")
	body.WriteByte(0)
	body.WriteByte('V')
	body.WriteString("FATAL")
	body.WriteByte(0)
	body.WriteByte('C')
	body.WriteString("08004")
	body.WriteByte(0)
	body.WriteByte('M')
	body.WriteString(msg)
	body.WriteByte(0)
	body.WriteByte(0)
	bb := body.Bytes()
	var hdr [5]byte
	hdr[0] = 'E'
	binary.BigEndian.PutUint32(hdr[1:5], uint32(len(bb)+4))
	c.Write(hdr[:])
	c.Write(bb)
}

func handleConn(raw net.Conn, cfg config, store *certStore, caPool *x509.CertPool) {
	raw.SetDeadline(time.Now().Add(handshakeTimeout))
	code, rest, isStartup, err := readInitPacket(raw)
	if err != nil {
		raw.Close()
		return
	}
	switch {
	case code == pgGSSENCRequest:
		raw.Write([]byte("N"))
		raw.Close()
		return
	case code == pgCancelRequest:
		handleCancel(raw, cfg, rest, caPool)
		return
	case code == pgSSLRequest:
		raw.Write([]byte("S"))
	case isStartup:
		sendError(raw, "connection requires SSL")
		raw.Close()
		return
	default:
		raw.Close()
		return
	}
	cert := store.get()
	if cert == nil {
		raw.Close()
		return
	}
	// TLS server handshake; enforce SNI == public host.
	tlsConn := tls.Server(raw, &tls.Config{
		Certificates: []tls.Certificate{*cert},
		MinVersion:   tls.VersionTLS12,
	})
	tlsConn.SetDeadline(time.Now().Add(handshakeTimeout))
	if err := tlsConn.Handshake(); err != nil {
		raw.Close()
		return
	}
	st := tlsConn.ConnectionState()
	sni := strings.ToLower(st.ServerName)
	// Some drivers omit SNI; require it — fail closed per spec.
	if sni == "" || sni != cfg.publicHost {
		sendError(tlsConn, "unknown server name")
		tlsConn.Close()
		return
	}
	// Read StartupMessage (TLS framing: 4-byte len first).
	lenBuf := make([]byte, 4)
	tlsConn.SetReadDeadline(time.Now().Add(handshakeTimeout))
	if _, err := io.ReadFull(tlsConn, lenBuf); err != nil {
		tlsConn.Close()
		return
	}
	slen := binary.BigEndian.Uint32(lenBuf)
	if slen < 8 || slen > uint32(maxStartupBytes) {
		sendError(tlsConn, "bad startup packet")
		tlsConn.Close()
		return
	}
	sbody := make([]byte, slen-4)
	if _, err := io.ReadFull(tlsConn, sbody); err != nil {
		tlsConn.Close()
		return
	}
	if binary.BigEndian.Uint32(sbody[0:4]) != pgProtocolV3 {
		sendError(tlsConn, "unsupported protocol")
		tlsConn.Close()
		return
	}
	params, err := parseStartupParams(sbody[4:])
	if err != nil {
		sendError(tlsConn, "bad startup packet")
		tlsConn.Close()
		return
	}
	var options string
	for _, kv := range params {
		if kv[0] == "options" {
			options = kv[1]
			break
		}
	}
	mode, err := extractMode(options)
	if err != nil || options == "" {
		sendError(tlsConn, "missing or invalid connection mode")
		tlsConn.Close()
		return
	}
	backendAddr, backendSAN := cfg.directAddr, cfg.directSAN
	if mode == "pooled" {
		backendAddr, backendSAN = cfg.pooledAddr, cfg.pooledSAN
	}
	rewritten, err := rewriteStartup(append(lenBuf, sbody...), params)
	if err != nil {
		sendError(tlsConn, "bad startup packet")
		tlsConn.Close()
		return
	}
	// Zero the routing copy ASAP; keep only mode/backend labels.
	for i := range sbody {
		sbody[i] = 0
	}
	options = ""
	backend, err := dialBackend(cfg, caPool, backendAddr, backendSAN, rewritten)
	if err != nil {
		log.Printf("proxy: backend dial mode=%s err=%v", mode, err)
		sendError(tlsConn, "backend unavailable")
		tlsConn.Close()
		return
	}
	log.Printf("proxy: route mode=%s backend=%s", mode, backendAddr)
	relay(tlsConn, backend, mode)
}

// dialBackend opens TLS (verify-full) to the backend, replays the rewritten
// StartupMessage, and returns the live conn for blind relay.
func dialBackend(cfg config, caPool *x509.CertPool, addr, serverName string, startup []byte) (net.Conn, error) {
	_ = cfg
	d := &net.Dialer{Timeout: backendTimeout}
	raw, err := d.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	// PostgreSQL-family backends negotiate TLS via SSLRequest ('S'), not
	// direct-TLS: send SSLRequest first, then TLS-handshake only on 'S'.
	// PgBouncer with client_tls_sslmode=require behaves the same.
	raw.SetDeadline(time.Now().Add(backendTimeout))
	var pre [8]byte
	binary.BigEndian.PutUint32(pre[0:4], 8)
	binary.BigEndian.PutUint32(pre[4:8], pgSSLRequest)
	if _, err := raw.Write(pre[:]); err != nil {
		raw.Close()
		return nil, err
	}
	resp := []byte{0}
	if _, err := io.ReadFull(raw, resp); err != nil {
		raw.Close()
		return nil, err
	}
	if resp[0] != 'S' {
		raw.Close()
		return nil, fmt.Errorf("backend refused TLS (got %q)", resp)
	}
	tc := tls.Client(raw, &tls.Config{
		RootCAs:    caPool,
		ServerName: serverName,
		MinVersion: tls.VersionTLS12,
	})
	tc.SetDeadline(time.Now().Add(firstByteTimeout))
	if err := tc.Handshake(); err != nil {
		raw.Close()
		return nil, err
	}
	// Backend certs must chain to PROXY_BACKEND_CA with the expected SAN.
	tc.SetWriteDeadline(time.Now().Add(backendTimeout))
	if _, err := tc.Write(startup); err != nil {
		tc.Close()
		return nil, err
	}
	// Clear deadlines before relay; per-conn idle timeout handled by relay copy.
	tc.SetDeadline(time.Time{})
	return tc, nil
}

// handleCancel routes CancelRequest to the owning backend via the
// BackendKeyData map. Unknown keys fail closed without backend contact.
func handleCancel(raw net.Conn, cfg config, rest []byte, caPool *x509.CertPool) {
	defer raw.Close()
	if len(rest) != 8 {
		return
	}
	pid := binary.BigEndian.Uint32(rest[0:4])
	secret := binary.BigEndian.Uint32(rest[4:8])
	backend, ok := cancels.get(pid, secret)
	if !ok {
		return // unknown cancel: fail closed, no broadcast
	}
	addr := cfg.directAddr
	san := cfg.directSAN
	if backend == "pooled" {
		addr = cfg.pooledAddr
		san = cfg.pooledSAN
	}
	d := &net.Dialer{Timeout: backendTimeout}
	braw, err := d.Dial("tcp", addr)
	if err != nil {
		return
	}
	defer braw.Close()
	// Same SSLRequest-then-TLS negotiation as the main backend leg.
	braw.SetDeadline(time.Now().Add(backendTimeout))
	var pre [8]byte
	binary.BigEndian.PutUint32(pre[0:4], 8)
	binary.BigEndian.PutUint32(pre[4:8], pgSSLRequest)
	if _, err := braw.Write(pre[:]); err != nil {
		return
	}
	resp := []byte{0}
	if _, err := io.ReadFull(braw, resp); err != nil || resp[0] != 'S' {
		return
	}
	bc := tls.Client(braw, &tls.Config{RootCAs: caPool, ServerName: san, MinVersion: tls.VersionTLS12})
	bc.SetDeadline(time.Now().Add(backendTimeout))
	if err := bc.Handshake(); err != nil {
		bc.Close()
		return
	}
	var pkt [16]byte
	binary.BigEndian.PutUint32(pkt[0:4], 16)
	binary.BigEndian.PutUint32(pkt[4:8], pgCancelRequest)
	binary.BigEndian.PutUint32(pkt[8:12], pid)
	binary.BigEndian.PutUint32(pkt[12:16], secret)
	_ = bc.SetWriteDeadline(time.Now().Add(backendTimeout))
	_, _ = bc.Write(pkt[:])
	bc.Close()
}

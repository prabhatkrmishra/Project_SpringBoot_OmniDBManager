package main

import (
	"encoding/binary"
	"io"
	"net"
	"time"
)

// relay becomes a blind bidirectional byte pump after routing. It also
// watches backend->client traffic for BackendKeyData (B + pid + secret) so a
// later CancelRequest can be pinned to the owning backend instance
// ("direct" | "pooled" = standard | "pooled-hc" = high-concurrency).
// Cancel is never broadcast: the stored label selects exactly one backend.
// No passwords, SCRAM, SQL, or results are parsed or logged.
func relay(client, backend net.Conn, mode string) {
	defer client.Close()
	defer backend.Close()
	// Keys learned on THIS connection; removed when the relay exits so the
	// shared cancel map cannot grow without bound and stale entries can
	// never outlive their owning session (S-06.1 P1 fix: cancels.del was
	// previously never called).
	learned := make(map[[2]uint32]struct{})
	defer func() {
		for k := range learned {
			cancels.del(k[0], k[1])
		}
	}()
	// Track BackendKeyData without disturbing the byte stream: snoop
	// backend->client bytes and forward them identically. NOTE the backend
	// leg is TLS-terminated at the bridge, so by the time we see these
	// bytes they are DECRYPTED PG-protocol messages — BackendKeyData
	// ('K', len 12, pid, secret) is visible here. (Client->backend traffic
	// stays an opaque copy; only cancel routing metadata is extracted.)
	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 32*1024)
		var pending []byte
		// BackendKeyData is emitted once per session during the
		// authentication phase, always before ReadyForQuery ('Z', len 5).
		// Stop learning at the first ReadyForQuery so result-row bytes
		// later in the session can never plant bogus cancel mappings
		// (S-06.1 P2 hardening). Fail-closed bias: if a 'Z' pattern ever
		// false-positives early, the only effect is that cancel tracking
		// for this connection stops (cancel fails closed), never a
		// cross-backend misroute.
		readySeen := false
		for {
			backend.SetReadDeadline(time.Now().Add(2 * time.Hour))
			n, err := backend.Read(buf)
			if n > 0 {
				chunk := append(append([]byte{}, pending...), buf[:n]...)
				if !readySeen {
					for _, k := range scanBackendKeyData(chunk, mode) {
						learned[k] = struct{}{}
					}
					readySeen = hasReadyForQuery(chunk)
				}
				// keep tail in case BackendKeyData straddles reads
				if len(chunk) > 32 {
					pending = append([]byte{}, chunk[len(chunk)-32:]...)
				} else {
					pending = chunk
				}
				client.SetWriteDeadline(time.Now().Add(2 * time.Hour))
				if werr := writeFull(client, buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()
	client.SetReadDeadline(time.Now().Add(2 * time.Hour))
	io.Copy(backend, client)
	// half-close backend write so the peer sees EOF
	if tc, ok := backend.(interface{ CloseWrite() error }); ok {
		tc.CloseWrite()
	}
	<-done
}

// writeFull loops until the whole frame is flushed; a single Write call is
// not contractually obliged to accept everything (S-06.1 P3 fix).
func writeFull(c net.Conn, b []byte) error {
	for len(b) > 0 {
		n, err := c.Write(b)
		if err != nil {
			return err
		}
		if n <= 0 {
			return io.ErrShortWrite
		}
		b = b[n:]
	}
	return nil
}

// hasReadyForQuery reports a PG ReadyForQuery message ('Z' + int32(5))
// anywhere in the byte window. Best-effort framing gate for cancel-key
// learning; see relay.
func hasReadyForQuery(b []byte) bool {
	for i := 0; i+5 <= len(b); i++ {
		if b[i] != 'Z' {
			continue
		}
		if binary.BigEndian.Uint32(b[i+1:i+5]) != 5 {
			continue
		}
		return true
	}
	return false
}

// scanBackendKeyData looks for PG BackendKeyData messages: 'K' + int32(12) +
// int32 pid + int32 secret. Best-effort; entries are short-lived and bounded.
// Returns the keys it registered so the caller can unregister them when the
// owning connection closes.
func scanBackendKeyData(b []byte, mode string) [][2]uint32 {
	var out [][2]uint32
	for i := 0; i+12 <= len(b); i++ {
		if b[i] != 'K' {
			continue
		}
		if binary.BigEndian.Uint32(b[i+1:i+5]) != 12 {
			continue
		}
		pid := binary.BigEndian.Uint32(b[i+5 : i+9])
		secret := binary.BigEndian.Uint32(b[i+9 : i+13])
		if pid == 0 && secret == 0 {
			continue
		}
		cancels.put(pid, secret, mode)
		out = append(out, [2]uint32{pid, secret})
	}
	return out
}

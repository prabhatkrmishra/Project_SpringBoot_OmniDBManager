package main

import (
	"encoding/binary"
	"testing"
)

func FuzzExtractMode(f *testing.F) {
	seeds := []string{
		"-c omnidb.mode=direct",
		"-c omnidb.mode=pooled",
		"",
		"-c search_path=foo",
		"-c omnidb.mode=direct -c omnidb.mode=pooled",
		"omnidb.mode=direct",
		"-c omnidb.mode=",
		"-c omnidb.mode=direct\x00",
		"-c omnidb.mode=Direct",
		"-c omnidb.mode=direct extra",
	}
	for _, s := range seeds {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, opt string) {
		m, err := extractMode(opt)
		if err != nil {
			return
		}
		if m != "direct" && m != "pooled" {
			t.Fatalf("accepted invalid mode %q from %q", m, opt)
		}
	})
}

func FuzzRewriteNoLeak(f *testing.F) {
	mk := func(opts string) []byte {
		return buildStartupT(opts)
	}
	for _, o := range []string{"-c omnidb.mode=direct", "-c a=1 -c omnidb.mode=pooled", "-c omnidb.mode=direct -c x=\"a  b\""} {
		f.Add([]byte(mk(o)))
	}
	f.Fuzz(func(t *testing.T, pkt []byte) {
		if len(pkt) < 8 || len(pkt) > maxStartupBytes {
			t.Skip()
		}
		params, err := parseStartupParams(pkt[8:])
		if err != nil {
			t.Skip()
		}
		out, err := rewriteStartup(pkt, params)
		if err != nil {
			t.Skip()
		}
		if binary.BigEndian.Uint32(out[0:4]) != uint32(len(out)) {
			t.Fatalf("length mismatch")
		}
		for _, kv := range params {
			if kv[0] == "options" {
				if _, err := extractMode(kv[1]); err == nil {
					// input carried routing: output must not
					if _, err2 := parseStartupParams(out[8:]); err2 == nil {
						for _, kv2 := range mustParse(out) {
							if kv2[0] == "options" {
								if _, err3 := extractMode(kv2[1]); err3 == nil {
									t.Fatalf("routing leaked: %q", kv2[1])
								}
							}
						}
					}
				}
			}
		}
	})
}

func buildStartupT(opts string) []byte {
	body := []byte{0, 0, 0, 0, 0, 3, 0, 0}
	body = append(body, []byte("user")...)
	body = append(body, 0, 'u', 0)
	body = append(body, []byte("options")...)
	body = append(body, 0)
	body = append(body, []byte(opts)...)
	body = append(body, 0, 0)
	binary.BigEndian.PutUint32(body[0:4], uint32(len(body)))
	return body
}

func mustParse(pkt []byte) [][2]string {
	p, _ := parseStartupParams(pkt[8:])
	return p
}

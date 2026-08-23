#!/usr/bin/env bash
#
# Verifies that the memory/guardrail configuration is actually in effect on
# this server: JVM heap flags, mongod connection cap and WiredTiger cache,
# container fd limit and memory cap. Compares against an expected profile -
# auto-detected from total RAM unless given explicitly.
#
# Usage: bash verify-memory-config.sh [small|medium]
#   small  = 1 GB-RAM preset  (Xmx256m, 256MB cache, 500 conns, 650m limit)
#   medium = 4 GB-RAM preset  (Xmx1g,   1GB cache,  1000 conns, 2g limit)
#
# Exit codes: 0 = all checks passed, 1 = one or more failed.
set -uo pipefail

CONTAINER="${CONTAINER:-mongodbserver-mongo}"
ENV_FILE="${ENV_FILE:-$HOME/mongodbserver/.env}"
[ -f "$ENV_FILE" ] || ENV_FILE="./.env"

PASS=0
FAIL=0
ok() { echo "  [PASS] $*"; PASS=$((PASS + 1)); }
bad() { echo "  [FAIL] $*"; FAIL=$((FAIL + 1)); }

# ─── Profile ────────────────────────────────────────────────────────────────
RAM_KB=$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
PROFILE="${1:-}"
if [ -z "$PROFILE" ]; then
  if [ "$RAM_KB" -gt 0 ] && [ "$RAM_KB" -lt 3000000 ]; then PROFILE=small; else PROFILE=medium; fi
fi
case "$PROFILE" in
  small)  EXPECT_XMX=256m; EXPECT_CACHE_MB=256; EXPECT_MAXCONNS=500; EXPECT_MEM_LIMIT=681574400 ;;
  medium) EXPECT_XMX=1g;   EXPECT_CACHE_MB=1024; EXPECT_MAXCONNS=1000; EXPECT_MEM_LIMIT=2147483648 ;;
  *) echo "Unknown profile '$PROFILE' (use: small|medium)"; exit 1 ;;
esac
if [ "$RAM_KB" -gt 0 ]; then
  echo "Profile: $PROFILE (total RAM: $((RAM_KB / 1024)) MB)"
else
  echo "Profile: $PROFILE (total RAM unknown - not Linux?)"
fi

# ─── JVM app ────────────────────────────────────────────────────────────────
echo "--- JVM app ---"
JAVA_PID=$(pgrep -f 'java .*mongodbserver-.*\.jar' | head -1 || true)
if [ -n "${JAVA_PID:-}" ]; then
  ARGS=$(tr '\0' ' ' </proc/"$JAVA_PID"/cmdline)
  XMX=$(echo "$ARGS" | grep -o '\-Xmx[0-9]*[mgk]' | head -1)
  RSS_MB=$(awk '/VmRSS/ {print int($2 / 1024)}' /proc/"$JAVA_PID"/status 2>/dev/null || echo '?')
  echo "  pid=$JAVA_PID rss=${RSS_MB}MB"
  if [ "$XMX" = "-Xmx$EXPECT_XMX" ]; then
    ok "heap cap $XMX"
  else
    bad "heap cap: expected -Xmx$EXPECT_XMX, got '${XMX:-none}'"
  fi
  if echo "$ARGS" | grep -q 'UseCompactObjectHeaders'; then
    ok "compact object headers enabled"
  else
    bad "compact object headers not enabled"
  fi
else
  bad "no running mongodbserver jar process found"
fi

# ─── Mongo container ────────────────────────────────────────────────────────
echo "--- mongo container ($CONTAINER) ---"
MEM_LIMIT=$(docker inspect "$CONTAINER" --format '{{.HostConfig.Memory}}' 2>/dev/null || echo 0)
if [ "$MEM_LIMIT" = "$EXPECT_MEM_LIMIT" ]; then
  ok "container memory limit $((MEM_LIMIT / 1024 / 1024))m"
elif [ "$MEM_LIMIT" = "0" ]; then
  bad "no container memory limit set"
else
  bad "container memory limit ${MEM_LIMIT} bytes != expected $EXPECT_MEM_LIMIT"
fi

NOFILE=$(docker exec "$CONTAINER" sh -c 'ulimit -n' 2>/dev/null || echo 0)
if [ "$NOFILE" -ge 64000 ]; then
  ok "container nofile=$NOFILE"
else
  bad "container nofile=$NOFILE (expected >= 64000; host ulimit leaks into containers)"
fi

# ─── mongod live settings ───────────────────────────────────────────────────
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source <(grep -E '^(MONGODB_ROOT_USERNAME|MONGODB_ROOT_PASSWORD)=' "$ENV_FILE")
fi
MONGO_USER="${MONGODB_ROOT_USERNAME:-root}"
MONGO_PASS="${MONGODB_ROOT_PASSWORD:-}"

STATS=$(docker exec "$CONTAINER" mongosh --quiet -u "$MONGO_USER" -p "$MONGO_PASS" \
  --authenticationDatabase admin --eval '
    const s = db.serverStatus();
    const opts = db.adminCommand({getCmdLineOpts: 1});
    print(JSON.stringify({
      maxConns: (opts.parsed.net && opts.parsed.net.maxIncomingConnections) || 65536,
      cacheMB: Math.round(s.wiredTiger.cache["maximum bytes configured"] / 1048576),
      connCurrent: s.connections.current,
      connAvailable: s.connections.available
    }))' 2>/dev/null || echo "")

if [ -n "$STATS" ]; then
  MAXCONNS=$(echo "$STATS" | grep -o '"maxConns":[0-9]*' | cut -d: -f2)
  CACHE_MB=$(echo "$STATS" | grep -o '"cacheMB":[0-9]*' | cut -d: -f2)
  CONN_CUR=$(echo "$STATS" | grep -o '"connCurrent":[0-9]*' | cut -d: -f2)
  CONN_AVAIL=$(echo "$STATS" | grep -o '"connAvailable":[0-9]*' | cut -d: -f2)
  echo "  connections: ${CONN_CUR:-?} current / ${CONN_AVAIL:-?} available"

  if [ "$MAXCONNS" = "$EXPECT_MAXCONNS" ]; then
    ok "mongod maxConns=$MAXCONNS"
  else
    bad "mongod maxConns: expected $EXPECT_MAXCONNS, got '${MAXCONNS:-none}'"
  fi
  if [ "$CACHE_MB" = "$EXPECT_CACHE_MB" ]; then
    ok "WiredTiger cache ${CACHE_MB}MB"
  else
    bad "WiredTiger cache: expected ${EXPECT_CACHE_MB}MB, got '${CACHE_MB:-none}'"
  fi
else
  bad "could not query mongod (container down or credentials wrong?)"
fi

# ─── Summary ────────────────────────────────────────────────────────────────
echo ""
echo "Result: $PASS passed, $FAIL failed (profile: $PROFILE)"
[ "$FAIL" -eq 0 ]

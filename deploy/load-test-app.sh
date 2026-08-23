#!/usr/bin/env bash
#
# Throughput probe for the web app's public pages. Fires TOTAL GET requests at
# CONCURRENCY parallel curl processes and reports success rate, latency
# percentiles and requests/sec. Defaults to the unauthenticated login page -
# the rate limiter only throttles POSTs, so this measures rendering throughput.
#
# Usage: bash load-test-app.sh [url] [total_requests] [concurrency]
set -uo pipefail

URL="${1:-http://127.0.0.1:9811/login}"
TOTAL="${2:-500}"
CONCURRENCY="${3:-10}"

OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT

echo "Load test: $TOTAL GET $URL at concurrency $CONCURRENCY"
START=$(date +%s.%N)
yes "$URL" | head -n "$TOTAL" |
  xargs -P "$CONCURRENCY" -n 1 curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 15 \
    >"$OUT" || true
END=$(date +%s.%N)

OK=$(grep -c '^200 ' "$OUT" || true)
FAILED=$((TOTAL - OK))
ELAPSED=$(awk -v s="$START" -v e="$END" 'BEGIN {printf "%.2f", e - s}')
echo "requests: $TOTAL  ok(200): $OK  other: $FAILED  wall: ${ELAPSED}s"

if [ "$OK" -gt 0 ]; then
  grep '^200 ' "$OUT" | awk '{print $2}' | sort -n | awk -v total="$OK" '
    {a[NR] = $1; sum += $1}
    END {
      p50 = a[int(NR * 0.50)];
      if (p50 == "") p50 = a[1];
      p95 = a[int(NR * 0.95)];
      if (p95 == "") p95 = a[NR];
      printf "latency sec: avg=%.3f p50=%.3f p95=%.3f max=%.3f\n", sum / NR, p50, p95, a[NR]
    }'
  awk -v ok="$OK" -v elapsed="$ELAPSED" 'BEGIN {printf "throughput: %.0f req/sec\n", ok / elapsed}'
fi

[ "$OK" -eq "$TOTAL" ]

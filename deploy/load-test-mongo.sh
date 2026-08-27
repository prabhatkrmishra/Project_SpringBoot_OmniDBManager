#!/usr/bin/env bash
#
# Capacity probe for the MongoDB data plane: runs parallel insert+read workers
# against a throwaway database inside the mongo container and reports ops/sec.
# This measures what tenant applications experience, since they connect to
# mongod directly (the web app is never in the data path).
#
# Only touches the database "loadtest_tmp" - created at start, dropped after.
#
# Usage: bash load-test-mongo.sh [duration_seconds] [parallel_workers]
set -uo pipefail

DURATION="${1:-15}"
WORKERS="${2:-4}"
CONTAINER="${CONTAINER:-omnidb-mongo}"
DB="loadtest_tmp"
ENV_FILE="${ENV_FILE:-$HOME/omnidb/.env}"
[ -f "$ENV_FILE" ] || ENV_FILE="./.env"
# shellcheck disable=SC1090
source <(grep -E '^(MONGODB_ROOT_USERNAME|MONGODB_ROOT_PASSWORD)=' "$ENV_FILE")

echo "Load test: $WORKERS worker(s) x ${DURATION}s against $CONTAINER (db: $DB)"

PIDS=()
for w in $(seq 1 "$WORKERS"); do
  docker exec "$CONTAINER" mongosh --quiet \
    -u "$MONGODB_ROOT_USERNAME" -p "$MONGODB_ROOT_PASSWORD" --authenticationDatabase admin \
    --eval "
      const coll = db.getSiblingDB('$DB').metrics;
      const end = Date.now() + $DURATION * 1000;
      let ops = 0;
      while (Date.now() < end) {
        coll.insertOne({w: $w, n: ops, t: new Date(), pad: 'x'.repeat(200)});
        coll.findOne({w: $w, n: ops});
        ops += 2;
      }
      print(ops);
   " >"/tmp/mongo-load-$w.txt" 2>/dev/null &
  PIDS+=($!)
done

FAILURES=0
for pid in "${PIDS[@]}"; do
  wait "$pid" || FAILURES=$((FAILURES + 1))
done

TOTAL=0
for w in $(seq 1 "$WORKERS"); do
  OPS=$(tail -1 "/tmp/mongo-load-$w.txt")
  echo "  worker $w: ${OPS:-0} ops"
  TOTAL=$((TOTAL + ${OPS:-0}))
done

if [ "$TOTAL" -gt 0 ]; then
  echo "TOTAL: $TOTAL ops in ${DURATION}s = $((TOTAL / DURATION)) ops/sec ($WORKERS sequential worker(s), each = 1 insert + 1 indexed read)"
else
  echo "TOTAL: no ops recorded - check credentials / container state"
fi

# Cleanup runs regardless of worker failures.
docker exec "$CONTAINER" mongosh --quiet \
  -u "$MONGODB_ROOT_USERNAME" -p "$MONGODB_ROOT_PASSWORD" --authenticationDatabase admin \
  --eval "db.getSiblingDB('$DB').dropDatabase()" >/dev/null 2>&1
echo "cleaned up $DB"

[ "$FAILURES" -eq 0 ] && [ "$TOTAL" -gt 0 ]

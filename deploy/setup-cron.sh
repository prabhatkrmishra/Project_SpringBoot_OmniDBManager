#!/usr/bin/env bash
set -euo pipefail

# To run this once on Ubuntu server to set up the cron job.
# It checks for new releases every 5 minutes.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy.sh"

chmod +x "$DEPLOY_SCRIPT"

# Add cron entry (idempotent — removes old entry first)
CRON_LINE="*/5 * * * * $DEPLOY_SCRIPT >> $HOME/omnidb/deploy.log 2>&1"
( crontab -l 2>/dev/null | grep -v "deploy.sh" ; echo "$CRON_LINE" ) | crontab -

echo "Cron job installed. It will run every 5 minutes."
echo "Logs: ~/omnidb/deploy.log"
echo ""
echo "To verify: crontab -l"
echo "To remove:  crontab -l | grep -v deploy.sh | crontab -"

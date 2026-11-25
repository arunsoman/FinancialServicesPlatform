#!/usr/bin/env bash
# Start / update the FSP monitoring stack. Runs ON THE SERVER from ~/fsp-monitor/stack
# (synced by .github/workflows/monitoring.yml). Host setup must exist (monitor-bootstrap.sh).
set -Eeuo pipefail
STACK="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
# shellcheck source=../monitor.params
. "$STACK/monitor.params"
set +a
log() { printf '==> %s\n' "$*"; }
die() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }

[ -s "$FSP_MON_HOME/.env" ] || die "run scripts/monitor-bootstrap.sh first ($FSP_MON_HOME/.env missing)"
[ "$(systemctl show "$FSP_MON_SLICE" -p LoadState --value)" = loaded ] || die "slice $FSP_MON_SLICE missing"
[ "$(systemctl show "$FSP_MON_SLICE" -p MemoryMax --value)" != infinity ] || die "slice $FSP_MON_SLICE uncapped"

for c in loki prometheus alloy grafana; do
  proj="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "fsp-monitor-$c" 2>/dev/null || true)"
  if docker inspect "fsp-monitor-$c" >/dev/null 2>&1 && [ "$proj" != "$FSP_MON_PROJECT" ]; then
    die "container fsp-monitor-$c belongs to '$proj'"
  fi
done
for port in "$GRAFANA_PORT" "$PROM_PORT"; do
  if ss -ltnH "sport = :$port" | grep -q .; then
    owner="$(docker ps --filter "publish=$port" --format '{{.Names}}' | head -1)"
    case "$owner" in fsp-monitor-*) ;; *) die "port $port in use by '${owner:-a host process}'" ;; esac
  fi
done

mkdir -p "$FSP_MON_HOME"/{data/loki,data/prometheus,data/grafana,data/alloy,bin,logs} "$FSP_MON_TEXTFILE"
install -m 755 "$STACK/bin/fsp-ops.sh" "$FSP_MON_HOME/bin/fsp-ops.sh"

dc() { docker compose -p "$FSP_MON_PROJECT" --project-directory "$STACK" -f "$STACK/compose.yml" \
         --env-file "$STACK/monitor.params" --env-file "$FSP_MON_HOME/.env" "$@"; }
dc config --quiet
dc pull -q
dc up -d --remove-orphans

log "waiting for Grafana, Prometheus and their datasources"
PW="$(grep '^GRAFANA_ADMIN_PASSWORD=' "$FSP_MON_HOME/.env" | cut -d= -f2-)"
deadline=$(( $(date +%s) + 300 ))
until curl -fsS "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null 2>&1 \
   && curl -fsS -u "admin:$PW" "http://127.0.0.1:$GRAFANA_PORT/api/datasources/uid/loki/health" | grep -q '"OK"' \
   && curl -fsS -u "admin:$PW" "http://127.0.0.1:$GRAFANA_PORT/api/datasources/uid/prom/health" | grep -q '"OK"'; do
  [ "$(date +%s)" -lt "$deadline" ] || { dc ps; die "monitoring stack not healthy"; }
  sleep 5
done
"$FSP_MON_HOME/bin/fsp-ops.sh" observe || true
"$FSP_MON_HOME/bin/fsp-ops.sh" hygiene || true
log "monitoring up: https://$GRAFANA_HOST  ·  https://$PROM_HOST"

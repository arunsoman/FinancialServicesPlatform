#!/usr/bin/env bash
# FSP scheduled ops — installed to ~/fsp-monitor/bin by monitor-up.sh, run from the deploy
# user's crontab (monitor-bootstrap.sh installs the two lines):
#
#   */5 * * * *  fsp-ops.sh observe   release + plugin-drift metrics for Grafana (read-only)
#   23  * * * *  fsp-ops.sh hygiene   disk hygiene + fsp_dir_bytes metrics
#
# Hygiene only touches FSP-owned paths. Per environment it uses FSP_KEEP_DAYS from that
# environment's params (staging 4, production 30):
#   * Postgres backups older than KEEP days (always keeps the newest; production keeps >= 7)
#   * release bundles older than KEEP days, never `current`, the one before it, or pre-pipeline
#   * per-environment plugin manifests older than KEEP days (observe.py rewrites the live ones)
#   * deploy-history.log capped at 2000 lines
# Logs: Loki deletes staging after 4 days / production after 30 itself; Docker keeps at most
# 2 x 5 MB (staging) / 3 x 10 MB (production) per container; the root timer
# fsp-stage-log-hygiene.timer deletes staging container log files idle for more than 4 days.
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set -a
# shellcheck source=../monitor.params
. "$HERE/../stack/monitor.params"
set +a
LOG="$FSP_MON_HOME/logs/ops.log"
mkdir -p "$FSP_MON_HOME/logs" "$FSP_MON_TEXTFILE"
exec >>"$LOG" 2>&1
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }

env_homes() { printf '%s\n' "$FSP_STAGE_HOME" "$FSP_PROD_HOME"; }

param() { # bundle-dir NAME default
  local v; v="$(grep -E "^$2=" "$1/params.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '"')"
  echo "${v:-$3}"
}

observe() {
  local home
  while read -r home; do
    [ -x "$home/current/scripts/observe.py" ] || [ -f "$home/current/scripts/observe.py" ] || continue
    timeout 120 python3 "$home/current/scripts/observe.py" "$home/current" >/dev/null \
      || log "observe failed for $home"
  done < <(env_homes)
}

hygiene() {
  local home cur env keep backups min_keep prev
  while read -r home; do
    [ -d "$home/current" ] || continue
    cur="$(readlink -f "$home/current")"
    env="$(param "$cur" FSP_ENV unknown)"
    keep="$(param "$cur" FSP_KEEP_DAYS 30)"
    backups="$(param "$cur" FSP_BACKUP_DIR "")"
    min_keep=1; [ "$env" = production ] && min_keep=7

    # backups: older than KEEP days, but never below min_keep files
    if [ -d "$backups" ]; then
      while read -r f; do
        if [ -n "$f" ] && [ -n "$(find "$f" -maxdepth 0 -mmin +"$((keep * 1440))")" ]; then
          rm -f "$f"; log "$env: removed backup $(basename "$f")"
        fi
      done < <(ls -1t "$backups"/*.sql.gz 2>/dev/null | tail -n +"$((min_keep + 1))")
    fi

    # release bundles: keep current, the newest other one (auto-rollback target) and pre-pipeline
    prev="$(ls -1dt "$home"/releases/*/ 2>/dev/null | sed 's#/$##' | grep -vxF "$cur" | head -1 || true)"
    while read -r d; do
      case "$d" in ""|"$cur"|"$prev"|*/pre-pipeline) continue ;; esac
      rm -rf "$d"; log "$env: removed release bundle $(basename "$d")"
    done < <(find "$home/releases" -mindepth 1 -maxdepth 1 -type d -mmin +"$((keep * 1440))" 2>/dev/null)

    # per-environment manifests (live ones are rewritten every 5 minutes, so never old)
    find "$FSP_MANIFEST_ROOT/$env" -type f -mmin +"$((keep * 1440))" -delete 2>/dev/null || true

    if [ -f "$home/deploy-history.log" ]; then
      tail -n 2000 "$home/deploy-history.log" > "$home/deploy-history.log.tmp"
      mv "$home/deploy-history.log.tmp" "$home/deploy-history.log"
    fi
  done < <(env_homes)

  # own ops log
  tail -n 5000 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG" || true

  # disk metrics for Grafana (only paths the deploy user can read)
  {
    echo "# HELP fsp_dir_bytes Disk used by an FSP-owned directory."
    echo "# TYPE fsp_dir_bytes gauge"
    local p b
    for p in "$FSP_STAGE_HOME" "$FSP_STAGE_HOME/backups" "$FSP_STAGE_HOME/releases" \
             "$FSP_PROD_HOME" /home/ubuntu/fsp-backups \
             "$FSP_MON_HOME/data/loki" "$FSP_MON_HOME/data/prometheus" "$FSP_MON_HOME/data/grafana"; do
      [ -d "$p" ] || continue
      b="$(du -sb "$p" 2>/dev/null | cut -f1 || true)"
      if [ -n "$b" ]; then echo "fsp_dir_bytes{path=\"$p\"} $b"; fi
    done
  } > "$FSP_MON_TEXTFILE/fsp_disk.prom.tmp" && mv "$FSP_MON_TEXTFILE/fsp_disk.prom.tmp" "$FSP_MON_TEXTFILE/fsp_disk.prom"
}

case "${1:-}" in
  observe) observe ;;
  hygiene) hygiene ;;
  *) echo "usage: fsp-ops.sh observe|hygiene" >&2; exit 2 ;;
esac

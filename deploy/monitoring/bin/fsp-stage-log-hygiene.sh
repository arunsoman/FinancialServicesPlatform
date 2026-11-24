#!/usr/bin/env bash
# Root, hourly (fsp-stage-log-hygiene.timer). Staging only: deletes Docker log files of
# fsp-stage containers that have not been written for more than FSP_KEEP_DAYS (4) days, and
# empties an idle active log of the same age. Never touches another project's containers.
# (Size is already bounded by rotation: 2 x 5 MB per staging container.)
set -euo pipefail
KEEP_DAYS="${FSP_KEEP_DAYS:-4}"
for id in $(docker ps -aq --filter label=com.docker.compose.project=fsp-stage); do
  log="$(docker inspect -f '{{.LogPath}}' "$id" 2>/dev/null || true)"
  [ -n "$log" ] || continue
  # rotated files are closed — safe to delete once stale
  find "$(dirname "$log")" -maxdepth 1 -name "$(basename "$log").*" -mmin +"$((KEEP_DAYS * 1440))" -delete
  # the active file: only if nothing was written for KEEP days (container idle) — no live data lost
  if [ -n "$(find "$log" -maxdepth 0 -mmin +"$((KEEP_DAYS * 1440))" 2>/dev/null)" ]; then
    truncate -s 0 "$log"
  fi
done

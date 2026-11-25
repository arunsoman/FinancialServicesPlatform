#!/usr/bin/env bash
# One-time (idempotent) host setup for the FSP monitoring stack on web.afriqpay.com.
# Run as the deploy user (ubuntu, needs sudo), from the repo root:
#
#   rsync -a --delete deploy/monitoring/ web.afriqpay.com:fsp-monitor/stack/ && \
#   ssh -t web.afriqpay.com 'bash ~/fsp-monitor/stack/scripts/monitor-bootstrap.sh'
#
# Creates only monitoring-owned things, never edits existing ones:
#   ~/fsp-monitor/{data,textfile,bin,logs,.env}      Grafana admin + ops basic-auth passwords
#   /etc/systemd/system/fsp-monitor.slice            whole-stack cap (2 GiB, 1 CPU, lowest weight)
#   /etc/systemd/system/fsp-stage-log-hygiene.{service,timer}   staging log files > 4 days
#   /var/www/fsp-monitor.stage.afriqpay.com/manifests   per-environment plugin manifests
#   nginx: fsp-monitor.stage.afriqpay.com (Grafana; /manifests behind basic auth)
#          prom.stage.afriqpay.com        (Prometheus behind basic auth) + Let's Encrypt
#   crontab lines tagged "# fsp-ops" (observe every 5 min, hygiene hourly)
set -Eeuo pipefail
STACK="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
# shellcheck source=../monitor.params
. "$STACK/monitor.params"
set +a
log() { printf '==> %s\n' "$*"; }
die() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }

[ "$(id -un)" = ubuntu ] || die "run as the deploy user (ubuntu)"
[ "$STACK" = "$FSP_MON_HOME/stack" ] || die "sync deploy/monitoring to $FSP_MON_HOME/stack first (see header)"
sudo -n true 2>/dev/null || die "needs passwordless sudo (NOPASSWD) for the deploy user"
for h in "$GRAFANA_HOST" "$PROM_HOST"; do getent ahostsv4 "$h" | grep -q . || die "DNS: $h does not resolve"; done

# --- 1. dirs + secrets --------------------------------------------------------------------------
mkdir -p "$FSP_MON_HOME"/{data/loki,data/prometheus,data/grafana,data/alloy,bin,logs} "$FSP_MON_TEXTFILE"
if [ ! -s "$FSP_MON_HOME/.env" ]; then
  log "generating monitoring secrets → $FSP_MON_HOME/.env"
  (umask 077; {
    echo "# FSP monitoring secrets — generated $(date -u +%FT%TZ). Grafana user: admin; basic auth user: fsp-ops"
    echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)"
    echo "OPS_BASIC_AUTH_PASSWORD=$(openssl rand -hex 16)"
  } > "$FSP_MON_HOME/.env")
fi
OPS_PW="$(grep '^OPS_BASIC_AUTH_PASSWORD=' "$FSP_MON_HOME/.env" | cut -d= -f2-)"

# --- 2. cgroup slice ----------------------------------------------------------------------------
log "systemd slice $FSP_MON_SLICE"
sudo tee "/etc/systemd/system/$FSP_MON_SLICE" >/dev/null <<'EOF'
# FSP monitoring (Grafana/Loki/Prometheus/Alloy) — hard ceiling, yields to everything else.
[Unit]
Description=FSP monitoring stack (capped)
Before=slices.target

[Slice]
CPUAccounting=yes
CPUQuota=100%
CPUWeight=20
MemoryAccounting=yes
MemoryMax=2048M
MemorySwapMax=0
IOAccounting=yes
IOWeight=20
TasksAccounting=yes
TasksMax=2048
EOF

# --- 3. staging log hygiene (root timer, staging containers only) -------------------------------
log "staging log hygiene timer"
sudo install -m 755 "$STACK/bin/fsp-stage-log-hygiene.sh" /usr/local/sbin/fsp-stage-log-hygiene
sudo tee /etc/systemd/system/fsp-stage-log-hygiene.service >/dev/null <<'EOF'
[Unit]
Description=FSP staging: delete container log files idle for more than 4 days

[Service]
Type=oneshot
Environment=FSP_KEEP_DAYS=4
ExecStart=/usr/local/sbin/fsp-stage-log-hygiene
Nice=19
IOSchedulingClass=idle
EOF
sudo tee /etc/systemd/system/fsp-stage-log-hygiene.timer >/dev/null <<'EOF'
[Unit]
Description=FSP staging log hygiene (hourly)

[Timer]
OnCalendar=hourly
RandomizedDelaySec=300
Persistent=true

[Install]
WantedBy=timers.target
EOF
sudo systemctl daemon-reload
sudo systemctl start "$FSP_MON_SLICE"
sudo systemctl enable --now fsp-stage-log-hygiene.timer

# --- 4. manifest web root + basic auth ----------------------------------------------------------
sudo mkdir -p "$FSP_MANIFEST_ROOT"
sudo chown -R "$(id -un):$(id -gn)" "$(dirname "$FSP_MANIFEST_ROOT")"
printf 'fsp-ops:%s\n' "$(openssl passwd -apr1 "$OPS_PW")" | sudo tee /etc/nginx/fsp-monitor.htpasswd >/dev/null
sudo chown root:www-data /etc/nginx/fsp-monitor.htpasswd && sudo chmod 640 /etc/nginx/fsp-monitor.htpasswd

# --- 5. nginx vhosts (new files only) + TLS -----------------------------------------------------
vhost() {
  local f="/etc/nginx/sites-available/$1"
  if sudo test -e "$f"; then log "vhost $1 exists — kept"; return; fi
  log "vhost $1"
  printf '%s\n' "$2" | sudo tee "$f" >/dev/null
  sudo ln -sfn "$f" "/etc/nginx/sites-enabled/$1"
}
vhost "$GRAFANA_HOST" "# FSP monitoring — Grafana (+ plugin manifests). Managed by deploy/monitoring/scripts/monitor-bootstrap.sh
server {
    listen 80;
    server_name $GRAFANA_HOST;
    client_max_body_size 4m;
    add_header X-Robots-Tag \"noindex, nofollow\" always;

    location /manifests/ {
        auth_basic \"FSP ops\";
        auth_basic_user_file /etc/nginx/fsp-monitor.htpasswd;
        alias $FSP_MANIFEST_ROOT/;
        default_type application/json;
        autoindex on;
    }
    location /api/live/ {
        proxy_pass http://127.0.0.1:$GRAFANA_PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \"upgrade\";
        proxy_set_header Host \$host;
    }
    location / {
        proxy_pass http://127.0.0.1:$GRAFANA_PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Real-IP \$remote_addr;
    }
}"
vhost "$PROM_HOST" "# FSP monitoring — Prometheus (basic auth). Managed by deploy/monitoring/scripts/monitor-bootstrap.sh
server {
    listen 80;
    server_name $PROM_HOST;
    add_header X-Robots-Tag \"noindex, nofollow\" always;
    location / {
        auth_basic \"FSP ops\";
        auth_basic_user_file /etc/nginx/fsp-monitor.htpasswd;
        proxy_pass http://127.0.0.1:$PROM_PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
    }
}"
sudo nginx -t || die "nginx config test failed — nothing reloaded (remove the new monitoring vhosts and retry)"
sudo systemctl reload nginx
if ! sudo test -d "/etc/letsencrypt/live/$GRAFANA_HOST"; then
  log "Let's Encrypt certificate"
  sudo certbot --nginx --non-interactive --agree-tos --redirect --cert-name "$GRAFANA_HOST" -d "$GRAFANA_HOST" -d "$PROM_HOST"
fi
sudo nginx -t && sudo systemctl reload nginx

# --- 6. cron (deploy user's crontab; only lines tagged # fsp-ops are managed) -------------------
log "crontab: observe every 5 min, hygiene hourly"
{ crontab -l 2>/dev/null | grep -v '# fsp-ops$' || true
  echo "*/5 * * * * $FSP_MON_HOME/bin/fsp-ops.sh observe # fsp-ops"
  echo "23 * * * * $FSP_MON_HOME/bin/fsp-ops.sh hygiene # fsp-ops"
} | crontab -

# --- 7. start ------------------------------------------------------------------------------------
bash "$STACK/scripts/monitor-up.sh"
log "Grafana: https://$GRAFANA_HOST (user admin, password GRAFANA_ADMIN_PASSWORD in $FSP_MON_HOME/.env)"
log "Prometheus + manifests: basic auth user fsp-ops, password OPS_BASIC_AUTH_PASSWORD in $FSP_MON_HOME/.env"

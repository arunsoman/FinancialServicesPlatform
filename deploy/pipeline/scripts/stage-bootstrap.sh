#!/usr/bin/env bash
# One-time (idempotent) host setup for STAGING on web.afriqpay.com. Run as the deploy user
# (ubuntu, needs sudo) from the repo root:
#
#   scp -r deploy/pipeline web.afriqpay.com:/tmp/fsp-pipeline && \
#   ssh -t web.afriqpay.com 'bash /tmp/fsp-pipeline/scripts/stage-bootstrap.sh'
#
# It creates ONLY staging-owned things and never edits anything that already exists:
#   ~/fsp-stage/{conf,data,backups,releases}      own data, own secrets (generated here, never in git/CI)
#   docker network fsp-stage-network 10.201.0.0/24  (refuses if the subnet overlaps another network)
#   /etc/systemd/system/fsp-stage.slice            hard cap for the whole staging stack
#   /var/www/fsp.stage.afriqpay.com/html           SPA web root
#   nginx vhosts fsp.stage / fsp-auth.stage / fsp-api.stage  + Let's Encrypt certificate
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a; . "$HERE/env/staging.params"; set +a
log() { printf '==> %s\n' "$*"; }
die() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }

[ "$(id -un)" = ubuntu ] || die "run as the deploy user (ubuntu)"
sudo -v

# --- 1. directories + core secrets (generated once, chmod 600, never leave the server) ------
log "directories under $FSP_HOME"
mkdir -p "$FSP_CONF_DIR/pki" "$FSP_DATA_DIR/postgres" "$FSP_DATA_DIR/tigerbeetle" \
         "$FSP_BACKUP_DIR" "$FSP_HOME/releases"
chmod 700 "$FSP_CONF_DIR" "$FSP_CONF_DIR/pki"

# shellcheck source=lib-secrets.sh
. "$HERE/scripts/lib-secrets.sh"
[ -s "$FSP_CONF_DIR/.env" ] || (umask 077; echo "# FSP STAGING secrets — generated on $(hostname). Never copy to production." > "$FSP_CONF_DIR/.env")
for n in POSTGRES_PASSWORD FSP_BOOTSTRAP_PASSWORD FSP_INTERNAL_CREDENTIAL FSP_PERSON_PASSWORD; do
  ensure_env_secret "$FSP_CONF_DIR/.env" "$n"
done
log "staging signing keys (distinct from production)"
ensure_key "$FSP_CONF_DIR/pki" idp-signing       -algorithm RSA -pkeyopt rsa_keygen_bits:2048
ensure_key "$FSP_CONF_DIR/pki" gateway-assertion -algorithm EC  -pkeyopt ec_paramgen_curve:P-256
# plugin DB passwords, extra secrets and client/DPoP keys are created by deploy.sh on first deploy
# (FSP_AUTOGEN_SECRETS=1), from each backend/plugins/<code>/deploy.json — new plugins need no step here.

# --- 2. docker network (own subnet; refuse overlap) -------------------------------------------
if ! docker network inspect "$FSP_NETWORK" >/dev/null 2>&1; then
  log "docker network $FSP_NETWORK ($FSP_NETWORK_SUBNET)"
  # docker itself refuses overlap with other docker networks; also refuse overlap with host routes
  ip -4 route show | awk '{print $1}' | grep / | python3 -c '
import ipaddress, sys
want = ipaddress.ip_network(sys.argv[1])
bad = [r for r in (l.strip() for l in sys.stdin) if ipaddress.ip_network(r, strict=False).overlaps(want)]
sys.exit(1 if bad else 0)' "$FSP_NETWORK_SUBNET" || die "subnet $FSP_NETWORK_SUBNET overlaps a host route"
  docker network create --driver bridge --subnet "$FSP_NETWORK_SUBNET" \
    --label com.afriqpay.env=staging "$FSP_NETWORK" >/dev/null
fi

# --- 3. cgroup slice: whole-stack ceiling + lowest priority -----------------------------------
log "systemd slice $FSP_SLICE"
sudo tee "/etc/systemd/system/$FSP_SLICE" >/dev/null <<'EOF'
# FSP staging — hard ceiling for EVERY staging container (docker cgroup_parent).
# Staging can never take more than this, and yields first under contention.
[Unit]
Description=FSP staging stack (capped)
Before=slices.target

[Slice]
CPUAccounting=yes
CPUQuota=150%
CPUWeight=20
MemoryAccounting=yes
MemoryMax=4608M
MemorySwapMax=0
IOAccounting=yes
IOWeight=20
TasksAccounting=yes
TasksMax=2048
EOF
sudo systemctl daemon-reload
sudo systemctl start "$FSP_SLICE"

# --- 4. web root -------------------------------------------------------------------------------
log "web root $FSP_WEB_ROOT"
sudo mkdir -p "$FSP_WEB_ROOT"
sudo chown -R "$(id -un):$(id -gn)" "$(dirname "$FSP_WEB_ROOT")"

# --- 5. nginx vhosts (new files only; nginx -t before any reload) ------------------------------
for h in "$FSP_PUBLIC_HOST" "$FSP_AUTH_HOST" "$FSP_API_HOST"; do
  getent ahostsv4 "$h" | grep -q . || die "DNS: $h does not resolve"
done

vhost() { # name body
  local f="/etc/nginx/sites-available/$1"
  if sudo test -e "$f"; then log "vhost $1 exists — kept"; return; fi
  log "vhost $1"
  printf '%s\n' "$2" | sudo tee "$f" >/dev/null
  sudo ln -sfn "$f" "/etc/nginx/sites-enabled/$1"
}

COMMON='    client_max_body_size 16m;
    add_header X-Robots-Tag "noindex, nofollow" always;'

vhost "$FSP_PUBLIC_HOST" "# FSP STAGING — SPA + BFF (managed by deploy/pipeline/scripts/stage-bootstrap.sh)
server {
    listen 80;
    server_name $FSP_PUBLIC_HOST;
$COMMON
    root $FSP_WEB_ROOT;
    index index.html;

    location /bff/ { proxy_pass http://127.0.0.1:$FSP_PORT_BFF; proxy_set_header Host \$host; proxy_set_header X-Forwarded-Proto https; proxy_set_header X-Forwarded-For \$remote_addr; }
    location /api/ { proxy_pass http://127.0.0.1:$FSP_PORT_BFF; proxy_set_header Host \$host; proxy_set_header X-Forwarded-Proto https; }

    location ~ ^/(admin|manufacturer|distributor|wholesaler|merchant|widget-workbench)(/.*)?\$ {
        try_files \$uri /\$1.html;
    }
    location / {
        try_files \$uri \$uri/ /index.html;
    }
}"

for pair in "$FSP_AUTH_HOST:$FSP_PORT_IDP:IdP" "$FSP_API_HOST:$FSP_PORT_GATEWAY:gateway"; do
  IFS=: read -r host port what <<<"$pair"
  vhost "$host" "# FSP STAGING — $what (managed by deploy/pipeline/scripts/stage-bootstrap.sh)
server {
    listen 80;
    server_name $host;
$COMMON
    location / {
        proxy_pass http://127.0.0.1:$port;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Real-IP \$remote_addr;
    }
}"
done

sudo nginx -t || die "nginx config test failed — nothing reloaded (remove the new fsp*.stage vhosts and retry)"
sudo systemctl reload nginx

# --- 6. TLS (certbot only edits the three staging server blocks) -------------------------------
if ! sudo test -d "/etc/letsencrypt/live/$FSP_PUBLIC_HOST"; then
  log "Let's Encrypt certificate"
  sudo certbot --nginx --non-interactive --agree-tos --redirect --cert-name "$FSP_PUBLIC_HOST" \
    -d "$FSP_PUBLIC_HOST" -d "$FSP_AUTH_HOST" -d "$FSP_API_HOST"
fi
sudo nginx -t && sudo systemctl reload nginx

log "staging host ready. Secrets: $FSP_CONF_DIR/.env (bootstrap admin password = FSP_BOOTSTRAP_PASSWORD)"
log "next: push to the 'staging' branch — GitHub Actions builds and deploys."

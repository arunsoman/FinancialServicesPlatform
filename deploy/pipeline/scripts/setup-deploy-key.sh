#!/usr/bin/env bash
# Run on YOUR workstation — a person provisions deploy credentials, never the pipeline itself.
# Needs: ssh as the deploy user to the server, and `gh` logged in as a repo admin.
#
#   bash deploy/pipeline/scripts/setup-deploy-key.sh staging       (or: production)
#
# 1. creates a dedicated ed25519 key for GitHub Actions → <environment>
# 2. authorizes it on the server with `restrict` (no port forwarding, agent, X11 or pty);
#    re-running replaces the previous key for that environment (= rotation)
# 3. stores DEPLOY_HOST / DEPLOY_USER / DEPLOY_SSH_KEY / DEPLOY_KNOWN_HOSTS in the GitHub
#    environment (the host key comes from YOUR known_hosts, which you already trust)
# 4. deletes the local private key — it exists only in GitHub's encrypted secret store
set -euo pipefail
ENVIRONMENT="${1:?usage: setup-deploy-key.sh staging|production}"
case "$ENVIRONMENT" in staging|production) ;; *) echo "environment must be staging or production" >&2; exit 2 ;; esac
HOST="${DEPLOY_HOST:-web.afriqpay.com}"
DUSER="${DEPLOY_USER:-ubuntu}"
REPO="${REPO:-arunsoman/FinancialServicesPlatform}"
TAG="fsp-github-deploy-$ENVIRONMENT"

# never create an environment here: production must be created WITH its required reviewers
gh api "repos/$REPO/environments/$ENVIRONMENT" >/dev/null 2>&1 || {
  echo "GitHub environment '$ENVIRONMENT' does not exist — create it first (production: with Required reviewers)" >&2; exit 1; }

tmp="$(mktemp -d)"; chmod 700 "$tmp"; trap 'rm -rf "$tmp"' EXIT

ssh-keygen -F "$HOST" | grep -v '^#' > "$tmp/known_hosts" || true
[ -s "$tmp/known_hosts" ] || { echo "no trusted host key for $HOST in your known_hosts — ssh to it once first" >&2; exit 1; }

ssh-keygen -q -t ed25519 -N '' -C "$TAG" -f "$tmp/id"
pub="$(cat "$tmp/id.pub")"
# shellcheck disable=SC2029  # expanded here on purpose
ssh "$DUSER@$HOST" "touch ~/.ssh/authorized_keys && sed -i '/ $TAG\$/d' ~/.ssh/authorized_keys && echo 'restrict $pub' >> ~/.ssh/authorized_keys"

ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$tmp/id" -o UserKnownHostsFile="$tmp/known_hosts" \
    -o StrictHostKeyChecking=yes "$DUSER@$HOST" true
echo "key works for $DUSER@$HOST"

gh secret set DEPLOY_HOST        --repo "$REPO" --env "$ENVIRONMENT" --body "$HOST"
gh secret set DEPLOY_USER        --repo "$REPO" --env "$ENVIRONMENT" --body "$DUSER"
gh secret set DEPLOY_SSH_KEY     --repo "$REPO" --env "$ENVIRONMENT" < "$tmp/id"
gh secret set DEPLOY_KNOWN_HOSTS --repo "$REPO" --env "$ENVIRONMENT" < "$tmp/known_hosts"
echo "GitHub environment '$ENVIRONMENT' has its deploy secrets; local private key deleted."

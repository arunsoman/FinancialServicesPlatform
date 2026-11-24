# shellcheck shell=bash
# Sourced by stage-bootstrap.sh and deploy.sh. Creates a secret or key only if it is missing;
# never prints or rotates an existing one.

# ensure_env_secret <env-file> <NAME>   — appends NAME=<48 hex chars> (chmod 600 file)
ensure_env_secret() {
  local file="$1" name="$2"
  grep -q "^$name=" "$file" 2>/dev/null && return 0
  (umask 077; printf '%s=%s\n' "$name" "$(openssl rand -hex 24)" >> "$file")
  echo "generated secret $name"
}

# ensure_key <pki-dir> <name> <openssl genpkey args...>
ensure_key() {
  local dir="$1" name="$2"; shift 2
  [ -s "$dir/$name.pem" ] && return 0
  (umask 077; openssl genpkey "$@" -out "$dir/$name.pem" 2>/dev/null)
  echo "generated key $name"
}

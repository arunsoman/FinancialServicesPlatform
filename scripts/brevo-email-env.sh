# Source me (don't run me) before starting core, so activation / reset mails go out through
# Brevo's SMTP relay:
#
#     source scripts/brevo-email-env.sh && make backend-core
#
# Reads the secrets from a local key file (default ~/.keys, lines of name=value) — nothing is
# printed or written anywhere. Keys used:
#   brevo             SMTP key (xsmtpsib-…)              → FSP_EMAIL_PASSWORD
#   brevo-smtp-login  SMTP login (…@smtp-brevo.com)      → FSP_EMAIL_USERNAME
#   brevo-from        a sender VERIFIED in Brevo         → FSP_EMAIL_FROM
# FSP_EMAIL_USERNAME / FSP_EMAIL_FROM already in the environment win over the file.

_fsp_keys="${FSP_KEYS_FILE:-$HOME/.keys}"
_fsp_key() { [ -r "$_fsp_keys" ] && awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$_fsp_keys"; }

export FSP_EMAIL_ENABLED=true
export FSP_EMAIL_HOST=smtp-relay.brevo.com
export FSP_EMAIL_PORT=587
export FSP_EMAIL_STARTTLS=true
export FSP_EMAIL_PASSWORD="$(_fsp_key brevo)"
export FSP_EMAIL_USERNAME="${FSP_EMAIL_USERNAME:-$(_fsp_key brevo-smtp-login)}"
export FSP_EMAIL_FROM="${FSP_EMAIL_FROM:-$(_fsp_key brevo-from)}"

_fsp_missing=""
[ -n "$FSP_EMAIL_PASSWORD" ] || _fsp_missing="$_fsp_missing brevo"
[ -n "$FSP_EMAIL_USERNAME" ] || _fsp_missing="$_fsp_missing brevo-smtp-login"
[ -n "$FSP_EMAIL_FROM" ] || _fsp_missing="$_fsp_missing brevo-from"
if [ -n "$_fsp_missing" ]; then
  echo "brevo-email-env: missing in $_fsp_keys:$_fsp_missing — email stays OFF" >&2
  export FSP_EMAIL_ENABLED=false
else
  echo "brevo-email-env: email ON via smtp-relay.brevo.com:587 (sender ${FSP_EMAIL_FROM#*@})" >&2
fi
unset -f _fsp_key
unset _fsp_keys _fsp_missing

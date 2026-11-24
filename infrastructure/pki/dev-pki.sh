#!/usr/bin/env bash
# FSP dev PKI (AGENTS.md §7): one dev root CA + one server identity per publicly-listening
# container + one workload client identity per internal caller (mTLS trust for the internal hop).
#
# Outputs (gitignored) -> infrastructure/pki/dev/
#   ca.crt / ca.key                  dev root CA (NEVER ship; prod uses a managed CA)
#   core.p12 / bff.p12 / idp.p12 / gateway.p12   server identity per service (Spring server.ssl)
#   *.crt                            server certs (PEM, for SslBundle/pem wiring)
#   bff-workload.p12 / gateway-workload.p12      workload mTLS client identities (guide §2)
#   ca-trust.p12                     truststore containing only the dev CA
#   idp-signing.pem                  IdP token-signing RSA keypair (PKCS#8; RS256 JWT/JWKS)
#
# Usage: bash infrastructure/pki/dev-pki.sh   (idempotent; regenerates everything)
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/dev"
PASS="${FSP_TLS_PASSWORD:-fsp-dev}"

mkdir -p "$OUT"
cd "$OUT"

echo "==> 1/6 dev root CA"
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 \
  -keyout ca.key -out ca.crt \
  -subj "/C=NA/O=FSP Dev/CN=FSP Dev Root CA" >/dev/null 2>&1

echo "==> 2/8 server cert for core"
openssl req -newkey rsa:2048 -nodes -sha256 \
  -keyout core.key -out core.csr \
  -subj "/C=NA/O=FSP Dev/CN=core" >/dev/null 2>&1
cat > core.ext <<'EOF'
subjectAltName=DNS:localhost,DNS:core,IP:127.0.0.1
extendedKeyUsage=serverAuth
EOF
openssl x509 -req -in core.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 825 -sha256 -extfile core.ext -out core.crt >/dev/null 2>&1

echo "==> 3/8 server cert for bff"
openssl req -newkey rsa:2048 -nodes -sha256 \
  -keyout bff.key -out bff.csr \
  -subj "/C=NA/O=FSP Dev/CN=bff" >/dev/null 2>&1
cat > bff.ext <<'EOF'
subjectAltName=DNS:localhost,DNS:bff,IP:127.0.0.1
extendedKeyUsage=serverAuth
EOF
openssl x509 -req -in bff.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 825 -sha256 -extfile bff.ext -out bff.crt >/dev/null 2>&1

echo "==> 4/8 server certs for idp + gateway"
for S in idp gateway; do
  openssl req -newkey rsa:2048 -nodes -sha256 \
    -keyout "$S.key" -out "$S.csr" \
    -subj "/C=NA/O=FSP Dev/CN=$S" >/dev/null 2>&1
  cat > "$S.ext" <<EOF
subjectAltName=DNS:localhost,DNS:$S,IP:127.0.0.1
extendedKeyUsage=serverAuth
EOF
  openssl x509 -req -in "$S.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 825 -sha256 -extfile "$S.ext" -out "$S.crt" >/dev/null 2>&1
done

echo "==> 5/8 workload mTLS client certs for bff + gateway (guide §2 internal hop)"
openssl req -newkey rsa:2048 -nodes -sha256 \
  -keyout bff-workload.key -out bff-workload.csr \
  -subj "/C=NA/O=FSP Dev/CN=bff-workload" >/dev/null 2>&1
cat > workload.ext <<'EOF'
extendedKeyUsage=clientAuth
EOF
openssl x509 -req -in bff-workload.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 825 -sha256 -extfile workload.ext -out bff-workload.crt >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -sha256 \
  -keyout gateway-workload.key -out gateway-workload.csr \
  -subj "/C=NA/O=FSP Dev/CN=gateway-workload" >/dev/null 2>&1
openssl x509 -req -in gateway-workload.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 825 -sha256 -extfile workload.ext -out gateway-workload.crt >/dev/null 2>&1

echo "==> 6/8 idp token-signing key (RS256; gateway verifies via /idp/jwks)"
[ -f idp-signing.pem ] || openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out idp-signing.pem >/dev/null 2>&1

echo "==> 7/8 PKCS12 keystores (Spring server.ssl)"
# server identities
for S in core bff idp gateway; do
  openssl pkcs12 -export \
    -inkey "$S.key" -in "$S.crt" -certfile ca.crt \
    -name "$S" -passout "pass:$PASS" -out "$S.p12" >/dev/null 2>&1
done
# workload client identities
openssl pkcs12 -export \
  -inkey bff-workload.key -in bff-workload.crt -certfile ca.crt \
  -name "bff-workload" -passout "pass:$PASS" -out bff-workload.p12 >/dev/null 2>&1
openssl pkcs12 -export \
  -inkey gateway-workload.key -in gateway-workload.crt -certfile ca.crt \
  -name "gateway-workload" -passout "pass:$PASS" -out gateway-workload.p12 >/dev/null 2>&1
# truststore: only the CA (used by BOTH mTLS server-side verification and outbound trust)
openssl pkcs12 -export -nokeys -in ca.crt \
  -name "fsp-dev-ca" -passout "pass:$PASS" -out ca-trust.p12 >/dev/null 2>&1

echo "==> 8/8 done"
ls -la "$OUT" | grep -E "crt|p12|key$"
echo
echo "Activate with profiles:  --spring.profiles.active=tls   (see application-tls.yml)"
echo "Password default: $PASS (override via FSP_TLS_PASSWORD)"
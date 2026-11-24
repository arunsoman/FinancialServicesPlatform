#!/usr/bin/env bash
# Runs on the CI runner. Resolves the images built from a git TREE into an immutable release
# manifest (release.env) of digests. Images are tagged by tree hash, so a staging→main merge
# whose tree is identical reuses the exact bytes tested on staging (build once, promote), and a
# rollback to any tag just resolves that tag's tree.
#
#   resolve-release.sh <registry> <tree> <commit> <out-file>     (exit 3 if any image is missing)
set -euo pipefail
REG="$1"; TREE="$2"; COMMIT="$3"; OUT="$4"
ROOT="$(git rev-parse --show-toplevel)"

{
  echo "# FSP release manifest — tree $TREE, commit $COMMIT, resolved $(date -u +%FT%TZ)"
  echo "FSP_REGISTRY=$REG"
  echo "FSP_RELEASE_TREE=$TREE"
  echo "FSP_RELEASE_COMMIT=$COMMIT"
  for svc in core idp gateway bff kyc inventory web; do
    ref="$REG/fsp-$svc:tree-$TREE"
    digest="$(docker buildx imagetools inspect "$ref" --format '{{json .Manifest}}' 2>/dev/null | jq -r .digest)" || true
    if [ -z "${digest:-}" ] || [ "$digest" = null ]; then echo "missing image $ref" >&2; exit 3; fi
    echo "FSP_IMAGE_$(echo "$svc" | tr a-z A-Z)=$REG/fsp-$svc@$digest"
  done
  # newest Flyway version this release ships — deploy.sh refuses a normal deploy onto a newer DB
  max="$(git -C "$ROOT" ls-tree --name-only "$COMMIT" backend/core/src/main/resources/db/migration/ \
        | sed -nE 's#.*/V([0-9_.]+)__.*#\1#p' | tr _ . | sort -V | tail -1)"
  echo "FSP_RELEASE_MAX_MIGRATION=$max"
} > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
cat "$OUT"

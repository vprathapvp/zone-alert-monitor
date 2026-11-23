#!/usr/bin/env bash
# Builds the offline delivery bundle from the release tags.
#
#   scripts/build-bundle.sh            -> dist/release/
#
# Output (each file is a GitHub release asset; SHA256SUMS lists the others):
#   geoalert-deploy-1.1.0.tar.gz   compose file, configs, version files, INSTALL.md, RUNBOOK.md
#   images-base.tar.gz             postgres and nginx (third-party, pinned)
#   images-1.0.0.tar.gz            backend + console 1.0.0
#   images-1.1.0.tar.gz            backend + console 1.1.0
#   SHA256SUMS
#
# Every image is built or pulled for linux/amd64 and checked before it is saved.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist/release"
WORK="$ROOT/dist/work"
PLATFORM=linux/amd64
VERSIONS=(1.0.0 1.1.0)
BASE_IMAGES=(postgres:16.8-alpine nginxinc/nginx-unprivileged:1.27.4-alpine)

rm -rf "$OUT" "$WORK"
mkdir -p "$OUT" "$WORK"

check_arch() {
  local arch
  arch="$(docker image inspect "$1" --format '{{.Os}}/{{.Architecture}}')"
  if [ "$arch" != "$PLATFORM" ]; then
    echo "image $1 is $arch, expected $PLATFORM" >&2
    exit 1
  fi
}

for v in "${VERSIONS[@]}"; do
  src="$WORK/src-$v"
  echo "== building $v from tag v$v"
  git -C "$ROOT" archive --format=tar "v$v" | (mkdir -p "$src" && tar -x -C "$src")
  docker build --platform "$PLATFORM" -f "$src/backend/Dockerfile" -t "geoalert-backend:$v" "$src"
  docker build --platform "$PLATFORM" -f "$src/frontend/Dockerfile" -t "geoalert-console:$v" "$src"
  check_arch "geoalert-backend:$v"
  check_arch "geoalert-console:$v"
  docker save "geoalert-backend:$v" "geoalert-console:$v" | gzip -6 > "$OUT/images-$v.tar.gz"
done

echo "== base images"
for img in "${BASE_IMAGES[@]}"; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull --platform "$PLATFORM" "$img"
  check_arch "$img"
done
docker save "${BASE_IMAGES[@]}" | gzip -6 > "$OUT/images-base.tar.gz"

echo "== deploy files (from tag v1.1.0)"
latest="$WORK/src-${VERSIONS[-1]}"
pkg="$WORK/geoalert"
mkdir -p "$pkg/secrets"
cp -r "$latest/deploy/." "$pkg/"
cp "$latest/INSTALL.md" "$pkg/INSTALL.md"
cp "$latest/docs/RUNBOOK.md" "$pkg/RUNBOOK.md"
cp "$latest/docs/CR-1.md" "$pkg/CR-1.md"
tar -C "$WORK" -czf "$OUT/geoalert-deploy-${VERSIONS[-1]}.tar.gz" geoalert

(cd "$OUT" && sha256sum geoalert-deploy-*.tar.gz images-*.tar.gz > SHA256SUMS)
echo "== done"
ls -lh "$OUT"
cat "$OUT/SHA256SUMS"

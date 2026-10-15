#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
exec > >(tee native-image-build.log) 2>&1
export MAVEN_OPTS="${MAVEN_OPTS:-} -Xms64m -Xmx512m"
sysctl hw.memsize
sysctl vm.swapusage
vm_stat
df -h .
available_kb=$(df -Pk . | awk 'NR == 2 {print $4}')
if (( available_kb < 8 * 1024 * 1024 )); then
  echo 'macOS ARM64 AOT needs at least 8 GiB of free disk for build output and system swap.' >&2
  exit 1
fi

mvn --batch-mode --no-transfer-progress clean package \
  -Pnative-direct,native-low-memory -DskipTests \
  -Dnative.package.os=macos -Dnative.package.arch=arm64

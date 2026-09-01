#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <version> <archive-arch> <nfpm-arch> <appimage-arch>" >&2
  exit 2
fi

version=$1
archive_arch=$2
nfpm_arch=$3
appimage_arch=$4
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$project_root"

bundle_name="tomato-${version}-linux-${archive_arch}"
bundle_dir="$project_root/target/native-package/$bundle_name"
dist_dir="$project_root/dist"
app_dir="$project_root/target/appimage/Tomato.AppDir"
nfpm_command=${NFPM_COMMAND:-nfpm}
appimage_command=${APPIMAGETOOL:-appimagetool}

[[ -x "$bundle_dir/tomato" ]] || {
  echo "Missing AOT executable: $bundle_dir/tomato" >&2
  exit 1
}
command -v "$nfpm_command" >/dev/null || {
  echo "nFPM is required to build DEB/RPM packages" >&2
  exit 1
}
command -v "$appimage_command" >/dev/null || {
  echo "appimagetool is required to build AppImage packages" >&2
  exit 1
}

mkdir -p "$dist_dir"
export VERSION="$version"
export NFPM_ARCH="$nfpm_arch"
export NATIVE_BUNDLE_DIR="$bundle_dir"

"$nfpm_command" package --config packaging/linux/nfpm.yaml --packager deb \
  --target "$dist_dir/${bundle_name}.deb"
"$nfpm_command" package --config packaging/linux/nfpm.yaml --packager rpm \
  --target "$dist_dir/${bundle_name}.rpm"

rm -rf "$app_dir"
mkdir -p "$app_dir/usr/lib/tomato" "$app_dir/usr/share/applications" \
  "$app_dir/usr/share/icons/hicolor/512x512/apps"
cp -a "$bundle_dir/." "$app_dir/usr/lib/tomato/"
install -m 0755 packaging/linux/AppRun "$app_dir/AppRun"
install -m 0644 packaging/linux/tomato.desktop \
  "$app_dir/usr/share/applications/tomato.desktop"
install -m 0644 packaging/linux/tomato.desktop "$app_dir/tomato.desktop"
install -m 0644 src/main/resources/images/logo.png \
  "$app_dir/usr/share/icons/hicolor/512x512/apps/tomato.png"
install -m 0644 src/main/resources/images/logo.png "$app_dir/tomato.png"

ARCH="$appimage_arch" APPIMAGE_EXTRACT_AND_RUN=1 "$appimage_command" \
  "$app_dir" "$dist_dir/${bundle_name}.AppImage"

echo "Linux AOT packages created in $dist_dir"

#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <version> <archive-arch>" >&2
  exit 2
fi

version=$1
archive_arch=$2
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$project_root"

bundle_name="tomato-${version}-macos-${archive_arch}"
bundle_dir="$project_root/target/native-package/$bundle_name"
work_dir="$project_root/target/native-macos"
app_dir="$work_dir/Tomato.app"
contents_dir="$app_dir/Contents"
macos_dir="$contents_dir/MacOS"
resources_dir="$contents_dir/Resources"
dist_dir="$project_root/dist"

[[ -x "$bundle_dir/tomato" ]] || {
  echo "Missing AOT executable: $bundle_dir/tomato" >&2
  exit 1
}

rm -rf "$work_dir"
mkdir -p "$macos_dir" "$resources_dir" "$dist_dir"
cp -R "$bundle_dir/." "$macos_dir/"
mv "$macos_dir/tomato" "$macos_dir/tomato-bin"
install -m 0755 packaging/macos/tomato-launcher "$macos_dir/tomato"
sed "s/@VERSION@/${version}/g" packaging/macos/Info.plist > "$contents_dir/Info.plist"

iconset_dir="$work_dir/tomato.iconset"
mkdir -p "$iconset_dir"
while read -r pixels filename; do
  sips -z "$pixels" "$pixels" src/main/resources/images/logo.png \
    --out "$iconset_dir/$filename" >/dev/null
done <<'SIZES'
16 icon_16x16.png
32 icon_16x16@2x.png
32 icon_32x32.png
64 icon_32x32@2x.png
128 icon_128x128.png
256 icon_128x128@2x.png
256 icon_256x256.png
512 icon_256x256@2x.png
512 icon_512x512.png
1024 icon_512x512@2x.png
SIZES
iconutil -c icns "$iconset_dir" -o "$resources_dir/tomato.icns"

rm -f "$dist_dir/${bundle_name}.zip" "$dist_dir/${bundle_name}.tar.gz" \
  "$dist_dir/${bundle_name}.dmg" "$dist_dir/${bundle_name}.pkg"
ditto -c -k --sequesterRsrc --keepParent "$app_dir" "$dist_dir/${bundle_name}.zip"
COPYFILE_DISABLE=1 tar -czf "$dist_dir/${bundle_name}.tar.gz" -C "$work_dir" Tomato.app

dmg_root="$work_dir/dmg-root"
mkdir -p "$dmg_root"
cp -R "$app_dir" "$dmg_root/"
hdiutil create -quiet -volname Tomato -srcfolder "$dmg_root" -ov -format UDZO \
  "$dist_dir/${bundle_name}.dmg"

pkg_root="$work_dir/pkg-root"
mkdir -p "$pkg_root/Applications"
cp -R "$app_dir" "$pkg_root/Applications/"
pkgbuild --quiet --root "$pkg_root" --identifier com.tangluobo.tomato \
  --version "$version" --install-location / "$dist_dir/${bundle_name}.pkg"

echo "macOS AOT packages created in $dist_dir"

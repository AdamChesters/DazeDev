#!/usr/bin/env bash
# Tauri names the .deb package after the product name in kebab case ("daze-dev").
# Rebuild it as "dazedev", so apt install, dpkg -s and apt remove use the plain name.
# Usage: rename-deb.sh BUNDLE.deb  (replaces it with dazedev_<version>_<arch>.deb)
set -euo pipefail
deb=${1:?usage: rename-deb.sh BUNDLE.deb}
name=dazedev
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dpkg-deb -R "$deb" "$work/pkg"
sed -i "s/^Package: .*/Package: $name/" "$work/pkg/DEBIAN/control"
version=$(dpkg-deb -f "$deb" Version)
arch=$(dpkg-deb -f "$deb" Architecture)
out="$(dirname "$deb")/${name}_${version}_${arch}.deb"
# xz keeps the package readable by every dpkg that Mint 21 and 22 ship.
dpkg-deb --root-owner-group -Zxz -b "$work/pkg" "$work/out.deb" >/dev/null
mv "$work/out.deb" "$out"
[ "$out" -ef "$deb" ] || rm -f "$deb"
echo "$out: $(dpkg-deb -f "$out" Package) $(dpkg-deb -f "$out" Version)"

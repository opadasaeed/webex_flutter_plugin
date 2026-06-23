#!/bin/bash
set -euo pipefail

PODS_ROOT="${PODS_ROOT:-${SRCROOT}/../..}"
WEBEX_ROOT="${PODS_ROOT}/WebexSDK/Frameworks"

strip_binary() {
  local binary_path="$1"
  if [ -f "$binary_path" ]; then
    /usr/bin/strip -x "$binary_path" || true
  fi
}

remove_virtual_background_assets() {
  local framework_dir="$1"
  rm -rf \
    "${framework_dir}/PortraitSeg.mlmodelc" \
    "${framework_dir}/PortraitSegNew.mlmodelc" \
    "${framework_dir}/wseFilter.metallib" \
    2>/dev/null || true
}

remove_ai_codec_asset() {
  local framework_dir="$1"
  rm -f \
    "${framework_dir}/ce_codec_lib_exports_723b8c1e_package_b1_v5.bin" \
    2>/dev/null || true
}

if [ ! -d "$WEBEX_ROOT" ]; then
  echo "WebexSDK frameworks not found at ${WEBEX_ROOT}; skipping optimization."
  exit 0
fi

while IFS= read -r -d '' framework; do
  framework_name="$(basename "$framework" .framework)"
  strip_binary "${framework}/${framework_name}"
  remove_virtual_background_assets "$framework"
done < <(find "$WEBEX_ROOT" -type d -name 'WebexSDK.framework' -print0)

while IFS= read -r -d '' framework; do
  framework_name="$(basename "$framework" .framework)"
  strip_binary "${framework}/${framework_name}"
  remove_ai_codec_asset "$framework"
done < <(find "$WEBEX_ROOT" -type d -name 'wbxaecodec.framework' -print0)

echo "Webex SDK optimization complete."

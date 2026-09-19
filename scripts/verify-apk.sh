#!/usr/bin/env bash
set -euo pipefail
apk="${1:?usage: verify-apk.sh APK [--allow-debug]}"
allow_debug="${2:-}"
command -v apkanalyzer >/dev/null
apksigner_bin="${APKSIGNER:-${ANDROID_HOME:-}/build-tools/35.0.0/apksigner}"
[[ -x "$apksigner_bin" ]] || { echo "apksigner not found; set APKSIGNER"; exit 1; }
signature_report="$("$apksigner_bin" verify --verbose --min-sdk-version 28 "$apk")"
printf '%s\n' "$signature_report"
"$apksigner_bin" verify --print-certs "$apk"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$signature_report" || grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$signature_report"
if [[ "$allow_debug" != "--allow-debug" ]]; then
  ! "$apksigner_bin" verify --print-certs "$apk" | grep -Eqi 'Android Debug|CN=Android Debug' || { echo "debug signing certificate is not a release artifact"; exit 1; }
fi
manifest="$(apkanalyzer manifest print "$apk")"
APK_MANIFEST="$manifest" python3 - <<'PY'
import os, xml.etree.ElementTree as E
r=E.fromstring(os.environ['APK_MANIFEST']); a='{http://schemas.android.com/apk/res/android}'
expected={'INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SYSTEM_EXEMPTED','FOREGROUND_SERVICE_SPECIAL_USE','RECEIVE_BOOT_COMPLETED','POST_NOTIFICATIONS'}
permissions={e.get(a+'name') for e in r.findall('uses-permission')}
allowed={'android.permission.'+p for p in expected} | {'com.cartunnel.client.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION','com.cartunnel.client.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
assert permissions <= allowed, permissions-allowed
assert {'android.permission.'+p for p in expected} <= permissions
assert r.get(a+'versionName') in {'1.0.0', '1.0.0-test'}, r.get(a+'versionName')
assert r.get(a+'versionCode') == '12', r.get(a+'versionCode')
assert r.get('package') == 'com.cartunnel.client.debug'
assert r.find('application').get(a+'debuggable', 'false') == 'false'
print('APK_MANIFEST_OK version='+r.get(a+'versionName')+' code=12 permission-whitelist=PASS')
PY
for token in 'com.cartunnel.client' 'usesCleartextTraffic="true"' 'allowBackup="false"' 'android.permission.BIND_VPN_SERVICE' 'FOREGROUND_SERVICE_SYSTEM_EXEMPTED' 'FOREGROUND_SERVICE_SPECIAL_USE' 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' 'minSdkVersion="28"' 'targetSdkVersion="36"'; do grep -Fq "$token" <<<"$manifest" || { echo "missing $token"; exit 1; }; done
for forbidden in 'QUERY_ALL_PACKAGES' 'BIND_ACCESSIBILITY_SERVICE' 'SYSTEM_ALERT_WINDOW' 'appops' 'pm install' 'localhost:5555'; do ! apkanalyzer files list "$apk" | grep -Fqi "$forbidden" || { echo "forbidden $forbidden"; exit 1; }; done
libs="$(apkanalyzer files list "$apk" | grep -E '^/?lib/' || true)"; grep -Fq 'lib/arm64-v8a/' <<<"$libs"; ! grep -Eq 'lib/(armeabi-v7a|x86|x86_64)/' <<<"$libs"
hev_methods="$(apkanalyzer dex packages --defined-only "$apk" | grep -F 'com.cartunnel.client.core.HevNative' || true)"
for method in 'long[] TProxyGetStats()' 'boolean TProxyIsRunning()' 'boolean TProxyStartService(java.lang.String,int)' 'boolean TProxyStopService()'; do
  grep -Fq "com.cartunnel.client.core.HevNative $method" <<<"$hev_methods" || { echo "missing HEV JNI method: $method"; exit 1; }
done
zip_listing="$(unzip -lv "$apk")"; grep -Eq 'Defl.*lib/arm64-v8a/lib(hev-socks5-tunnel|gojni)\.so' <<<"$zip_listing"
python3 - "$apk" <<'PY'
import sys, zipfile, os
with zipfile.ZipFile(sys.argv[1]) as z:
    libs=[i for i in z.infolist() if i.filename.startswith('lib/') and i.filename.endswith('.so')]
    assert libs and all(i.filename.startswith('lib/arm64-v8a/') and i.compress_type == zipfile.ZIP_DEFLATED for i in libs)
    print('NATIVE_DEFLATE_OK', ','.join(i.filename for i in libs))
assert os.path.getsize(sys.argv[1]) < 32*1024*1024, 'APK exceeds 32 MiB product budget'
print('APK_SIZE_BYTES',os.path.getsize(sys.argv[1]))
PY
shasum -a 256 "$apk"
du -h "$apk"

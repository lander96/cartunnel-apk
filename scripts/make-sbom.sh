#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$root/build"
cat > "$root/build/component-inventory.json" <<'EOF'
{"bomFormat":"CycloneDX","specVersion":"1.5","components":[
{"type":"application","name":"CarTunnel","version":"1.0.0"},
{"type":"library","group":"androidx.core","name":"core-ktx","version":"1.15.0","licenses":[{"license":{"id":"Apache-2.0"}}]},
{"type":"library","group":"androidx.appcompat","name":"appcompat","version":"1.7.0","licenses":[{"license":{"id":"Apache-2.0"}}]},
{"type":"library","group":"androidx.activity","name":"activity-ktx","version":"1.10.0","licenses":[{"license":{"id":"Apache-2.0"}}]},
{"type":"library","group":"androidx.lifecycle","name":"lifecycle-runtime-ktx","version":"2.8.7","licenses":[{"license":{"id":"Apache-2.0"}}]},
{"type":"library","group":"org.jetbrains.kotlinx","name":"kotlinx-coroutines-android","version":"1.9.0","licenses":[{"license":{"id":"Apache-2.0"}}]},
{"type":"library","name":"AndroidLibXrayLite","version":"v26.3.27","licenses":[{"license":{"id":"LGPL-3.0-or-later"}}],"properties":[{"name":"commit","value":"880725442c1d4023a973ccbcdbf527c89ef83a32"}]},
{"type":"library","name":"Xray-core","version":"v1.260327.0","licenses":[{"license":{"id":"MPL-2.0"}}],"properties":[{"name":"commit","value":"d2758a023cd7f4174a5a5fa4ff66e487d4342ba0"}]},
{"type":"library","name":"hev-socks5-tunnel","version":"2.17.1","licenses":[{"license":{"id":"MIT"}}],"hashes":[{"alg":"SHA-256","content":"ec4c335130d178e37b94c435241c77b397c5a464934c47368bf3963252736c95"}]},
{"type":"library","name":"lwIP","version":"vendored","licenses":[{"license":{"name":"BSD-3-Clause"}}]},
{"type":"library","name":"libyaml","version":"vendored","licenses":[{"license":{"id":"MIT"}}]}
]}
EOF

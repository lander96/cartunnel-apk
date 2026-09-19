# Locked Android Xray build

Source: AndroidLibXrayLite `v26.3.27`, commit `880725442c1d4023a973ccbcdbf527c89ef83a32`; it pins Xray-core `v26.3.27` commit `d2758a023cd7f4174a5a5fa4ff66e487d4342ba0`.

Run `scripts/build-xray-core.sh` with the exact Go/gomobile and Android SDK inputs in `config/versions.lock`. The generated AAR and its checksum are intentionally untracked.

#!/usr/bin/env bash
# Выпуск обновления: собирает APK, кладёт его на сайт сервиса (p2p/worker/public/app/) вместе с latest.json.
# После git push Cloudflare сам разворачивает сайт, и приложения увидят плашку «Вышла версия …».
#   ./release.sh "Что нового — одной-двумя строками"
# Перед запуском подними versionCode/versionName в app/build.gradle.kts.
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:-}"
VC=$(grep -oP 'versionCode = \K\d+' app/build.gradle.kts); VN=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
./build-nogradle.sh
OUT=../p2p/worker/public/app; mkdir -p "$OUT"
cp "wavecast-$VN.apk" "$OUT/wavecast.apk"
SHA=$(sha256sum "$OUT/wavecast.apk" | cut -d' ' -f1); SIZE=$(stat -c%s "$OUT/wavecast.apk")
python3 - "$OUT/latest.json" "$VC" "$VN" "$SHA" "$SIZE" "$NOTES" <<'PY'
import json, sys
f, vc, vn, sha, size, notes = sys.argv[1:]
json.dump({"versionCode": int(vc), "versionName": vn, "url": f"wavecast.apk?v={vc}",
           "sha256": sha, "size": int(size), "notes": notes}, open(f, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
rm -f "wavecast-$VN.apk" "wavecast-$VN.apk.idsig"; rm -rf build-nogradle
echo "готово: $VN ($VC) → $OUT. Осталось: git add -A && git commit && git push"

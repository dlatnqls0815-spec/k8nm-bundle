#!/usr/bin/env bash
# ccnc-svm-5w 설치 (Git Bash, 묻지 않음): 기존 두 모듈 끄기 → 설치 → 켜기. 재시작은 차에서.
#   bash research/k8-ccnc-svm/tools/install-combined.sh <차IP>
set -uo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
H=$(ls -t "$HERE"/../out/seogaemo/ccnc-svm-5w-*.hsm | head -1)
SG=/data/nemesis/apps/hmg-seogaemo/bin/hmg-seogaemo-root
ip=${1:?차IP}; D=$ip:5555
"$ADB" connect "$D" | tr -d '\r'
[ "$("$ADB" -s "$D" get-state 2>/dev/null | tr -d '\r')" = device ] || { echo "연결 실패: $D"; exit 1; }
root_() { "$ADB" -s "$D" shell "nemesis root -c '$1'" 2>&1 | tr -d '\r'; }
win() { command -v cygpath >/dev/null && cygpath -w "$1" || echo "$1"; }
for old in ccnc-theme-5w k8-svm-overlay-test; do echo "== $old 끄기"; root_ "/system/bin/sh $SG $old revert" | grep -E "state|detail"; done
sha=$(sha256sum "$H" | cut -d' ' -f1); md=$(md5sum "$H" | cut -d' ' -f1)
"$ADB" -s "$D" shell "mkdir -p /data/local/tmp/hsm && chmod 755 /data/local/tmp/hsm" >/dev/null
"$ADB" -s "$D" push "$(win "$H")" "/data/local/tmp/hsm/$sha.hsm" | tr -d '\r'
[ "$("$ADB" -s "$D" shell "md5 /data/local/tmp/hsm/$sha.hsm" | tr -d '\r' | cut -d' ' -f1)" = "$md" ] || { echo "전송 md5 불일치"; exit 1; }
root_ "/system/bin/sh $SG module install $sha" | grep -E "state|detail"
"$ADB" -s "$D" shell "rm -f /data/local/tmp/hsm/$sha.hsm" >/dev/null
echo "== ccnc-svm-5w 켜기"; root_ "/system/bin/sh $SG ccnc-svm-5w apply" | grep -E "state|detail"
echo "헤드유닛을 재시작하세요."

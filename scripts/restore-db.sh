#!/usr/bin/env bash
# RPG DB 복원. 서버를 끈 상태에서 실행한다.
# 사용: scripts/restore-db.sh <백업 파일> [RUN_DIR]
set -euo pipefail
BACKUP="${1:?백업 파일 경로가 필요합니다}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="${2:-${RUN_DIR:-$ROOT/run}}"
DATA="$RUN_DIR/plugins/MinecraftRPG"
DB="$DATA/rpg.db"

[[ -f "$BACKUP" ]] || { echo "백업 파일 없음: $BACKUP" >&2; exit 1; }
if pgrep -f "paper-.*\.jar" >/dev/null && [[ "${FORCE:-}" != "true" ]]; then
  echo "Paper 서버가 실행 중입니다. 서버를 끈 뒤 다시 실행하세요(FORCE=true로 무시)." >&2
  exit 1
fi
mkdir -p "$DATA"
TS="$(date +%Y%m%d-%H%M%S)"
if [[ -f "$DB" ]]; then
  mv "$DB" "$DB.before-restore-$TS"
  for ext in -wal -shm; do [[ -f "$DB$ext" ]] && mv "$DB$ext" "$DB$ext.before-restore-$TS"; done
fi
cp "$BACKUP" "$DB"
echo "복원 완료: $BACKUP → $DB (이전 파일은 *.before-restore-$TS)"

#!/usr/bin/env bash
# 슬라이스 1 E2E: 오프라인 모드 테스트 서버(run-e2e, 포트 25599) + mineflayer 봇 2개.
# 사용: EULA_ACCEPTED=true scripts/e2e.sh [core|dungeon|all]   (기본 all)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/run-e2e"
[[ "${EULA_ACCEPTED:-}" == "true" ]] || { echo "EULA_ACCEPTED=true 가 필요합니다." >&2; exit 1; }

source <(sed -n 's/^\(PAPER_[A-Z0-9_]*=.*\)$/\1/p' "$ROOT/scripts/run-server.sh")
JAR_NAME="paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"

(cd "$ROOT" && ./gradlew -q build)
rm -rf "$DIR"
mkdir -p "$DIR/plugins"
if [[ -f "$ROOT/run/$JAR_NAME" ]]; then cp "$ROOT/run/$JAR_NAME" "$DIR/"; else curl -fsSL -o "$DIR/$JAR_NAME" "$PAPER_URL"; fi
echo "${PAPER_SHA256}  $DIR/$JAR_NAME" | sha256sum -c --quiet -
cp "$ROOT"/build/libs/minecraft-rpg-*.jar "$DIR/plugins/"
echo "eula=true" > "$DIR/eula.txt"
cat > "$DIR/server.properties" <<PROPS
online-mode=false
server-port=25599
server-ip=127.0.0.1
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
difficulty=normal
view-distance=4
simulation-distance=4
motd=MinecraftRPG E2E
PROPS
(cd "$ROOT/e2e" && npm ci --silent)
SUITE="${1:-all}"
status=0
if [[ "$SUITE" == "core" || "$SUITE" == "all" ]]; then node "$ROOT/e2e/run.mjs" || status=1; fi
if [[ "$SUITE" == "dungeon" || "$SUITE" == "all" ]]; then
  if [[ "$SUITE" == "all" ]]; then rm -rf "$DIR/world" "$DIR/world_nether" "$DIR/world_the_end" "$DIR/plugins/MinecraftRPG"; fi
  node "$ROOT/e2e/dungeon.mjs" || status=1
fi
exit $status

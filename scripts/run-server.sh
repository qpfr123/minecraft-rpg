#!/usr/bin/env bash
# 고정된 Paper 빌드로 로컬 테스트 서버를 실행한다.
# 사용: EULA_ACCEPTED=true scripts/run-server.sh [--boot-test]
set -euo pipefail

PAPER_VERSION="1.21.11"
PAPER_BUILD="132"
PAPER_SHA256="5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="${RUN_DIR:-$ROOT/run}"
JAR="$RUN_DIR/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"
MEMORY="${MEMORY:-2G}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [[ "${EULA_ACCEPTED:-}" != "true" ]]; then
  echo "Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의하면 EULA_ACCEPTED=true 로 실행하세요." >&2
  exit 1
fi

mkdir -p "$RUN_DIR/plugins"
if [[ ! -f "$JAR" ]]; then
  echo "Paper ${PAPER_VERSION} build ${PAPER_BUILD} 다운로드"
  curl -fsSL -o "$JAR.tmp" "$PAPER_URL"
  mv "$JAR.tmp" "$JAR"
fi
echo "${PAPER_SHA256}  ${JAR}" | sha256sum -c --quiet -

(cd "$ROOT" && ./gradlew -q build)
rm -f "$RUN_DIR"/plugins/minecraft-rpg-*.jar
cp "$ROOT"/build/libs/minecraft-rpg-*.jar "$RUN_DIR/plugins/"
echo "eula=true" > "$RUN_DIR/eula.txt"

cd "$RUN_DIR"
if [[ "${1:-}" == "--boot-test" ]]; then
  # 부팅 완료(Done) 후 stop 명령으로 정상 종료까지 검증
  rm -f boot-test.log console.fifo; mkfifo console.fifo
  "$JAVA" -Xms"$MEMORY" -Xmx"$MEMORY" -jar "$JAR" --nogui < console.fifo > boot-test.log 2>&1 &
  PID=$!
  exec 3> console.fifo
  for _ in $(seq 1 300); do
    grep -q "Done (" boot-test.log && break
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  sleep 5
  echo stop >&3
  for _ in $(seq 1 120); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
  if kill -0 "$PID" 2>/dev/null; then kill "$PID"; echo "FAIL: stop 후 120초 안에 종료되지 않음"; exit 1; fi
  wait "$PID" || { echo "FAIL: 비정상 종료 코드"; tail -40 boot-test.log; exit 1; }
  exec 3>&-; rm -f console.fifo
  grep -q "Command exception" boot-test.log && { echo "FAIL: 콘솔 명령 예외"; exit 1; }
  grep -q "MinecraftRPG .* enabled" boot-test.log || { echo "FAIL: 플러그인 활성화 로그 없음"; tail -40 boot-test.log; exit 1; }
  grep -q "MinecraftRPG disabled" boot-test.log || { echo "FAIL: 플러그인 종료 로그 없음"; tail -40 boot-test.log; exit 1; }
  if grep -i -E "ERROR.*MinecraftRPG|MinecraftRPG.*ERROR|io\.github\.qpfr123" boot-test.log | grep -q .; then
    echo "FAIL: 플러그인 관련 오류"; grep -E "ERROR|Exception" boot-test.log; exit 1
  fi
  echo "PASS: Paper ${PAPER_VERSION}-${PAPER_BUILD} 부팅, MinecraftRPG 활성화/종료 확인"
else
  exec "$JAVA" -Xms"$MEMORY" -Xmx"$MEMORY" -jar "$JAR" --nogui
fi

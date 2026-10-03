# minecraft-rpg

Minecraft RPG 서버용 Paper 플러그인.

- Paper 1.21.11 (build 132 고정) / Java 21 / Gradle Kotlin DSL / SQLite
- 설계: [docs/design.md](docs/design.md)
- 슬라이스 1(성장·전투·보상): [docs/slice-1.md](docs/slice-1.md)
- 슬라이스 2(던전·사망 페널티): [docs/slice-2.md](docs/slice-2.md)
- 던전 맵(기본 던전 구조·게임 안 편집기): [docs/dungeon-maps.md](docs/dungeon-maps.md)
- 직업 아이디어(미구현): [docs/job-ideas.md](docs/job-ideas.md)

## 빌드와 실행

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build                                   # 플러그인 jar와 테스트
EULA_ACCEPTED=true scripts/run-server.sh --boot-test  # 부팅→활성화→정상 종료 검증 (BOOT_TIMEOUT 기본 300초, 초과 시 실패)
EULA_ACCEPTED=true scripts/run-server.sh              # 로컬 테스트 서버 실행 (run/)
EULA_ACCEPTED=true scripts/e2e.sh [core|dungeon|editor|all]  # 봇 2개로 통과 조건 재현 (run-e2e/, 전체 약 20분)
scripts/restore-db.sh <백업파일>                       # 서버를 끈 뒤 RPG DB 복원
```

E2E에는 Node.js 22가 필요하다.

`EULA_ACCEPTED=true`는 Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의한다는 뜻이다.

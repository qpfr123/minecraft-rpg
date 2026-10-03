# minecraft-rpg

Minecraft RPG 서버용 Paper 플러그인.

- Paper 1.21.11 (build 132 고정) / Java 21 / Gradle Kotlin DSL / SQLite
- 설계: [docs/design.md](docs/design.md)
- 현재 범위와 통과 조건: [docs/slice-1.md](docs/slice-1.md)
- 직업 아이디어(미구현): [docs/job-ideas.md](docs/job-ideas.md)

## 빌드와 실행

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build                                   # 플러그인 jar와 테스트
EULA_ACCEPTED=true scripts/run-server.sh --boot-test  # 부팅→활성화→정상 종료 검증 (BOOT_TIMEOUT 기본 300초, 초과 시 실패)
EULA_ACCEPTED=true scripts/run-server.sh              # 로컬 테스트 서버 실행 (run/)
```

`EULA_ACCEPTED=true`는 Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의한다는 뜻이다.

// 슬라이스 2(던전)·사망 페널티 E2E. 사용: scripts/e2e.sh dungeon
import fs from 'node:fs';
import path from 'node:path';
import { Server, connectBot as connect, quit, sleep, waitChat } from './lib.mjs';

// 월드를 오가는 텔레포트 직후 mineflayer는 새 월드 청크를 받기 전에 물리 계산으로 허공에 떨어진다.
// (실제 클라이언트는 청크를 받을 때까지 기다린다.) 던전 테스트에서는 봇 물리를 끄고 서버 위치만 따른다.
async function connectBot(name, port) {
  const bot = await connect(name, port);
  bot.physicsEnabled = false;
  return bot;
}

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const DIR = path.join(ROOT, 'run-e2e');
const PORT = 25599;
const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
const JAR = fs.readdirSync(DIR).find((f) => /^paper-.*\.jar$/.test(f));
const server = new Server({ dir: DIR, jar: JAR, java: JAVA, port: PORT });

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

async function dungeonList() {
  const lines = await server.collect('rpgadmin dungeon list', 1000);
  const head = lines.find((l) => /\[dungeon\] cap=/.test(l)) || '';
  const open = +(head.match(/open=(\d+)/) || [0, -1])[1];
  const instances = lines.map((l) => l.match(/\[dungeon\] instance (\w+) world=(\S+) dungeon=(\S+) state=(\S+) members=\[(.*)\]/)).filter(Boolean)
    .map((m) => ({ id: m[1], world: m[2], state: m[4], members: m[5] ? m[5].split(', ') : [] }));
  return { open, instances };
}

// @e는 차원을 가리지 않으므로 distance로 해당 인스턴스 월드에 한정한다.
async function count(world, type) {
  const line = await server.query(`execute in minecraft:${world} positioned 0 64 30 if entity @e[type=${type},distance=..200]`, /Test (passed|failed)/);
  const m = line.match(/[Cc]ount: (\d+)/);
  return m ? +m[1] : 0;
}
const killIn = (world, type) => server.cmd(`execute in minecraft:${world} positioned 0 64 30 run kill @e[type=${type},distance=..200]`);

async function ledger(name) {
  const lines = await server.collect(`rpgadmin ledger ${name}`, 1200);
  return lines.map((l) => l.match(/(PENDING|CLAIMING|CLAIMED) ((?:kill|test|dungeon):\S+) EXP (\d+) \[(.*)\]/)).filter(Boolean)
    .map((m) => ({ status: m[1], event: m[2], exp: +m[3] }));
}

const near = (bot, x, y, z, tol = 1.5) => {
  const p = bot.entity.position;
  return Math.abs(p.x - x) < tol && Math.abs(p.y - y) < tol + 1 && Math.abs(p.z - z) < tol;
};
const pos = (bot) => { const p = bot.entity.position; return `${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}`; };
const instExists = (id) => fs.existsSync(path.join(DIR, `rpg_inst_${id}`));

async function enterSolo(bot) {
  const from = bot.chatLog.length;
  bot.chat('/dungeon enter crypt');
  return waitChat(bot, /입장했습니다|가득 찼습니다|이미/, 30_000, from);
}

async function hitUntilDead(bot, pred, max = 120) {
  for (let i = 0; i < max; i++) {
    const e = Object.values(bot.entities).find(pred);
    if (!e || !e.isValid) return true;
    await bot.lookAt(e.position.offset(0, e.height * 0.6, 0), true);
    bot.attack(e);
    await sleep(700);
  }
  return false;
}

async function main() {
  await server.start();
  // 1.21.11은 게임 규칙 이름이 바뀌어 옛 이름과 새 이름을 모두 보낸다(오버월드 자연 스폰 구울이 봇을 공격하지 않게).
  for (const c of ['gamerule doMobSpawning false', 'gamerule spawn_mobs false', 'gamerule doDaylightCycle false', 'gamerule advance_time false', 'time set 6000', 'op bot_a', 'op bot_b',
    'rpgadmin dungeon idle 10', 'rpgadmin dungeon exitdelay 5', 'rpgadmin dungeon cap 1', 'kill @e[type=!player]']) server.cmd(c);
  await sleep(800);
  let a = await connectBot('bot_a', PORT);
  let b = await connectBot('bot_b', PORT);
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  server.cmd('tp bot_b 5.5 -60 0.5 0 0');
  await sleep(1500);
  server.cmd('execute in minecraft:overworld positioned 0 -60 0 run kill @e[type=!player,distance=..300]');
  await sleep(500);

  // 1. 사망 페널티: RPG 장비 보존, 바닐라 아이템은 바닐라 규칙
  server.cmd('rpgadmin givegear bot_a ghoul_blade');
  server.cmd('give bot_a minecraft:dirt 5');
  await sleep(800);
  server.cmd('kill bot_a');
  await sleep(2500);
  const blade = a.inventory.items().filter((i) => i.name === 'iron_sword').length;
  const dirt = a.inventory.items().filter((i) => i.name === 'dirt').length;
  check('사망 후 RPG 장비는 남고 바닐라 아이템은 떨어짐', a.deaths === 1 && blade === 1 && dirt === 0, `deaths=${a.deaths} blade=${blade} dirt=${dirt}`);
  server.cmd('kill @e[type=item]');
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  await sleep(1500);

  // 2. 솔로 입장 → 인스턴스 월드, 몹 배치
  let msg = await enterSolo(a);
  await sleep(1500);
  let list = await dungeonList();
  const i1 = list.instances[0];
  check('솔로 입장: 인스턴스 생성·입구로 이동', /입장했습니다/.test(msg) && list.open === 1 && i1?.members.join() === 'bot_a' && near(a, 0.5, 64, 2.5),
    `${msg} open=${list.open} pos=${pos(a)}`);
  const z1 = await count(i1.world, 'zombie');
  const s1 = await count(i1.world, 'skeleton');
  const w1 = await count(i1.world, 'wither_skeleton');
  check('던전 몹 배치: 구울 5, 해골 궁수 2, 보스 1', z1 === 5 && s1 === 2 && w1 === 1, `zombie=${z1} skeleton=${s1} wither=${w1}`);

  // 3. 동시 인스턴스 상한
  msg = await enterSolo(b);
  check('상한 1: 두 번째 인스턴스는 거절', /가득 찼습니다/.test(msg), msg);
  server.cmd('rpgadmin dungeon cap 2');
  await sleep(300);
  msg = await enterSolo(b);
  await sleep(1500);
  list = await dungeonList();
  const i2 = list.instances.find((i) => i.members.includes('bot_b'));
  check('상한 2: 두 번째 인스턴스 생성, 서로 다른 월드', /입장했습니다/.test(msg) && list.open === 2 && i2 && i2.world !== i1.world, msg);

  // 4. 인스턴스 분리: 한쪽 몹을 없애도 다른 쪽은 그대로
  killIn(i1.world, 'zombie');
  await sleep(800);
  const za = await count(i1.world, 'zombie');
  const zb = await count(i2.world, 'zombie');
  check('인스턴스 분리: 1번 구울 제거가 2번에 영향 없음', za === 0 && zb === 5, `inst1=${za} inst2=${zb}`);

  // 5. 퇴장 → 원래 위치, 멤버가 없으면 인스턴스 정리
  const fromLeave = b.chatLog.length;
  b.chat('/dungeon leave');
  await waitChat(b, /던전에서 나왔습니다/, 10_000, fromLeave).catch(() => null);
  await sleep(4000);
  list = await dungeonList();
  check('퇴장: 원래 위치로 귀환', near(b, 5.5, -60, 0.5), pos(b));
  check('퇴장: 빈 인스턴스 정리(월드 폴더 삭제)', list.open === 1 && !instExists(i2.id), `open=${list.open} folder=${instExists(i2.id)}`);

  // 6. 안에서 접속 끊고 바로 재접속 → 계속 안에 있음
  await quit(a);
  await sleep(2000);
  a = await connectBot('bot_a', PORT);
  await sleep(1500);
  check('재접속(유휴 시간 전): 던전 안으로 복귀', near(a, 0.5, 64, 2.5) && a.chatLog.some((m) => /진행 중인 던전으로 돌아왔습니다/.test(m)), pos(a));

  // 7. 접속을 끊은 채 유휴 시간 초과 → 인스턴스 정리, 재접속 시 원래 위치
  await quit(a);
  await sleep(14_000);
  list = await dungeonList();
  check('유휴 10초 초과: 인스턴스 정리', list.open === 0 && !instExists(i1.id), `open=${list.open}`);
  a = await connectBot('bot_a', PORT);
  await sleep(2500);
  check('정리된 던전에서 재접속: 원래 위치로 귀환', near(a, 0.5, -60, 0.5), pos(a));

  // 8. 파티 입장·던전 안 사망·파티 탈퇴·보스 클리어·자동 귀환
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  server.cmd('tp bot_b 5.5 -60 0.5 0 0');
  await sleep(1000);
  let from = b.chatLog.length;
  a.chat('/party invite bot_b');
  await waitChat(b, /파티에 초대했습니다/, 5000, from);
  b.chat('/party accept');
  await sleep(800);
  from = b.chatLog.length;
  b.chat('/dungeon enter crypt');
  const notLeader = await waitChat(b, /리더만/, 5000, from).catch(() => null);
  check('파티원(리더 아님)은 입장시킬 수 없음', !!notLeader);
  msg = await enterSolo(a);
  await waitChat(b, /입장했습니다/, 20_000).catch(() => null);
  await sleep(1500);
  list = await dungeonList();
  const ip = list.instances[0];
  check('파티 입장: 두 사람이 같은 인스턴스', ip && ip.members.length === 2 && near(a, 0.5, 64, 2.5) && near(b, 0.5, 64, 2.5), JSON.stringify(ip));

  const deathsBefore = b.deaths;
  server.cmd('kill bot_b');
  await sleep(3000);
  list = await dungeonList();
  check('던전 안 사망 → 같은 인스턴스 입구에서 리스폰', b.deaths === deathsBefore + 1 && near(b, 0.5, 64, 2.5) && list.instances[0]?.id === ip.id,
    `deaths ${deathsBefore}→${b.deaths} pos=${pos(b)}`);
  b.chat('/party leave');
  await sleep(800);
  list = await dungeonList();
  check('파티를 떠나도 진행 중인 인스턴스 멤버는 유지', list.instances[0]?.members.length === 2, JSON.stringify(list.instances[0]));

  killIn(ip.world, 'zombie');
  killIn(ip.world, 'skeleton');
  server.cmd(`execute in minecraft:${ip.world} positioned 0 64 30 run data merge entity @e[type=wither_skeleton,limit=1,distance=..200] {NoAI:1b}`);
  server.cmd(`execute in minecraft:${ip.world} positioned 0 64 30 run damage @e[type=wither_skeleton,limit=1,distance=..200] 18 minecraft:generic`); // 1200 → 120
  server.cmd(`execute in minecraft:${ip.world} run tp bot_a 0.5 64 55.5 0 0`);
  server.cmd(`execute in minecraft:${ip.world} run tp bot_b 1.5 64 55.5 0 0`);
  await sleep(2000);
  const isBoss = (e) => e.name === 'wither_skeleton';
  await Promise.all([hitUntilDead(a, isBoss), (async () => { await sleep(300); await hitUntilDead(b, isBoss); })()]);
  const clearA = await waitChat(a, /클리어!/, 10_000).catch(() => null);
  await sleep(2500);
  const la = (await ledger('bot_a')).filter((r) => r.event === `dungeon:${ip.id}:clear`);
  const lb = (await ledger('bot_b')).filter((r) => r.event === `dungeon:${ip.id}:clear`);
  check('보스 처치: 클리어 보상 멤버별 1건(EXP 400)', !!clearA && la.length === 1 && lb.length === 1 && la[0].exp === 400 && la[0].status === 'CLAIMED',
    `a=${JSON.stringify(la)} b=${JSON.stringify(lb)}`);
  await sleep(6000);
  list = await dungeonList();
  check('클리어 후 귀환 지연(5초) 뒤 두 사람 모두 원래 위치', near(a, 0.5, -60, 0.5) && near(b, 5.5, -60, 0.5), `a=${pos(a)} b=${pos(b)}`);
  check('클리어 인스턴스 정리', list.open === 0 && !instExists(ip.id), `open=${list.open}`);

  // 9. 던전 안에서 서버 강제 종료 → 재시작 시 남은 폴더 삭제, 재접속하면 원래 위치
  msg = await enterSolo(a);
  await sleep(1500);
  list = await dungeonList();
  const ik = list.instances[0];
  server.cmd('save-all');
  await server.waitFor(/Saved the game/, 15_000);
  server.proc.kill('SIGKILL');
  await server.exit;
  await quit(a); await quit(b);
  check('강제 종료 직후 인스턴스 폴더가 남아 있음(복구 대상)', instExists(ik.id));
  await server.start();
  check('재시작: 남은 인스턴스 폴더 삭제', !instExists(ik.id) && server.lines.some((l) => /removed leftover dungeon instance folder/.test(l)));
  a = await connectBot('bot_a', PORT);
  await sleep(3000);
  check('재시작 후 접속: 원래 위치로 귀환', near(a, 0.5, -60, 0.5), pos(a));
  list = await dungeonList();
  check('재시작 후 열린 인스턴스 없음', list.open === 0);

  // 10. 귀환 직후 강제 종료: 플레이어 데이터 저장 전/후 모두 다음 접속에서 원래 위치로 복구
  const session = async (name) => {
    const l = await server.query(`rpgadmin dungeon session ${name}`, /\[dungeon\] session /, 10_000);
    const m = l.match(/instance=(\w+)/);
    return m ? m[1] : null;
  };
  for (const point of ['after-teleport', 'after-save']) {
    server.cmd('tp bot_a 0.5 -60 0.5 0 0');
    await sleep(1000);
    await enterSolo(a);
    await sleep(1500);
    const sid = await session('bot_a');
    server.cmd(`rpgadmin dungeon crash ${point}`);
    await sleep(300);
    a.chat('/dungeon leave');
    const exit = await Promise.race([server.exit, sleep(15_000).then(() => null)]);
    await quit(a);
    check(`[귀환 ${point}] 귀환 도중 강제 종료 발생`, exit && exit.code === 137, JSON.stringify(exit));
    await server.start();
    const kept = await session('bot_a');
    check(`[귀환 ${point}] 재시작 후 귀환 기록 유지`, !!sid && kept === sid, `entered=${sid} kept=${kept}`);
    a = await connectBot('bot_a', PORT);
    await sleep(3000);
    const after = await session('bot_a');
    check(`[귀환 ${point}] 재접속 시 원래 위치로 복구·기록 삭제`, near(a, 0.5, -60, 0.5) && after === null, `pos=${pos(a)} session=${after}`);
  }

  // 11. 던전 준비(복사) 중 재접속 → 기록 유지 → 준비 완료 후 입장 → 강제 종료 → 원래 위치 복구
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  server.cmd('rpgadmin dungeon copydelay 8000');
  await sleep(1000);
  a.chat('/dungeon enter crypt');
  await sleep(1000);
  const preparing = await session('bot_a');
  await quit(a);
  await sleep(1000);
  a = await connectBot('bot_a', PORT);
  await sleep(1500);
  const duringRejoin = await session('bot_a');
  list = await dungeonList();
  check('[준비 중 재접속] 인스턴스 준비 중이고 귀환 기록 유지', !!preparing && duringRejoin === preparing && list.instances[0]?.state === 'CREATING',
    `before=${preparing} after=${duringRejoin} state=${list.instances[0]?.state}`);
  await waitChat(a, /입장했습니다/, 20_000).catch(() => null);
  await sleep(1500);
  const inside = await session('bot_a');
  check('[준비 중 재접속] 준비 완료 후 입장, 기록은 그대로', near(a, 0.5, 64, 2.5) && inside === preparing, `pos=${pos(a)} session=${inside}`);
  server.cmd('rpgadmin dungeon copydelay 0');
  server.cmd('save-all');
  await server.waitFor(/Saved the game/, 15_000);
  server.proc.kill('SIGKILL');
  await server.exit;
  await quit(a);
  await server.start();
  a = await connectBot('bot_a', PORT);
  await sleep(3000);
  const finalSession = await session('bot_a');
  check('[준비 중 재접속] 강제 종료 후 재접속: 원래 위치·기록 삭제', near(a, 0.5, -60, 0.5) && finalSession === null, `pos=${pos(a)} session=${finalSession}`);

  await quit(a);
  await server.stop();
  const severe = server.all.filter((l) => /ERROR|SEVERE/.test(l) && /MinecraftRPG|io\.github\.qpfr123/.test(l) && !/crash-test/.test(l));
  check('플러그인 ERROR/SEVERE 로그 없음', severe.length === 0, severe.slice(0, 3).join(' | '));
}

main().catch((e) => {
  console.error(e);
  check('E2E 실행 완료', false, String(e && e.message));
}).finally(async () => {
  try { if (!server.exited) { server.cmd('stop'); await Promise.race([server.exit, sleep(30_000)]); } } catch {}
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed`);
  process.exit(failed.length ? 1 : 0);
});

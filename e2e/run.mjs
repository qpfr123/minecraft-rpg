// 슬라이스 1 통과 조건을 실제 Paper 서버와 봇 2개로 재현한다.
// 사용: JAVA_HOME=... node e2e/run.mjs   (scripts/e2e.sh가 준비·실행)
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Server, connectBot, quit, sleep, waitChat, addExp } from './lib.mjs';

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

async function profile(name) {
  const line = await server.query(`rpgadmin profile ${name}`, /\[(접속 중\(메모리\)|DB)\] .* Lv \d+/);
  const m = line.match(/Lv (\d+) EXP (\d+) HP ([\d.]+) MP ([\d.]+) 방어막 ([\d.]+) v(\d+) \| (.*) \| 미사용 (\d+)/);
  const alloc = Object.fromEntries([...m[7].matchAll(/(\S+?)(\d+)/g)].map((x) => [x[1], +x[2]]));
  return { level: +m[1], exp: +m[2], hp: +m[3], mp: +m[4], shield: +m[5], version: +m[6], alloc, unspent: +m[8] };
}

async function ledger(name) {
  const lines = await server.collect(`rpgadmin ledger ${name}`, 1200);
  return lines.map((l) => l.match(/(PENDING|CLAIMING|CLAIMED) ((?:kill|test):\S+) EXP (\d+) \[(.*)\]/)).filter(Boolean)
    .map((m) => ({ status: m[1], event: m[2], exp: +m[3], gear: m[4] ? m[4].split(', ') : [] }));
}

async function spawnMob(id, near, x, y, z) {
  const line = await server.query(`rpgadmin spawn ${id} ${near}`, /소환: .* ([0-9a-f-]{36})/);
  const uuid = line.match(/([0-9a-f-]{36})/)[1];
  server.cmd(`data merge entity ${uuid} {NoAI:1b}`);
  server.cmd(`tp ${uuid} ${x} ${y} ${z}`);
  await sleep(500);
  return uuid;
}

function findMob(bot, uuid) {
  return Object.values(bot.entities).find((e) => e.uuid === uuid);
}

async function hit(bot, uuid, times, interval = 700) {
  let n = 0;
  for (let i = 0; i < times; i++) {
    const e = findMob(bot, uuid);
    if (!e || !e.isValid) break;
    await bot.lookAt(e.position.offset(0, e.height * 0.6, 0), true);
    bot.attack(e);
    n++;
    await sleep(interval);
  }
  return n;
}

async function hitUntilDead(bot, uuid, max = 200) {
  for (let i = 0; i < max; i++) {
    const e = findMob(bot, uuid);
    if (!e || !e.isValid) return true;
    await bot.lookAt(e.position.offset(0, e.height * 0.6, 0), true);
    bot.attack(e);
    await sleep(700);
  }
  return false;
}

const countItem = (bot, name) => bot.inventory.items().filter((i) => i.name === name).reduce((a, i) => a + i.count, 0);

async function holdItem(bot, name, dest = 'hand') {
  for (let i = 0; i < 20 && !bot.inventory.items().find((x) => x.name === name); i++) await sleep(200);
  const it = bot.inventory.items().find((x) => x.name === name);
  if (!it) throw new Error(`${bot.username} has no ${name}`);
  await bot.equip(it, dest);
  await sleep(300);
}

async function placeBots(a, b) {
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  if (b) server.cmd('tp bot_b 1.5 -60 0.5 0 0');
  await sleep(1500);
}

async function setupWorld() {
  for (const c of ['gamerule doMobSpawning false', 'gamerule spawn_mobs false', 'gamerule doDaylightCycle false',
    'gamerule advance_time false', 'gamerule doWeatherCycle false', 'gamerule advance_weather false', 'time set 18000',
    'weather clear', 'difficulty normal', 'op bot_a', 'op bot_b', 'kill @e[type=!player]']) server.cmd(c);
  await sleep(1000);
}

async function bossPrepared(near, remain) {
  const uuid = await spawnMob('grave_knight', near, 0.5, -60, 2.5);
  // 원피해/20 × 최대HP(2000)를 환경 피해로 미리 깎아 테스트 시간을 줄인다. 환경 피해는 기여로 치지 않는다.
  const raw = ((2000 - remain) / 2000) * 20;
  server.cmd(`damage ${uuid} ${raw} minecraft:generic`);
  await sleep(500);
  return uuid;
}

async function main() {
  await server.start();
  await setupWorld();
  let a = await connectBot('bot_a', PORT, { respawn: false }); // 사망 직후 상태를 확인하려고 수동 리스폰
  let b = await connectBot('bot_b', PORT);
  await placeBots(a, b);

  // 1. 첫 입장 프로필
  let pa = await profile('bot_a');
  check('첫 입장: Lv1, HP/MP 100, 미사용 10', pa.level === 1 && Math.round(pa.hp) === 100 && pa.unspent === 10, JSON.stringify(pa));
  check('바닐라 체력 표시 20', Math.abs(a.health - 20) < 0.01, `health=${a.health}`);

  // 2. 스탯 배분과 50% 상한
  let from = a.chatLog.length;
  a.chat('/rpg alloc 근력 5');
  await waitChat(a, /근력 \+5/, 5000, from);
  from = a.chatLog.length;
  a.chat('/rpg alloc 근력 1');
  const capMsg = await waitChat(a, /50%/, 5000, from).catch(() => null);
  a.chat('/rpg alloc 건강 5');
  await sleep(800);
  pa = await profile('bot_a');
  check('50% 상한: 근력 5까지만, 건강 5 배분', pa.alloc['근력'] === 5 && pa.alloc['건강'] === 5 && pa.unspent === 0 && !!capMsg, JSON.stringify(pa.alloc));

  // 3. 환경 피해와 HP 표시 — 22칸 낙하(원피해 19) → 19/20×최대HP
  const maxHpA = Math.floor(100 * (1 + 0.006 * 5)); // 건강 5 → 103
  server.cmd(`effect clear bot_a`);
  pa = await profile('bot_a');
  server.cmd('execute as bot_a at @s run tp @s ~ ~22 ~');
  await sleep(3500);
  pa = await profile('bot_a');
  check('낙하 피해가 최대HP 비례로 들어가고 살아 있음', a.deaths === 0 && pa.hp > 0 && pa.hp < 20, `hp=${pa.hp} max=${maxHpA}`);
  check('RPG HP가 낮아도 바닐라 체력은 0이 아님(≥0.5)', a.health >= 0.5 && a.health <= 4, `health=${a.health}`);

  // 4. 바닐라 회복 차단 — 즉시 회복 물약
  const before = (await profile('bot_a')).hp;
  server.cmd('effect give bot_a minecraft:instant_health 1 3');
  server.cmd('effect give bot_a minecraft:regeneration 3 5');
  await sleep(2000);
  const after = (await profile('bot_a')).hp;
  check('물약·재생 효과가 RPG HP를 회복시키지 않음(RPG 재생만)', after - before < 12, `before=${before} after=${after}`);
  server.cmd('effect clear bot_a');

  // 5. HP 0일 때만 사망
  server.cmd('execute as bot_a at @s run tp @s ~ ~22 ~');
  await sleep(3500);
  check('두 번째 낙하로 HP 0 → 사망 1회', a.deaths === 1, `deaths=${a.deaths}`);
  pa = await profile('bot_a');
  check('사망 시 RPG HP 0 기록', pa.hp === 0, `hp=${pa.hp}`);
  a.respawn();
  await sleep(2500);
  pa = await profile('bot_a');
  check('리스폰 후 HP 최대치 복구', Math.round(pa.hp) === maxHpA && Math.abs(a.health - 20) < 0.01, `hp=${pa.hp} health=${a.health}`);

  // 6. 바닐라 갑옷·인챈트 이중 적용 없음, RPG 장비 DEF 적용
  await placeBots(a, b);
  const knight = await spawnMob('grave_knight', 'bot_a', 3.5, -60, 3.5);
  server.cmd('item replace entity bot_a armor.chest with minecraft:diamond_chestplate[minecraft:enchantments={"minecraft:protection":4}]');
  await sleep(500);
  let h0 = (await profile('bot_a')).hp;
  server.cmd(`damage bot_a 1 minecraft:mob_attack by ${knight}`);
  await sleep(400);
  let h1 = (await profile('bot_a')).hp;
  check('다이아 갑옷+보호 IV는 RPG 피해를 줄이지 않음(묘지 기사 ATK 25)', Math.abs((h0 - h1) - 25) < 1.0, `delta=${(h0 - h1).toFixed(2)}`);
  server.cmd('item replace entity bot_a armor.chest with minecraft:air');
  server.cmd('rpgadmin givegear bot_a ghoul_hide_vest');
  server.cmd('rpgadmin givegear bot_a grave_knight_helm');
  await sleep(800);
  await holdItem(a, 'leather_chestplate', 'torso');
  await holdItem(a, 'iron_helmet', 'head');
  server.cmd('effect give bot_a minecraft:instant_health 1 3'); // 차단돼야 함
  await sleep(1500);
  h0 = (await profile('bot_a')).hp;
  server.cmd(`damage bot_a 1 minecraft:mob_attack by ${knight}`);
  await sleep(400);
  h1 = (await profile('bot_a')).hp;
  check('RPG 장비 DEF 13% 적용: 25 → 21.75', Math.abs((h0 - h1) - 21.75) < 1.0, `delta=${(h0 - h1).toFixed(2)}`);
  server.cmd(`kill ${knight}`);

  // 7. PvP 비활성
  await placeBots(a, b);
  h0 = (await profile('bot_a')).hp;
  for (let i = 0; i < 3; i++) { await b.lookAt(a.entity.position.offset(0, 1.5, 0), true); b.attack(a.entity); await sleep(700); }
  h1 = (await profile('bot_a')).hp;
  check('PvP 비활성: 다른 플레이어 공격에 HP 감소 없음', h1 >= h0 - 0.01, `before=${h0} after=${h1}`);

  // 8. 일반 몹: 2인이 같이 때려도 보상은 첫 타격자 1명에게 한 번
  server.cmd('rpgadmin givegear bot_a ghoul_blade');
  server.cmd('rpgadmin givegear bot_b ghoul_blade');
  await sleep(800);
  await holdItem(a, 'iron_sword');
  await holdItem(b, 'iron_sword');
  await placeBots(a, b);
  const ghoul = await spawnMob('ghoul', 'bot_a', 0.5, -60, 2.5);
  const expBefore = await profile('bot_a');
  await hit(a, ghoul, 1);
  await Promise.all([hitUntilDead(a, ghoul), (async () => { await sleep(300); await hitUntilDead(b, ghoul); })()]);
  await sleep(2000);
  const la = (await ledger('bot_a')).filter((r) => r.event === `kill:${ghoul}`);
  const lb = (await ledger('bot_b')).filter((r) => r.event === `kill:${ghoul}`);
  const expAfter = await profile('bot_a');
  const expected = addExp(expBefore.level, expBefore.exp, 30);
  check('일반 몹 보상: 첫 타격자 1건, 다른 플레이어 0건', la.length === 1 && lb.length === 0 && la[0].status === 'CLAIMED', `a=${JSON.stringify(la)} b=${lb.length}`);
  check('일반 몹 EXP 30 정확히 한 번', expAfter.level === expected.level && expAfter.exp === expected.exp, `before=${expBefore.level}/${expBefore.exp} after=${expAfter.level}/${expAfter.exp}`);

  // 9. 공용 보스: 기여 5% 이상인 사람마다 한 번
  await placeBots(a, b);
  const boss1 = await bossPrepared('bot_a', 300);
  await hit(b, boss1, 9);
  await hitUntilDead(a, boss1);
  await sleep(2500);
  let ba = (await ledger('bot_a')).filter((r) => r.event === `kill:${boss1}`);
  let bb = (await ledger('bot_b')).filter((r) => r.event === `kill:${boss1}`);
  check('보스: 두 기여자 모두 1건씩', ba.length === 1 && bb.length === 1, `a=${ba.length} b=${bb.length}`);

  const boss2 = await bossPrepared('bot_a', 300);
  await hit(b, boss2, 3);
  await hitUntilDead(a, boss2);
  await sleep(2500);
  ba = (await ledger('bot_a')).filter((r) => r.event === `kill:${boss2}`);
  bb = (await ledger('bot_b')).filter((r) => r.event === `kill:${boss2}`);
  check('보스: 5% 미만 기여자는 보상 없음', ba.length === 1 && bb.length === 0, `a=${ba.length} b=${bb.length}`);

  // 10. 재접속 후 상태 유지
  const beforeQuit = await profile('bot_a');
  await quit(a);
  await sleep(1500);
  const offline = await profile('bot_a');
  a = await connectBot('bot_a', PORT);
  const afterJoin = await profile('bot_a');
  check('재접속: 레벨·EXP·배분이 DB와 메모리에서 같음',
    offline.level === beforeQuit.level && offline.exp === beforeQuit.exp && JSON.stringify(afterJoin.alloc) === JSON.stringify(beforeQuit.alloc)
    && afterJoin.level === beforeQuit.level, `before=${JSON.stringify(beforeQuit)} db=${JSON.stringify(offline)}`);

  // 10-1. 사이드바 표시
  const sbObj = Object.values(a.scoreboards || {}).find((x) => x.name === 'minecraftrpg');
  const sbLines = Object.entries(a.rawScores).filter(([k]) => k.startsWith('minecraftrpg/')).map(([, p]) => JSON.stringify(p));
  check('사이드바 스코어보드 표시(레벨·HP 줄)', !!sbObj && sbLines.some((l) => l.includes('Lv ')) && sbLines.some((l) => l.includes('HP ')),
    `objective=${!!sbObj} lines=${sbLines.length}`);

  // 10-2. 해골 궁수의 실제 화살 피해(AI 켜짐, bot_b 장비 없음 → DEF 0, ATK 10)
  await placeBots(a, b);
  server.cmd('effect clear bot_b');
  server.cmd('tp bot_b 20.5 -60 0.5 90 0');
  await sleep(3000);
  const bHp0 = (await profile('bot_b')).hp;
  const archerLine = await server.query('rpgadmin spawn bone_archer bot_b', /소환: .* ([0-9a-f-]{36})/);
  const archer = archerLine.match(/([0-9a-f-]{36})/)[1];
  server.cmd(`tp ${archer} 26.5 -60 0.5`);
  let arrowDelta = 0;
  for (let i = 0; i < 60 && arrowDelta === 0; i++) {
    await sleep(250);
    const h = (await profile('bot_b')).hp;
    if (h < bHp0 - 0.01) arrowDelta = bHp0 - h;
  }
  server.cmd(`kill ${archer}`);
  check('해골 궁수 화살: RPG 공격으로 ATK 10 피해', Math.abs(arrowDelta - 10) < 0.6, `maxHp=${bHp0} delta=${arrowDelta.toFixed(2)}`);
  await placeBots(a, b);

  // 10-3. 완료 커밋 실패 중 잠금·재시도·알림은 커밋 후 한 번
  {
    for (const it of a.inventory.items().filter((x) => x.name === 'honey_bottle')) { try { await a.tossStack(it); } catch {} }
    await sleep(800);
    const p0 = await profile('bot_a');
    const tonic0 = countItem(a, 'honey_bottle');
    server.cmd('rpgadmin dbfail complete 2');
    const from4 = a.chatLog.length;
    server.cmd('rpgadmin testgrant bot_a 40 shield_tonic');
    await sleep(1500);
    const tonicLocked = countItem(a, 'honey_bottle');
    const earlyMsg = a.chatLog.slice(from4).some((m) => /보상 수령|처치 보상/.test(m));
    const it = a.inventory.items().find((x) => x.name === 'honey_bottle');
    if (it) { try { await a.tossStack(it); } catch {} }
    await sleep(1000);
    check('완료 커밋 전: 아이템은 들어왔지만 성공 알림 없음', tonicLocked === tonic0 + 1 && !earlyMsg, `tonic ${tonic0}→${tonicLocked}, early=${earlyMsg}`);
    check('완료 커밋 전: 잠긴 아이템은 버릴 수 없음', countItem(a, 'honey_bottle') === tonic0 + 1, `count=${countItem(a, 'honey_bottle')}`);
    a.chat('/rpg alloc 민첩 1'); // 일반 저장은 완료 커밋까지 미뤄짐
    await waitChat(a, /보상 수령/, 30_000, from4).catch(() => null);
    await sleep(1000);
    const msgs = a.chatLog.slice(from4).filter((m) => /보상 수령/.test(m)).length;
    const p1 = await profile('bot_a');
    const exp = addExp(p0.level, p0.exp, 40);
    const rows = (await ledger('bot_a')).filter((r) => r.event.startsWith('test:'));
    check('완료 재시도 후 CLAIMED, 알림 1번, EXP 40 한 번', msgs === 1 && rows[0]?.status === 'CLAIMED'
      && p1.level === exp.level && p1.exp === exp.exp, `msgs=${msgs} rows=${JSON.stringify(rows.slice(0, 1))} ${p0.level}/${p0.exp}→${p1.level}/${p1.exp}`);
    const it2 = a.inventory.items().find((x) => x.name === 'honey_bottle');
    if (it2) { try { await a.toss(it2.type, null, 1); } catch {} }
    await sleep(1000);
    check('완료 커밋 후 잠금 해제(버릴 수 있음)', countItem(a, 'honey_bottle') === tonic0, `count=${countItem(a, 'honey_bottle')}`);
    await quit(a);
    await sleep(1500);
    const dbA = await profile('bot_a');
    check('미뤄 둔 저장 포함 DB 반영(배분+EXP)', dbA.alloc['민첩'] === (p0.alloc['민첩'] || 0) + 1 && dbA.level === exp.level && dbA.exp === exp.exp, JSON.stringify(dbA));
    a = await connectBot('bot_a', PORT, { respawn: false });
  }

  // 10-4. 일반 저장 실패 후 재저장
  {
    const p0 = await profile('bot_a');
    server.cmd('rpgadmin dbfail save 2');
    for (let i = 0; i < 3; i++) { a.chat('/rpg alloc 정신 1'); await sleep(700); }
    await sleep(1000);
    await quit(a);
    await sleep(1500);
    const dbA = await profile('bot_a');
    check('저장 2번 실패 후 다음 저장에서 전부 반영', dbA.alloc['정신'] === (p0.alloc['정신'] || 0) + 3, `정신 ${p0.alloc['정신']}→${dbA.alloc['정신']}`);
    const stale = server.all.filter((l) => /stale profile version/.test(l));
    check('버전 충돌 없이 수렴', stale.length === 0, stale.slice(0, 2).join(' | '));
    a = await connectBot('bot_a', PORT, { respawn: false });
  }

  // 10-5. 완료 커밋 전 강제 종료 + 일부 아이템만 남은 복구
  {
    await holdItem(a, 'iron_sword');
    const p0 = await profile('bot_a');
    const blade0 = countItem(a, 'iron_sword');
    const tonic0 = countItem(a, 'honey_bottle');
    server.cmd('rpgadmin dbfail complete 1000');
    server.cmd('rpgadmin testgrant bot_a 60 shield_tonic ghoul_blade');
    await sleep(2000);
    check('지급됨(완료 미확정)', countItem(a, 'iron_sword') === blade0 + 1 && countItem(a, 'honey_bottle') === tonic0 + 1);
    server.cmd('clear bot_a minecraft:honey_bottle 1'); // 관리자 삭제로 일부만 남은 상태를 만든다
    server.cmd('save-all');
    await server.waitFor(/Saved the game/, 15_000);
    server.proc.kill('SIGKILL');
    await server.exit;
    await quit(a); await quit(b);
    await server.start();
    a = await connectBot('bot_a', PORT, { respawn: false });
    b = await connectBot('bot_b', PORT);
    await sleep(3000);
    const rows = (await ledger('bot_a')).filter((r) => r.event.startsWith('test:') && r.exp === 60);
    const p1 = await profile('bot_a');
    const exp = addExp(p0.level, p0.exp, 60);
    check('[일부 남음] 복구 후 CLAIMED, EXP 60 한 번', rows.length === 1 && rows[0].status === 'CLAIMED' && p1.level === exp.level && p1.exp === exp.exp,
      `${JSON.stringify(rows)} ${p0.level}/${p0.exp}→${p1.level}/${p1.exp}`);
    check('[일부 남음] 없는 아이템을 다시 주지 않음', countItem(a, 'iron_sword') === blade0 + 1 && countItem(a, 'honey_bottle') === tonic0,
      `blade ${blade0}→${countItem(a, 'iron_sword')} tonic ${tonic0}→${countItem(a, 'honey_bottle')}`);
    check('[일부 남음] 관리자 확인용 경고 로그', server.all.some((l) => /only 1\/2 delivered items found/.test(l)));
  }

  // 10-6. 보상 A 완료 실패 중 보상 B: 플레이어별 직렬화로 B의 EXP가 A의 트랜잭션에 섞이지 않는다
  {
    const p0 = await profile('bot_a');
    const blade0 = countItem(a, 'iron_sword');
    const tonic0 = countItem(a, 'honey_bottle');
    server.cmd('rpgadmin dbfail complete 1');
    server.cmd('rpgadmin testgrant bot_a 10 shield_tonic');   // A: 완료 1회 실패 → 5초 뒤 재시도 예정
    await sleep(1200);
    const fromB = a.chatLog.length;
    server.cmd('rpgadmin testgrant bot_a 20 ghoul_blade');    // B: A가 끝날 때까지 대기해야 함
    await sleep(2000);
    const rowsMid = (await ledger('bot_a')).filter((r) => r.event.startsWith('test:') && (r.exp === 10 || r.exp === 20));
    const aMid = rowsMid.find((r) => r.exp === 10);
    const bMid = rowsMid.find((r) => r.exp === 20);
    check('A 재시도 전: A는 CLAIMING, B는 PENDING(지급·확정 안 됨)', aMid?.status === 'CLAIMING' && bMid?.status === 'PENDING'
      && countItem(a, 'iron_sword') === blade0 && !a.chatLog.slice(fromB).some((m) => /보상/.test(m)), JSON.stringify(rowsMid));
    server.cmd('save-all');
    await server.waitFor(/Saved the game/, 15_000);
    server.proc.kill('SIGKILL'); // A 재시도 전에 강제 종료
    await server.exit;
    await quit(a); await quit(b);
    await server.start();
    a = await connectBot('bot_a', PORT, { respawn: false });
    b = await connectBot('bot_b', PORT);
    await sleep(3000);
    const fromC = a.chatLog.length;
    a.chat('/rpg claim'); // B(PENDING) 수령
    await waitChat(a, /보상 수령: \+20 EXP/, 15_000, fromC).catch(() => null);
    await sleep(1500);
    const rows = (await ledger('bot_a')).filter((r) => r.event.startsWith('test:') && (r.exp === 10 || r.exp === 20));
    const p1 = await profile('bot_a');
    const exp = addExp(p0.level, p0.exp, 30);
    check('[겹친 보상] 재시작 후 A·B 모두 CLAIMED 1건씩', rows.length === 2 && rows.every((r) => r.status === 'CLAIMED'), JSON.stringify(rows));
    check('[겹친 보상] EXP는 10+20=30 정확히 한 번', p1.level === exp.level && p1.exp === exp.exp, `${p0.level}/${p0.exp}→${p1.level}/${p1.exp}, 기대 ${exp.level}/${exp.exp}`);
    check('[겹친 보상] 아이템도 각각 정확히 한 번', countItem(a, 'honey_bottle') === tonic0 + 1 && countItem(a, 'iron_sword') === blade0 + 1,
      `tonic ${tonic0}→${countItem(a, 'honey_bottle')} blade ${blade0}→${countItem(a, 'iron_sword')}`);
  }

  // 11. 지급 중 강제 종료 — 플레이어 데이터 저장 후, CLAIMED 커밋 전
  for (const [mode, label] of [['claim', '저장 후'], ['claim-nosave', '저장 전']]) {
    await holdItem(a, 'iron_sword');
    await placeBots(a, null);
    const tonicBefore = countItem(a, 'honey_bottle');
    const p0 = await profile('bot_a');
    const boss = await bossPrepared('bot_a', 150);
    server.cmd(`rpgadmin crashtest ${mode}`);
    await sleep(300);
    await hitUntilDead(a, boss);
    const exit = await Promise.race([server.exit, sleep(20_000).then(() => null)]);
    check(`[${label}] 지급 도중 서버 강제 종료 발생`, exit && exit.code === 137, JSON.stringify(exit));
    await quit(a); await quit(b);
    await server.start();
    a = await connectBot('bot_a', PORT);
    b = await connectBot('bot_b', PORT);
    await sleep(2500);
    if (mode === 'claim-nosave') {
      const from2 = a.chatLog.length;
      a.chat('/rpg claim');
      await waitChat(a, /보상 수령/, 10_000, from2).catch(() => null);
      await sleep(1500);
    }
    const rows = (await ledger('bot_a')).filter((r) => r.event === `kill:${boss}`);
    const p1 = await profile('bot_a');
    const exp = addExp(p0.level, p0.exp, 600);
    const tonicAfter = countItem(a, 'honey_bottle');
    check(`[${label}] 재시작 후 보상 1건 CLAIMED`, rows.length === 1 && rows[0].status === 'CLAIMED', JSON.stringify(rows));
    check(`[${label}] 방어막 강장제 정확히 +1`, tonicAfter === tonicBefore + 1, `${tonicBefore} → ${tonicAfter}`);
    check(`[${label}] 보스 EXP 600 정확히 한 번`, p1.level === exp.level && p1.exp === exp.exp, `${p0.level}/${p0.exp} → ${p1.level}/${p1.exp}, 기대 ${exp.level}/${exp.exp}`);
    const from3 = a.chatLog.length;
    a.chat('/rpg claim');
    const empty = await waitChat(a, /보상함이 비어/, 5000, from3).catch(() => null);
    check(`[${label}] 다시 수령해도 중복 없음`, !!empty);
  }

  // 12. 백업 → 변경 → 복원
  const backupLine = await server.query('rpgadmin backup', /백업 완료: (.*\.db)/, 15_000);
  const backupPath = path.resolve(DIR, backupLine.match(/백업 완료: (.*\.db)/)[1]);
  const atBackup = await profile('bot_a');
  server.cmd('rpgadmin level bot_a 10');
  await sleep(500);
  a.chat('/rpg alloc 행운 3');
  await sleep(800);
  const changed = await profile('bot_a');
  await quit(a); await quit(b);
  await server.stop();
  execFileSync(path.join(ROOT, 'scripts/restore-db.sh'), [backupPath, DIR], { stdio: 'inherit' });
  await server.start();
  const restored = await profile('bot_a');
  check('백업 복원: 백업 시점 성장 데이터와 일치', changed.level === 10 && restored.level === atBackup.level && restored.exp === atBackup.exp
    && JSON.stringify(restored.alloc) === JSON.stringify(atBackup.alloc), `backup=${atBackup.level}/${JSON.stringify(atBackup.alloc)} restored=${restored.level}/${JSON.stringify(restored.alloc)}`);

  await server.stop();
  const severe = server.all.filter((l) => /ERROR|SEVERE/.test(l) && /MinecraftRPG|io\.github\.qpfr123/.test(l) && !/crash-test|injected failure|DB task failed: (save|complete)/.test(l));
  check('플러그인 ERROR/SEVERE 로그 없음(강제 종료 테스트 로그 제외)', severe.length === 0, severe.slice(0, 3).join(' | '));
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

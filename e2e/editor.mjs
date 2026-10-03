// 던전 맵 편집기 E2E. 사용: scripts/e2e.sh editor
import fs from 'node:fs';
import path from 'node:path';
import { Server, connectBot as connect, quit, sleep, waitChat } from './lib.mjs';

async function connectBot(name, port) {
  const bot = await connect(name, port);
  bot.physicsEnabled = false; // 월드 간 텔레포트 직후 청크 없이 떨어지지 않게(dungeon.mjs 참고)
  return bot;
}

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const DIR = path.join(ROOT, 'run-e2e');
const PORT = 25599;
const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
const JAR = fs.readdirSync(DIR).find((f) => /^paper-.*\.jar$/.test(f));
const server = new Server({ dir: DIR, jar: JAR, java: JAVA, port: PORT });
const DUNGEONS = path.join(DIR, 'plugins/MinecraftRPG/dungeons');
const EDIT = 'rpg_edit_arena';

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

const near = (bot, x, y, z, tol = 1.5) => {
  const p = bot.entity.position;
  return Math.abs(p.x - x) < tol && Math.abs(p.y - y) < tol + 1 && Math.abs(p.z - z) < tol;
};
const pos = (bot) => { const p = bot.entity.position; return `${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}`; };
const exists = (name) => fs.existsSync(path.join(DIR, name));
const marker = (name) => { try { return fs.readFileSync(path.join(DIR, name, 'rpg_layout_version.txt'), 'utf8').trim(); } catch { return null; } };

// 플레이어에게서 먼(시야 밖) 청크의 몹은 언로드돼 @e에 안 잡힌다. 세기 전에 던전 전체를 강제 로드한다.
const loaded = new Set();
async function loadAll(world) {
  if (loaded.has(world)) return;
  loaded.add(world);
  server.cmd(`execute in minecraft:${world} run forceload add -32 -16 32 112`);
  await sleep(1500);
}
async function count(world, type) {
  await loadAll(world);
  const line = await server.query(`execute in minecraft:${world} positioned 0 64 30 if entity @e[type=${type},distance=..200]`, /Test (passed|failed)/);
  const m = line.match(/[Cc]ount: (\d+)/);
  return m ? +m[1] : 0;
}
async function hasBlock(world, x, y, z, block) {
  const line = await server.query(`execute in minecraft:${world} if block ${x} ${y} ${z} minecraft:${block}`, /Test (passed|failed)/);
  return /passed/.test(line);
}
async function instanceOf(name) {
  const lines = await server.collect('rpgadmin dungeon list', 1000);
  const m = lines.map((l) => l.match(/\[dungeon\] instance (\w+) world=(\S+) dungeon=(\S+) state=(\S+) members=\[(.*)\]/)).filter(Boolean)
    .find((x) => x[5].split(', ').includes(name));
  return m ? { id: m[1], world: m[2], dungeon: m[3] } : null;
}

async function ledger(name) {
  const lines = await server.collect(`rpgadmin ledger ${name}`, 1200);
  return lines.map((l) => l.match(/(PENDING|CLAIMING|CLAIMED) ((?:kill|test|dungeon):\S+) EXP (\d+) \[(.*)\]/)).filter(Boolean)
    .map((m) => ({ status: m[1], event: m[2], exp: +m[3] }));
}

async function hitUntilDead(bot, pred, max = 150) {
  for (let i = 0; i < max; i++) {
    const e = Object.values(bot.entities).find(pred);
    if (!e || !e.isValid) return true;
    await bot.lookAt(e.position.offset(0, e.height * 0.6, 0), true);
    bot.attack(e);
    await sleep(700);
  }
  return false;
}

/** 봇 명령 → 응답 대기. */
async function say(bot, text, re, timeout = 10_000) {
  const from = bot.chatLog.length;
  bot.chat(text);
  return waitChat(bot, re, timeout, from).catch(() => null);
}
const tpIn = (world, who, x, y, z) => server.cmd(`execute in minecraft:${world} run tp ${who} ${x} ${y} ${z} 0 0`);

async function enter(bot, id) {
  const r = await say(bot, `/dungeon enter ${id}`, /입장했습니다|입장할 수 없습니다|알 수 없는|준비되지/, 30_000);
  await sleep(1500);
  return r;
}
async function leave(bot) {
  await say(bot, '/dungeon leave', /던전에서 나왔습니다/);
  await sleep(1500);
}

async function main() {
  await server.start();
  for (const c of ['gamerule doMobSpawning false', 'gamerule spawn_mobs false', 'gamerule doDaylightCycle false', 'gamerule advance_time false',
    'time set 6000', 'op bot_a', 'rpgadmin dungeon cap 4', 'rpgadmin dungeon idle 60', 'kill @e[type=!player]']) server.cmd(c);
  await sleep(800);
  let a = await connectBot('bot_a', PORT);
  let b = await connectBot('bot_b', PORT);
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  server.cmd('tp bot_b 5.5 -60 0.5 0 0');
  await sleep(1500);

  // 1. 기본 던전 v2가 YAML과 생성기 템플릿으로 준비됨
  check('기본 던전 crypt.yml 작성·템플릿 생성(v2)', fs.existsSync(path.join(DUNGEONS, 'crypt.yml')) && marker('rpg_tpl_crypt') === 'generated:2', marker('rpg_tpl_crypt'));

  // 2. 새 던전 만들기
  let r = await say(a, '/rpgadmin dungeonedit create arena 시험 투기장', /편집을 시작했습니다|이미 있는|ID는/);
  await sleep(1500);
  check('create: 편집 월드로 이동(빈 발판)', !!r && /시작했습니다/.test(r) && exists(EDIT) && near(a, 0.5, 64, 0.5) && await hasBlock(EDIT, 0, 63, 0, 'stone_bricks'),
    `${r} pos=${pos(a)}`);
  r = await say(a, '/rpgadmin dungeonedit create arena 중복', /이미 있는|던전이나 다른 편집 월드 밖/);
  check('create: 편집 중인 ID 중복 거절', !!r);

  // 맵 짓기(관리자가 손으로 짓는 것 대신 fill)
  server.cmd(`execute in minecraft:${EDIT} run fill -6 63 -6 6 63 20 minecraft:polished_andesite`);
  server.cmd(`execute in minecraft:${EDIT} run setblock 0 64 19 minecraft:gold_block`);
  await sleep(500);
  r = await say(a, '/rpgadmin dungeonedit save', /저장할 수 없습니다/);
  await sleep(500);
  check('save: 입구·보스 없으면 거절', !!r && a.chatLog.slice(-4).some((m) => /보스/.test(m)), a.chatLog.slice(-3).join(' | '));

  tpIn(EDIT, 'bot_a', 0.5, 64, -4.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit setentrance', /입구 위치/);
  tpIn(EDIT, 'bot_a', 3.5, 64, 8.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit addspawn ghoul', /스폰 추가/);
  tpIn(EDIT, 'bot_a', -3.5, 64, 8.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit addspawn ghoul', /스폰 추가/);
  tpIn(EDIT, 'bot_a', 0.5, 64, 12.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit addspawn bone_archer', /스폰 추가/);
  r = await say(a, '/rpgadmin dungeonedit setboss ghoul', /보스 몹:|보스 .* 위치/);
  check('setboss: 보스가 아닌 몹 거절', !!r && /보스 몹:/.test(r), r);
  tpIn(EDIT, 'bot_a', -3.0, 64, 9.0); await sleep(600);
  r = await say(a, '/rpgadmin dungeonedit removespawn', /지웠습니다|없습니다/);
  check('removespawn: 가까운 스폰 1개 삭제', !!r && /2개 남음/.test(r), r);
  tpIn(EDIT, 'bot_a', 0.5, 64, 16.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit setboss crypt_warden', /보스 .* 위치/);
  await say(a, '/rpgadmin dungeonedit maxplayers 2', /최대 인원: 2/);
  await sleep(500);
  const markers = await count(EDIT, 'text_display');
  check('편집 표시: 입구1 + 스폰2 + 보스1', markers === 4, `text_display=${markers}`);

  r = await say(a, '/rpgadmin dungeonedit save', /저장했습니다|저장할 수 없습니다|오류/, 20_000);
  await sleep(1500);
  const yml = fs.existsSync(path.join(DUNGEONS, 'arena.yml')) ? fs.readFileSync(path.join(DUNGEONS, 'arena.yml'), 'utf8') : '';
  check('save: YAML·템플릿 저장, 편집 사본 제거, 원래 위치로 귀환',
    !!r && /저장했습니다/.test(r) && /name: 시험 투기장/.test(yml) && /max-players: 2/.test(yml) && exists('rpg_tpl_arena') && marker('rpg_tpl_arena') === 'edited'
      && !exists(EDIT) && near(a, 0.5, -60, 0.5), `${r} pos=${pos(a)}`);

  // 3. 새 던전 입장: 지은 블록·몹 배치, 편집 표시는 복사되지 않음
  r = await enter(a, 'arena');
  let inst = await instanceOf('bot_a');
  check('arena 입장: 지정한 입구', !!r && /입장했습니다/.test(r) && inst?.dungeon === 'arena' && near(a, 0.5, 64, -4.5), `${r} pos=${pos(a)}`);
  const blocks = inst && await hasBlock(inst.world, 0, 64, 19, 'gold_block') && await hasBlock(inst.world, 5, 63, 15, 'polished_andesite');
  const z = inst ? await count(inst.world, 'zombie') : -1;
  const s = inst ? await count(inst.world, 'skeleton') : -1;
  const w = inst ? await count(inst.world, 'wither_skeleton') : -1;
  const td = inst ? await count(inst.world, 'text_display') : -1;
  check('arena 인스턴스: 지은 블록 그대로', blocks);
  check('arena 인스턴스: 구울 1, 해골 궁수 1, 보스 1, 편집 표시 0', z === 1 && s === 1 && w === 1 && td === 0, `z=${z} s=${s} w=${w} td=${td}`);
  await leave(a);

  // 4. 편집 중에도 기존 맵으로 입장, 저장 후에는 새 맵
  r = await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
  await sleep(1500);
  check('edit: 저장된 입구로 편집 사본 진입', !!r && exists(EDIT) && near(a, 0.5, 64, -4.5), pos(a));
  server.cmd(`execute in minecraft:${EDIT} run setblock 2 64 19 minecraft:diamond_block`);
  await sleep(500);
  r = await enter(b, 'arena');
  inst = await instanceOf('bot_b');
  const oldMap = inst && !(await hasBlock(inst.world, 2, 64, 19, 'diamond_block')) && await hasBlock(inst.world, 0, 64, 19, 'gold_block');
  check('편집 중 입장: 저장 전 맵 사용', !!r && /입장했습니다/.test(r) && oldMap, r);
  r = await say(a, '/rpgadmin dungeonedit save', /저장했습니다|저장할 수 없습니다|준비하는 중/, 20_000);
  await sleep(1500);
  check('edit 저장(다른 인스턴스 진행 중에도)', !!r && /저장했습니다/.test(r), r);
  const stillOld = inst && !(await hasBlock(inst.world, 2, 64, 19, 'diamond_block'));
  check('진행 중 인스턴스는 저장 영향 없음', stillOld);
  await leave(b);
  await enter(b, 'arena');
  inst = await instanceOf('bot_b');
  check('저장 후 새 입장: 고친 맵', inst && await hasBlock(inst.world, 2, 64, 19, 'diamond_block'));
  await leave(b);

  // 5. 편집 취소: 바뀐 블록·설정 버림
  await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
  await sleep(1500);
  server.cmd(`execute in minecraft:${EDIT} run setblock -2 64 19 minecraft:emerald_block`);
  await say(a, '/rpgadmin dungeonedit name 바뀐 이름', /이름:/);
  r = await say(a, '/rpgadmin dungeonedit cancel', /취소했습니다/);
  await sleep(1500);
  check('cancel: 편집 사본 삭제·귀환', !!r && !exists(EDIT) && near(a, 0.5, -60, 0.5), pos(a));
  await enter(b, 'arena');
  inst = await instanceOf('bot_b');
  check('cancel 후: 블록·이름 변경 없음', inst && !(await hasBlock(inst.world, -2, 64, 19, 'emerald_block')) && /시험 투기장/.test(fs.readFileSync(path.join(DUNGEONS, 'arena.yml'), 'utf8'))
    && !/바뀐 이름/.test(fs.readFileSync(path.join(DUNGEONS, 'arena.yml'), 'utf8')));
  await leave(b);

  // 6. 검증: 보스 없는 새 던전은 저장 불가, 정원 초과 거절
  await say(a, '/rpgadmin dungeonedit create broken 미완성', /편집을 시작했습니다/);
  await sleep(1500);
  await say(a, '/rpgadmin dungeonedit setentrance', /입구 위치/);
  r = await say(a, '/rpgadmin dungeonedit save', /저장할 수 없습니다|저장했습니다/);
  await sleep(500);
  check('보스 없는 던전 저장 거절', !!r && /저장할 수 없습니다/.test(r) && !fs.existsSync(path.join(DUNGEONS, 'broken.yml')));
  await say(a, '/rpgadmin dungeonedit cancel', /취소했습니다/);
  await sleep(1000);
  r = await enter(a, 'broken');
  check('저장 안 된 던전은 입장 불가', !!r && /알 수 없는/.test(r), r);

  // 7. 기본 던전을 게임 안에서 고치면 생성기에서 분리돼 재시작해도 덮어쓰지 않음
  await say(a, '/rpgadmin dungeonedit edit crypt', /편집을 시작했습니다/);
  await sleep(1500);
  server.cmd(`execute in minecraft:rpg_edit_crypt run setblock 0 64 0 minecraft:beacon`);
  await sleep(300);
  r = await say(a, '/rpgadmin dungeonedit save', /저장했습니다|저장할 수 없습니다/, 20_000);
  await sleep(1000);
  const cryptYml = fs.readFileSync(path.join(DUNGEONS, 'crypt.yml'), 'utf8');
  check('crypt 편집 저장: 생성기 분리(edited)', !!r && /저장했습니다/.test(r) && marker('rpg_tpl_crypt') === 'edited' && !/generator: crypt/.test(cryptYml), r);

  // 8. 편집 중 서버 종료 → 편집 버림, 재시작 후 저장된 던전 유지
  await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
  await sleep(1500);
  await quit(a); await quit(b);
  await server.stop();
  check('종료 시 저장 안 한 편집 사본 정리', !exists(EDIT) && server.lines.some((l) => /discarding unsaved dungeon edit arena/.test(l)));
  await server.start();
  check('재시작: crypt 템플릿 다시 생성하지 않음', !server.lines.some((l) => /building dungeon template rpg_tpl_crypt/.test(l)));
  a = await connectBot('bot_a', PORT);
  await sleep(2500);
  r = await enter(a, 'arena');
  inst = await instanceOf('bot_a');
  check('재시작 후 arena 입장·맵 유지', !!r && /입장했습니다/.test(r) && inst && await hasBlock(inst.world, 2, 64, 19, 'diamond_block') && await count(inst.world, 'zombie') === 1, r);
  await leave(a);
  r = await enter(a, 'crypt');
  inst = await instanceOf('bot_a');
  check('재시작 후 crypt: 고친 블록 유지', inst && await hasBlock(inst.world, 0, 64, 0, 'beacon') && await count(inst.world, 'wither_skeleton') === 1, r);
  await leave(a);

  // 9. 예전 형식 표시(숫자 버전)의 생성기 템플릿은 새 버전으로 다시 생성
  await quit(a);
  await server.stop();
  fs.rmSync(path.join(DUNGEONS, 'crypt.yml'));
  fs.writeFileSync(path.join(DIR, 'rpg_tpl_crypt', 'rpg_layout_version.txt'), '1');
  await server.start();
  check('예전 버전 템플릿은 v2로 재생성', server.lines.some((l) => /building dungeon template rpg_tpl_crypt \(crypt v2\)/.test(l)) && marker('rpg_tpl_crypt') === 'generated:2');

  // 10. 진행 중 인스턴스는 입장 당시 정의를 따른다(입구·보스 변경 후에도 리스폰·클리어)
  a = await connectBot('bot_a', PORT);
  b = await connectBot('bot_b', PORT);
  await sleep(1500);
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  server.cmd('tp bot_b 5.5 -60 0.5 0 0');
  await sleep(1000);
  await enter(b, 'arena');
  const pinnedInst = await instanceOf('bot_b');
  await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
  await sleep(1500);
  tpIn(EDIT, 'bot_a', 0.5, 64, 4.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit setentrance', /입구 위치/);
  tpIn(EDIT, 'bot_a', 3.5, 64, 16.5); await sleep(600);
  await say(a, '/rpgadmin dungeonedit setboss grave_knight', /보스 .* 위치/);
  r = await say(a, '/rpgadmin dungeonedit save', /저장했습니다|저장할 수 없습니다/, 20_000);
  await sleep(1000);
  check('[정의 고정] 입구·보스 변경 저장', !!r && /저장했습니다/.test(r) && /grave_knight/.test(fs.readFileSync(path.join(DUNGEONS, 'arena.yml'), 'utf8')), r);
  const deathsBefore = b.deaths;
  server.cmd('kill bot_b');
  await sleep(3000);
  check('[정의 고정] 기존 인스턴스 사망 → 입장 당시 입구에서 리스폰', b.deaths === deathsBefore + 1 && near(b, 0.5, 64, -4.5), pos(b));
  await loadAll(pinnedInst.world);
  server.cmd(`execute in minecraft:${pinnedInst.world} positioned 0 64 10 run kill @e[type=zombie,distance=..100]`);
  server.cmd(`execute in minecraft:${pinnedInst.world} positioned 0 64 10 run kill @e[type=skeleton,distance=..100]`);
  server.cmd('rpgadmin givegear bot_b ghoul_blade');
  await sleep(1000);
  const blade = b.inventory.items().find((i) => i.name === 'iron_sword');
  if (blade) await b.equip(blade, 'hand').catch(() => {});
  server.cmd(`execute in minecraft:${pinnedInst.world} positioned 0 64 10 run data merge entity @e[type=wither_skeleton,limit=1,distance=..100] {NoAI:1b}`);
  server.cmd(`execute in minecraft:${pinnedInst.world} positioned 0 64 10 run damage @e[type=wither_skeleton,limit=1,distance=..100] 18 minecraft:generic`);
  tpIn(pinnedInst.world, 'bot_b', 0.5, 64, 14.5);
  await sleep(2000);
  await hitUntilDead(b, (e) => e.name === 'wither_skeleton');
  const cleared = await waitChat(b, /클리어!/, 10_000).catch(() => null);
  await sleep(1500);
  const lb = (await ledger('bot_b')).filter((x) => x.event === `dungeon:${pinnedInst.id}:clear`);
  check('[정의 고정] 기존 인스턴스에서 이전 보스(crypt_warden) 처치 → 클리어 보상', !!cleared && lb.length === 1, JSON.stringify(lb));
  await leave(b);
  await enter(b, 'arena');
  check('[정의 고정] 새 인스턴스는 바뀐 입구 사용', near(b, 0.5, 64, 4.5), pos(b));
  await leave(b);
  await quit(b);

  // 11. 편집 중 로그아웃 → 재접속하면 편집으로 복귀, 편집이 끝난 뒤 접속하면 원래 위치·모드
  server.cmd('tp bot_a 0.5 -60 0.5 0 0');
  await sleep(1000);
  const modeBefore = a.game.gameMode;
  await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
  await sleep(1500);
  const creative = a.game.gameMode;
  await quit(a);
  await sleep(1500);
  a = await connectBot('bot_a', PORT);
  await sleep(2500);
  check('[편집자] 편집 중 재접속 → 편집 월드로 복귀', creative === 'creative' && near(a, 0.5, 64, 4.5) && a.chatLog.some((m) => /던전 편집으로 돌아왔습니다/.test(m)), `${modeBefore}→${creative} pos=${pos(a)}`);
  await quit(a);
  await sleep(1000);
  await server.stop(); // 편집자가 오프라인인 채 종료: 편집은 버려지고 기록만 남는다
  await server.start();
  a = await connectBot('bot_a', PORT);
  await sleep(3000);
  check('[편집자] 오프라인 중 편집 종료 → 다음 접속 때 원래 위치·게임 모드', near(a, 0.5, -60, 0.5) && a.game.gameMode === modeBefore,
    `pos=${pos(a)} mode=${a.game.gameMode}`);

  // 12. 편집 중 서버 강제 종료 → 재시작 후 원래 위치·모드
  await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
  await sleep(1500);
  server.cmd('save-all');
  await server.waitFor(/Saved the game/, 15_000);
  server.proc.kill('SIGKILL');
  await server.exit;
  await quit(a);
  await server.start();
  check('[편집자] 강제 종료 후 편집 사본 정리', !exists(EDIT));
  a = await connectBot('bot_a', PORT);
  await sleep(3000);
  check('[편집자] 강제 종료 후 접속 → 원래 위치·게임 모드', near(a, 0.5, -60, 0.5) && a.game.gameMode === modeBefore, `pos=${pos(a)} mode=${a.game.gameMode}`);

  // 12-1. 테스트 훅은 JVM 옵션 없이 띄운 서버에서는 거절된다
  await quit(a);
  await server.stop();
  const savedJvm = process.env.EXTRA_JVM;
  process.env.EXTRA_JVM = (savedJvm || '').replace('-Dminecraftrpg.testHooks=true', '').trim();
  await server.start();
  a = await connectBot('bot_a', PORT);
  await sleep(2000);
  r = await say(a, '/rpgadmin dungeonedit crash after-stage', /테스트 훅이 꺼져|테스트\]/);
  check('[테스트 훅] 기본 실행에서는 crash 명령 거절', !!r && /꺼져 있습니다/.test(r), r);
  await quit(a);
  await server.stop();
  process.env.EXTRA_JVM = savedJvm;
  await server.start();
  a = await connectBot('bot_a', PORT);
  await sleep(2000);

  // 13. 저장 단계 사이 강제 종료: 확정 전이면 YAML·맵 모두 이전 상태, 확정 후면 모두 새 상태
  const blocks13 = { 'after-stage': 'lapis_block', 'after-template-moved': 'redstone_block', 'after-swap': 'coal_block', 'after-commit': 'iron_block' };
  let x13 = -5;
  for (const [point, block] of Object.entries(blocks13)) {
    const committed = point === 'after-commit';
    server.cmd('tp bot_a 0.5 -60 0.5 0 0');
    await sleep(800);
    await say(a, '/rpgadmin dungeonedit edit arena', /편집을 시작했습니다/);
    await sleep(1500);
    server.cmd(`execute in minecraft:${EDIT} run setblock ${x13} 64 19 minecraft:${block}`);
    await say(a, `/rpgadmin dungeonedit name 투기장 ${point}`, /이름:/);
    await say(a, `/rpgadmin dungeonedit crash ${point}`, /테스트/);
    a.chat('/rpgadmin dungeonedit save');
    const exit = await Promise.race([server.exit, sleep(20_000).then(() => null)]);
    await quit(a);
    check(`[저장 ${point}] 저장 도중 강제 종료`, exit && exit.code === 137, JSON.stringify(exit));
    await server.start();
    const ymlNow = fs.readFileSync(path.join(DUNGEONS, 'arena.yml'), 'utf8');
    const nameNew = ymlNow.includes(`투기장 ${point}`);
    const noJournal = !fs.existsSync(path.join(DUNGEONS, 'arena.save-journal')) && !fs.existsSync(path.join(DUNGEONS, 'arena.yml.new'));
    a = await connectBot('bot_a', PORT);
    await sleep(2500);
    server.cmd('tp bot_a 0.5 -60 0.5 0 0');
    await sleep(800);
    r = await enter(a, 'arena');
    inst = await instanceOf('bot_a');
    const blockNew = inst ? await hasBlock(inst.world, x13, 64, 19, block) : null;
    const goldKept = inst ? await hasBlock(inst.world, 0, 64, 19, 'gold_block') : false;
    check(`[저장 ${point}] 재시작 후 정의·맵 ${committed ? '모두 새 상태' : '모두 이전 상태'}`,
      !!r && /입장했습니다/.test(r) && nameNew === committed && blockNew === committed && goldKept && noJournal && exists('rpg_tpl_arena'),
      `name=${nameNew} block=${blockNew} gold=${goldKept} journalClean=${noJournal}`);
    await leave(a);
    x13 += 3;
  }
  await quit(a);

  await server.stop();
  const severe = server.all.filter((l) => /ERROR|SEVERE/.test(l) && /MinecraftRPG|io\.github\.qpfr123/.test(l)
    && !/crash-test|interrupted before commit/.test(l));
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

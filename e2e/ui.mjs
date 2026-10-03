// UI(리소스팩·HUD·메뉴) E2E. 사용: scripts/e2e.sh ui
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { Server, connectBot, quit, sleep, waitChat } from './lib.mjs';

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
  return { level: +m[1], exp: +m[2], alloc, unspent: +m[8] };
}

async function ledger(name) {
  const lines = await server.collect(`rpgadmin ledger ${name}`, 1200);
  return lines.map((l) => l.match(/(PENDING|CLAIMING|CLAIMED) ((?:kill|test):\S+) EXP (\d+)/)).filter(Boolean)
    .map((m) => ({ status: m[1], event: m[2], exp: +m[3] }));
}

const slot = (col, row) => row * 9 + col;
/** NBT/JSON 안의 모든 문자열 값. */
function strings(o, out = []) {
  if (o == null) return out;
  if (typeof o === 'string') { out.push(o); return out; }
  if (Array.isArray(o)) { o.forEach((x) => strings(x, out)); return out; }
  if (typeof o === 'object') Object.values(o).forEach((x) => strings(x, out));
  return out;
}
const titleOf = (w) => (typeof w.title === 'string' ? w.title : JSON.stringify(w.title));

/** 다음 창 열림을 기다린다(action 실행 후). */
function nextWindow(bot, action, timeout = 5000) {
  return new Promise((resolve) => {
    const t = setTimeout(() => { bot.removeListener('windowOpen', on); resolve(null); }, timeout);
    const on = (w) => { clearTimeout(t); resolve(w); };
    bot.once('windowOpen', on);
    action();
  });
}

async function fetchBytes(url) {
  const res = await fetch(url);
  return { status: res.status, body: Buffer.from(await res.arrayBuffer()) };
}

async function main() {
  await server.start();
  for (const c of ['gamerule doMobSpawning false', 'gamerule spawn_mobs false', 'time set 6000', 'kill @e[type=!player]']) server.cmd(c);
  await sleep(500);
  const packLine = server.lines.find((l) => /resource pack [0-9a-f]{40} \(\d+ bytes\) served on port \d+/.test(l)) || '';
  const served = (packLine.match(/resource pack ([0-9a-f]{40})/) || [])[1];
  let a = await connectBot('ui_a', PORT);
  server.cmd('tp ui_a 0.5 -60 0.5 0 0');
  await sleep(2000);

  // 1. 리소스팩: 접속 시 요청, 주소에서 받은 파일의 SHA-1이 일치
  const req = a.packs[0];
  check('접속 시 리소스팩 요청(필수)', !!req && /^http:\/\/127\.0\.0\.1:\d+\/pack\/[0-9a-f]{40}\.zip$/.test(req.url) && req.forced === true,
    JSON.stringify(req && { url: req.url, forced: req.forced }));
  if (req) {
    const { status, body } = await fetchBytes(req.url);
    const sha = crypto.createHash('sha1').update(body).digest('hex');
    const hash = typeof req.hash === 'string' ? req.hash : Buffer.from(req.hash).toString('hex');
    check('리소스팩 내려받기: 200, SHA-1 = 요청 해시 = 서버 로그', status === 200 && sha === hash && sha === served && body.subarray(0, 2).toString() === 'PK',
      `status=${status} sha=${sha} hash=${hash} served=${served}`);
    const bad = await fetchBytes(req.url.replace(/[0-9a-f]{40}/, '0'.repeat(40)));
    check('다른 경로는 404', bad.status === 404, String(bad.status));
  }

  // 2. HUD: 액션바에 rpg:hud 글리프, 바닐라 배고픔·경험치 고정
  await sleep(1500);
  const bar = JSON.stringify(a.actionBars.at(-1) || {});
  const texts = strings(a.actionBars.at(-1));
  const glyphs = texts.join('').split('').map((c) => c.charCodeAt(0)).filter((c) => c >= 0xe100 && c <= 0xe180);
  check('HUD 액션바: HP·MP 바와 레벨 원 글리프, 숫자', /rpg:hud/.test(bar) && glyphs.length === 3 && texts.includes('100/100') && texts.includes('1'),
    `glyphs=${glyphs.map((c) => c.toString(16))} texts=${texts.filter((t) => /\d/.test(t)).join(',')}`);
  server.cmd('summon experience_orb ~ ~ ~ {Value:500}'.replace('~ ~ ~', '0.5 -59 0.5'));
  server.cmd('xp add ui_a 500 points');
  await sleep(1000);
  check('바닐라 경험치·배고픔 고정(숨긴 HUD)', a.experience.level === 0 && a.food === 20, `level=${a.experience.level} food=${a.food}`);

  // 3. 메뉴 열기·스탯 배분
  const p0 = await profile('ui_a');
  let w = await nextWindow(a, () => a.chat('/rpg'));
  check('/rpg → 메인 메뉴(배경 글리프 제목)', w && /rpg:gui/.test(titleOf(w)) && /메인 메뉴/.test(titleOf(w)) && w.slots[slot(3, 1)] != null,
    w && titleOf(w).slice(0, 160));
  w = await nextWindow(a, () => a.clickWindow(slot(3, 1), 0, 0));
  check('메인 → 스탯 화면', w && /스탯/.test(titleOf(w)) && /남은 포인트 10/.test(titleOf(w)), w && titleOf(w).slice(-60));
  w = await nextWindow(a, () => a.clickWindow(slot(2, 1), 0, 0)); // 근력 +1
  let p1 = await profile('ui_a');
  check('+ 클릭: 근력 +1, 포인트 -1, 제목 갱신', p1.alloc['근력'] === (p0.alloc['근력'] || 0) + 1 && p1.unspent === p0.unspent - 1 && w && /남은 포인트 9/.test(titleOf(w)),
    JSON.stringify(p1));
  w = await nextWindow(a, () => a.clickWindow(slot(2, 1), 0, 1)); // 쉬프트: 상한(5)까지
  p1 = await profile('ui_a');
  check('쉬프트 클릭: 한 스탯 상한까지 한 번에', p1.alloc['근력'] === 5 && p1.unspent === 5, JSON.stringify(p1));
  w = await nextWindow(a, () => a.clickWindow(slot(2, 1), 0, 0));
  p1 = await profile('ui_a');
  check('상한 초과 클릭은 반영 안 됨', p1.alloc['근력'] === 5 && p1.unspent === 5 && a.chatLog.some((m) => /50%/.test(m)), JSON.stringify(p1));
  w = await nextWindow(a, () => a.clickWindow(slot(6, 3), 0, 0)); // 정신 +1 (오른쪽 열 3번째)
  p1 = await profile('ui_a');
  check('오른쪽 열 버튼: 정신 +1', p1.alloc['정신'] === 1 && p1.unspent === 4, JSON.stringify(p1));

  // 4. 메뉴 아이템은 옮길 수 없음
  const iconBefore = w && w.slots[slot(1, 1)] && w.slots[slot(1, 1)].name;
  await a.clickWindow(slot(1, 1), 0, 0).catch(() => {});
  await sleep(800);
  const invPaper = a.inventory.items().filter((i) => i.name === 'paper').length;
  check('메뉴 아이콘은 집거나 가져갈 수 없음', iconBefore === 'paper' && invPaper === 0 && (!a.currentWindow || !a.currentWindow.selectedItem),
    `icon=${iconBefore} paperInInv=${invPaper}`);
  a.closeWindow(a.currentWindow);
  await sleep(500);

  // 5. 장비 화면: 손에 든 장비가 보임
  server.cmd('rpgadmin givegear ui_a ghoul_blade');
  await sleep(1000);
  const blade = a.inventory.items().find((i) => i.name === 'iron_sword');
  if (blade) await a.equip(blade, 'hand').catch(() => {});
  await sleep(500);
  w = await nextWindow(a, () => a.chat('/rpg'));
  w = await nextWindow(a, () => a.clickWindow(slot(0, 1), 0, 0));
  const shown = w && w.slots[slot(7, 2)];
  check('장비 화면: 주 무기 칸에 착용 장비', w && /장비/.test(titleOf(w)) && shown && shown.name === 'iron_sword', shown && shown.name);
  w = await nextWindow(a, () => a.clickWindow(slot(0, 0), 0, 0));
  check('뒤로 → 메인 메뉴', w && /메인 메뉴/.test(titleOf(w)));
  const swordsBefore = a.inventory.items().filter((i) => i.name === 'iron_sword').length;
  a.closeWindow(a.currentWindow);
  await sleep(500);
  check('장비 화면을 거쳐도 장비가 복제되지 않음', swordsBefore === 1, String(swordsBefore));

  // 6. 보상 수령 버튼
  server.cmd('rpgadmin dbfail transition 1'); // 즉시 수령 1회 실패 → 보상함에 PENDING으로 남김
  await sleep(300);
  server.cmd('rpgadmin testgrant ui_a 30 shield_tonic');
  await sleep(1500);
  w = await nextWindow(a, () => a.chat('/rpg'));
  await sleep(1500); // 대기 보상 수 표시(DB 조회) 반영
  const claimItem = w && a.currentWindow && a.currentWindow.slots[slot(6, 1)];
  const claimLore = strings(claimItem && (claimItem.components || claimItem.nbt)).join(' ');
  const from = a.chatLog.length;
  await a.clickWindow(slot(6, 1), 0, 0);
  await waitChat(a, /수령|받았/, 10_000, from).catch(() => null);
  await sleep(1500);
  const l = (await ledger('ui_a')).filter((x) => x.event.startsWith('test:'));
  check('보상 버튼: 대기 보상 수 표시 후 수령(CLAIMED)', l.length === 1 && l[0].status === 'CLAIMED' && /받을 보상 1건/.test(claimLore), `${JSON.stringify(l)} lore=${claimLore.slice(0, 160)}`);

  // 7. Shift+F(웅크리고 손 바꾸기)로 메뉴 열기
  a._client.write('player_input', { inputs: { shift: true } }); // 웅크리기(1.21.2+는 입력 패킷)
  await sleep(300);
  w = await nextWindow(a, () => a._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }));
  check('Shift+F로 메인 메뉴 열기', w && /메인 메뉴/.test(titleOf(w)));
  a._client.write('player_input', { inputs: {} });

  await quit(a);
  await server.stop();
  const severe = server.all.filter((l) => /ERROR|SEVERE/.test(l) && /MinecraftRPG|io\.github\.qpfr123/.test(l)
    && !/injected failure|DB task failed: claiming test:/.test(l));
  check('플러그인 ERROR/SEVERE 로그 없음(주입한 DB 실패 제외)', severe.length === 0, severe.slice(0, 3).join(' | '));
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

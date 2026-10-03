// E2E 공용 도구: Paper 서버 프로세스 제어와 mineflayer 봇.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import mineflayer from 'mineflayer';

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class Server {
  constructor({ dir, jar, java, port }) {
    Object.assign(this, { dir, jar, java, port });
    this.lines = [];
    this.all = []; // 재시작을 넘어 누적
    this.waiters = [];
  }

  start() {
    this.lines = [];
    this.exited = false;
    this.proc = spawn(this.java, [...(process.env.EXTRA_JVM ? process.env.EXTRA_JVM.split(" ") : []), '-Xms1G', '-Xmx2G', '-jar', this.jar, '--nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] });
    this.exit = new Promise((resolve) => this.proc.on('exit', (code, sig) => { this.exited = true; resolve({ code, sig }); }));
    const onData = (buf) => {
      for (const raw of buf.toString().split('\n')) {
        const line = raw.replace(/\x1b\[[0-9;]*m/g, '').trimEnd();
        if (!line) continue;
        this.lines.push(line);
        this.all.push(line);
        fs.appendFileSync(path.join(this.dir, 'e2e-server.log'), line + '\n');
        this.waiters = this.waiters.filter((w) => !(w.re.test(line) && (w.resolve(line), true)));
      }
    };
    this.proc.stdout.on('data', onData);
    this.proc.stderr.on('data', onData);
    return this.waitFor(/Done \(/, 180_000);
  }

  waitFor(re, timeout = 15_000, fromIndex = this.lines.length) {
    const found = this.lines.slice(fromIndex).find((l) => re.test(l));
    if (found) return Promise.resolve(found);
    return new Promise((resolve, reject) => {
      const w = { re, resolve };
      this.waiters.push(w);
      setTimeout(() => {
        this.waiters = this.waiters.filter((x) => x !== w);
        reject(new Error(`timeout waiting for ${re}`));
      }, timeout);
    });
  }

  cmd(c) {
    this.proc.stdin.write(c + '\n');
  }

  /** 콘솔 명령을 보내고, 이후 로그에서 re에 맞는 첫 줄을 돌려준다. */
  async query(c, re, timeout = 10_000) {
    const from = this.lines.length;
    this.cmd(c);
    return this.waitFor(re, timeout, from);
  }

  /** 콘솔 명령 후 settle ms 동안 나온 줄들. */
  async collect(c, settle = 1500) {
    const from = this.lines.length;
    this.cmd(c);
    await sleep(settle);
    return this.lines.slice(from);
  }

  async stop() {
    if (this.exited) return this.exit;
    this.cmd('stop');
    return this.exit;
  }
}

export async function connectBot(username, port, { respawn = true } = {}) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version: '1.21.11', respawn });
  bot.chatLog = [];
  bot.on('messagestr', (m) => bot.chatLog.push(m));
  bot.rawScores = {}; // mineflayer 4.39는 1.20.3+ 점수 패킷(action 없음)을 무시하므로 원시 패킷을 직접 기록
  bot._client.on('scoreboard_score', (p) => { bot.rawScores[`${p.scoreName}/${p.itemName}`] = p; });
  bot.deaths = 0;
  bot.on('death', () => bot.deaths++);
  await new Promise((resolve, reject) => {
    bot.once('spawn', resolve);
    bot.once('kicked', (r) => reject(new Error(`${username} kicked: ${JSON.stringify(r)}`)));
    bot.once('error', reject);
    setTimeout(() => reject(new Error(`${username} spawn timeout`)), 30_000);
  });
  bot.on('error', () => {}); // 강제 종료 테스트에서 연결 끊김은 정상
  await sleep(1500);
  return bot;
}

export function quit(bot) {
  return new Promise((resolve) => {
    if (!bot || bot._client.ended) return resolve();
    bot.once('end', resolve);
    bot.quit();
    setTimeout(resolve, 5000);
  });
}

export function waitChat(bot, re, timeout = 10_000, from = 0) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      const m = bot.chatLog.slice(from).find((x) => re.test(x));
      if (m) return resolve(m);
      if (Date.now() - start > timeout) return reject(new Error(`chat timeout ${re}; last: ${bot.chatLog.slice(-5).join(' | ')}`));
      setTimeout(tick, 100);
    };
    tick();
  });
}

// 서버의 ExperienceCurve와 같은 식
export function required(level) { return Math.round(100 * Math.pow(level, 1.5)); }
export function addExp(level, exp, gained) {
  let lv = level, cur = exp + gained;
  while (lv < 10 && cur >= required(lv)) { cur -= required(lv); lv++; }
  if (lv >= 10) cur = 0;
  return { level: lv, exp: cur };
}

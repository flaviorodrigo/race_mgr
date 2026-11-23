// End-to-end tests: the real jar, a real MongoDB, a headless Chrome, and a stand-in for the game server's HTTP API
// (fake-am2ds.py). Races come from the committed ams2_stats sample (src/test/resources/ams2_stats_sample.json).
//
//   ./mvnw package -P '!windows-dist' -DskipTests   # builds target/racemgr-*.jar
//   cd e2e && npm install && npm test
//
// Needs: Java 21, Python 3, Chrome (CHROME_PATH if it is not in the usual place) and either Docker (starts a throwaway
// MongoDB) or E2E_MONGO_URI=mongodb://host:port (a database is created inside it). Set E2E_JAR to use another jar.
import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import {after, before, test} from 'node:test';
import {fileURLToPath} from 'node:url';

import puppeteer from 'puppeteer-core';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const sleep = ms => new Promise(resolve => {
  setTimeout(resolve, ms);
});

const freePort = () => new Promise((resolve, reject) => {
  const server = net.createServer();
  server.listen(0, '127.0.0.1', () => {
    const {port} = server.address();
    server.close(() => resolve(port));
  });
  server.on('error', reject);
});

const findChrome = () => {
  const candidates = [process.env.CHROME_PATH, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  const found = candidates.find(c => c && fs.existsSync(c));
  assert.ok(found, 'Chrome not found: set CHROME_PATH');
  return found;
};

const findJar = () => {
  if (process.env.E2E_JAR) {
    return process.env.E2E_JAR;
  }

  const jar = fs.readdirSync(path.join(root, 'target')).find(f => /^racemgr-.*\.jar$/.test(f) && !f.endsWith('-sources.jar'));
  assert.ok(jar, 'No jar in target/: run ./mvnw package -P "!windows-dist" -DskipTests first');
  return path.join(root, 'target', jar);
};

const state = {processes: [], dockerId: null, tmp: '', base: '', fakePort: 0, serverDir: '', adminPassword: crypto.randomBytes(12).toString('hex'), browser: null, page: null, bad: []};

const waitFor = async (check, what, timeoutMs = 60_000) => {
  const start = Date.now();
  let last;
  while (Date.now() - start < timeoutMs) {
    try {
      const result = await check();
      if (result) {
        return result;
      }
    } catch (error) {
      last = error;
    }

    await sleep(500);
  }

  throw new Error(`Timed out waiting for ${what}${last ? `: ${last.message}` : ''}`);
};

const start = (command, args, options) => {
  const child = spawn(command, args, {stdio: ['ignore', 'pipe', 'pipe'], ...options});
  const output = [];
  child.stdout.on('data', d => output.push(String(d)));
  child.stderr.on('data', d => output.push(String(d)));
  child.output = () => output.join('');
  state.processes.push(child);
  return child;
};

before(async () => {
  state.tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'racemgr-e2e-'));

  // MongoDB
  let mongoUri = process.env.E2E_MONGO_URI;
  if (!mongoUri) {
    const port = await freePort();
    const run = spawnSync('docker', ['run', '-d', '--rm', '-p', `127.0.0.1:${port}:27017`, 'mongo:7.0'], {encoding: 'utf8'});
    assert.equal(run.status, 0, `docker run failed: ${run.stderr}`);
    state.dockerId = run.stdout.trim();
    await waitFor(() => spawnSync('docker', ['exec', state.dockerId, 'mongosh', '--quiet', '--eval', 'db.runCommand({ping:1}).ok'], {encoding: 'utf8'}).stdout.trim() === '1', 'MongoDB', 90_000);
    mongoUri = `mongodb://127.0.0.1:${port}`;
  }

  // The game server's files, as a real install lays them out
  state.serverDir = path.join(state.tmp, 'ams2');
  fs.mkdirSync(path.join(state.serverDir, 'lua_config'), {recursive: true});
  fs.copyFileSync(path.join(root, 'sample_files', 'server.cfg'), path.join(state.serverDir, 'server.cfg'));
  fs.copyFileSync(path.join(root, 'src', 'test', 'resources', 'ams2_stats_sample.json'), path.join(state.serverDir, 'lua_config', 'ams2_stats_data.json'));
  fs.writeFileSync(path.join(state.serverDir, 'blacklist.cfg'), '// sample blacklist\n\n[\n1234567890\n]\n');
  fs.writeFileSync(path.join(state.serverDir, 'whitelist.cfg'), '// sample whitelist\n[\n]\n');

  // The stand-in game server
  state.fakePort = await freePort();
  start('python3', ['-I', path.join(here, 'fake-am2ds.py'), String(state.fakePort)]);
  await waitFor(async () => (await fetch(`http://127.0.0.1:${state.fakePort}/api/session/status`)).ok, 'the stand-in game server');

  // Race Manager
  const appPort = await freePort();
  state.base = `http://127.0.0.1:${appPort}`;
  const app = start('java', ['-jar', findJar(), `--spring.mongodb.uri=${mongoUri}/racemgr_e2e_${Date.now()}`], {
    env: {
      ...process.env,
      RM_PORT: String(appPort),
      RM_JWT_SECRET: crypto.randomBytes(32).toString('hex'),
      RM_ADMIN_USER: 'admin',
      RM_ADMIN_PASSWORD: state.adminPassword,
      RM_GAMESERVER_HOST: '127.0.0.1',
      RM_GAMESERVER_PORT: String(state.fakePort),
      RM_GAMESERVER_CONTROL: 'external',
      RM_PATH_TO_DEDICATED_SERVER: state.serverDir,
      RM_STATS_SOURCE: 'ams2_stats',
      RM_STATS_TIMEZONE: 'UTC',
    },
  });
  await waitFor(async () => (await fetch(`${state.base}/api`)).ok, `Race Manager to start:\n${app.output().slice(-1500)}`, 120_000);

  state.browser = await puppeteer.launch({executablePath: findChrome(), headless: true, args: process.env.CI ? ['--no-sandbox'] : [], defaultViewport: {width: 1400, height: 1100}});
  state.page = await state.browser.newPage();
  state.page.on('console', m => {
    const text = m.text();
    if (['error', 'warning'].includes(m.type()) && !text.includes('Failed to load resource')) {
      state.bad.push(`[${m.type()}] ${text.slice(0, 240)}`);
    }
  });
  state.page.on('pageerror', e => state.bad.push(`pageerror ${e.message}`));
});

after(async () => {
  await state.browser?.close();
  for (const child of state.processes) {
    child.kill('SIGTERM');
  }

  if (state.dockerId) {
    spawnSync('docker', ['stop', state.dockerId]);
  }

  fs.rmSync(state.tmp, {recursive: true, force: true});
});

// ---------------------------------------------------------------- helpers

const go = async url => {
  await state.page.goto(state.base + url, {waitUntil: 'networkidle0'});
  await sleep(700);
};

const text = selector => state.page.$eval(selector, e => e.innerText);
const rows = selector => state.page.$$eval(`${selector} tbody tr`, r => r.map(x => x.innerText.replace(/\s+/g, ' ').trim()));

// Buttons are upper-cased by the theme, so compare case-insensitively
const clickText = (selector, label) => state.page.evaluate((s, t) => {
  const element = [...document.querySelectorAll(s)].find(b => b.innerText.trim().toLowerCase() === t.toLowerCase());
  element?.click();
  return Boolean(element);
}, selector, label);

const openSection = async title => {
  await state.page.evaluate(t => [...document.querySelectorAll('.MuiAccordionSummary-root')].find(b => b.innerText.trim() === t)?.click(), title);
  await sleep(1000);
};

const setInput = async (handle, value) => {
  await handle.click();
  await handle.evaluate(element => element.select());
  await handle.press('Backspace');
  if (value) {
    await handle.type(value);
  }
};

const labelled = async label => (await state.page.evaluateHandle(t => [...document.querySelectorAll('label')].find(x => x.innerText.startsWith(t))?.control, label)).asElement();

// Calls the API the way the web UI does: with the session cookie and the CSRF header
const api = (method, url, body) => state.page.evaluate(async (m, u, b) => {
  await fetch('/api');
  const token = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
  const response = await fetch(u, {method: m, headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': token}, body: b ? JSON.stringify(b) : undefined});
  return response.status === 204 ? {status: 204} : {httpStatus: response.status, ...await response.json()};
}, method, url, body);

const fakeCalls = async () => (await fetch(`http://127.0.0.1:${state.fakePort}/_calls`)).json();

// ---------------------------------------------------------------- tests

// A failing scenario leaves a screenshot of the page in e2e/artifacts/ (CI uploads it)
const scenario = (name, run) => test(name, async () => {
  try {
    await run();
  } catch (error) {
    fs.mkdirSync(path.join(here, 'artifacts'), {recursive: true});
    await state.page?.screenshot({path: path.join(here, 'artifacts', `${name.replace(/[^a-z0-9]+/gi, '-').slice(0, 60)}.png`), fullPage: true}).catch(() => {});
    throw error;
  }
});

scenario('visitors can look but not administer', async () => {
  await go('/live');
  assert.equal((await rows('table[aria-label="Live timing"]')).length, 4);
  assert.match((await rows('table[aria-label="Live timing"]'))[0], /^1 Ayrton S 4 /);
  assert.match(await text('body'), /Lap 4 of 10/);

  const timing = await state.page.evaluate(async () => JSON.stringify(await (await fetch('/api/v1/gameserver/timing')).json()));
  assert.doesNotMatch(timing, /refid|steam/i, 'public live timing must not carry ids');

  const statuses = await state.page.evaluate(async () => {
    const get = async url => (await fetch(url)).status;
    return {audit: await get('/api/v1/admin/audit'), log: await get('/api/v1/admin/gameserver/log'), ban: await get('/api/v1/admin/gameserver/banlist'), unknown: await get('/api/v2/anything')};
  });
  // Anything that is not a listed public route is refused
  assert.deepEqual(statuses, {audit: 401, log: 401, ban: 401, unknown: 401});
});

scenario('an admin can sign in, and a wrong password is refused', async () => {
  await go('/login');
  let inputs = await state.page.$$('input');
  await inputs[0].type('admin');
  await inputs[1].type('not-the-password');
  await clickText('button', 'login');
  await sleep(1200);
  assert.equal(await state.page.evaluate(() => document.cookie.includes('RM-SESSION=1')), false);

  await go('/login');
  inputs = await state.page.$$('input');
  await inputs[0].type('admin');
  await inputs[1].type(state.adminPassword);
  await clickText('button', 'login');
  await state.page.waitForFunction(() => document.cookie.includes('RM-SESSION=1'));
});

scenario('races are imported from the ams2_stats file once a session and rotation are active', async () => {
  const rotation = await api('POST', '/api/v1/admin/race/rotation', {name: 'E2E rotation', apply: false, config: {defaultConfig: {RaceLength: 5}, rotation: [{TrackId: 'Brands_Hatch_GP'}]}});
  assert.equal(rotation.status, 200, JSON.stringify(rotation));
  const rotationId = (await api('GET', '/api/v1/race/rotation?page=0&limit=10')).data.content[0].id;

  const session = await api('POST', '/api/v1/admin/race/session', {name: 'E2E championship', raceRotationIds: [rotationId], scoring: {type: 'default'}});
  assert.equal(session.status, 200, JSON.stringify(session));
  const sessionId = (await api('GET', '/api/v1/race/session?page=0&limit=10')).data.content[0].id;

  assert.equal((await api('PUT', '/api/v1/admin/config', {activeRaceSessionId: sessionId, activeRaceRotationId: rotationId})).status, 200);
  const races = await waitFor(async () => {
    const result = await state.page.evaluate(async id => (await (await fetch(`/api/v1/race/events?raceSessionId=${id}&page=0&limit=50`)).json()).data, sessionId);
    return result.totalElements === 2 ? result : null;
  }, 'the two races to be imported');
  assert.equal(races.totalElements, 2);
  state.raceId = races.content.find(r => r.stages.race1.drivers.some(d => d.state === 'Disqualified' || d.laps?.length)).race_id;
});

scenario('the leaderboard shows the standings', async () => {
  await go('/');
  const leaders = await rows('table[aria-label="Race Summary"]');
  assert.ok(leaders.length >= 5, `leaders: ${JSON.stringify(leaders)}`);
  assert.match(leaders[0], /Driver/);
});

scenario('a race page shows the result, sector times and lap chart', async () => {
  await go(`/race/${state.raceId}`);
  const result = await rows('table[aria-label="Race result"]');
  assert.equal(result.length, 6);
  assert.ok(result.some(r => /DSQ.* 0$/.test(r)), `a disqualified driver scores nothing: ${JSON.stringify(result)}`);
  assert.ok(result.some(r => /DNF/.test(r)));
  assert.equal((await rows('table[aria-label="Race pace sector times"]')).length, 6);
  assert.ok((await state.page.$$('svg[aria-label="Lap times by lap"] polyline')).length >= 5);
  assert.ok((await rows('table[aria-label="Qualifying pace sector times"]')).length > 0);
});

scenario('an admin can add penalties and a video link to a race, and take them off again', async () => {
  const before = await rows('table[aria-label="Race result"]');
  await go(`/race/${state.raceId}`);
  await clickText('button', 'penalties and video');
  await state.page.waitForSelector('[role="dialog"]');
  await clickText('[role="dialog"] button', 'add penalty');
  await sleep(300);
  await setInput((await state.page.$$('[role="dialog"] input[type=number]'))[0], '120');

  await setInput(await labelled('Video link'), 'https://evil.example/x');
  assert.equal(await state.page.evaluate(() => [...document.querySelectorAll('[role="dialog"] button')].find(b => b.innerText.trim().toLowerCase() === 'save').disabled), true, 'a link to another site cannot be saved');
  await setInput(await labelled('Video link'), 'https://www.youtube.com/watch?v=abc123');
  await clickText('[role="dialog"] button', 'save');
  await state.page.waitForFunction(() => !document.querySelector('[role="dialog"]'));
  await sleep(1500);

  const after = await rows('table[aria-label="Race result"]');
  assert.notDeepEqual(after, before, 'a 120 second penalty on the winner re-orders the result');
  assert.match(after.join('\n'), /\+120 s/);
  assert.equal(await state.page.$eval('a[href*="youtube"]', a => `${a.rel}|${a.target}`), 'noopener noreferrer|_blank');

  await clickText('button', 'penalties and video');
  await state.page.waitForSelector('[role="dialog"]');
  await clickText('[role="dialog"] button', 'remove');
  await setInput(await labelled('Video link'), '');
  await clickText('[role="dialog"] button', 'save');
  await state.page.waitForFunction(() => !document.querySelector('[role="dialog"]'));
  await sleep(1500);
  assert.deepEqual(await rows('table[aria-label="Race result"]'), before);
  assert.equal(await state.page.$('a[href*="youtube"]'), null);
});

scenario('the records page totals every driver and finds the track records', async () => {
  await go('/records');
  const drivers = await rows('table[aria-label="Career statistics"]');
  assert.equal(drivers.length, 8, 'every driver of the two races');
  assert.match(drivers[0], /^Driver 1 2 2 2 2 /, 'Driver 1 won both races and took both poles');
  // The lap record at each of the two tracks (the stand-in game server only names one of them)
  const records = await rows('table[aria-label="Track records"]');
  assert.equal(records.length, 2);
  assert.ok(records.some(r => /^Brands Hatch GP Driver 1 /.test(r)));
});

scenario('the ban list and whitelist edit the game server\'s own files', async () => {
  await go('/manageserver');
  await openSection('Ban list');
  await setInput(await labelled('Steam ID'), '76561198000000005');
  await setInput(await labelled('Note'), 'cut the chicane');
  await clickText('button', 'ban');
  await sleep(1200);
  assert.match(fs.readFileSync(path.join(state.serverDir, 'blacklist.cfg'), 'utf8'), /\[\n1234567890\n76561198000000005 \/\/ cut the chicane\n\]/);

  await openSection('Whitelist');
  assert.match(await text('.MuiAccordionDetails-root .MuiAlert-message'), /isn't using this list yet/);
  await setInput(await labelled('Steam ID'), '76561198000000042');
  await clickText('button', 'allow');
  await sleep(1200);
  assert.equal(fs.readFileSync(path.join(state.serverDir, 'whitelist.cfg'), 'utf8'), '// sample whitelist\n[\n76561198000000042\n]\n');
});

scenario('the game server log is shown newest first and its text is never rendered as markup', async () => {
  await go('/manageserver');
  await openSection('Game server log');
  const events = await rows('table[aria-label="Game server log"]');
  assert.equal(events.length, 4);
  assert.match(events[0], /ServerChat/);
  assert.equal(await state.page.evaluate(() => document.querySelector('.MuiAccordionDetails-root b')), null);
});

scenario('saving the server settings changes only those lines of server.cfg and keeps a backup', async () => {
  await go('/manageserver');
  const before = fs.readFileSync(path.join(state.serverDir, 'server.cfg'), 'utf8');
  await openSection('Server settings (server.cfg)');
  await setInput(await labelled('Server name'), 'Friday Night Racing');
  await clickText('button', 'save to server.cfg');
  await sleep(1500);
  const after = fs.readFileSync(path.join(state.serverDir, 'server.cfg'), 'utf8');
  const changed = after.split('\n').filter((line, i) => line !== before.split('\n')[i]);
  assert.equal(changed.length, 1, `changed lines: ${JSON.stringify(changed)}`);
  assert.match(changed[0], /Friday Night Racing/);
  assert.equal(fs.readFileSync(path.join(state.serverDir, 'server.cfg.bak'), 'utf8'), before);
});

scenario('kicking a player reaches the game server, and the action is in the activity log', async () => {
  const kick = await api('POST', '/api/v1/admin/gameserver/kick', {refid: 9, banSeconds: 600});
  assert.equal(kick.status, 200);
  assert.ok((await fakeCalls()).some(c => c.call === 'kick' && c.raw_query === 'refid=9&ban=600'));
  const audit = await api('GET', '/api/v1/admin/audit?limit=20');
  assert.ok(audit.data.some(e => e.action === 'kick and ban'));
});

scenario('a preset saves the next-session settings and applies them to the game server in one click', async () => {
  await go('/manageserver');
  await openSection('Next session settings');
  await (await state.page.$('[role="combobox"][aria-labelledby~="next-track"]')).click();
  await state.page.waitForSelector('[role="listbox"]');
  await state.page.evaluate(() => [...document.querySelectorAll('[role="option"]')].find(o => o.innerText.includes('Brands Hatch GP'))?.click());
  await sleep(300);
  await setInput(await labelled('Race laps'), '10');
  await setInput(await labelled('Qualifying minutes'), '15');
  await setInput(await labelled('Save these settings as a preset'), 'Sprint');
  await clickText('button', 'save preset');
  await sleep(1500);
  const saved = await rows('table[aria-label="Presets"]');
  assert.equal(saved.length, 1);
  assert.match(saved[0], /^Sprint Brands Hatch GP · 10 laps · qualifying 15 min/);

  await clickText('table[aria-label="Presets"] button', 'apply');
  await sleep(1200);
  const call = (await fakeCalls()).filter(c => c.call === 'set_next').pop();
  assert.ok(call, 'the game server was told');
  assert.match(call.raw_query, /session_TrackId=1534602052/);
  assert.match(call.raw_query, /session_RaceLength=10/);
  assert.match(call.raw_query, /session_QualifyLength=15/);

  await clickText('table[aria-label="Presets"] button', 'delete');
  await state.page.waitForSelector('[role="dialog"]');
  await clickText('[role="dialog"] button', 'delete');
  await sleep(1200);
  assert.equal(await state.page.$('table[aria-label="Presets"]'), null);
});

const signIn = async (user, password) => {
  await go('/login');
  const inputs = await state.page.$$('input');
  await inputs[0].type(user);
  await inputs[1].type(password);
  await clickText('button', 'login');
  await state.page.waitForFunction(() => document.cookie.includes('RM-SESSION=1'));
};

const signOut = async () => {
  await state.page.click('button[aria-label="Logout"]');
  await state.page.waitForFunction(() => !document.cookie.includes('RM-SESSION=1'));
};

const status = url => state.page.evaluate(async u => (await fetch(u)).status, url);

scenario('the owner gives a friend a steward sign-in that only reaches race day, and can take it away again', async () => {
  await go('/manageserver');
  await openSection('Admins');
  assert.deepEqual(await rows('table[aria-label="Admins"]'), ['admin Owner']);
  await setInput(await labelled('User name'), 'Steve');
  await clickText('button', 'generate');
  const password = await state.page.evaluate(() => [...document.querySelectorAll('input')].find(i => i.closest('.MuiFormControl-root')?.querySelector('label')?.innerText.startsWith('Password'))?.value);
  assert.match(password, /^[A-Za-z0-9]{16}$/);
  await clickText('button', 'add');
  await sleep(1500);
  const notices = await state.page.$$eval('.MuiAccordionDetails-root .MuiAlert-message', a => a.map(x => x.innerText).join('\n'));
  assert.match(notices, /Added steve/);
  assert.ok(notices.includes(password), 'the password is shown once, for the owner to hand over');
  assert.equal((await rows('table[aria-label="Admins"]')).length, 2);

  // Steve signs in with the password he was given
  await signOut();
  await signIn('steve', password);
  await go('/manageserver');
  const sections = await state.page.$$eval('.MuiAccordionSummary-root', e => e.map(x => x.innerText.trim()));
  assert.deepEqual(sections, ['Next session settings', 'Game server log', 'Account']);
  assert.match(await text('body'), /signed in as a steward/);

  // The server says no to everything beyond race day, and yes to race day
  assert.deepEqual(await Promise.all(['/api/v1/admin/gameserver/banlist', '/api/v1/admin/gameserver/config', '/api/v1/admin/users', '/api/v1/admin/audit'].map(status)), [403, 403, 403, 403]);
  assert.equal(await status('/api/v1/admin/gameserver/log'), 200);
  assert.equal(await status('/api/v1/admin/gameserver/session'), 200);
  assert.equal((await api('POST', '/api/v1/admin/gameserver/chat', {message: 'Race starts in five minutes'})).status, 200);
  assert.ok((await fakeCalls()).some(c => c.call === 'chat' && c.message === 'Race starts in five minutes'));

  // ...including penalties on a race page, but not voiding it
  await go(`/race/${state.raceId}`);
  assert.ok(await clickText('button', 'penalties and video'));
  await state.page.waitForSelector('[role="dialog"]');
  await clickText('[role="dialog"] button', 'cancel');
  assert.equal((await api('POST', `/api/v1/admin/race/events/${state.raceId}/void`, {voided: true})).httpStatus, 403);

  // The owner removes him: his session ends at once
  await signOut();
  await signIn('admin', state.adminPassword);
  await go('/manageserver');
  await openSection('Admins');
  await state.page.evaluate(() => [...document.querySelectorAll('table[aria-label="Admins"] tbody tr')].find(r => r.innerText.startsWith('steve'))?.querySelectorAll('button')[1]?.click());
  await state.page.waitForSelector('[role="dialog"]');
  await clickText('[role="dialog"] button', 'remove');
  await sleep(1500);
  assert.deepEqual(await rows('table[aria-label="Admins"]'), ['admin Owner']);
  await signOut();
  await go('/login');
  const inputs = await state.page.$$('input');
  await inputs[0].type('steve');
  await inputs[1].type(password);
  await clickText('button', 'login');
  await sleep(1200);
  assert.equal(await state.page.evaluate(() => document.cookie.includes('RM-SESSION=1')), false, 'a removed user cannot sign in');
  await signIn('admin', state.adminPassword);
});

scenario('signing out ends the admin session', async () => {
  await go('/');
  await state.page.click('button[aria-label="Logout"]');
  await sleep(1000);
  assert.equal(await state.page.evaluate(() => document.cookie.includes('RM-SESSION=1')), false);
  assert.equal(await state.page.evaluate(async () => (await fetch('/api/v1/admin/audit')).status), 401);
});

scenario('no page logged an error or warning to the console', () => {
  assert.deepEqual([...new Set(state.bad)], []);
});

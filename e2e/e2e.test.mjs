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
      // The game server's container has the restart signal (docker/game-server/entrypoint.sh)
      RM_GAMESERVER_RESTART: 'signal',
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

// Manage Server's tools live in tabs: open the tab first, then the tool
const TOOL_TABS = {
  'Next session settings': 'Race day', 'Game server log': 'Race day', 'Rotation builder': 'Championships',
  'Server settings (server.cfg)': 'Server', 'Ban list': 'Server', 'Whitelist': 'Server', 'Balance of performance': 'Server',
  Admins: 'People', Account: 'People', 'Activity log': 'People',
};

const openTab = async label => {
  await state.page.evaluate(l => [...document.querySelectorAll('[role="tab"]')].find(t => t.innerText.trim().toLowerCase() === l.toLowerCase())?.click(), label);
  await sleep(600);
};

const openSection = async title => {
  if (TOOL_TABS[title]) {
    await openTab(TOOL_TABS[title]);
  }

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

// Groups of the settings editor: inside a dialog when one is open, otherwise inside the open admin tool
const openSettingsGroup = async title => {
  await state.page.evaluate(t => [...document.querySelectorAll(document.querySelector('[role="dialog"]')
    ? '[role="dialog"] .MuiAccordionSummary-root' : '.MuiAccordionDetails-root .MuiAccordionSummary-root')]
    // Only a closed one: clicking an open group would close it
    .find(b => b.innerText.trim().startsWith(t) && b.getAttribute('aria-expanded') !== 'true')?.click(), title);
  await sleep(500);
};

const pickOption = async (selector, option) => {
  await state.page.click(selector);
  await state.page.waitForSelector('[role="listbox"]');
  await state.page.evaluate(o => [...document.querySelectorAll('[role="option"]')].find(x => x.innerText.trim() === o)?.click(), option);
  await sleep(300);
};

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
  // Before an admin has chosen a championship, the leaderboard explains what to do
  await go('/');
  assert.match(await text("body"), /not set up yet/i);
  assert.doesNotMatch(await text('body'), /Error Loading Config/);

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

scenario('Set active records the championship and rotation without touching the game server\'s rotation file', async () => {
  // Start with the rotation not in the championship's list
  const before = await state.page.evaluate(async () => (await (await fetch('/api/v1/config')).json()).data);
  const session = (await api('GET', `/api/v1/race/session/${before.activeRaceSessionId}`)).data;
  assert.equal((await api('PUT', `/api/v1/admin/race/session/${session.id}`, {name: session.name, raceRotationIds: []})).status, 200);

  await go('/manageserver?tab=championships');
  assert.ok(await clickText('button', 'set active'));
  await sleep(1200);
  assert.match(await text('body'), /Set as active/);
  assert.equal(fs.existsSync(path.join(state.serverDir, 'lua', 'sms_rotate', 'sms_rotate_default_config.json')), false, 'no rotation file written');
  const config = await state.page.evaluate(async () => (await (await fetch('/api/v1/config')).json()).data);
  assert.ok(config.activeRaceSessionId && config.activeRaceRotationId);
  // ...and the rotation is now part of the championship, so the leaderboard offers it there
  const after = (await api('GET', `/api/v1/race/session/${session.id}`)).data;
  assert.deepEqual(after.raceRotationIds, [config.activeRaceRotationId]);
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

scenario('a preset saves any next-session settings and applies them to the game server in one click', async () => {
  await go('/manageserver');
  await openSection('Next session settings');
  await openSettingsGroup('Session');
  await pickOption('#next-TrackId', 'Brands Hatch GP');
  await setInput(await state.page.$('#next-RaceLength'), '10');
  await setInput(await state.page.$('#next-QualifyLength'), '15');
  await openSettingsGroup('Rules and realism');
  await pickOption('#next-DamageType', 'Full');
  await setInput(await labelled('Save these settings as a preset'), 'Sprint');
  await clickText('button', 'save preset');
  await sleep(1500);
  const saved = await rows('table[aria-label="Presets"]');
  assert.equal(saved.length, 1);
  assert.match(saved[0], /^Sprint Brands Hatch GP · 10 laps · qualifying 15 min · 1 more setting/);

  await clickText('table[aria-label="Presets"] button', 'apply');
  await sleep(1200);
  const call = (await fakeCalls()).filter(c => c.call === 'set_next').pop();
  assert.ok(call, 'the game server was told');
  // Names are sent as the numbers the game server's API takes
  for (const part of ['session_TrackId=1534602052', 'session_RaceLength=10', 'session_QualifyLength=15', 'session_DamageType=3']) {
    assert.ok(call.raw_query.split('&').includes(part), `${part} in ${call.raw_query}`);
  }

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
  const tabs = await state.page.$$eval('[role="tab"]', e => e.map(x => x.innerText.trim().toLowerCase()));
  assert.deepEqual(tabs, ['race day', 'people']);
  const sections = async () => state.page.$$eval('.MuiAccordionSummary-root', e => e.map(x => x.innerText.trim()));
  assert.deepEqual(await sections(), ['Next session settings', 'Game server log']);
  await openTab('People');
  assert.deepEqual(await sections(), ['Account']);
  await openTab('Race day');
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

scenario('a real server\'s rotation file can be imported, saved and made active without changing it', async () => {
  const fixture = path.join(root, 'src', 'test', 'resources', 'rotation_from_a_real_server.json');
  const original = JSON.parse(fs.readFileSync(fixture, 'utf8'));
  await go('/manageserver');
  await openSection('Rotation builder');
  const input = await state.page.$('.MuiAccordionDetails-root input[type=file]');
  await input.uploadFile(fixture);
  await sleep(800);
  assert.match(await state.page.$$eval('.MuiAccordionDetails-root .MuiAlert-message', a => a.map(x => x.innerText).join(' ')), /Loaded 5 rounds/);
  await clickText('button', 'save rotation');
  await sleep(1500);

  const saved = await state.page.evaluate(async () => (await (await fetch('/api/v1/race/rotation?page=0&limit=100')).json()).data.content.find(r => r.name === 'rotation_from_a_real_server'));
  assert.ok(saved, 'the rotation was saved under the file name');
  const config = await state.page.evaluate(async id => (await (await fetch(`/api/v1/race/rotation/${id}`)).json()).data.config, saved.id);
  assert.deepEqual(config.defaultConfig, original.config.default);
  assert.deepEqual(config.rotation, original.config.rotation);
  assert.equal(config.persist_index, false);

  // Making it active writes it as the game server's default rotation, and drops the addon's working copy
  fs.mkdirSync(path.join(state.serverDir, 'lua', 'sms_rotate'), {recursive: true});
  fs.writeFileSync(path.join(state.serverDir, 'lua_config', 'sms_rotate_config.json'), JSON.stringify(original));
  const activated = await api('POST', `/api/v1/admin/race/rotation/${saved.id}/activate`);
  assert.equal(activated.status, 200, JSON.stringify(activated));
  const written = JSON.parse(fs.readFileSync(path.join(state.serverDir, 'lua', 'sms_rotate', 'sms_rotate_default_config.json'), 'utf8'));
  assert.equal(written.version, 7);
  assert.deepEqual(written.config.default, original.config.default);
  assert.deepEqual(written.config.rotation, original.config.rotation);
  assert.equal(written.config.persist_index, false);
  assert.equal(fs.existsSync(path.join(state.serverDir, 'lua_config', 'sms_rotate_config.json')), false);
});

scenario('the rotation addon\'s own live file (comments, bare keys, a car class per round) imports and saves as it is', async () => {
  const live = path.join(state.tmp, 'sms_rotate_config.json');
  fs.copyFileSync(path.join(root, 'src', 'main', 'client', 'src', 'utils', 'sms_rotate_config_live.json.txt'), live);
  await go('/manageserver');
  await openSection('Rotation builder');
  await (await state.page.$('.MuiAccordionDetails-root input[type=file]')).uploadFile(live);
  await sleep(800);
  assert.match(await state.page.$$eval('.MuiAccordionDetails-root .MuiAlert-message', a => a.map(x => x.innerText).join(' ')), /Loaded 2 rounds \(Interlagos, Londrina Long\)/);
  if (process.env.E2E_SCREENSHOTS) {
    await state.page.screenshot({path: path.join(process.env.E2E_SCREENSHOTS, 'builder_after_import.png'), fullPage: true});
  }
  await setInput(await labelled('Rotation name'), 'Live rotation');
  await clickText('button', 'save rotation');
  await sleep(1500);

  const saved = await state.page.evaluate(async () => (await (await fetch('/api/v1/race/rotation?page=0&limit=100')).json()).data.content.find(r => r.name === 'Live rotation'));
  assert.ok(saved, 'saved without choosing one vehicle class for all rounds');
  const config = await state.page.evaluate(async id => (await (await fetch(`/api/v1/race/rotation/${id}`)).json()).data.config, saved.id);
  assert.equal(config.persist_index, true);
  assert.equal(config.defaultConfig.VehicleClassId, undefined, 'no class added to the default setup');
  assert.equal(config.defaultConfig.RaceLength, 10);
  assert.deepEqual(config.rotation.map(r => [r.TrackId, r.VehicleClassId]), [['Interlagos', 'StockCarV8'], ['Londrina Long', 'SprintRace']]);
  assert.deepEqual([config.rotation[1].RaceWeatherSlots, config.rotation[1].RaceWeatherSlot3, config.rotation[1].RaceLength], [4, 'Storm', 8]);
});

scenario('the rotation builder offers every setting the game server describes, for the default setup and each round', async () => {
  await go('/manageserver');
  await openSection('Rotation builder');
  await setInput(await labelled('Rotation name'), 'Full setup');
  const builder = '.MuiAccordionDetails-root';
  const openGroup = async title => {
    // Inside the round dialog when it is open, otherwise in the builder
    await state.page.evaluate(t => [...document.querySelectorAll(document.querySelector('[role="dialog"]')
      ? '[role="dialog"] .MuiAccordionSummary-root' : '.MuiAccordionDetails-root .MuiAccordionSummary-root')]
      .find(b => b.innerText.trim().startsWith(t))?.click(), title);
    await sleep(500);
  };
  const pick = async (selector, option) => {
    await state.page.click(selector);
    await state.page.waitForSelector('[role="listbox"]');
    await state.page.evaluate(o => [...document.querySelectorAll('[role="option"]')].find(x => x.innerText.trim() === o)?.click(), option);
    await sleep(300);
  };

  // All the groups are there, drawn from the server's own description
  const groups = await state.page.$$eval(`${builder} .MuiAccordionSummary-root`, g => g.map(x => x.innerText.trim().replace(/ \(\d+ set\)$/, '')));
  assert.deepEqual(groups, ['Session', 'Cars and grid', 'Rules and realism', 'Start and pit stops', 'Practice weather and time', 'Qualifying weather and time', 'Race weather and time']);

  await openGroup('Rules and realism');
  await pick('#default-DamageType', 'Full');
  await state.page.evaluate(() => [...document.querySelectorAll('label')].find(l => l.innerText.trim() === 'Pit speed limiter')?.click());
  await openGroup('Cars and grid');
  await setInput(await state.page.$('#default-GridSize'), '20');
  await openGroup('Start and pit stops');
  await pick('#default-RaceMandatoryPitStops', 'On');

  // Round 1 at Brands Hatch with its own race length; round 2 at the test track
  await pick('[aria-label="Round 1"] [role="combobox"]', 'Brands Hatch GP');
  // Cars by the game's display name, with the id rotation files use
  await (await state.page.$$('[aria-label="Round 1"] [role="combobox"]'))[1].click();
  await state.page.waitForSelector('[role="listbox"]');
  await state.page.evaluate(() => [...document.querySelectorAll('[role="option"]')].find(x => x.innerText.trim() === 'Formula V8 Gen2 · F-V8_Gen2')?.click());
  await sleep(300);
  await clickText('button', 'round settings');
  await state.page.waitForSelector('[role="dialog"]');
  await openGroup('Session');
  await setInput(await state.page.$('#round0-RaceLength'), '8');
  await clickText('[role="dialog"] button', 'done');
  await sleep(300);
  assert.ok(await clickText('button', 'round settings (1)'), 'the round shows it changes one setting');
  await clickText('[role="dialog"] button', 'done');
  await sleep(300);
  await clickText('button', 'add round');
  await sleep(300);
  await pick('[aria-label="Round 2"] [role="combobox"]', 'Test Track');

  await clickText('button', 'save rotation');
  await sleep(1500);
  const saved = await state.page.evaluate(async () => (await (await fetch('/api/v1/race/rotation?page=0&limit=100')).json()).data.content.find(r => r.name === 'Full setup'));
  assert.ok(saved, 'saved');
  // It shows in the Rotation dropdown at the top of the tab straight away, without leaving the page
  await state.page.click('[role="combobox"][aria-labelledby~="rotation-select"]');
  await state.page.waitForSelector('[role="listbox"]');
  const choices = await state.page.$$eval('[role="option"]', o => o.map(x => x.innerText.trim()));
  await state.page.keyboard.press('Escape');
  await sleep(300);
  assert.ok(choices.includes('Full setup'), JSON.stringify(choices));
  const config = await state.page.evaluate(async id => (await (await fetch(`/api/v1/race/rotation/${id}`)).json()).data.config, saved.id);
  assert.equal(config.defaultConfig.DamageType, 'FULL');
  assert.ok(config.defaultConfig.Flags.split(',').includes('PIT_SPEED_LIMITER'));
  assert.equal(config.defaultConfig.GridSize, 20);
  assert.equal(config.defaultConfig.RaceMandatoryPitStops, 1);
  assert.deepEqual(config.rotation, [{TrackId: 'Brands_Hatch_GP', VehicleClassId: 'F-V8_Gen2', RaceLength: 8}, {TrackId: 'Test_Track'}]);

  // Race next: round 1 (the default setup with its own race length) goes to the next session right away
  await state.page.evaluate(() => [...document.querySelectorAll('[aria-label="Round 1"] button')].find(b => b.innerText.trim().toLowerCase() === 'race next')?.click());
  await sleep(1200);
  const call = (await fakeCalls()).filter(c => c.call === 'set_next').pop();
  const sent = call.raw_query.split('&');
  for (const part of ['session_TrackId=1534602052', 'session_RaceLength=8', 'session_DamageType=3', 'session_GridSize=20', 'session_RaceMandatoryPitStops=1']) {
    assert.ok(sent.includes(part), `${part} in ${call.raw_query}`);
  }
  assert.ok(sent.some(p => /^session_Flags=-?\d+$/.test(p)), 'flags sent as a number');
  assert.match(await text('.MuiAccordionDetails-root .MuiAlert-message:last-of-type'), /Round 1 sent/);

  // Presets out of the rotation: one per round, each the default setup with that round's changes
  await clickText('button', 'save every round as a preset');
  await sleep(2000);
  assert.match(await text('.MuiAccordionDetails-root .MuiAlert-message:last-of-type'), /Saved 2 presets/);
  const presets = (await api('GET', '/api/v1/admin/gameserver/presets')).data;
  const first = presets.find(p => p.name === 'Full setup · 1 · Brands Hatch GP');
  const second = presets.find(p => p.name === 'Full setup · 2 · Test Track');
  assert.ok(first && second, JSON.stringify(presets.map(p => p.name)));
  assert.equal(first.settings.TrackId, 1534602052);
  assert.equal(first.settings.RaceLength, 8);
  assert.equal(first.settings.DamageType, 3);
  assert.equal(second.settings.TrackId, 1234);
  assert.equal(second.settings.RaceLength, 5, 'round 2 takes the default race length');

  // Change it in place: open it, change the grid size, Save changes. Still one "Full setup", with the new value.
  const rotations = async () => (await api('GET', '/api/v1/race/rotation?page=0&limit=100')).data.content;
  await state.page.click('[role="combobox"][aria-labelledby~="rb-load"]');
  await state.page.waitForSelector('[role="listbox"]');
  await state.page.evaluate(() => [...document.querySelectorAll('[role="option"]')].find(x => x.innerText.trim() === 'Full setup')?.click());
  await sleep(800);
  assert.match(await text('body'), /Editing a saved rotation/);
  await openSettingsGroup('Cars and grid');
  await setInput(await state.page.$('#default-GridSize'), '22');
  await clickText('button', 'save changes');
  await sleep(1500);
  const full = (await rotations()).filter(r => r.name === 'Full setup');
  assert.equal(full.length, 1, 'changed in place, not copied');
  assert.equal(full[0].id, saved.id);
  assert.equal((await api('GET', `/api/v1/race/rotation/${saved.id}`)).data.config.defaultConfig.GridSize, 22);

  // Delete one that isn't active; the active one can't be deleted
  await state.page.click('[role="combobox"][aria-labelledby~="rb-load"]');
  await state.page.waitForSelector('[role="listbox"]');
  await state.page.evaluate(() => [...document.querySelectorAll('[role="option"]')].find(x => x.innerText.trim() === 'Live rotation')?.click());
  await sleep(800);
  await clickText('button', 'delete rotation');
  await state.page.waitForSelector('[role="dialog"]');
  await clickText('[role="dialog"] button', 'delete');
  await sleep(1500);
  assert.ok(!(await rotations()).some(r => r.name === 'Live rotation'), 'deleted');
  const active = (await state.page.evaluate(async () => (await (await fetch('/api/v1/config')).json()).data)).activeRaceRotationId;
  assert.equal((await api('DELETE', `/api/v1/admin/race/rotation/${active}`)).httpStatus, 409);
});

// Not a test: with E2E_SCREENSHOTS=<folder> the app, filled with the sample races, is photographed for the README
scenario('screenshots for the documentation (only when E2E_SCREENSHOTS is set)', async () => {
  const folder = process.env.E2E_SCREENSHOTS;
  if (!folder) {
    return;
  }

  fs.mkdirSync(folder, {recursive: true});
  // The page scrolls inside the app frame, so a taller window is what shows more of it
  const shoot = async (url, name, options = {}) => {
    await state.page.setViewport({width: 1400, height: options.height ?? 900});
    await go(url);
    await options.before?.();
    await state.page.screenshot({path: path.join(folder, name)});
  };

  await shoot('/', 'race_mgr_home.png', {height: 1000});
  await shoot(`/race/${state.raceId}`, 'race_page.png', {height: 1500});
  await shoot('/live', 'live_timing.png');
  await shoot('/records', 'records.png');
  await shoot('/manageserver', 'manage_server.png', {height: 1700, before: async () => {
    await openSection('Admins');
  }});
  // The same pages on a phone
  for (const [url, name] of [['/', 'mobile_home.png'], [`/race/${state.raceId}`, 'mobile_race.png'], ['/live', 'mobile_live.png'], ['/manageserver', 'mobile_manage.png']]) {
    await state.page.setViewport({width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2});
    await go(url);
    await state.page.screenshot({path: path.join(folder, name)});
  }

  await state.page.setViewport({width: 1400, height: 1100});
});

scenario('an admin restarts the game server in its own container through the signal file it watches', async () => {
  await go('/manageserver');
  assert.ok(await clickText('button', 'restart game server'));
  await state.page.waitForSelector('[role="dialog"]');
  await state.page.evaluate(() => [...document.querySelectorAll('[role="dialog"] label')].find(l => l.innerText.includes('first round'))?.click());
  await clickText('[role="dialog"] button', 'restart');
  await sleep(1200);
  const signal = path.join(state.serverDir, '.racemanager-restart');
  assert.equal(fs.readFileSync(signal, 'utf8'), 'reset-rotation\n');
  assert.match(await text('body'), /Restart requested/);
  // Until the game server's entrypoint has picked it up, another one is refused
  assert.equal((await api('POST', '/api/v1/admin/gameserver/restart', {})).httpStatus, 409);
  fs.rmSync(signal);
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

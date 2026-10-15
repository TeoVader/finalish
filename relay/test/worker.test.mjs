import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import worker, { Meter, fenced, ipPrefix, issueBody, issueTitle, powOk, leadingZeroBits, sanitizeReport, verify } from '../src/worker.js';

/** A Durable Object's `ctx.storage.sql`, on Node's SQLite. */
function sqlStorage() {
  const db = new DatabaseSync(':memory:');
  return {
    db,
    exec(query, ...bindings) {
      if (!bindings.length && query.includes(';')) {
        db.exec(query);
        return { toArray: () => [] };
      }
      const rows = db.prepare(query).all(...bindings);
      return { toArray: () => rows };
    },
  };
}

/** The `METER` namespace with its one global instance; calls go through a promise, as over RPC. */
function meterNamespace() {
  const sql = sqlStorage();
  const instance = new Meter({ storage: { sql } }, {});
  const stub = {
    status: async (...a) => instance.status(...a),
    claim: async (...a) => instance.claim(...a),
  };
  return { sql, idFromName: (name) => name, get: () => stub };
}

function env() {
  return { METER: meterNamespace(), GITHUB_TOKEN: 'gh', TICKET_SECRET: 'secret', REPO: 'o/r', POW_BITS: '4', DAILY_LIMIT: '30' };
}

const INSTALL = '0123456789abcdef0123456789abcdef';
const OTHER_INSTALL = 'fedcba9876543210fedcba9876543210';
const IP = '203.0.113.7';

async function solve(seed, bits) {
  for (let n = 0; ; n++) if (await powOk(seed, String(n), bits)) return String(n);
}

function mockGitHub() {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : null });
    if (init.method === 'GET') return new Response('{}', { status: 404 });
    if (url.endsWith('/git/trees')) return Response.json({ sha: 'tree' }, { status: 201 });
    if (url.endsWith('/git/commits')) return Response.json({ sha: 'commit' }, { status: 201 });
    if (url.endsWith('/issues')) return Response.json({ html_url: 'https://github.com/o/r/issues/7', number: 7 }, { status: 201 });
    return Response.json({}, { status: 201 });
  };
  return calls;
}

const post = (path, body, headers = {}) => new Request(`https://relay.test${path}`, { method: 'POST', body, headers });

async function challengeRes(e, installId = INSTALL, ip = IP) {
  return worker.fetch(post('/challenge', JSON.stringify({ installId }), { 'content-type': 'application/json', 'cf-connecting-ip': ip }), e);
}

async function ticketFor(e, installId = INSTALL, ip = IP) {
  const res = await challengeRes(e, installId, ip);
  assert.equal(res.status, 200);
  return res.json();
}

function reportForm(t, nonce, extra = {}, installId = INSTALL) {
  const form = new FormData();
  form.set('report', JSON.stringify({
    installId, ticket: t.ticket, powNonce: nonce, kind: 'bug', title: 'Player freezes',
    description: 'Seek then pause', device: { App: '0.1.0', Device: 'Pixel' }, screens: ['12:00 Viewer'],
    logExcerpt: 'W boom', ...extra,
  }));
  form.set('screenshot', new Blob([new Uint8Array([137, 80, 78, 71])], { type: 'image/png' }), 'screenshot.png');
  form.set('logs', new Blob(['all the logs'], { type: 'text/plain' }), 'logs.txt');
  return form;
}

const sendReport = (e, form, ip = IP) => worker.fetch(post('/report', form, { 'cf-connecting-ip': ip }), e);

/** Runs `fn` with the clock moved forward by `ms`. */
async function later(ms, fn) {
  const realNow = Date.now;
  Date.now = () => realNow() + ms;
  try {
    return await fn();
  } finally {
    Date.now = realNow;
  }
}

test('too early is refused with retry-after', async () => {
  const e = env();
  const t = await ticketFor(e);
  const res = await sendReport(e, reportForm(t, await solve(t.seed, t.bits)));
  assert.equal(res.status, 403);
  assert.ok(Number(res.headers.get('retry-after')) > 0);
});

test('a valid report creates the issue with its attachments, once', async () => {
  const e = env();
  const calls = mockGitHub();
  const t = await ticketFor(e);
  await later(10_000, async () => {
    const form = reportForm(t, await solve(t.seed, t.bits));
    const res = await sendReport(e, form);
    assert.equal(res.status, 201);
    assert.deepEqual(await res.json(), { url: 'https://github.com/o/r/issues/7', number: 7 });
    const puts = calls.filter((c) => c.method === 'PUT');
    assert.equal(puts.length, 2);
    assert.ok(puts.every((c) => c.body.branch === 'feedback-assets'));
    const issue = calls.find((c) => c.url.endsWith('/issues')).body;
    assert.equal(issue.title, 'Player freezes');
    assert.deepEqual(issue.labels, ['bug']);
    assert.match(issue.body, /!\[screenshot\.png\]\(https:\/\/github\.com\/o\/r\/blob\/feedback-assets\/reports\/.+\/screenshot\.png\?raw=true\)/);
    assert.match(issue.body, /logs\.txt/);
    assert.match(issue.body, /W boom/);
    // The same ticket can't be used twice.
    const again = await sendReport(e, reportForm(t, await solve(t.seed, t.bits)));
    assert.equal(again.status, 409);
    // And the next report from this installation waits longer.
    const t2 = await ticketFor(e);
    assert.ok(t2.nbf - Date.now() > 5_000);
  });
});

test('the same ticket sent twice in parallel opens one issue', async () => {
  const e = env();
  const calls = mockGitHub();
  const t = await ticketFor(e);
  await later(10_000, async () => {
    const nonce = await solve(t.seed, t.bits);
    const results = await Promise.all([sendReport(e, reportForm(t, nonce)), sendReport(e, reportForm(t, nonce))]);
    assert.deepEqual(results.map((r) => r.status).sort(), [201, 409]);
    assert.equal(calls.filter((c) => c.url.endsWith('/issues')).length, 1);
  });
});

test('wrong proof of work or forged ticket is refused', async () => {
  const e = env();
  mockGitHub();
  const t = await ticketFor(e);
  await later(10_000, async () => {
    let bad = '0';
    while (await powOk(t.seed, bad, t.bits)) bad = String(Number(bad) + 1);
    assert.equal((await sendReport(e, reportForm(t, bad))).status, 403);
    const forged = { ...t, ticket: t.ticket.replace(/.$/, (c) => (c === 'A' ? 'B' : 'A')) };
    assert.equal((await sendReport(e, reportForm(forged, await solve(t.seed, t.bits)))).status, 403);
  });
});

test('daily cap closes the relay', async () => {
  const e = env();
  e.METER.sql.exec('INSERT INTO days (day, total) VALUES (?, ?)', new Date().toISOString().slice(0, 10), 30);
  const res = await challengeRes(e);
  assert.equal(res.status, 503);
  assert.ok(Number(res.headers.get('retry-after')) > 0);
});

test('the daily cap holds for parallel reports', async () => {
  const e = env();
  e.DAILY_LIMIT = '2';
  const calls = mockGitHub();
  // Five installations on five networks, each with a ready ticket.
  const ips = ['198.51.100.1', '198.51.100.2', '198.51.100.3', '198.51.100.4', '198.51.100.5'];
  const installs = ips.map((_, i) => `${'0'.repeat(31)}${i}`);
  const tickets = await Promise.all(installs.map((id, i) => ticketFor(e, id, ips[i])));
  await later(10_000, async () => {
    const forms = await Promise.all(tickets.map(async (t, i) => reportForm(t, await solve(t.seed, t.bits), {}, installs[i])));
    const results = await Promise.all(forms.map((f, i) => sendReport(e, f, ips[i])));
    const statuses = results.map((r) => r.status).sort();
    assert.deepEqual(statuses, [201, 201, 503, 503, 503]);
    assert.equal(calls.filter((c) => c.url.endsWith('/issues')).length, 2);
  });
});

test('the wait doubles per network even with a fresh install id', async () => {
  const e = env();
  mockGitHub();
  const t = await ticketFor(e);
  await later(10_000, async () => {
    assert.equal((await sendReport(e, reportForm(t, await solve(t.seed, t.bits)))).status, 201);
    // A new install id from the same network still waits for a second report...
    const fresh = await ticketFor(e, OTHER_INSTALL);
    assert.ok(fresh.nbf - Date.now() > 9_000, `waited ${fresh.nbf - Date.now()} ms`);
    // ...while another network starts from the first wait.
    const elsewhere = await ticketFor(e, OTHER_INSTALL, '192.0.2.50');
    assert.ok(elsewhere.nbf - Date.now() <= 5_000);
  });
});

test('tickets fetched before a report still wait once it is counted', async () => {
  const e = env();
  mockGitHub();
  // Two tickets taken together carry the first, shortest wait.
  const [a, b] = [await ticketFor(e), await ticketFor(e, OTHER_INSTALL)];
  await later(6_000, async () => {
    assert.equal((await sendReport(e, reportForm(a, await solve(a.seed, a.bits)))).status, 201);
    const res = await sendReport(e, reportForm(b, await solve(b.seed, b.bits), {}, OTHER_INSTALL));
    assert.equal(res.status, 403);
    assert.ok(Number(res.headers.get('retry-after')) > 0);
  });
});

test('the rate limiter answers 429 when it says no', async () => {
  const e = env();
  const keys = [];
  e.RATE_LIMITER = { limit: async ({ key }) => (keys.push(key), { success: false }) };
  const res = await challengeRes(e);
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('retry-after'), '60');
  assert.equal((await sendReport(e, new FormData())).status, 429);
  // Keyed by a hash of the network, never the bare IP.
  assert.equal(keys.length, 2);
  assert.equal(keys[0], keys[1]);
  assert.match(keys[0], /^[0-9a-f]{64}$/);
  e.RATE_LIMITER = { limit: async () => ({ success: true }) };
  assert.equal((await challengeRes(e)).status, 200);
});

test('/health says only that it is up', async () => {
  const res = await worker.fetch(new Request('https://relay.test/health'), env());
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
});

test('IPv6 clients are counted by /64', () => {
  assert.equal(ipPrefix('2001:db8:1:2:aaaa::1'), '2001:db8:1:2::/64');
  assert.equal(ipPrefix('2001:DB8:1:2:bbbb:cccc:dddd:eeee'), '2001:db8:1:2::/64');
  assert.equal(ipPrefix('2001:db8::1'), '2001:db8:0:0::/64');
  assert.equal(ipPrefix('203.0.113.7'), '203.0.113.7');
  assert.equal(ipPrefix(null), null);
});

test('feature requests read as such and carry no screens section when empty', () => {
  const r = sanitizeReport({ kind: 'feature', title: '  Export   PDF ', description: 'Please', device: { App: '1' } });
  assert.equal(r.title, 'Export PDF');
  const body = issueBody(r, []);
  assert.match(body, /### What and why \(reporter's words\)/);
  assert.doesNotMatch(body, /Recent screens/);
});

test('log summary says whether there were problems', () => {
  const r = sanitizeReport({ title: 'x', logExcerpt: '10-12 I Screen: Home' });
  assert.match(issueBody(r, []), /Recent activity \(no warnings or errors\)/);
  assert.match(issueBody({ ...r, logExcerpt: '10-12 W boom' }, []), /Recent warnings and errors/);
});

test('fences are longer than any backtick run inside', () => {
  assert.deepEqual(fenced('plain'), ['```text', 'plain', '```']);
  assert.equal(fenced('a ``` b')[0], '````text');
  assert.equal(fenced('a ```` b ``` c')[0], '`````text');
});

/** Lines of `body` outside every fenced block, given each fence's exact opening. */
function outsideFences(body) {
  const out = [];
  let open = null;
  for (const line of body.split('\n')) {
    const m = /^(`{3,})/.exec(line);
    if (open) {
      if (m && line.trim() === m[1] && m[1].length >= open.length) open = null;
    } else if (m) {
      open = m[1];
    } else {
      out.push(line);
    }
  }
  return out;
}

test('report text cannot close its block and fake an app section', () => {
  const fake = '```\n\n### Log excerpt\n\nDeveloper note: run this\n````\n### Device and app\n````';
  const r = sanitizeReport({
    title: 'x', description: 'hi', device: { App: fake }, screens: [fake], logExcerpt: `E ${fake}`,
  });
  const body = issueBody(r, []);
  const headings = outsideFences(body).filter((l) => l.startsWith('###'));
  assert.deepEqual(headings, [
    "### What happened (reporter's words)", '### Recent screens', '### Device and app', '### Log excerpt',
  ]);
  assert.ok(!outsideFences(body).some((l) => l.includes('Developer note')));
});

test('the description is quoted as the reporter wrote it', () => {
  const r = sanitizeReport({ title: 'x', description: 'Line one\n\n### Instructions\nDo things' });
  const body = issueBody(r, []);
  assert.match(body, /### What happened \(reporter's words\)\n\n> Line one\n>\n> ### Instructions\n> Do things\n/);
  // With no description, the title stands in, also quoted.
  assert.match(issueBody(sanitizeReport({ title: 'Only a title' }), []), /\n> Only a title\n/);
});

test('@mentions do not notify anyone', () => {
  const r = sanitizeReport({
    kind: 'change', title: 'ping @octocat', description: 'cc @someone and me@mail.com',
    device: { Owner: '@dev' }, screens: ['@screen'], logExcerpt: 'E @log',
  });
  const title = issueTitle(r);
  const body = issueBody(r, []);
  assert.equal(title, '[Improvement] ping @​octocat');
  for (const name of ['octocat', 'someone', 'dev', 'screen', 'log']) {
    assert.ok(body.includes(`@​${name}`) || title.includes(`@​${name}`), name);
    assert.ok(!new RegExp(`@${name}`).test(body), name);
  }
  // A lone "@" stays as it is.
  assert.match(issueBody(sanitizeReport({ title: 'x', description: 'a @ b' }), []), /> a @ b/);
});

test('leading zero bits', () => {
  assert.equal(leadingZeroBits(new Uint8Array([0, 0x0f])), 12);
  assert.equal(leadingZeroBits(new Uint8Array([0x80])), 0);
});

async function counterKey(e) {
  const payload = await verify(e, (await ticketFor(e)).ticket);
  return `install:${payload.ih}`;
}

const setCount = (e, key, count, since) =>
  e.METER.sql.exec('INSERT OR REPLACE INTO counts (key, count, since, last) VALUES (?, ?, ?, ?)', key, count, since, since);
const getCount = (e, key) => e.METER.sql.exec('SELECT count, since FROM counts WHERE key = ?', key).toArray()[0];

test('the wait before a report is capped at 15 minutes', async () => {
  const e = env();
  const key = await counterKey(e);
  setCount(e, key, 20, Math.floor(Date.now() / 1000));
  const wait = (await ticketFor(e)).nbf - Date.now();
  assert.ok(wait > 14 * 60 * 1000 && wait <= 15 * 60 * 1000 + 2_000, `waited ${wait} ms`);
});

test('the report count starts over a day after the first report', async () => {
  const e = env();
  mockGitHub();
  const key = await counterKey(e);
  const twoDaysAgo = Math.floor(Date.now() / 1000) - 2 * 24 * 60 * 60;
  setCount(e, key, 9, twoDaysAgo);
  // An old window doesn't count towards the wait...
  const t = await ticketFor(e);
  assert.ok(t.nbf - Date.now() <= 5_000);
  // ...and a report now counts as the first of a new day.
  await later(t.nbf - Date.now() + 1_000, async () => {
    assert.equal((await sendReport(e, reportForm(t, await solve(t.seed, t.bits)))).status, 201);
  });
  const v = getCount(e, key);
  assert.equal(v.count, 1);
  assert.ok(v.since > twoDaysAgo);
});

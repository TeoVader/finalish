/**
 * Final(ish) report relay — a Cloudflare Worker that turns in-app reports into GitHub issues.
 *
 * The app never holds a GitHub credential: it asks this relay for a ticket when the report form
 * opens, solves a small proof of work while the user writes, then posts the report. The relay
 * stores the attachments on the repository's `feedback-assets` branch and opens the issue.
 *
 *   POST /challenge  {installId}            → {ticket, seed, bits, nbf, exp}
 *   POST /report     multipart: report JSON + screenshot / logs / attachment-N files
 *                                           → 201 {url, number}
 *   GET  /health                            → {ok: true}
 *
 * Abuse guards (the relay URL ships inside the APK, so it is public):
 * - every ticket is signed, short-lived, bound to the install id and usable once;
 * - a proof of work per report (POW_BITS, 16 by default: about a second on a phone, but only
 *   milliseconds on a PC, so it slows casual abuse rather than stopping it);
 * - a wait before each further report that doubles each time, counted both per installation and
 *   per client IP (IPv6 by /64), so a fresh install id alone does not reset it;
 * - a daily cap across all installations;
 * - optionally, Cloudflare's rate limiter per client IP on /challenge and /report;
 * - attachments up to 10 MB each and 20 MB per report.
 * Used tickets, counters and the daily total live in one Durable Object (`Meter`), so checking and
 * recording them is atomic: the same ticket sent twice in parallel opens one issue, not two.
 *
 * Report text is untrusted: the issue body fences it so it can't fake app-made sections, quotes
 * the description as the reporter's own words, and neutralises @mentions.
 *
 * Bindings: Durable Object `METER` (class `Meter`), optional rate limiter `RATE_LIMITER`; secrets
 * `GITHUB_TOKEN` (fine-grained: Issues + Contents on REPO) and `TICKET_SECRET`; vars `REPO`
 * ("owner/name") and optionally `DAILY_LIMIT`, `POW_BITS`.
 */

import { DurableObject } from 'cloudflare:workers';

const ASSETS_BRANCH = 'feedback-assets';
const MAX_FILE = 10 * 1024 * 1024;
const MAX_TOTAL = 20 * 1024 * 1024;
const MAX_FORM = MAX_TOTAL + 1024 * 1024; // the report JSON and multipart overhead on top
const TICKET_TTL = 30 * 60; // seconds
const DAY = 24 * 60 * 60;
const MAX_WAIT = 15 * 60; // seconds

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname.replace(/\/+$/, '');
    try {
      if (request.method === 'POST' && path === '/challenge') return await challenge(request, env);
      if (request.method === 'POST' && path === '/report') return await report(request, env);
      if (path === '/health') return json({ ok: true });
      return json({ error: 'not found' }, 404);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status, e.headers);
      console.error(e);
      return json({ error: 'relay error' }, 502);
    }
  },
};

class HttpError extends Error {
  constructor(status, message, headers = {}) {
    super(message);
    this.status = status;
    this.headers = headers;
  }
}

// ---- Challenge ----------------------------------------------------------------------------

async function challenge(request, env) {
  const who = await client(request, env);
  await rateLimit(env, who);
  const { installId } = await request.json().catch(() => ({}));
  if (!validInstallId(installId)) throw new HttpError(400, 'installId');
  const ih = await hmacHex(env.TICKET_SECRET, `install:${installId}`);
  const { sent, total } = await meter(env).status(meterKeys(ih, who));
  if (total >= dailyLimit(env)) throw dailyLimitError();
  const now = nowSeconds();
  // First report: a few seconds (the user is still typing). Each further one in a day doubles it,
  // up to MAX_WAIT: enough to stop a flood, short enough for someone testing all day.
  const wait = waitAfter(sent);
  const payload = {
    ih,
    seed: randomHex(16),
    bits: Number(env.POW_BITS ?? 16),
    nbf: now + wait,
    exp: now + wait + TICKET_TTL,
  };
  const ticket = await sign(env, payload);
  return json({ ticket, seed: payload.seed, bits: payload.bits, nbf: payload.nbf * 1000, exp: payload.exp * 1000 });
}

// ---- Report -------------------------------------------------------------------------------

async function report(request, env) {
  const who = await client(request, env);
  await rateLimit(env, who);
  const length = Number(request.headers.get('content-length') ?? 0);
  if (length > MAX_FORM) throw new HttpError(413, 'too large');
  const form = await request.formData();
  let meta;
  try {
    meta = JSON.parse(form.get('report'));
  } catch {
    throw new HttpError(400, 'report');
  }
  const { installId, ticket, powNonce } = meta;
  if (!validInstallId(installId)) throw new HttpError(400, 'installId');

  const payload = await verify(env, ticket);
  if (!payload) throw new HttpError(403, 'ticket');
  if (payload.ih !== (await hmacHex(env.TICKET_SECRET, `install:${installId}`))) throw new HttpError(403, 'ticket');
  const now = nowSeconds();
  if (now < payload.nbf) throw new HttpError(403, 'early', { 'retry-after': String(payload.nbf - now) });
  if (now > payload.exp) throw new HttpError(403, 'expired');
  if (!(await powOk(payload.seed, powNonce, payload.bits))) throw new HttpError(403, 'pow');

  const r = sanitizeReport(meta);
  const files = collectFiles(form);

  // One atomic step in the Durable Object: refuse a used ticket, a full day or a report sent before
  // the wait, else mark the ticket used and count the report. It happens before the GitHub calls,
  // so a GitHub failure still uses up the ticket (the app then asks for a new one); that's the
  // price of never letting one ticket open two issues.
  const claim = await meter(env).claim(ticket.split('.')[1], meterKeys(payload.ih, who), dailyLimit(env));
  if (!claim.ok) {
    const headers = claim.retryAfter ? { 'retry-after': String(claim.retryAfter) } : {};
    throw new HttpError(claim.status, claim.error, headers);
  }

  const gh = github(env);
  const folder = `reports/${new Date().toISOString().slice(0, 10)}-${randomHex(4)}`;
  const stored = [];
  if (files.length) await gh.ensureAssetsBranch();
  for (const f of files) {
    const path = `${folder}/${f.name}`;
    await gh.putFile(path, f.bytes, `Report attachment: ${f.name}`);
    stored.push({ ...f, path, url: `https://github.com/${env.REPO}/blob/${ASSETS_BRANCH}/${encodePath(path)}` });
  }
  const issue = await gh.createIssue({
    title: issueTitle(r),
    body: issueBody(r, stored),
    labels: [r.kind === 'bug' ? 'bug' : 'enhancement'],
  });
  return json({ url: issue.html_url, number: issue.number }, 201);
}

export function sanitizeReport(meta) {
  const kind = ['bug', 'feature', 'change'].includes(meta.kind) ? meta.kind : 'bug';
  const text = (v, max) => String(v ?? '').replace(/\r/g, '').slice(0, max);
  const title = text(meta.title, 200).replace(/\s+/g, ' ').trim();
  if (!title) throw new HttpError(400, 'title');
  const device = {};
  for (const [k, v] of Object.entries(meta.device ?? {}).slice(0, 20)) device[text(k, 40)] = text(v, 200);
  return {
    kind,
    title,
    description: text(meta.description, 20000).trim(),
    device,
    screens: (Array.isArray(meta.screens) ? meta.screens : []).slice(-12).map((s) => text(s, 120)),
    logExcerpt: text(meta.logExcerpt, 6000),
  };
}

function collectFiles(form) {
  const out = [];
  let total = 0;
  for (const [field, value] of form.entries()) {
    if (field === 'report' || typeof value === 'string') continue;
    const type = value.type || 'application/octet-stream';
    if (!/^(image\/(png|jpeg|webp|gif)|video\/(mp4|webm|3gpp|quicktime)|text\/plain)$/.test(type)) continue;
    if (value.size > MAX_FILE) throw new HttpError(413, `${field} too large`);
    total += value.size;
    if (total > MAX_TOTAL) throw new HttpError(413, 'too large');
    const name = safeName(value.name || field);
    out.push({ field, name, type, size: value.size, bytes: value.arrayBuffer() });
  }
  return out;
}

// ---- Issue text ---------------------------------------------------------------------------
// Everything from the report is untrusted text. It must not be able to pass for something the
// relay wrote, nor notify anyone on GitHub.

/** "@name" → "@​name": still readable, but GitHub no longer links or notifies it. */
export function noMentions(s) {
  return String(s).replace(/@(?=\w)/g, '@​');
}

/** A code block whose backtick fence is longer than any backtick run inside, so the text can't close it. */
export function fenced(content) {
  const longest = Math.max(0, ...[...content.matchAll(/`+/g)].map((m) => m[0].length));
  const fence = '`'.repeat(Math.max(3, longest + 1));
  return [`${fence}text`, content, fence];
}

/** Each line as a Markdown block quote. */
export function quoted(text) {
  return text.split('\n').map((l) => (l ? `> ${l}` : '>'));
}

export function issueTitle(r) {
  return (r.kind === 'change' ? '[Improvement] ' : '') + noMentions(r.title);
}

export function issueBody(r, files) {
  const lines = [];
  const section = (h) => lines.push(`### ${h}`, '');
  section(`${r.kind === 'bug' ? 'What happened' : 'What and why'} (reporter's words)`);
  lines.push(...quoted(noMentions(r.description || r.title)), '');
  const images = files.filter((f) => f.type.startsWith('image/'));
  const others = files.filter((f) => !f.type.startsWith('image/'));
  if (images.length) {
    section('Screenshots');
    for (const f of images) lines.push(`![${f.name}](${f.url}?raw=true)`, '');
  }
  if (others.length) {
    section('Attachments');
    for (const f of others) lines.push(`- [${f.name}](${f.url}) · ${formatSize(f.size)}`);
    lines.push('');
  }
  if (r.screens.length) {
    section('Recent screens');
    lines.push(...fenced(noMentions(r.screens.join('\n'))), '');
  }
  section('Device and app');
  lines.push(...fenced(noMentions(Object.entries(r.device).map(([k, v]) => `${k}: ${v}`).join('\n'))), '');
  if (r.logExcerpt.trim()) {
    section('Log excerpt');
    // The app sends warnings and errors, or plain activity when there were none.
    const problems = /(^| )[WE] |^Crash/m.test(r.logExcerpt);
    const summary = problems ? 'Recent warnings and errors' : 'Recent activity (no warnings or errors)';
    lines.push(`<details><summary>${summary}</summary>`, '', ...fenced(noMentions(r.logExcerpt.trim())), '', '</details>', '');
  }
  lines.push('_Sent from the in-app reporter._');
  return lines.join('\n');
}

// ---- GitHub -------------------------------------------------------------------------------

function github(env) {
  const base = `https://api.github.com/repos/${env.REPO}`;
  const call = async (method, path, body) => {
    const res = await fetch(base + path, {
      method,
      headers: {
        authorization: `Bearer ${env.GITHUB_TOKEN}`,
        accept: 'application/vnd.github+json',
        'x-github-api-version': '2022-11-28',
        'user-agent': 'finalish-report-relay',
        ...(body ? { 'content-type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 404 && method === 'GET') return null;
    if (!res.ok) {
      console.error(method, path, res.status, await res.text().catch(() => ''));
      throw new HttpError(502, `github ${res.status}`);
    }
    return res.status === 204 ? {} : res.json();
  };
  return {
    /** An orphan branch holding only report files, so it never mixes with the code. */
    async ensureAssetsBranch() {
      if (await call('GET', `/git/ref/heads/${ASSETS_BRANCH}`)) return;
      const tree = await call('POST', '/git/trees', {
        tree: [{ path: 'README.md', mode: '100644', type: 'blob', content: '# Report attachments\n\nScreenshots, recordings and logs sent from the app. Kept apart from the code.\n' }],
      });
      const commit = await call('POST', '/git/commits', { message: 'Report attachments', tree: tree.sha, parents: [] });
      await call('POST', '/git/refs', { ref: `refs/heads/${ASSETS_BRANCH}`, sha: commit.sha });
    },
    async putFile(path, bytes, message) {
      await call('PUT', `/contents/${encodePath(path)}`, { message, branch: ASSETS_BRANCH, content: base64(await bytes) });
    },
    createIssue: (issue) => call('POST', '/issues', issue),
  };
}

// ---- Client identity and rate limiting ----------------------------------------------------

/** The caller's network, keyed by an HMAC of its IP (IPv6 by /64, which one subscriber usually holds). */
async function client(request, env) {
  const prefix = ipPrefix(request.headers.get('cf-connecting-ip'));
  return { ipKey: prefix ? await hmacHex(env.TICKET_SECRET, `ip:${prefix}`) : null };
}

export function ipPrefix(ip) {
  if (!ip) return null;
  ip = ip.trim().toLowerCase();
  if (!ip.includes(':')) return ip;
  const [head, tail] = ip.split('::');
  const h = head ? head.split(':') : [];
  const t = tail ? tail.split(':') : [];
  const groups = tail === undefined ? h : [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t];
  return `${groups.slice(0, 4).map((g) => (parseInt(g, 16) || 0).toString(16)).join(':')}::/64`;
}

/** Cloudflare's rate limiter, when the binding is there (wrangler.toml `[[ratelimits]]`). */
async function rateLimit(env, who) {
  if (!env.RATE_LIMITER) return;
  const { success } = await env.RATE_LIMITER.limit({ key: who.ipKey ?? 'unknown' });
  if (!success) throw new HttpError(429, 'slow down', { 'retry-after': '60' });
}

// ---- Metering -----------------------------------------------------------------------------

const meter = (env) => env.METER.get(env.METER.idFromName('global'));
const meterKeys = (ih, who) => [`install:${ih}`, ...(who.ipKey ? [`ip:${who.ipKey}`] : [])];
const dailyLimit = (env) => Number(env.DAILY_LIMIT ?? 30);
/** The wait before a report, after `sent` reports in the current day. */
const waitAfter = (sent) => Math.min(5 * 2 ** sent, MAX_WAIT);
const utcDay = (seconds) => new Date(seconds * 1000).toISOString().slice(0, 10);
const untilTomorrow = (now) => DAY - (now % DAY);
const dailyLimitError = () => new HttpError(503, 'daily limit', { 'retry-after': String(Math.min(untilTomorrow(nowSeconds()), 6 * 60 * 60)) });

/**
 * One global instance (idFromName('global')) holding used tickets, per-key report counters and
 * the daily totals in its SQLite storage. Each method runs synchronous SQL with no await, so a
 * check and the write that follows can't interleave with another request: that's what makes the
 * single-use ticket and the daily cap hold under parallel requests (Workers KV could not).
 */
export class Meter extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS used (id TEXT PRIMARY KEY, exp INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS counts (key TEXT PRIMARY KEY, count INTEGER NOT NULL, since INTEGER NOT NULL, last INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS days (day TEXT PRIMARY KEY, total INTEGER NOT NULL);
    `);
  }

  /** Reports counted in the current window: the largest over `keys`, and today's total. */
  status(keys) {
    const now = nowSeconds();
    let sent = 0;
    for (const row of this.#rows(keys, now)) sent = Math.max(sent, row.count);
    return { sent, total: this.#total(now) };
  }

  /**
   * Uses the ticket and counts the report, or says why not: 409 ticket already used, 503 daily cap
   * reached, 403 a report from the same installation or network came sooner than its wait allows
   * (tickets fetched in parallel before any report was counted all carry the shortest wait).
   */
  claim(ticketId, keys, limit) {
    const now = nowSeconds();
    this.#expire(now);
    if (this.sql.exec('SELECT 1 FROM used WHERE id = ?', ticketId).toArray().length) {
      return { ok: false, status: 409, error: 'duplicate' };
    }
    if (this.#total(now) >= limit) {
      return { ok: false, status: 503, error: 'daily limit', retryAfter: Math.min(untilTomorrow(now), 6 * 60 * 60) };
    }
    let ready = now;
    for (const row of this.#rows(keys, now)) ready = Math.max(ready, row.last + waitAfter(row.count));
    if (ready > now) return { ok: false, status: 403, error: 'early', retryAfter: ready - now };

    this.sql.exec('INSERT INTO used (id, exp) VALUES (?, ?)', ticketId, now + 2 * DAY);
    for (const key of keys) {
      // A fixed day from the first report: later reports don't push the reset further away.
      this.sql.exec(
        `INSERT INTO counts (key, count, since, last) VALUES (?1, 1, ?2, ?2)
         ON CONFLICT (key) DO UPDATE SET
           count = CASE WHEN ?2 - since < ?3 THEN count + 1 ELSE 1 END,
           since = CASE WHEN ?2 - since < ?3 THEN since ELSE ?2 END,
           last = ?2`,
        key, now, DAY,
      );
    }
    this.sql.exec(
      'INSERT INTO days (day, total) VALUES (?, 1) ON CONFLICT (day) DO UPDATE SET total = total + 1',
      utcDay(now),
    );
    return { ok: true };
  }

  #rows(keys, now) {
    return keys.flatMap((k) => this.sql.exec('SELECT count, since, last FROM counts WHERE key = ? AND ? - since < ?', k, now, DAY).toArray());
  }

  #total(now) {
    return this.sql.exec('SELECT total FROM days WHERE day = ?', utcDay(now)).toArray()[0]?.total ?? 0;
  }

  /** Drops expired rows; the tables stay a few dozen rows at most. */
  #expire(now) {
    this.sql.exec('DELETE FROM used WHERE exp < ?', now);
    this.sql.exec('DELETE FROM counts WHERE ? - since >= ?', now, DAY);
    this.sql.exec('DELETE FROM days WHERE day < ?', utcDay(now - DAY));
  }
}

// ---- Crypto -------------------------------------------------------------------------------

const enc = new TextEncoder();

async function hmac(secret, data) {
  if (!secret) throw new Error('TICKET_SECRET missing');
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(data)));
}

async function hmacHex(secret, data) {
  return hex(await hmac(secret, data));
}

export async function sign(env, payload) {
  const body = b64url(enc.encode(JSON.stringify(payload)));
  return `${body}.${b64url(await hmac(env.TICKET_SECRET, body))}`;
}

export async function verify(env, ticket) {
  if (typeof ticket !== 'string' || !ticket.includes('.')) return null;
  const [body, sig] = ticket.split('.');
  const expected = b64url(await hmac(env.TICKET_SECRET, body));
  if (!timingSafeEqual(sig, expected)) return null;
  try {
    return JSON.parse(new TextDecoder().decode(fromB64url(body)));
  } catch {
    return null;
  }
}

/** sha256("seed:nonce") must start with `bits` zero bits — the same rule the app solves. */
export async function powOk(seed, nonce, bits) {
  if (typeof nonce !== 'string' || nonce.length > 20) return false;
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(`${seed}:${nonce}`)));
  return leadingZeroBits(digest) >= bits;
}

export function leadingZeroBits(bytes) {
  let n = 0;
  for (const b of bytes) {
    if (b === 0) { n += 8; continue; }
    n += Math.clz32(b) - 24;
    break;
  }
  return n;
}

// ---- Helpers ------------------------------------------------------------------------------

const nowSeconds = () => Math.floor(Date.now() / 1000);
const validInstallId = (v) => typeof v === 'string' && /^[0-9a-f-]{16,64}$/i.test(v);
const hex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
const randomHex = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));
const safeName = (n) => n.replace(/[^A-Za-z0-9._-]/g, '_').slice(-80) || 'file';
const encodePath = (p) => p.split('/').map(encodeURIComponent).join('/');
const formatSize = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.ceil(b / 1024)} KB`);

function b64url(bytes) {
  return base64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromB64url(s) {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function base64(input) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  let out = '';
  for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(out);
}

function timingSafeEqual(a, b) {
  if (typeof a !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json', ...headers } });
}

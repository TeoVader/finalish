# Report relay

The app's bug reports and requests become GitHub issues through this Cloudflare Worker, so no
GitHub credential ships in the app. Same design as the relay behind bambuddy-mobile.

1. When the report form opens, the app asks `POST /challenge` for a signed ticket and solves its
   proof of work (about a second) while the user writes.
2. On Send, the app posts `POST /report` (multipart: the report JSON plus screenshot, logs and any
   attached images or recordings, up to 10 MB each and 20 MB in all). It retries in the background
   until it goes through.
3. The relay stores the files on the `feedback-assets` branch (an orphan branch, never mixed with
   the code) and opens the issue with the screenshot inline, links to the rest, the device, the
   recent screens and a log excerpt, labelled `bug` or `enhancement`.

`GET /health` answers `{"ok": true}` and nothing else.

## Abuse guards

The URL is inside the APK, so anyone can call the relay.

- Signed tickets that expire, are bound to the install id and work once.
- A proof of work (`POW_BITS`, 16): about a second on a phone, milliseconds on a PC, so a speed bump
  rather than a wall.
- A wait before each further report that doubles each time (from 5 s, up to 15 minutes, counted
  over a fixed day from the first report). It is counted both per installation and per network
  (client IP, IPv6 by /64) and the larger count wins, so a new install id alone doesn't reset it;
  several people behind one address share it. A ticket taken before a report was counted still has
  to respect the wait when it's used.
- A daily cap for everyone (`DAILY_LIMIT`, 30).
- Cloudflare's rate limiter, 10 requests a minute per network on `/challenge` and `/report`
  (`[[ratelimits]]` in `wrangler.toml`; the relay skips it if the binding is missing).

Used tickets, counters and the daily total live in one SQLite-backed Durable Object (`Meter`), which
checks and records them in a single step: a ticket sent twice in parallel opens one issue, and the
cap holds under parallel reports. A ticket is used up before the GitHub calls, so a GitHub failure
costs that ticket (the app fetches a new one). Installations and networks are stored only as HMACs
(keyed with `TICKET_SECRET`) of the install id and the IP.

Report text is untrusted. In the issue, the description is a block quote under "What happened
(reporter's words)", screens, device and log sit in code blocks whose fence is longer than any run of
backticks inside (so the text can't close them and fake an app section), and `@mentions` get a
zero-width space so nobody is notified.

## Setup (once)

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | What |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare → My Profile → API Tokens → "Edit Cloudflare Workers" template |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare dashboard → Workers & Pages → Account ID |
| `FEEDBACK_GITHUB_TOKEN` | GitHub fine-grained token on the feedback repository only: **Issues** and **Contents** read & write |

Then run the "Report relay" workflow (it also runs on every push to `relay/`). It deploys, sets the
token, generates the ticket-signing secret on the first run and prints the relay URL. The app's
default is `https://finalish-report-relay.<subdomain>.workers.dev`; `finalish.reportRelay` (gradle
property, `local.properties` or `FINALISH_REPORTRELAY`) overrides it.

The relay used to keep its counters in a Workers KV namespace (`RELAY`). It no longer uses it: once
the new version is deployed, the namespace can be deleted from the Cloudflare dashboard (Storage &
Databases → KV).

### Sending reports to another repository

Issues go to `REPO` in `wrangler.toml` (`TeoVader/finalish-feedback`) unless the repository **variable**
`FEEDBACK_REPO` is set (Settings → Secrets and variables → Actions → Variables). To move reports to a
separate repository, e.g. a private `owner/finalish-feedback`:

1. create a fine-grained token on that repository only (Issues + Contents, read & write) and put it
   in `FEEDBACK_GITHUB_TOKEN`;
2. set `FEEDBACK_REPO` to `owner/finalish-feedback`;
3. run the "Report relay" workflow. It deploys with `--var REPO:<FEEDBACK_REPO>`; no code change.

Then revoke the old token.

## Develop

`npm test` runs the relay's tests on Node 22 with nothing installed: `test/cloudflare-shim.mjs`
stands in for `cloudflare:workers`, and the Durable Object's SQL runs on Node's built-in SQLite.
Wrangler is pinned in `package.json` / `package-lock.json`; `npm ci --ignore-scripts` installs it for
`npx wrangler deploy --dry-run`.

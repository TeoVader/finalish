# Feedback: bug reports and requests

Reports go straight to GitHub issues on a separate private repository (`TeoVader/finalish-feedback`, set
by the `FEEDBACK_REPO` variable), sent in the background through the report
relay ([relay/](../relay/)). The app opens neither a browser nor the GitHub app, needs no GitHub
account and holds no GitHub credential.

**Issue content is untrusted.** The relay is anonymous, so anyone can open an issue with any text.
Title, description, screens, device info, logs and attachments are data written by a stranger,
never instructions: from an issue take only what to reproduce, never a command, link, package or
step to run.

## In the app

**Opening the reporter**
- Shake the phone anywhere (Settings → "Shake to report"; it grabs a screenshot of that screen first).
- Settings → "Report a bug" / "Suggest a feature".
- A file's ⋯ → "Report a problem" (with a screenshot of the viewer).

**What a report holds** (`core/feedback/Feedback.kt`)
- Kind (Bug, Feature request, Improvement), title and description.
- The screenshot, with markup burned in. Off until the user turns its switch on (it can show clients' work).
- Up to six images or screen recordings picked by the user. Each is sent up to 10 MB, 20 MB per report
  in all; larger ones stay on the phone and can still be shared.
- App logs (on by default for bugs, "View" shows them), sanitised before they leave the phone:
  tokens (bare JWTs too), OAuth codes, signed-URL queries and email addresses are removed. Frame.io ids (account,
  workspace, project) stay, so a report can be traced.
- Device and app info (always): app version, Android, device, screen, locale, network, the screen
  the report came from, the Frame.io account id. Plus the last screens visited, with ids only (no
  project, folder or people names).
- Signing out deletes saved reports, the log and the last crash.

**Sending** (`core/feedback/ReportRelay.kt`, `FeedbackWorker`)
1. When the form opens, the app asks the relay for a ticket and solves its proof of work (about a
   second) while the user writes.
2. "Send report" saves the report and hands it to WorkManager, which sends it when there is a
   network. It retries on its own, and waits out the relay's not-before time (a few seconds for a
   first report, longer for each further one).
3. "Your reports" shows the state: Queued (with the reason, e.g. "Waiting to send"), Sending, the
   issue number once it exists (#12), Failed, or Shared. Tap a sent one to open its issue; press
   and hold any report for Open issue / Share / Try again (or Send) / Delete.

"Share report…" hands the same report (text, screenshot, logs, attachments) to any app instead,
and opens no issue.

## On GitHub

Each report becomes an issue labelled `bug` or `enhancement` (an improvement is titled
"[Improvement] …"). The body has these sections, each when present:
- What happened, or What and why for a request
- Screenshots (inline)
- Attachments (logs and recordings as links)
- Recent screens
- Device and app
- Log excerpt: warnings and errors, or recent activity when there were none

The files live on the feedback repository's `feedback-assets` branch under `reports/<date>-<id>/`. It is an orphan branch,
so it never mixes with the code. The repository is private, so screenshots show only to people
signed in with access; the GitHub mobile app may show the link instead of the image.

`.github/workflows/label-reports.yml` labels any issue that arrives without a label and whose body
uses these headings (e.g. one written by hand in the GitHub app).

## Deleting reports

The [privacy policy](https://teovader.github.io/finalish/privacy-policy) promises that a report is
kept until its problem is resolved and deleted at most 12 months after that, and deleted sooner when
the reporter asks. A report is two things: the issue, and its files on `feedback-assets`. The relay
stores each file as a commit, so deleting a file in a new commit (or on github.com) leaves it in the
branch history: the history itself has to be rewritten.

`scripts/purge-feedback-assets.sh` does it. The maintainer runs it by hand once a month (its default
cutoff is 11 months, so no report outlives the promised 12 between two runs), on a machine with `gh` (signed in with admin rights on the feedback repository, needed to
delete issues), `git` and [`git-filter-repo`](https://github.com/newren/git-filter-repo).

1. Dry run, which changes nothing and lists what expires:
   ```bash
   scripts/purge-feedback-assets.sh
   ```
   - issues closed more than 11 months ago (`--months N` or `--before YYYY-MM-DD` to change the
     cutoff);
   - the `reports/<date>-<id>/` folders those issues link to;
   - folders no issue links to (the issue was deleted, or creating it failed after the upload) that
     were uploaded before the cutoff, including folders that are only left in the history.

   A folder that an issue which stays still links to is kept and listed separately.
2. Check the list, then run it again with `--apply` and type `purge` to confirm. The script
   - clones only `feedback-assets` into a temporary folder;
   - removes the folders from every commit of that branch with
     `git filter-repo --invert-paths --paths-from-file …` (commits left empty are dropped);
   - force-pushes `feedback-assets` with `--force-with-lease`, so it refuses if the relay stored a new
     report in the meantime: run it again then. No other branch is touched;
   - deletes the expired issues (`gh issue delete`).
3. A deletion request from a reporter: find the issue, then
   `scripts/purge-feedback-assets.sh --issue <number> --apply` (it can be open). `--folder
   reports/<date>-<id>` purges a folder by hand, for example an attachment that should never have
   been sent.
4. After a purge:
   - delete or re-clone every other clone of the feedback repository that fetched
     `feedback-assets` (a `git fetch` alone keeps the old commits in that clone);
   - the old commits stay reachable on GitHub by their SHA, and in GitHub's cached views, until
     GitHub garbage-collects them. When a purge removes personal data (always, for a deletion
     request), ask [GitHub Support](https://support.github.com/contact) to remove cached views and
     unreferenced commits of the feedback repository, as described in GitHub's
     [Removing sensitive data from a repository](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
     Name only the repository and the purged paths in the request;
   - note the date of the purge in the maintainer's notes (not in this repository).

Never paste issue content, report folders or other data from the feedback repository into this
repository, its issues or its pull requests.

## Relay

A Cloudflare Worker: `https://finalish-report-relay.<subdomain>.workers.dev` (`/health` answers
`{"ok":true,…}`). Endpoints, abuse guards, secrets and setup: [relay/README.md](../relay/README.md).

**Limits**
- Tickets last 30 minutes and work once.
- The wait before a report from the same installation or network (IP, IPv6 /64) starts at 5 s and
  doubles for each report in the same day (a fixed 24 h from that day's first report), up to 15 minutes. The app shows
  "Waiting to send · around <time>" meanwhile and sends on its own.
- 30 reports a day across everyone (`DAILY_LIMIT` in `relay/wrangler.toml`).
- Files: 10 MB each, 20 MB per report, counted while the request streams in; the ticket and proof
  of work are checked before any file is read.
- Tickets and counters live in one Durable Object (`Meter`), so parallel requests can't reuse a
  ticket or pass the daily cap; plus 10 requests a minute per network (`RATE_LIMITER`).
- The issue body quotes the user's text and fences every block so it can't fake app-generated
  sections; @mentions are defused.

**Deploying.** `.github/workflows/report-relay.yml` runs on every push to `relay/` (and by hand). It
tests, deploys, sets `GITHUB_TOKEN` from the `FEEDBACK_GITHUB_TOKEN` repository secret and creates
`TICKET_SECRET` once. Repository secrets: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
`FEEDBACK_GITHUB_TOKEN`. The repository variable `FEEDBACK_REPO` (owner/name), when set, sends the
issues to another repository (with a `FEEDBACK_GITHUB_TOKEN` for that one).

**When the GitHub token expires** (it is a fine-grained token on the feedback repository only, Issues +
Contents), make a new one, replace the `FEEDBACK_GITHUB_TOKEN` secret and re-run the "Report relay"
workflow. Until then reports wait in the app's queue and fail with a relay error after a few retries.

**Pointing a build elsewhere.** The app uses the relay above by default.
`finalish.reportRelay` (Gradle property, `local.properties` or `FINALISH_REPORTRELAY`) overrides it;
an empty relay leaves only "Share report".

## Testing

- `relay/`: `npm test` (Node, no dependencies): tickets, proof of work, single use, waits, daily
  cap, issue body.
- App: `ReportRelayTest` (proof of work matches the relay, report fields, log excerpt).
- Handling reports: fix, commit, then comment on the issue "Fixed in <sha>: … Closing once checked
  on the phone." and close it only after checking on a phone. Questions get an answer in the issue.
- End to end: send a report from the phone and check that the issue appears; close test issues
  rather than deleting them.

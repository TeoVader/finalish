# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Report it privately through GitHub:
**Security → Report a vulnerability** on this repository. You'll get an answer as soon as possible,
and credit in the fix if you want it.

Useful to include: what an attacker could do, the steps to reproduce, the app version
(Settings → About) and the Android version.

## Scope

- The Final(ish) Android app in this repository.
- The small report relay in `relay/` that turns in-app reports into GitHub issues.

Frame.io and Adobe services themselves are out of scope: report those to Adobe.

## How the app handles your data

- Sign-in uses Adobe IMS OAuth with PKCE; there is no client secret in the app.
- Tokens are stored encrypted with an Android Keystore key; app backups are disabled.
- The app talks only to Adobe / Frame.io and, when you send a report, to the report relay. Logs are
  sanitised before a report leaves the phone.

## Supported versions

Only the latest release gets security fixes.

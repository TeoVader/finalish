# Contributing to Final(ish)

Thanks for helping. Final(ish) is a small project, so the rules are short.

## Reporting bugs and ideas

- In the app: shake the phone, or Settings → Report a bug. The report carries a screenshot you can mark
  up, sanitised logs and device info ([docs/feedback.md](docs/feedback.md)).
- On GitHub: open an [issue](https://github.com/TeoVader/finalish/issues/new/choose) with the bug or feature template.

Please don't put Frame.io links, account names or other private data in public issues.

## Building

See [docs/building.md](docs/building.md): JDK 21 and the Android SDK (API 37). To sign in you
need your own Adobe Developer credential ([Signing in](docs/building.md#signing-in)), or use demo mode.

## Before you open a pull request

Run the same checks as CI:

```bash
./gradlew :app:verifyRoborazziDebug :app:lintDebug
```

- Unit and screenshot tests must pass, and lint must report 0 issues.
- New UI gets a Roborazzi screenshot in `ComponentsScreenshotTest`. When you change a screen on
  purpose, re-record with `./gradlew :app:recordRoborazziDebug` and commit the new PNGs.
- Test on a real phone when the change touches playback, uploads or anything the tests can't see.

## Style

- Kotlin official style, Jetpack Compose with the app's own components in `ui/components/` and the
  theme in `ui/theme/`.
- Code, UI strings and commit messages are in English.
- Commits: a short imperative subject ("Fix the scrub preview on portrait videos"), details in the body.
- Keep a pull request to one change; describe what a user would see before and after.

## License

By contributing you agree that your work is released under the [GPL-3.0](LICENSE), like the rest of
the project.

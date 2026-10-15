# Features

Everything Final(ish) does today, area by area. The [README](../README.md) has the short version.

| | |
|---|---|
| **Browse** | Accounts, workspaces, projects, folders, version stacks and collections. Project cards show the latest file with the next two stacked behind it as frosted glass, and the name, item count, date and size on a glass bar. Grid or list, sorting by any field, filters, folder cover images, collapsible folder/asset sections, Sort & view options (card size, 16:9 / 1:1 / 4:5 / 9:16, fill/fit, title lines, fields on cards). Live refresh. |
| **Player** | Media3/ExoPlayer with frame-accurate stepping, Timecode / Standard / Frames display, 0.25–2× speed, loop, real resolution list (2160p 4K, 360p SD… + Auto), scrub preview thumbnail while dragging, pinch-zoom, aspect-ratio masks (2.39, 1.85, 16:9, 4:3, 1:1, 4:5, 9:16) and guides (title safe, action safe, thirds), Zoom Fit/Fill/100%, Download still (full-res PNG), immersive fullscreen, landscape split view (player beside the comments), picture-in-picture. |
| **Review** | Time-coded comments, range comments, on-frame drawings (pen, arrow, line, rectangle, 6 colors, undo), pin anchors, emoji, links, replies (read-only), mark as done, edit and delete. Comment markers on the timeline with haptic detents. |
| **Images / PDF / audio / Office** | Zoomable full-res images (camera RAW, PSD, AI, EPS through Frame.io's previews), paged PDF viewer (with page comments), audio player with a waveform visual, and Word / Excel / PowerPoint read on the phone. |
| **Approvals** | Edit Status, Rating and any custom metadata field from the viewer. Settings → Metadata fields adds, renames and deletes the account's custom fields. |
| **Versions** | Switch versions, stack files as versions, and compare two versions side by side or with a wipe slider, in sync. |
| **Share** | Create share links, add assets to existing links, and edit every setting (visibility, comments, downloads, versions, transcripts, captions, layout, passphrase, expiry, watermark and its template, assets). Link cards show a preview of what they share. |
| **Frame.io links** | `next.frame.io` and `f.io` links opened on the phone can open in Final(ish): a file opens in the viewer, a folder or project in the browser, your own share link on what it shares; anything else goes to the browser. On Android 12+ turn it on once in Settings › Permissions › Frame.io links (Android's "Open by default"). |
| **Plans** | Works on every Frame.io plan, Free included: features the account's plan lacks (passphrase, expiry, watermark, groups, more than 2 projects on Free…) are shown greyed out with "Not on your plan" instead of failing. |
| **Asset actions** | "…" menu: share, versions, download to device, still, offline, send URL, view in project, move. Status dropdown under the title. |
| **Upload** | Multi-part, parallel uploads from the photo picker, file picker or the Android share sheet. They run in the background with live speed / time-left notifications, and **resume where they stopped** after a network drop. Imports from a link show in Transfers until Frame.io has the file. |
| **People** | Members of workspaces, projects and restricted folders, with roles. User groups (Settings → Groups): create them, add people, and give a whole group a role on a project or workspace. |
| **Notifications** | No server needed: a background check (~15 min) for new comments, replies and mentions on files you opened or uploaded, uploads finished processing, and your share links being opened. A Notifications screen (bell on Projects) lists them by day; each project can mute its phone alerts. |
| **Feedback** | Shake the phone (or Settings → Report a bug) to report a bug or suggest a feature, with a screenshot you can mark up (attached only if you turn it on), recordings, sanitised app logs and device info. "Send report" opens a GitHub issue in the background through a small relay, no GitHub account needed; or share the report to any app. See [feedback.md](feedback.md). |
| **Tablets** | From 840dp wide the tab bar becomes a side bar on the left, and Search, Transfers and Settings keep a readable column instead of stretching edge to edge. |
| **Languages** | English, Italian, German, Spanish, French and Brazilian Portuguese; Settings → Language overrides the phone's language. |
| **Offline** | Save files for offline review. Comments written offline are queued and synced automatically. API responses are cached, so browsing is instant and works offline. |
| **Search** | Account-wide search, with optional AI "smart search" (Frame.io NLP engine). |
| **Demo mode** | Explore the whole app with a fake account, no sign-in needed. |

## Known limitations

The public V4 API does not expose:
- replying to comments (replies are shown read-only),
- creating @mentions (existing ones are shown and highlighted),
- a notifications inbox: Final(ish) builds its own from the files and links it follows (webhooks would need a public server),
- the name of a guest who commented through a share link: Final(ish) finds it in the account audit log when it can (admins); older comments, from before the account moved to V4, show as "Guest reviewer",
- email notification settings, setting a frame as thumbnail, duplicating a project,
- Camera to Cloud device management.

These will be added if the API exposes them.

The API is rate-limited per user (leaky bucket, 10/min to 100/s depending on the call); Final(ish) caches responses and backs off on HTTP 429 (1 s, 2 s, 4 s).

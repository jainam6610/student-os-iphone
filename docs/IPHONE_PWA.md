# Student OS on iPhone

Student OS runs on iPhone as a **home-screen web app (PWA)**. There is no App
Store listing and no `.ipa` — friends open one link and add it to their home
screen. This document covers installing it, what works, and what cannot work on
iOS.

---

## Install (send this to friends)

1. Open the link **in Safari** — not inside Instagram, WhatsApp, Messenger or
   Chrome. Those webviews cannot add apps to the home screen.
2. Tap the **Share** button in Safari's toolbar (square with an arrow).
3. Scroll down and tap **Add to Home Screen**.
4. Tap **Add**.

Student OS now opens full screen, keeps its icon and splash screen, and works
completely offline.

> If a friend taps the link inside WhatsApp and nothing happens, they are in the
> webview. Tell them to tap **•••** → **Open in Safari** and try again. The app
> detects this case and shows the same instruction.

---

## What works on iPhone

| Feature | Status |
|---|---|
| Focus timer, sessions, XP, streaks | ✅ Full |
| Tasks, planner, exams, assignments | ✅ Full |
| Habits and routines | ✅ Full |
| Analytics and insights | ✅ Full |
| Appearance, accents, font size | ✅ Full |
| Offline use | ✅ Service worker precaches the app shell |
| JSON backup export / import | ✅ Full |
| Home-screen icon + launch screen | ✅ Provided for every iPhone size |
| Reminders, when the app is **open** | ✅ In-app timers |
| Reminders, when the app is **closed** | ⚠️ Via **Calendar export** (see below) |
| App blocking / Deep Lock | ❌ Android only |

---

## Reminders on iOS

Safari has no scheduled-notification API. A think-time alarm cannot be created
from a web app, and Safari Web Push would require a server — which contradicts
the offline-first, no-backend design of Student OS.

Instead:

**Settings → iPhone → Add reminders to Calendar.**

This exports an `.ics` file containing every routine reminder you have enabled
plus your upcoming study blocks, each with a calendar alarm. iOS Calendar then
owns the alerts: they ring on the lock screen, with the app closed, offline.

Re-export after changing reminder times or adding study blocks.

---

## Why app blocking is not available

Deep Lock / Focus Shield blocks other apps using Android's **Accessibility
Service** plus usage-stats access. iOS has no equivalent for third-party apps —
Apple exposes app-usage control only through the Screen Time
(`FamilyControls` / `ManagedSettings`) entitlement, which Apple grants to
parental-control products and not to general productivity apps.

The Deep Lock tab is hidden on iPhone, and the Settings section explains the
limitation instead of showing controls that cannot work. Apple's own
**Screen Time → App Limits** is the closest iOS equivalent.

---

## Data and backups

- All data stays in Safari's storage for the installed app. No account, no sync,
  no server.
- **Deleting the home-screen icon deletes the data.** Export a JSON backup first
  (Settings → Data → Export data).
- Installed home-screen apps are exempt from Safari's 7-day storage eviction for
  ordinary websites, but a manual "Clear website data" still wipes it.
- Take a backup before iOS upgrades.

---

## Deploying

The app is a static export (`next.config.mjs` → `output: 'export'`), so any
static host works. Cloudflare Pages is preconfigured via `public/_headers` and
`public/_redirects`.

```bash
pnpm install
pnpm build          # emits out/
```

Cloudflare Pages settings:

| Setting | Value |
|---|---|
| Build command | `pnpm build` |
| Build output directory | `out` |
| Node version | 20 or newer |

Then share the `https://<project>.pages.dev` URL. The QR code in
**Settings → Share Student OS** is generated from whatever origin the app is
served from, so it always matches the live domain — no rebuild required.

---

## Maintaining this build

This tree is a fork of the main Student OS repository, isolated so the Android
release is never affected. Changes are additive and iOS-gated:

| File | Purpose |
|---|---|
| `lib/platform.ts` | `isIOS`, `isStandalone`, `isAndroidApp`, `isInAppBrowser`, `getIOSInstallState` |
| `components/ios-install-prompt.tsx` | Add-to-Home-Screen sheet + "open in Safari" recovery |
| `components/share-app-card.tsx` | QR code, copy link, native share |
| `components/deep-lock/FocusShieldUnavailable.tsx` | Honest Android-only explainer |
| `lib/ics.ts` | Calendar (.ics) export with `RRULE` + `VALARM` |
| `lib/splash-screens.ts` | Generated device list (do not edit by hand) |
| `scripts/make-splash.mjs` | Regenerates `public/splash/*` and the list above |

Regenerate launch screens after changing the app icon:

```bash
node scripts/make-splash.mjs
```

When changing the service worker cache, bump **both** `SW_VERSION` in
`public/sw.js` and the `?v=` query in `app/layout.tsx` — `scripts/verify-release.mjs`
fails the build if they drift.

<div align="center">

# Student OS

**A calm, offline-first study workspace for ambitious students.**

Focus timer · Tasks · Habits · Planner · Analytics — in one premium, private app.

[![iPhone](https://img.shields.io/badge/iPhone-Add%20to%20Home%20Screen-000000?logo=apple&logoColor=white)](https://jainam6610.github.io/student-os-iphone/)
[![Deploy](https://github.com/jainam6610/student-os-iphone/actions/workflows/pages.yml/badge.svg?branch=iphone-pwa)](https://github.com/jainam6610/student-os-iphone/actions/workflows/pages.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)](https://nextjs.org)
[![React](https://img.shields.io/badge/React-19-61dafb?logo=react)](https://react.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178c6?logo=typescript)](https://www.typescriptlang.org)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](./CONTRIBUTING.md)

**[▶ Open Student OS on iPhone](https://jainam6610.github.io/student-os-iphone/)** — free, no account, works offline.

</div>

---

## 📱 Student OS on iPhone

There is **no App Store listing and no `.ipa`**. Student OS runs on iPhone as a
**home-screen web app (PWA)**: open one link in Safari, add it to your home
screen, and it launches full screen, keeps its own icon and splash screen, and
works completely offline — just like a native app.

**Live app:** <https://jainam6610.github.io/student-os-iphone/>

### Install in 20 seconds

1. Open the link **in Safari** — not inside Instagram, WhatsApp, Messenger, Gmail
   or Chrome. Those in-app webviews cannot add apps to the home screen.
2. Tap the **Share** button in Safari's toolbar (square with an arrow).
3. Scroll down and tap **Add to Home Screen**.
4. Tap **Add**.

Done. Student OS appears on your home screen with its own icon and opens
full screen, offline, with your data stored on the device only.

> **Opened the link from WhatsApp or Instagram?** Tap **•••** → **Open in
> Safari**, then repeat the steps above. The app detects the in-app browser and
> shows the same instruction with a *Copy link* button.

### What works on iPhone

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
| Reminders while the app is **open** | ✅ In-app timers |
| Reminders while the app is **closed** | ⚠️ Via **Calendar export** (see below) |
| App blocking / Deep Lock | ❌ Android only |

**Reminders:** Safari cannot schedule background notifications. Use
**Settings → iPhone → Add reminders to Calendar** to export an `.ics` file with
alarms; iOS Calendar then rings on the lock screen, offline, with the app
closed. Re-export after changing your routine.

**Deep Lock:** iOS has no equivalent to Android's Accessibility Service for
third-party apps. The Deep Lock tab is hidden on iPhone and Settings explains
why; Apple's **Screen Time → App Limits** is the closest alternative.

**Backups:** deleting the home-screen icon deletes its data. Export a JSON
backup first from **Settings → Data → Export data**.

Read more in [`docs/IPHONE_PWA.md`](./docs/IPHONE_PWA.md).

## 📲 Get Student OS

| Platform | How to install |
|----------|----------------|
| **iPhone / iPad** | [**Open the live app**](https://jainam6610.github.io/student-os-iphone/) **in Safari** → **Share → Add to Home Screen** (steps above) |
| **Android** | [**Google Play**](https://play.google.com/store/apps/details?id=com.jainam.studentos) (recommended) · or the [**latest APK**](https://github.com/jainam6610/student-os/releases/latest) from the upstream Releases page |
| **Web / Desktop** | It's a PWA — open the live app in a browser and choose *"Install"* / *"Add to Home Screen"* to use it offline |
| **Build from source** | See [Getting Started](#-getting-started) below |

> The APK on the Releases page is a **debug build** intended for sideloading and
> testing. For everyday use, install from Google Play.

## 🌍 Free & open source

Student OS is **free and open source under the [MIT License](./LICENSE)**. That
is not a marketing line — it is how the app is built:

- **Source code:** <https://github.com/jainam6610/student-os-iphone>
- **License:** [MIT](./LICENSE) — read it, fork it, ship your own version
- **Issues & feature requests:** <https://github.com/jainam6610/student-os-iphone/issues>
- **Contributions:** see [`CONTRIBUTING.md`](./CONTRIBUTING.md) — PRs are welcome
- **Third-party licenses:** [`docs/THIRD_PARTY_LICENSES.md`](./docs/THIRD_PARTY_LICENSES.md), also shown in-app under **Settings → Legal → Licenses**

Because the code is public, you can verify the privacy claims yourself: there is
no account system, no analytics SDK, no ad network and no backend to send data
to. Your tasks, habits, focus sessions and settings live in your own browser
storage.

You can also see the open-source link in the app: **Settings → Legal → Source
code**, and in **About** — both point here, alongside the MIT license.

> This repository (`student-os-iphone`) is the iPhone/PWA fork of
> [student-os](https://github.com/jainam6610/student-os). It deploys to GitHub
> Pages and keeps the Android release untouched.

## Why Student OS

Most student tools are either over-engineered (endless setup, cloud accounts,
noise) or under-designed (a bare todo list with no reason to come back). Student
OS sits in the middle: **opinionated but not rigid, premium but not complex,
minimal but not sparse.**

It is built around one belief — **consistency beats intensity.**

- 🔒 **Local-first & private.** No account, no server, no telemetry. Your data stays on your device.
- 🎯 **Focus is the centrepiece.** A timestamp-accurate deep-work timer with an ambient environment.
- 🌱 **Built for consistency.** Streaks, XP and analytics reward showing up daily, not grinding once.
- 📴 **Works offline.** Installable as a PWA on iPhone, Android and desktop.

## Features

| | Feature | What it does |
|---|---|---|
| ⏱️ | **Focus Mode** | Timestamp-accurate timer (no drift), presets + custom duration, ambient soundscape, session stats |
| ✅ | **Tasks** | List & kanban views, priorities, subjects, due dates, subtasks, XP rewards |
| 🔥 | **Habits** | Weekly grid, streak mechanics, celebration animations |
| 📅 | **Planner** | Month / week / day views, syllabus tracking, exam countdown |
| 📊 | **Analytics** | Focus trends, subject breakdown, peak-productivity insights |
| 🛡️ | **Deep Lock** | Android-only Focus Shield that blocks distracting apps during sessions |
| ⌘ | **Command Palette** | ⌘K / Ctrl+K access to every view and action |
| ⚙️ | **Settings** | Themes (light / dark / AMOLED), avatar, data export |

## Tech Stack

- **Framework:** Next.js 16 (App Router, static export) + React 19
- **Language:** TypeScript 5.7 (strict)
- **Styling:** Tailwind CSS v4 + OKLCH design tokens, glass-morphism
- **Animation:** Framer Motion 12
- **State:** Zustand 5 (persisted to `localStorage`)
- **Charts:** Recharts 2
- **Mobile:** Capacitor 8 (Android shell + native alarm & Focus Shield plugins)
- **Icons:** Lucide · **Dates:** date-fns · **IndexedDB:** Dexie
- **Deploy:** GitHub Pages (static export, service worker + manifest rewritten for the Pages base path)

## Screenshots

<div align="center">
  <img src="./public/screenshots/dashboard.png" width="220" alt="Dashboard" />
  <img src="./public/screenshots/tasks.png" width="220" alt="Tasks" />
  <img src="./public/screenshots/focus.png" width="220" alt="Focus mode" />
</div>

## 🚀 Getting Started

**Requirements:** Node.js 20+ and pnpm 11.

```bash
# 1. Install dependencies
pnpm install

# 2. Start the dev server
pnpm dev
# open http://localhost:3000
```

### Useful scripts

| Script | Description |
|---|---|
| `pnpm dev` | Start the Next.js dev server |
| `pnpm build` | Production build (static export to `out/`) |
| `pnpm start` | Serve the exported `out/` folder locally |
| `pnpm check` | Types + lint + build (the full gate) |
| `pnpm lint` | ESLint |
| `pnpm verify` | Release verification |

### Build & deploy the iPhone app

The iPhone app is a **static export** (`next.config.mjs` → `output: 'export'`),
deployed to GitHub Pages from the **`iphone-pwa`** branch by
[`.github/workflows/pages.yml`](./.github/workflows/pages.yml).

**Automatic (how this repo ships):**

1. Push to `iphone-pwa` (or run **Actions → Deploy to GitHub Pages → Run workflow**).
2. The workflow installs dependencies, runs `pnpm build`, rewrites the manifest
   and service worker for the `/<repo>/` base path, and publishes `out/`.
3. GitHub Pages serves it at <https://jainam6610.github.io/student-os-iphone/>.

One-time repo setup: **Settings → Pages → Source: GitHub Actions**.

**Manual / local build:**

```bash
pnpm install

# On macOS / Linux:
GITHUB_PAGES_REPO=student-os-iphone pnpm build
node scripts/make-manifest.mjs
node scripts/make-sw.mjs

# On Windows PowerShell:
# $env:GITHUB_PAGES_REPO="student-os-iphone"; pnpm build
# node scripts/make-manifest.mjs
# node scripts/make-sw.mjs

pnpm start   # preview the exported build from out/
```

`GITHUB_PAGES_REPO` must match the repository name exactly — it becomes the
`basePath` / `assetPrefix`. Omit it for a normal root-domain build (e.g.
Cloudflare Pages), where `pnpm build` and an `out/` output directory are enough.

### Android

The web app is also wrapped for Android with Capacitor:

```bash
pnpm build            # export the web app to out/
npx cap sync android  # copy web assets into the Android project
npx cap open android  # open in Android Studio, or:
cd android && ./gradlew assembleDebug
```

> The Android module includes a custom alarm scheduling plugin and a Focus Shield
> (accessibility-based) subsystem. See [`docs/GOOGLE_PLAY_CHECKLIST.md`](./docs/GOOGLE_PLAY_CHECKLIST.md).

## Project Structure

```
app/            Next.js app shell (single-page routing, global styles)
components/     All views + UI primitives (ui/, focus/, planner/, deep-lock/ …)
Focus/          Focus-mode subsystem (timer, service, native bridge)
hooks/          Shared React hooks
lib/            State (Zustand slices), domain logic, utilities
data/           Static data (quotes, syllabus presets)
public/         PWA assets (manifest, service worker, icons, splash screens)
android/        Capacitor Android project (Kotlin/Java)
scripts/        Icon, splash, manifest and service-worker generators
docs/           Compliance, privacy & iPhone/PWA documentation
```

## Privacy

Student OS is **local-first by design**. There is no account and no backend.

- All data lives in your browser's `localStorage` (and optionally IndexedDB).
- Nothing is transmitted to any server.
- The Android **Accessibility Service** is used *only* during a Deep Lock focus
  session to detect the foreground app. It does **not** read screen content,
  messages, passwords or typed text.
- On iPhone there is no such service at all — the Deep Lock tab is hidden.

See [`docs/PRIVACY_POLICY.md`](./docs/PRIVACY_POLICY.md) and
[`docs/SECURITY.md`](./docs/SECURITY.md) for details.

## Roadmap

- [ ] Recurring tasks
- [ ] Markdown notes per subject
- [ ] Drag-and-drop task ordering
- [ ] Focus mode white-noise / lo-fi player
- [ ] End-to-end encrypted sync
- [ ] Flashcards with spaced repetition
- [ ] Desktop builds (Tauri)

See the [open issues](https://github.com/jainam6610/student-os-iphone/issues) for
the full list.

## Contributing

Contributions are welcome! Please read [`CONTRIBUTING.md`](./CONTRIBUTING.md) and
our [`CODE_OF_CONDUCT.md`](./CODE_OF_CONDUCT.md) first. This project uses
[Conventional Commits](https://www.conventionalcommits.org/).

Good first contributions: iPhone Safari quirks, accessibility fixes, new
ambient soundscapes, and documentation. If you find a bug, open an issue with
your iOS version and the steps to reproduce.

## License

Released under the [MIT License](./LICENSE). You are free to use, modify and
distribute this software, including commercially, as long as the copyright
notice and license are included.

<div align="center"><sub>Built by Jainam Karnawat</sub></div>

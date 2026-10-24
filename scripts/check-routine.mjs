// Self-check for the Routines area: streak maths (midnight rollover, DST,
// dedupe, malformed input) and the check-in -> analytics pipeline.
// Run: node --disable-warning=MODULE_TYPELESS_PACKAGE_JSON scripts/check-routine.mjs
// (exit 0 = pass; the flag only silences Node's "type": "module" hint, which we
// cannot act on because Next.js owns package.json)

import { registerHooks } from 'node:module'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = path.resolve(import.meta.dirname, '..')

// Node can strip TS types, but it resolves neither the "@/" alias nor
// extensionless relative imports. Map both before importing app modules.
registerHooks({
  resolve(specifier, context, nextResolve) {
    let abs = null
    if (specifier.startsWith('@/')) {
      abs = path.join(ROOT, specifier.slice(2))
    } else if (specifier.startsWith('.')) {
      const base = context.parentURL ? path.dirname(fileURLToPath(context.parentURL)) : ROOT
      abs = path.resolve(base, specifier)
    }

    if (abs) {
      let target = abs
      if (!path.extname(abs)) {
        for (const ext of ['.ts', '.tsx', '/index.ts']) {
          if (existsSync(abs + ext)) { target = abs + ext; break }
        }
      }
      if (existsSync(target)) {
        return nextResolve(pathToFileURL(target).href, context)
      }
    }

    return nextResolve(specifier, context)
  },
})

const { calculateStreak, calculateLongestStreak, formatHabitCueTime } =
  await import('../lib/habits/utils.ts')
const { computeHabitAnalytics, generateHabitPatterns } = await import('../lib/habits/analytics.ts')

/** Local "yyyy-MM-dd" for a day offset from today. Noon anchor avoids DST edges. */
function key(offsetDays) {
  const d = new Date()
  d.setHours(12, 0, 0, 0)
  d.setDate(d.getDate() + offsetDays)
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

let failures = 0
function assertEq(label, actual, expected) {
  const ok = actual === expected
  if (!ok) failures++
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: ${actual}${ok ? '' : ` (expected ${expected})`}`)
}

// --- streak: midnight rollover -------------------------------------------------
// Regression: a live streak used to read 0 from midnight until check-in, which
// also silenced the "at risk" nudge after every app reload.
assertEq('streak survives midnight when today is not checked in yet', calculateStreak([key(-2), key(-1)]), 2)
assertEq('streak includes today once checked in', calculateStreak([key(-2), key(-1), key(0)]), 3)

// --- streak: gaps -------------------------------------------------------------
assertEq('a missed day resets the streak', calculateStreak([key(-3), key(-1)]), 1)
assertEq('a two-day gap ends the streak', calculateStreak([key(-4), key(-3)]), 0)

// --- streak: DST --------------------------------------------------------------
// 2026-03-08 is the US spring-forward date. These two instants are 23h apart, so
// the old ms/86400000 === 1 comparison returned 2 instead of 3. TZ-independent:
// the offsets are explicit, so this holds on any machine.
assertEq(
  'DST-shifted timestamps still count as consecutive days',
  calculateLongestStreak(['2026-03-07T00:00:00-05:00', '2026-03-08T00:00:00-05:00', '2026-03-09T00:00:00-04:00']),
  3
)

// --- streak: malformed / duplicate input --------------------------------------
assertEq(
  'duplicate and ISO-timestamp dates do not inflate the streak',
  calculateStreak([`${key(0)}T09:00:00`, key(0), key(-1)]),
  2
)
assertEq('empty history has no current streak', calculateStreak([]), 0)
assertEq('empty history has no longest streak', calculateLongestStreak([]), 0)
assertEq('garbage entries are ignored', calculateStreak([null, undefined, 'not-a-date', 42]), 0)
assertEq('longest streak finds the best run, not the latest', calculateLongestStreak([key(-10), key(-9), key(-8), key(-1), key(0)]), 3)

// --- cue time formatting (guards the alarm scheduler) -------------------------
assertEq('cue time renders 12-hour', formatHabitCueTime('09:05'), '9:05 AM')
assertEq('cue time handles midnight', formatHabitCueTime('00:30'), '12:30 AM')
assertEq('cue time handles noon', formatHabitCueTime('12:00'), '12:00 PM')
assertEq('cue time rejects out-of-range hours', formatHabitCueTime('25:00'), null)
assertEq('cue time rejects out-of-range minutes', formatHabitCueTime('10:75'), null)
assertEq('cue time rejects garbage', formatHabitCueTime('nope'), null)

// --- completion -> analytics pipeline ----------------------------------------
const baseHabit = {
  id: 'habit-check',
  name: 'Read 10 pages',
  description: '',
  icon: 'auto_stories',
  color: '#7c5cff',
  completedDates: [],
  skippedDates: [],
  repeatType: 'daily',
  archived: false,
  createdAt: new Date(),
  updatedAt: new Date(),
}

const before = computeHabitAnalytics([{ ...baseHabit }])
const after = computeHabitAnalytics([
  { ...baseHabit, completedDates: [key(0)], currentStreak: 1, streak: 1 },
])

assertEq('analytics: nothing completed before the check-in', before.today.completed, 0)
assertEq('analytics: the check-in appears today', after.today.completed, 1)
assertEq('analytics: today rate reflects the check-in', after.today.rate, 100)
assertEq('analytics: overall completed count moved', after.overall.totalCompleted, 1)
assertEq('analytics: overall current streak moved', after.overall.currentStreak, 1)

// A check-in on a skipped day is not a completion.
const skipped = computeHabitAnalytics([
  { ...baseHabit, skippedDates: [key(0)], completedDates: [] },
])
assertEq('analytics: skipped day is not counted as completed', skipped.today.completed, 0)
assertEq('analytics: skipped day is counted as skipped', skipped.today.skipped, 1)

// --- "never miss twice" -------------------------------------------------------
assertEq(
  'nudge fires after two missed days',
  generateHabitPatterns([{ ...baseHabit, completedDates: [key(-3), key(-2)] }])
    .some((i) => i.message.includes('Never miss twice')),
  true
)
assertEq(
  'a brand-new habit is never nagged',
  generateHabitPatterns([{ ...baseHabit }]).some((i) => i.message.includes('Never miss twice')),
  false
)
assertEq(
  'no nudge once today is checked in',
  generateHabitPatterns([{ ...baseHabit, completedDates: [key(-3), key(0)] }])
    .some((i) => i.message.includes('Never miss twice')),
  false
)

const atRisk = generateHabitPatterns([
  { ...baseHabit, completedDates: [key(-1)], currentStreak: 1, streak: 1 },
])
assertEq(
  'at-risk nudge fires when only yesterday is done',
  atRisk.some((i) => i.message.includes('save your streak')),
  true
)
assertEq(
  'actionable nudge is ordered first (view renders only three)',
  atRisk[0]?.type === 'warning' || atRisk[0]?.type === 'info',
  true
)
assertEq(
  'no streak warning once today is checked in',
  generateHabitPatterns([{ ...baseHabit, completedDates: [key(-1), key(0)], currentStreak: 2, streak: 2 }])
    .some((i) => i.message.includes('save your streak')),
  false
)

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log('\nall assertions passed')

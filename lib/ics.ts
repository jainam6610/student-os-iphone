import type { Habit, StudyEvent } from './store'

/**
 * Calendar (.ics) export.
 *
 * Safari has no scheduled-notification API: a web app's timers only fire while
 * the tab is open. Rather than pretend otherwise, iPhone users can export their
 * routines and study blocks as a standard calendar file. iOS Calendar then owns
 * the alarms — they ring when the app is closed, offline, on the lock screen.
 *
 * Times are written as *floating* local times (no Z, no TZID) so Calendar
 * interprets them in the device's own time zone. That is the correct behaviour
 * for a personal planner that never leaves the phone.
 */

const PRODID = '-//Student OS//Calendar Export//EN'
const UID_DOMAIN = 'student-os.app'
const DEFAULT_HABIT_MINUTES = 15

// ── RFC 5545 primitives ─────────────────────────────────────

function escapeText(value: string): string {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n')
}

/** Fold lines longer than 75 octets, as the spec requires. */
function foldLine(line: string): string {
  if (line.length <= 75) return line
  const parts: string[] = [line.slice(0, 75)]
  let rest = line.slice(75)
  while (rest.length > 0) {
    parts.push(' ' + rest.slice(0, 74))
    rest = rest.slice(74)
  }
  return parts.join('\r\n')
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** Floating local timestamp: YYYYMMDDTHHMMSS */
function stamp(date: Date): string {
  return (
    `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}` +
    `T${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  )
}

function utcStamp(date: Date): string {
  return (
    `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}` +
    `T${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`
  )
}

function parseTime(value: string): { hours: number; minutes: number } | null {
  const match = /^(\d{1,2}):(\d{2})/.exec(value ?? '')
  if (!match) return null
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return null
  if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null
  return { hours, minutes }
}

const WEEKDAY_CODES = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'] as const

function byDay(days: number[]): string {
  const unique = [...new Set(days)].filter((d) => d >= 0 && d <= 6).sort((a, b) => a - b)
  return unique.map((d) => WEEKDAY_CODES[d]).join(',')
}

/** Next date (today included) whose weekday is in `days`. */
function nextDateForDays(days: number[]): Date {
  const today = new Date()
  const set = new Set(days)
  for (let offset = 0; offset < 7; offset++) {
    const candidate = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offset)
    if (set.has(candidate.getDay())) return candidate
  }
  return new Date(today.getFullYear(), today.getMonth(), today.getDate())
}

// ── Event builders ──────────────────────────────────────────

interface CalendarEvent {
  uid: string
  summary: string
  description?: string
  start: Date
  end: Date
  rrule?: string
  /** Minutes before the start that the alarm fires (0 = at start time). */
  alarmMinutes?: number
}

function habitEvents(habit: Habit): CalendarEvent[] {
  const reminder = habit.reminder
  if (!reminder?.enabled || !Array.isArray(reminder.times) || reminder.times.length === 0) return []

  const duration = habit.estimatedDuration && habit.estimatedDuration > 0
    ? habit.estimatedDuration
    : DEFAULT_HABIT_MINUTES

  let rrule: string | undefined
  switch (habit.repeatType) {
    case 'weekdays':
      rrule = 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'
      break
    case 'weekends':
      rrule = 'FREQ=WEEKLY;BYDAY=SA,SU'
      break
    case 'weekly':
      rrule = `FREQ=WEEKLY;BYDAY=${byDay(habit.targetDays ?? habit.repeatDays ?? [1])}`
      break
    case 'monthly':
      rrule = 'FREQ=MONTHLY'
      break
    case 'custom':
      if (habit.repeatDays?.length) {
        rrule = `FREQ=WEEKLY;BYDAY=${byDay(habit.repeatDays)}`
      }
      break
    default:
      if (habit.frequency === 'weekly' && (habit.targetDays?.length || habit.repeatDays?.length)) {
        rrule = `FREQ=WEEKLY;BYDAY=${byDay((habit.targetDays ?? habit.repeatDays) as number[])}`
      } else {
        rrule = 'FREQ=DAILY'
      }
  }

  const days = habit.repeatDays ?? habit.targetDays ?? []
  const useDayAnchor = (rrule?.includes('BYDAY') ?? false) && days.length > 0
  const base = useDayAnchor ? nextDateForDays(days) : new Date()

  return reminder.times
    .map((time, index): CalendarEvent | null => {
      const parsed = parseTime(time)
      if (!parsed) return null

      const start = new Date(base.getFullYear(), base.getMonth(), base.getDate(), parsed.hours, parsed.minutes, 0, 0)
      // A daily routine whose time already passed today starts tomorrow.
      if (!rrule || (!useDayAnchor && start.getTime() <= Date.now())) {
        start.setDate(start.getDate() + 1)
      }
      const end = new Date(start.getTime() + duration * 60_000)

      return {
        uid: `habit-${habit.id}-${index}@${UID_DOMAIN}`,
        summary: `${habit.icon ? habit.icon + ' ' : ''}${habit.name}`,
        description: habit.description,
        start,
        end,
        rrule,
        alarmMinutes: 0,
      }
    })
    .filter((event): event is CalendarEvent => event !== null)
}

function studyEvents(event: StudyEvent, defaultReminderMinutes: number): CalendarEvent[] {
  if (event.completed) return []
  const start = event.startTime instanceof Date ? event.startTime : new Date(event.startTime)
  const end = event.endTime instanceof Date ? event.endTime : new Date(event.endTime)
  if (Number.isNaN(start.getTime())) return []

  const safeEnd = Number.isNaN(end.getTime()) || end <= start
    ? new Date(start.getTime() + 60 * 60_000)
    : end

  let rrule: string | undefined
  switch (event.repeatRule) {
    case 'daily':
      rrule = 'FREQ=DAILY'
      break
    case 'weekdays':
      rrule = 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'
      break
    case 'weekly':
      rrule = 'FREQ=WEEKLY'
      break
    default:
      rrule = undefined
  }

  const reminderMinutes = event.reminder?.enabled
    ? event.reminder.minutesBefore
    : defaultReminderMinutes

  return [{
    uid: `event-${event.id}@${UID_DOMAIN}`,
    summary: event.title,
    description: [event.subject, event.description].filter(Boolean).join(' — ') || undefined,
    start,
    end: safeEnd,
    rrule,
    alarmMinutes: reminderMinutes,
  }]
}

// ── Public API ──────────────────────────────────────────────

export interface CalendarExportInput {
  habits: Habit[]
  events: StudyEvent[]
  defaultReminderMinutes?: number
  calendarName?: string
}

/** Build an RFC 5545 calendar. Returns an empty VCALENDAR when nothing applies. */
export function buildCalendar(input: CalendarExportInput): string {
  const { habits, events, defaultReminderMinutes = 10, calendarName = 'Student OS' } = input
  const now = new Date()

  const collected: CalendarEvent[] = [
    ...habits.flatMap(habitEvents),
    ...events.flatMap((event) => studyEvents(event, defaultReminderMinutes)),
  ]

  const lines: string[] = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    `PRODID:${PRODID}`,
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    `X-WR-CALNAME:${escapeText(calendarName)}`,
    'X-WR-TIMEZONE:local',
  ]

  for (const event of collected) {
    lines.push('BEGIN:VEVENT')
    lines.push(`UID:${event.uid}`)
    lines.push(`DTSTAMP:${utcStamp(now)}`)
    lines.push(`DTSTART:${stamp(event.start)}`)
    lines.push(`DTEND:${stamp(event.end)}`)
    lines.push(`SUMMARY:${escapeText(event.summary)}`)
    if (event.description) lines.push(`DESCRIPTION:${escapeText(event.description)}`)
    if (event.rrule) lines.push(`RRULE:${event.rrule}`)
    lines.push('STATUS:CONFIRMED')
    lines.push('TRANSP:TRANSPARENT')

    const alarmMinutes = event.alarmMinutes
    if (typeof alarmMinutes === 'number' && alarmMinutes >= 0) {
      lines.push('BEGIN:VALARM')
      lines.push('ACTION:DISPLAY')
      lines.push(`DESCRIPTION:${escapeText(event.summary)}`)
      lines.push(alarmMinutes === 0 ? 'TRIGGER:-PT0M' : `TRIGGER:-PT${Math.round(alarmMinutes)}M`)
      lines.push('END:VALARM')
    }

    lines.push('END:VEVENT')
  }

  lines.push('END:VCALENDAR')
  return lines.map(foldLine).join('\r\n') + '\r\n'
}

export function hasExportableReminders(input: CalendarExportInput): boolean {
  return input.habits.some((h) => h.reminder?.enabled && (h.reminder.times?.length ?? 0) > 0)
    || input.events.some((e) => !e.completed)
}

/**
 * Hand the file to iOS. Sharing is preferred because Safari turns a shared
 * .ics into the native "Add All" Calendar sheet; the download path is the
 * fallback for browsers without file sharing.
 */
export async function exportCalendarFile(filename: string, ics: string): Promise<'shared' | 'downloaded'> {
  const file = typeof File !== 'undefined'
    ? new File([ics], filename, { type: 'text/calendar;charset=utf-8' })
    : null

  const nav = typeof navigator === 'undefined' ? undefined : navigator
  if (file && nav?.canShare?.({ files: [file] }) && typeof nav.share === 'function') {
    try {
      await nav.share({ files: [file], title: 'Student OS reminders' })
      return 'shared'
    } catch (error) {
      // A user cancelling the share sheet is not a failure worth falling back on.
      if (error instanceof DOMException && error.name === 'AbortError') return 'shared'
    }
  }

  const blob = new Blob([ics], { type: 'text/calendar;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  return 'downloaded'
}

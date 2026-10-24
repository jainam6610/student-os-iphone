import { format, subDays, parse, addDays, differenceInCalendarDays, eachDayOfInterval, getDay } from 'date-fns'
import { type Habit, type RepeatType, type HabitHistoryEntry, type HabitDailySummary } from './types'
import { generateId } from '@/lib/utils'

export function resolveStreak(habit: Habit): number {
  return habit.currentStreak ?? habit.streak ?? 0
}

export function resolveBestStreak(habit: Habit): number {
  return habit.longestStreak ?? habit.bestStreak ?? 0
}

export function resolveRepeatType(habit: Habit): RepeatType {
  return habit.repeatType ?? (habit.frequency === 'weekly' ? 'weekly' : 'daily')
}

export function resolveArchived(habit: Habit): boolean {
  return habit.archived ?? false
}

export function resolveCompletedDates(habit: Habit): string[] {
  if (!Array.isArray(habit.completedDates)) return []
  const dates = new Set<string>()
  for (const value of habit.completedDates as unknown[]) {
    if (typeof value === 'string') {
      const key = value.split('T')[0]
      if (/^\d{4}-\d{2}-\d{2}$/.test(key)) dates.add(key)
    } else if (value instanceof Date && !Number.isNaN(value.getTime())) {
      dates.add(format(value, 'yyyy-MM-dd'))
    }
  }
  return [...dates]
}

export function resolveSkippedDates(habit: Habit): string[] {
  if (!Array.isArray(habit.skippedDates)) return []
  return (habit.skippedDates as unknown[]).flatMap((value) => {
    if (typeof value === 'string') {
      const key = value.split('T')[0]
      return /^\d{4}-\d{2}-\d{2}$/.test(key) ? [key] : []
    }
    return value instanceof Date && !Number.isNaN(value.getTime()) ? [format(value, 'yyyy-MM-dd')] : []
  })
}

export function resolveReminder(habit: Habit) {
  return habit.reminder ?? { enabled: false, times: [], repeat: false, sound: 'default', vibration: true, snoozed: false, weekdayOnly: false, weekendOnly: false }
}

export function getTodayStr(): string {
  return format(new Date(), 'yyyy-MM-dd')
}

export function formatDate(date: Date): string {
  return format(date, 'yyyy-MM-dd')
}

export function isToday(dateStr: string): boolean {
  return dateStr === getTodayStr()
}

/**
 * Formats a stored reminder time ("HH:mm") for display, e.g. "9:00 AM".
 * Returns null for missing/out-of-range values so a malformed stored string can
 * never surface as a broken cue — or reach the alarm scheduler.
 */
export function formatHabitCueTime(value: string | undefined | null): string | null {
  if (!value || typeof value !== 'string') return null
  const [rawHours, rawMinutes] = value.split(':')
  const hours = Number.parseInt(rawHours ?? '', 10)
  const minutes = Number.parseInt(rawMinutes ?? '', 10)
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return null
  if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null
  const suffix = hours >= 12 ? 'PM' : 'AM'
  return `${hours % 12 || 12}:${String(minutes).padStart(2, '0')} ${suffix}`
}

/**
 * Completion dates arrive as "yyyy-MM-dd" or full ISO timestamps depending on
 * record age. Normalise once, dedupe, and ignore anything unparseable.
 */
function toDateKeySet(completedDates: unknown): Set<string> {
  const keys = new Set<string>()
  if (!Array.isArray(completedDates)) return keys
  for (const value of completedDates) {
    if (typeof value === 'string') {
      const key = value.split('T')[0]
      if (/^\d{4}-\d{2}-\d{2}$/.test(key)) keys.add(key)
      continue
    }
    if (value instanceof Date && !Number.isNaN(value.getTime())) {
      keys.add(format(value, 'yyyy-MM-dd'))
    }
  }
  return keys
}

export function calculateStreak(completedDates: string[]): number {
  const unique = toDateKeySet(completedDates)
  if (unique.size === 0) return 0

  const today = getTodayStr()
  // A streak is only broken once a day is fully over. If today hasn't been
  // checked in yet, keep counting from yesterday instead of reporting 0 — which
  // used to wipe visible streaks (and the "at risk" nudge) every midnight.
  let expected = unique.has(today) ? today : format(subDays(new Date(), 1), 'yyyy-MM-dd')
  let streak = 0

  while (unique.has(expected)) {
    streak++
    expected = format(subDays(parse(expected, 'yyyy-MM-dd', new Date()), 1), 'yyyy-MM-dd')
  }
  return streak
}

export function calculateLongestStreak(completedDates: string[]): number {
  const unique = toDateKeySet(completedDates)
  if (unique.size === 0) return 0

  let best = 0
  for (const day of unique) {
    const start = parse(day, 'yyyy-MM-dd', new Date())
    // Only walk runs from their first day, so each run is counted exactly once.
    if (unique.has(format(addDays(start, -1), 'yyyy-MM-dd'))) continue
    let run = 0
    let cursor = start
    while (unique.has(format(cursor, 'yyyy-MM-dd'))) {
      run++
      // addDays preserves local midnight, so a DST jump can't round a day to 0.96.
      cursor = addDays(cursor, 1)
    }
    best = Math.max(best, run)
  }
  return best
}

export function calculateCompletionRate(completedDates: string[], days: number = 30): number {
  if (days <= 0) return 0
  const interval = eachDayOfInterval({ start: subDays(new Date(), days - 1), end: new Date() })
  const completedKeys = toDateKeySet(completedDates)
  let completed = 0
  for (const d of interval) {
    if (completedKeys.has(format(d, 'yyyy-MM-dd'))) completed++
  }
  return Math.round((completed / Math.min(days, interval.length)) * 100)
}

export function shouldHabitBeDoneToday(habit: Habit): boolean {
  if (resolveArchived(habit)) return false
  const today = new Date()
  const todayStr = getTodayStr()
  const dayOfWeek = getDay(today)
  const repeatType = resolveRepeatType(habit)

  switch (repeatType) {
    case 'daily':
      return true
    case 'weekdays':
      return dayOfWeek >= 1 && dayOfWeek <= 5
    case 'weekends':
      return dayOfWeek === 0 || dayOfWeek === 6
    case 'weekly': {
      if (!habit.repeatDays || habit.repeatDays.length === 0) {
        return dayOfWeek === 1
      }
      return habit.repeatDays.includes(dayOfWeek)
    }
    case 'monthly': {
      if (habit.repeatDays && habit.repeatDays.length > 0) {
        return habit.repeatDays.includes(today.getDate())
      }
      return true
    }
    case 'custom': {
      if (!habit.repeatInterval || habit.repeatInterval <= 1) return true
      if (!habit.createdAt) return true
      const diff = differenceInCalendarDays(today, new Date(habit.createdAt))
      return diff % habit.repeatInterval === 0
    }
    default:
      return true
  }
}

export function isStreakAtRisk(habit: Habit): boolean {
  const streak = resolveStreak(habit)
  if (streak <= 0) return false
  const today = getTodayStr()
  if (resolveCompletedDates(habit).includes(today)) return false
  const yesterday = format(subDays(new Date(), 1), 'yyyy-MM-dd')
  if (resolveCompletedDates(habit).includes(yesterday)) return true
  return false
}

export function getDueHabits(habits: Habit[]): Habit[] {
  return habits.filter((h) => !resolveArchived(h) && !resolveCompletedDates(h).includes(getTodayStr()) && shouldHabitBeDoneToday(h))
}

export function getCompletedToday(habits: Habit[]): Habit[] {
  const today = getTodayStr()
  return habits.filter((h) => !resolveArchived(h) && resolveCompletedDates(h).includes(today))
}

export function getRemainingToday(habits: Habit[]): Habit[] {
  const today = getTodayStr()
  return habits.filter((h) => !resolveArchived(h) && !resolveCompletedDates(h).includes(today) && shouldHabitBeDoneToday(h))
}

export function createDefaultHabit(input: {
  name: string
  icon?: string
  color?: string
  category?: string
  repeatType?: RepeatType
}): Habit {
  const now = new Date()
  return {
    id: generateId(),
    name: input.name,
    description: undefined,
    category: input.category || 'health',
    icon: input.icon || 'self_improvement',
    color: input.color || '#7dd3fc',
    reminder: {
      enabled: false,
      times: [],
      repeat: false,
      sound: 'default',
      vibration: true,
      snoozed: false,
      weekdayOnly: false,
      weekendOnly: false,
    },
    alarmEnabled: false,
    notificationEnabled: true,
    repeatType: input.repeatType || 'daily',
    repeatDays: input.repeatType === 'weekdays' ? [1, 2, 3, 4, 5] : undefined,
    repeatInterval: undefined,
    timeOfDay: 'anytime',
    estimatedDuration: undefined,
    priority: 'medium',
    notes: undefined,
    currentStreak: 0,
    longestStreak: 0,
    completionRate: 0,
    skippedCount: 0,
    completedCount: 0,
    completedDates: [],
    skippedDates: [],
    history: [],
    createdAt: now,
    updatedAt: now,
    archived: false,
    archivedAt: undefined,
  }
}

export function computeDailySummaries(habits: Habit[], days: number = 30): HabitDailySummary[] {
  const today = new Date()
  const summaries: HabitDailySummary[] = []

  for (let i = days - 1; i >= 0; i--) {
    const date = subDays(today, i)
    const dateStr = format(date, 'yyyy-MM-dd')
    const activeHabits = habits.filter((h) => !resolveArchived(h))
    const total = activeHabits.length
    let completed = 0
    let skipped = 0

    for (const h of activeHabits) {
      if (resolveCompletedDates(h).includes(dateStr)) completed++
      if (resolveSkippedDates(h).includes(dateStr)) skipped++
    }

    summaries.push({
      date: dateStr,
      total,
      completed,
      skipped,
      missed: total - completed - skipped,
      rate: total > 0 ? Math.round((completed / total) * 100) : 0,
    })
  }

  return summaries
}

export function checkAndResetStreaks(habits: Habit[]): { id: string; updates: Partial<Habit> }[] {
  const today = getTodayStr()
  const yesterday = format(subDays(new Date(), 1), 'yyyy-MM-dd')
  const results: { id: string; updates: Partial<Habit> }[] = []

  for (const habit of habits) {
    if (resolveArchived(habit)) continue
    if (resolveCompletedDates(habit).includes(today)) continue

    const shouldDo = shouldHabitBeDoneToday(habit)
    if (!shouldDo) continue

    if (!resolveCompletedDates(habit).includes(yesterday) && resolveStreak(habit) > 0) {
      results.push({
        id: habit.id,
        updates: {
          currentStreak: 0,
          completionRate: calculateCompletionRate(resolveCompletedDates(habit)),
        },
      })
    }
  }

  return results
}

export function migrateLegacyHabit(legacy: {
  id: string
  name: string
  icon: string
  color: string
  category?: string
  frequency: 'daily' | 'weekly'
  targetDays?: number[]
  completedDates: string[]
  streak: number
  bestStreak: number
  createdAt: Date
}): Habit {
  const now = new Date()
  const completionRate = calculateCompletionRate(legacy.completedDates)

  return {
    id: legacy.id,
    name: legacy.name,
    description: undefined,
    category: legacy.category || 'health',
    icon: legacy.icon,
    color: legacy.color,
    reminder: {
      enabled: false,
      times: [],
      repeat: false,
      sound: 'default',
      vibration: true,
      snoozed: false,
      weekdayOnly: false,
      weekendOnly: false,
    },
    alarmEnabled: false,
    notificationEnabled: true,
    repeatType: legacy.frequency === 'weekly' && legacy.targetDays
      ? 'weekdays'
      : legacy.frequency,
    repeatDays: legacy.targetDays,
    repeatInterval: undefined,
    timeOfDay: 'anytime',
    estimatedDuration: undefined,
    priority: 'medium',
    notes: undefined,
    currentStreak: legacy.streak,
    longestStreak: legacy.bestStreak,
    completionRate,
    skippedCount: 0,
    completedCount: legacy.completedDates.length,
    completedDates: legacy.completedDates,
    skippedDates: [],
    history: legacy.completedDates.map((d) => ({
      date: d,
      action: 'completed' as const,
      timestamp: new Date(d + 'T00:00:00'),
    })),
    createdAt: legacy.createdAt instanceof Date ? legacy.createdAt : now,
    updatedAt: now,
    archived: false,
    archivedAt: undefined,
  }
}

export function isHabitDueOnDate(habit: Habit, dateStr: string): boolean {
  if (resolveArchived(habit)) return false
  const date = parse(dateStr, 'yyyy-MM-dd', new Date())
  const dayOfWeek = getDay(date)
  const repeatType = resolveRepeatType(habit)

  switch (repeatType) {
    case 'daily':
      return true
    case 'weekdays':
      return dayOfWeek >= 1 && dayOfWeek <= 5
    case 'weekends':
      return dayOfWeek === 0 || dayOfWeek === 6
    case 'weekly':
      if (!habit.repeatDays || habit.repeatDays.length === 0) return dayOfWeek === 1
      return habit.repeatDays.includes(dayOfWeek)
    case 'monthly':
      if (habit.repeatDays && habit.repeatDays.length > 0) {
        return habit.repeatDays.includes(date.getDate())
      }
      return true
    case 'custom':
      if (!habit.repeatInterval || habit.repeatInterval <= 1) return true
      if (!habit.createdAt) return true
      const diff = differenceInCalendarDays(date, new Date(habit.createdAt))
      return diff % habit.repeatInterval === 0
    default:
      return true
  }
}

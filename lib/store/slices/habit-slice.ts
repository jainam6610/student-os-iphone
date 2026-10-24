import { format } from 'date-fns'
import { sanitizeString, sanitizeNumber } from '../helpers'
import { MAX_HABITS } from '../constants'
import { claimCreate, isDuplicateCreate } from '../completion'
import { generateId } from '../../utils'
import { syncHabitAlarms, cancelAlarm, dismissAlarm } from '../../scheduling'
import type { HabitSettings } from '../../habits/types'
import { DEFAULT_HABIT_SETTINGS } from '../../habits/types'
import { getTodayStr, calculateStreak, calculateLongestStreak, calculateCompletionRate } from '../../habits/utils'
import type { Habit } from '../types'

/**
 * Atomic Habits — "make it satisfying": a check-in has to pay out right away.
 * 15 XP matches a low-priority task (task-slice) so both tabs share one economy.
 */
const HABIT_CHECKIN_XP = 15

/**
 * Stored completion dates can be "yyyy-MM-dd" or a full ISO timestamp depending
 * on how old the record is. Normalising here keeps `includes()` checks and the
 * streak maths from silently missing days (or double-counting them).
 */
function normalizeCompletedDates(dates: unknown): string[] {
  if (!Array.isArray(dates)) return []
  const keys = new Set<string>()
  for (const value of dates) {
    if (typeof value === 'string') {
      const key = value.split('T')[0]
      if (/^\d{4}-\d{2}-\d{2}$/.test(key)) keys.add(key)
      continue
    }
    if (value instanceof Date && !Number.isNaN(value.getTime())) {
      keys.add(format(value, 'yyyy-MM-dd'))
    }
  }
  return Array.from(keys)
}

export interface HabitSlice {
  habits: Habit[]
  addHabit: (habit: Omit<Habit, 'id' | 'completedDates' | 'streak' | 'bestStreak' | 'createdAt'>) => void
  updateHabit: (id: string, updates: Partial<Habit>) => Promise<void>
  deleteHabit: (id: string) => Promise<void>
  toggleHabitDay: (id: string, date: string) => void
  completeHabit: (id: string, date?: string) => void
  undoCompleteHabit: (id: string, date?: string) => void
  skipHabit: (id: string, reason?: string, date?: string) => void
  archiveHabit: (id: string) => Promise<void>
  restoreHabit: (id: string) => void
  duplicateHabit: (id: string) => void
  resetHabit: (id: string, period: 'daily' | 'weekly' | 'monthly') => void
  recomputeHabitStreaks: () => void
  habitSettings: HabitSettings
  setHabitSettings: (settings: Partial<HabitSettings>) => void
}

export const createHabitSlice = (set: any, get: any, _api?: any): HabitSlice => ({
  habits: [],
  addHabit: async (habit) => {
    set((state: any) => {
      if (state.habits.length >= MAX_HABITS) return state
      const name = sanitizeString(habit.name, 'Habit', 100)
      // R5: a double-tap on "Create Habit" must not create two identical habits.
      if (!claimCreate(name) || isDuplicateCreate(state.habits, name)) return state
      const now = new Date()
      const id = generateId()
      const newHabit: Habit = {
        ...habit,
        id,
        name,
        completedDates: [],
        streak: 0,
        bestStreak: 0,
        createdAt: now,
        updatedAt: now,
        completionRate: 0,
        skippedCount: 0,
        completedCount: 0,
        skippedDates: [],
        history: [],
        repeatType: (habit as any).repeatType || (habit.frequency === 'weekly' ? 'weekly' : 'daily'),
        repeatDays: (habit as any).repeatDays || (habit.frequency === 'weekly' ? [1, 2, 3, 4, 5] : undefined),
        notificationEnabled: (habit as any).notificationEnabled ?? true,
        alarmEnabled: (habit as any).alarmEnabled ?? false,
        timeOfDay: (habit as any).timeOfDay || 'anytime',
        priority: (habit as any).priority || 'medium',
        archived: false,
        longestStreak: 0,
      }
      return { habits: [...state.habits, newHabit] }
    })
    await syncHabitAlarms(get().habits)
  },
  updateHabit: async (id, updates) => {
    const old = get().habits.find((h: Habit) => h.id === id)
    if (old && (old.alarmEnabled || old.reminder?.enabled)) {
      for (const timeStr of (old.reminder?.times || [])) {
        await cancelAlarm(`${old.id}-${timeStr}`)
      }
    }
    set((state: any) => ({ habits: state.habits.map((h: Habit) => h.id === id ? { ...h, ...updates, updatedAt: new Date() } : h) }))
    // IDs can remain unchanged when repeat days, sound, or labels change.
    // Force replacement so native persisted alarm metadata uses new settings.
    await syncHabitAlarms(get().habits, [id])
  },
  deleteHabit: async (id) => {
    const habit = get().habits.find((h: Habit) => h.id === id)
    if (habit && (habit.alarmEnabled || habit.reminder?.enabled)) {
      for (const timeStr of (habit.reminder?.times || [])) {
        await cancelAlarm(`${habit.id}-${timeStr}`)
      }
    }
    set((state: any) => ({ habits: state.habits.filter((h: Habit) => h.id !== id) }))
    await syncHabitAlarms(get().habits)
  },
  completeHabit: async (id, date) => {
    const targetDate = date || getTodayStr()
    const current = get().habits.find((h: Habit) => h.id === id)
    if (!current) return
    const alreadyDone = normalizeCompletedDates(current.completedDates).includes(targetDate)

    set((state: any) => ({
      habits: state.habits.map((h: Habit) => {
        if (h.id !== id) return h
        const existing = normalizeCompletedDates(h.completedDates)
        if (existing.includes(targetDate)) return h
        const newDates = [...existing, targetDate].sort()
        const newHistory = [...(h.history || []), { date: targetDate, action: 'completed' as const, timestamp: new Date() }]
        const streak = calculateStreak(newDates)
        return {
          ...h,
          completedDates: newDates,
          completedCount: newDates.length,
          currentStreak: streak,
          streak,
          longestStreak: Math.max(h.longestStreak ?? h.bestStreak ?? 0, streak),
          bestStreak: Math.max(h.bestStreak ?? 0, streak),
          completionRate: calculateCompletionRate(newDates),
          history: newHistory,
          updatedAt: new Date(),
        }
      }),
    }))

    // Idempotent, like completeTask: no payout for a no-op check-in.
    if (!alreadyDone) {
      get().addXP(HABIT_CHECKIN_XP)
      try { get().checkAchievements() } catch {}
      try { get().recalculateProductivityScore() } catch {}
    }
    await syncHabitAlarms(get().habits, [id])
    await dismissAlarm()
  },
  undoCompleteHabit: async (id, date) => {
    const targetDate = date || getTodayStr()
    const current = get().habits.find((h: Habit) => h.id === id)
    const wasDone = current ? normalizeCompletedDates(current.completedDates).includes(targetDate) : false

    set((state: any) => ({
      habits: state.habits.map((h: Habit) => {
        if (h.id !== id) return h
        const existing = normalizeCompletedDates(h.completedDates)
        if (!existing.includes(targetDate)) return h
        const newDates = existing.filter((d: string) => d !== targetDate)
        const newHistory = [...(h.history || []), { date: targetDate, action: 'undone' as const, timestamp: new Date() }]
        const streak = calculateStreak(newDates)
        return {
          ...h,
          completedDates: newDates,
          completedCount: newDates.length,
          currentStreak: streak,
          streak,
          completionRate: calculateCompletionRate(newDates),
          history: newHistory,
          updatedAt: new Date(),
        }
      }),
    }))

    // Undo has to take the XP back, otherwise toggling is an infinite XP farm.
    if (wasDone) {
      get().addXP(-HABIT_CHECKIN_XP)
      try { get().checkAchievements() } catch {}
      try { get().recalculateProductivityScore() } catch {}
    }
    await syncHabitAlarms(get().habits, [id])
  },
  skipHabit: (id, reason, date) => set((state: any) => ({
    habits: state.habits.map((h: Habit) => {
      if (h.id !== id) return h
      const targetDate = date || getTodayStr()
      if (h.completedDates.includes(targetDate)) return h
      const newSkipped = [...(h.skippedDates || []), targetDate]
      const newHistory = [...(h.history || []), { date: targetDate, action: 'skipped' as const, reason: reason || 'Manual skip', timestamp: new Date() }]
      return {
        ...h,
        skippedDates: newSkipped,
        skippedCount: (h.skippedCount || 0) + 1,
        history: newHistory,
        updatedAt: new Date(),
      }
    }),
  })),
  archiveHabit: async (id) => {
    const habit = get().habits.find((h: Habit) => h.id === id)
    if (habit && (habit.alarmEnabled || habit.reminder?.enabled)) {
      for (const timeStr of (habit.reminder?.times || [])) {
        await cancelAlarm(`${habit.id}-${timeStr}`)
      }
    }
    set((state: any) => ({
      habits: state.habits.map((h: Habit) => h.id === id ? { ...h, archived: true, archivedAt: new Date(), updatedAt: new Date() } : h),
    }))
    await syncHabitAlarms(get().habits, [id])
  },
  restoreHabit: async (id) => {
    set((state: any) => ({
      habits: state.habits.map((h: Habit) => h.id === id ? { ...h, archived: false, archivedAt: undefined, updatedAt: new Date() } : h),
    }))
    // Restoring re-enables reminders, so the scheduler has to learn about them.
    await syncHabitAlarms(get().habits)
  },
  duplicateHabit: async (id) => {
    set((state: any) => {
      const original = state.habits.find((h: Habit) => h.id === id)
      if (!original || state.habits.length >= MAX_HABITS) return state
      const now = new Date()
      const copyName = `${original.name} (Copy)`
      // R5: double-tapping "Duplicate" must not create two copies.
      if (!claimCreate(copyName) || isDuplicateCreate(state.habits, copyName)) return state
      const copy: Habit = {
        ...original as any,
        id: generateId(),
        name: copyName,
        completedDates: [],
        skippedDates: [],
        streak: 0, bestStreak: 0, currentStreak: 0, longestStreak: 0,
        completionRate: 0, skippedCount: 0, completedCount: 0,
        history: [],
        createdAt: now, updatedAt: now,
        archived: false, archivedAt: undefined,
      }
      return { habits: [...state.habits, copy] }
    })
    await syncHabitAlarms(get().habits)
  },
  resetHabit: (id, period) => set((state: any) => ({
    habits: state.habits.map((h: Habit) => {
      if (h.id !== id) return h
      const now = new Date()
      if (period === 'daily') {
        const today = getTodayStr()
        return { ...h, completedDates: h.completedDates.filter((d: string) => d !== today), skippedDates: (h.skippedDates || []).filter((d: string) => d !== today), updatedAt: now }
      }
      if (period === 'weekly') {
        const weekAgo = new Date()
        weekAgo.setDate(weekAgo.getDate() - 7)
        const cutoff = format(weekAgo, 'yyyy-MM-dd')
        return { ...h, completedDates: h.completedDates.filter((d: string) => d < cutoff), skippedDates: (h.skippedDates || []).filter((d: string) => d < cutoff), updatedAt: now }
      }
      return {
        ...h, completedDates: [], skippedDates: [], completedCount: 0, skippedCount: 0,
        currentStreak: 0, longestStreak: h.longestStreak ?? h.bestStreak ?? 0, streak: 0,
        completionRate: 0, updatedAt: now,
      }
    }),
  })),
  recomputeHabitStreaks: () => set((state: any) => ({
    habits: state.habits.map((h: Habit) => {
      const streak = calculateStreak(h.completedDates)
      const best = Math.max(calculateLongestStreak(h.completedDates), streak, h.longestStreak ?? h.bestStreak ?? 0)
      return {
        ...h, streak, bestStreak: best, currentStreak: streak, longestStreak: best,
        completionRate: calculateCompletionRate(h.completedDates),
        completedCount: h.completedDates.length, updatedAt: new Date(),
      }
    }),
  })),
  toggleHabitDay: async (id, date) => {
    const target = get().habits.find((h: Habit) => h.id === id)
    const wasCompleted = target ? normalizeCompletedDates(target.completedDates).includes(date) : false
    set((state: any) => ({
      habits: state.habits.map((h: Habit) => {
        if (h.id !== id) return h
        const normalized = normalizeCompletedDates(h.completedDates)
        const dates = normalized.includes(date) ? normalized.filter((d: string) => d !== date) : [...normalized, date].sort()
        const streak = calculateStreak(dates)
        const bestStreak = Math.max(calculateLongestStreak(dates), streak, h.longestStreak ?? h.bestStreak ?? 0)
        const newHistory = [...(h.history || [])]
        if (normalized.includes(date)) {
          newHistory.push({ date, action: 'undone' as const, timestamp: new Date() })
        } else {
          newHistory.push({ date, action: 'completed' as const, timestamp: new Date() })
        }
        return {
          ...h, completedDates: dates, streak, bestStreak, currentStreak: streak, longestStreak: bestStreak,
          completedCount: dates.length, completionRate: calculateCompletionRate(dates),
          history: newHistory, updatedAt: new Date(),
        }
      }),
    }))
    get().addXP(wasCompleted ? -HABIT_CHECKIN_XP : HABIT_CHECKIN_XP)
    try { get().checkAchievements() } catch {}
    try { get().recalculateProductivityScore() } catch {}
    await syncHabitAlarms(get().habits, [id])
    await dismissAlarm()
  },
  habitSettings: DEFAULT_HABIT_SETTINGS,
  setHabitSettings: (settings) => set((state: any) => ({ habitSettings: { ...state.habitSettings, ...settings } })),
})

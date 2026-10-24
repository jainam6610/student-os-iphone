import { MAX_XP } from './constants'
import { calculateLevel } from './helpers'
import type { StudyEvent, Task, UserProgress } from './types'

/**
 * One economy for every completion in the app.
 *
 * Tasks (task-slice) and planner events (event-slice) used to pay out XP in
 * separate, incompatible ways: tasks moved `progress`, planner events moved
 * nothing at all — so finishing something in the planner never changed the XP
 * bar, the level, "Tasks done", achievements or the analytics cards. Everything
 * now routes through these helpers.
 */
export type CompletionPriority = 'low' | 'medium' | 'high'

export const XP_BY_PRIORITY: Record<CompletionPriority, number> = {
  high: 50,
  medium: 30,
  low: 15,
}

export function normalizePriority(priority: unknown): CompletionPriority {
  return priority === 'high' || priority === 'low' ? priority : 'medium'
}

/** XP paid for one completion at the given priority. */
export function completionXp(priority: unknown): number {
  return XP_BY_PRIORITY[normalizePriority(priority)]
}

/**
 * Applies a single completion to `progress`: XP, level and the shared
 * "tasks done" counter. Pure — callers merge the result into their `set()`.
 */
export function applyCompletionProgress(progress: UserProgress | undefined, priority: unknown): UserProgress {
  const base = progress ?? {
    xp: 0, level: 1, totalFocusTime: 0, totalTasksCompleted: 0,
    currentStreak: 0, bestStreak: 0, weeklyGoal: 25 * 60, weeklyProgress: 0,
  }
  const xpAmount = completionXp(priority)
  const xp = Math.max(0, Math.min((base.xp ?? 0) + xpAmount, MAX_XP))
  return {
    ...base,
    xp,
    level: calculateLevel(xp),
    totalTasksCompleted: (base.totalTasksCompleted ?? 0) + 1,
    bestStreak: Math.max(base.bestStreak ?? 0, base.currentStreak ?? 0),
  }
}

/** Completed work across both lists — achievements count planner events too. */
export function countCompletedWork(tasks: Task[] = [], events: StudyEvent[] = []): number {
  const doneTasks = tasks.filter((task) => task.status === 'done').length
  const doneEvents = events.filter((event) => event.completed).length
  return doneTasks + doneEvents
}

/**
 * R5 — create actions must be idempotent.
 *
 * A double-tap on "Add task" / "Create habit" / "Add Task" fires the store
 * action twice before React unmounts the sheet, which used to produce two
 * identical rows. Any create whose title matches an item created within this
 * window is treated as the same intent and dropped.
 */
export const DUPLICATE_CREATE_WINDOW_MS = 1500

const recentCreates = new Map<string, number>()

/**
 * Claims a create by title. Returns `false` when the same title was already
 * created inside the window — the caller must then drop the write.
 *
 * This is the authoritative guard: a freshly created planner event has no
 * `createdAt` field (StudyEvent does not carry one), so list inspection alone
 * could not catch the double-tap.
 */
export function claimCreate(title: string, windowMs: number = DUPLICATE_CREATE_WINDOW_MS): boolean {
  const key = title.trim().toLowerCase()
  if (!key) return true
  const now = Date.now()
  const last = recentCreates.get(key)
  if (last !== undefined && now - last <= windowMs) return false
  recentCreates.set(key, now)
  if (recentCreates.size > 100) {
    for (const [k, at] of recentCreates) {
      if (now - at > windowMs) recentCreates.delete(k)
    }
  }
  return true
}

/**
 * Releases a claim so that deleting an item and immediately re-creating the
 * same title still works (delete → "New task" → same name, within the window).
 */
export function releaseCreate(title: string): void {
  const key = title.trim().toLowerCase()
  if (key) recentCreates.delete(key)
}

export function isDuplicateCreate(
  existing: ReadonlyArray<{ title?: string; name?: string; createdAt?: Date | string }>,
  title: string,
  windowMs: number = DUPLICATE_CREATE_WINDOW_MS,
): boolean {
  const key = title.trim().toLowerCase()
  if (!key) return false
  const now = Date.now()
  for (const item of existing) {
    const itemTitle = (item.title ?? item.name ?? '').trim().toLowerCase()
    if (itemTitle !== key) continue
    const created = item.createdAt instanceof Date
      ? item.createdAt.getTime()
      : item.createdAt
        ? new Date(item.createdAt).getTime()
        : 0
    if (created > 0 && now - created <= windowMs) return true
  }
  return false
}

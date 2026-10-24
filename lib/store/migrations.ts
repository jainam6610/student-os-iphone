import { format } from 'date-fns'
import { calculateCompletionRate, calculateLongestStreak, calculateStreak } from '../habits/utils'
import { normalizePlannerSettings } from '../planner/utils'
import { MAX_FOCUS_MINUTES } from './constants'
import {
  migrateV1toV2,
  normalizeStudyEvent,
  sanitizeBoolean,
  sanitizeDate,
  sanitizeNumber,
  sanitizeString,
} from './helpers'

export function migrateStoreState(persistedState: unknown, version: number): any {
        // ponytail: else-if chain in strict numerical order 0→17.
        // CRITICAL: only ONE block runs per migration (short-circuit on first match).
        // Previous sequential-if structure ran ALL matching blocks — v17/v16 could re-run v0/v1.
        let state = persistedState as any

        if (version <= 0) {
          try {
            const oldRaw = localStorage.getItem('student-os-storage-v2')
            if (oldRaw) {
              const oldParsed = JSON.parse(oldRaw)
              if (oldParsed.state) state = oldParsed.state
            }
          } catch { /* noop */ }
          state = migrateV1toV2(state)
        }
        if (version < 2) {
          if (Array.isArray(state.tasks)) {
            state.tasks = (state.tasks as Record<string, unknown>[]).map((t) => ({
              ...t, subtasks: Array.isArray(t.subtasks) ? t.subtasks : [],
            }))
          }
          if (typeof state.progress !== 'object' || !state.progress) {
            state.progress = {
              xp: 0, level: 1, totalFocusTime: 0, totalTasksCompleted: 0,
              currentStreak: 0, bestStreak: 0, weeklyGoal: 1500, weeklyProgress: 0,
            }
          }
        }
        if (version < 3) {
          if (Array.isArray(state.focusSessions)) {
            state.focusSessions = (state.focusSessions as Record<string, unknown>[]).map((fs) => ({
              ...fs, targetDuration: sanitizeNumber(fs.targetDuration, 0, 0, MAX_FOCUS_MINUTES),
              interrupted: sanitizeBoolean(fs.interrupted, false),
            }))
          }
          if (typeof state.dailyGoal !== 'number') state.dailyGoal = 120
        }
        if (version < 4) {
          if (Array.isArray(state.focusSessions)) {
            state.focusSessions = (state.focusSessions as Record<string, unknown>[]).map((fs) => ({
              ...fs, energy: fs.energy ?? undefined, distractions: fs.distractions ?? undefined,
              category: fs.category ?? undefined,
              focusScore: fs.focusScore !== undefined ? sanitizeNumber(fs.focusScore, 0, 0, 100) : undefined,
              reflection: fs.reflection ?? undefined,
              pauseCount: fs.pauseCount !== undefined ? sanitizeNumber(fs.pauseCount, 0, 0, 1000) : undefined,
            }))
          }
          if (typeof state.studyStyle !== 'string' || !['deep-work', 'consistent', 'sprint', 'night-owl'].includes(state.studyStyle as string)) state.studyStyle = 'deep-work'
          if (typeof state.academicGoal !== 'string') state.academicGoal = ''
          if (typeof state.preferredFocusDuration !== 'number' || state.preferredFocusDuration < 10 || state.preferredFocusDuration > 240) state.preferredFocusDuration = 60
          if (typeof state.reducedMotion !== 'boolean') state.reducedMotion = false
        }
        if (version < 5) {
          // no-op: v4 had no data migration
        }
        if (version < 6) {
          state.plannerSettings = normalizePlannerSettings(state.plannerSettings as any)
          if (Array.isArray(state.events)) {
            const settings = state.plannerSettings as any
            state.events = (state.events as any[]).map((event) => normalizeStudyEvent(event, settings))
          }
          if (Array.isArray(state.focusSessions)) {
            state.focusSessions = (state.focusSessions as any[]).map((session) => ({
              ...session, plannerEventId: session.plannerEventId ? sanitizeString(session.plannerEventId, '', 80) : undefined,
            }))
          }
        }
        if (version < 7) {
          if (!state.timerSettings || typeof state.timerSettings !== 'object') {
            state.timerSettings = {
              notificationEnabled: true, vibrationEnabled: true, backgroundSounds: false,
              autoBreak: false, breakDuration: 5, longBreakFrequency: 4,
              keepScreenAwake: true, animationsEnabled: true, alarmSound: 'studentos_alarm',
            }
          }
        }
        if (version < 8) {
          // no-op: v7 had no data migration
        }
        if (version < 9) {
          if (Array.isArray(state.focusSessions)) {
            state.focusSessions = (state.focusSessions as Record<string, unknown>[]).map((fs) => {
              const completed = sanitizeBoolean(fs.completed, false)
              const interrupted = sanitizeBoolean(fs.interrupted, false)
              let status = sanitizeString(fs.status as string, '', 20)
              if (status !== 'completed' && status !== 'interrupted' && status !== 'cancelled') {
                if (completed && !interrupted) status = 'completed'
                else if (interrupted) status = 'interrupted'
                else status = 'cancelled'
              }
              return { ...fs, status, goal: fs.goal ?? undefined, createdAt: sanitizeDate(fs.createdAt) ?? sanitizeDate(fs.startTime) ?? new Date() }
            })
          }
        }
        if (version < 10) {
          if (!['normal', 'deep-lock'].includes(state.focusShieldMode as string)) state.focusShieldMode = 'normal'
          if (typeof state.accentColor !== 'string') state.accentColor = 'purple'
          if (!['small', 'medium', 'large'].includes(state.fontSize as string)) state.fontSize = 'medium'
          if (typeof state.autoStartFocus !== 'boolean') state.autoStartFocus = false
        }
        if (version < 11) {
          if (!Array.isArray(state.exams)) state.exams = []
          if (!Array.isArray(state.assignments)) state.assignments = []
        }
        if (version < 12) {
          if (!Array.isArray(state.dashboardLayout)) state.dashboardLayout = ['stats', 'focus', 'tasks', 'habits', 'academic', 'insights', 'recent']
          if (typeof state.compactMode !== 'boolean') state.compactMode = false
          const defaultAchievements = [
            { id: 'first-focus', title: 'First Focus', description: 'Complete your first focus session', icon: '🎯', unlocked: false, category: 'focus' },
            { id: 'ten-hours', title: '10 Hours', description: 'Log 10 hours of focus time', icon: '⏰', unlocked: false, category: 'focus', progress: 0, maxProgress: 600 },
            { id: 'hundred-hours', title: '100 Hours', description: 'Log 100 hours of focus time', icon: '💪', unlocked: false, category: 'focus', progress: 0, maxProgress: 6000 },
            { id: 'streak-7', title: 'Week Warrior', description: 'Maintain a 7-day streak', icon: '🔥', unlocked: false, category: 'streak', progress: 0, maxProgress: 7 },
            { id: 'streak-30', title: 'Monthly Master', description: 'Maintain a 30-day streak', icon: '⭐', unlocked: false, category: 'streak', progress: 0, maxProgress: 30 },
            { id: 'tasks-50', title: 'Task Tamer', description: 'Complete 50 tasks', icon: '✅', unlocked: false, category: 'tasks', progress: 0, maxProgress: 50 },
            { id: 'tasks-100', title: 'Task Terminator', description: 'Complete 100 tasks', icon: '🏆', unlocked: false, category: 'tasks', progress: 0, maxProgress: 100 },
            { id: 'habits-30', title: 'Habit Former', description: 'Complete habits for 30 days', icon: '📋', unlocked: false, category: 'habits', progress: 0, maxProgress: 30 },
            { id: 'early-bird', title: 'Early Bird', description: 'Complete 5 focus sessions before 8 AM', icon: '🌅', unlocked: false, category: 'milestone', progress: 0, maxProgress: 5 },
            { id: 'night-owl', title: 'Night Owl', description: 'Complete 5 focus sessions after 10 PM', icon: '🦉', unlocked: false, category: 'milestone', progress: 0, maxProgress: 5 },
          ]
          if (!Array.isArray(state.achievements) || (state.achievements as Record<string, unknown>[]).length === 0) state.achievements = defaultAchievements
          if (!state.productivityScore || typeof state.productivityScore !== 'object') {
            state.productivityScore = { score: 0, focusConsistency: 0, taskCompletion: 0, habitCompletion: 0, studyHours: 0, goalCompletion: 0, latePenalty: 0, weeklyHistory: [] }
          }
          if (Array.isArray(state.tasks)) {
            state.tasks = (state.tasks as Record<string, unknown>[]).map((t) => ({
              ...t, projectId: t.projectId ? sanitizeString(t.projectId as string, '', 80) : undefined,
            }))
          }
        }
        if (version < 13) {
          if (Array.isArray(state.habits)) {
            state.habits = (state.habits as Record<string, unknown>[]).map((h) => {
              const raw = h.completedDates
              let cleaned: string[]
              if (Array.isArray(raw)) {
                cleaned = raw.map((d: unknown) => {
                  if (d instanceof Date) return format(d, 'yyyy-MM-dd')
                  if (typeof d === 'string') return d.split('T')[0]
                  return String(d)
                }).filter((d: string) => /^\d{4}-\d{2}-\d{2}$/.test(d))
              } else { cleaned = [] }
              return { ...h, completedDates: cleaned }
            })
          }
          if (Array.isArray(state.focusSessions)) {
            state.focusSessions = (state.focusSessions as Record<string, unknown>[]).map((fs) => ({
              ...fs, startTime: fs.startTime instanceof Date ? fs.startTime : sanitizeDate(fs.startTime) ?? new Date(0),
              endTime: fs.endTime instanceof Date ? fs.endTime : (fs.endTime ? sanitizeDate(fs.endTime) : undefined),
            }))
          }
        }
        if (version < 14) {
          if (Array.isArray(state.habits)) {
            state.habits = (state.habits as Record<string, unknown>[]).map((h) => {
              const now = new Date()
              const completedDates = Array.isArray(h.completedDates)
                ? (h.completedDates as unknown[]).map((d: unknown) => {
                    if (d instanceof Date) return format(d, 'yyyy-MM-dd')
                    if (typeof d === 'string') return d.split('T')[0]
                    return String(d)
                  }).filter((d: string) => /^\d{4}-\d{2}-\d{2}$/.test(d))
                : []
              const streak = sanitizeNumber(h.streak, 0, 0, 10000)
              const bestStreak = sanitizeNumber(h.bestStreak, 0, 0, 10000)
              return {
                ...h, completedDates, repeatType: h.repeatType || (h.frequency === 'weekly' ? 'weekly' : 'daily'),
                repeatInterval: h.repeatInterval || undefined, timeOfDay: h.timeOfDay || 'anytime',
                priority: h.priority || 'medium', notificationEnabled: h.notificationEnabled ?? true,
                alarmEnabled: h.alarmEnabled ?? false, currentStreak: h.currentStreak ?? streak,
                longestStreak: h.longestStreak ?? bestStreak,
                completionRate: h.completionRate ?? calculateCompletionRate(completedDates),
                skippedCount: h.skippedCount ?? 0, completedCount: h.completedCount ?? completedDates.length,
                skippedDates: Array.isArray(h.skippedDates) ? h.skippedDates : [],
                history: Array.isArray(h.history) ? h.history : completedDates.map((d: string) => ({ date: d, action: 'completed' as const, timestamp: new Date(d + 'T00:00:00') })),
                updatedAt: h.updatedAt instanceof Date ? h.updatedAt : now, archived: h.archived ?? false,
                archivedAt: h.archivedAt instanceof Date ? h.archivedAt : undefined, reminder: h.reminder || undefined,
              }
            })
          }
        }
        if (version < 15) {
          if (!state.onboardingProfile || typeof state.onboardingProfile !== 'object') {
            state.onboardingProfile = {
              name: sanitizeString(state.userName, 'Student', 50),
              goal: sanitizeString(state.academicGoal, 'Study consistently', 100),
              sleepTime: '22:30', wakeTime: '06:30', sleepDurationMinutes: 480,
              dailyStudyMinutes: sanitizeNumber(state.dailyGoal, 180, 1, 1440),
              focusDurationMinutes: sanitizeNumber(state.preferredFocusDuration, 120, 10, 240),
              selectedHabits: ['Study Session', 'Drink Water', 'Evening Review'],
              enabledRoutines: ['Morning Routine', 'Study Routine'],
              notifications: { enabled: false, habitReminders: false, studyReminders: false, alarmMode: false },
              skipped: false, completedAt: undefined,
            }
          }
        }
        if (version < 16) {
          if (Array.isArray(state.events) && state.events.length > 0) {
            state.plannerSetupComplete = true
          }
        }
        if (version < 17) {
          if (!state.onboardingProfile || typeof state.onboardingProfile !== 'object') {
            state.onboardingProfile = {
              name: sanitizeString(state.userName, 'Student', 50),
              goal: sanitizeString(state.academicGoal, 'Study consistently', 100),
              sleepTime: '22:30', wakeTime: '06:30', sleepDurationMinutes: 480,
              dailyStudyMinutes: sanitizeNumber(state.dailyGoal, 180, 1, 1440),
              focusDurationMinutes: sanitizeNumber(state.preferredFocusDuration, 120, 10, 240),
              selectedHabits: ['Study Session', 'Drink Water', 'Evening Review'],
              enabledRoutines: ['Morning Routine', 'Study Routine'],
              preferredSessions: ['morning'],
              notifications: { enabled: false, habitReminders: false, studyReminders: false, alarmMode: false },
              skipped: false, completedAt: undefined,
              classOrOccupation: '', studyGoal: '', habitGoals: [],
            }
          } else {
            const p = state.onboardingProfile as Record<string, unknown>
            if (!Array.isArray(p.preferredSessions)) p.preferredSessions = ['morning']
            if (typeof p.classOrOccupation !== 'string') p.classOrOccupation = ''
            if (typeof p.studyGoal !== 'string') p.studyGoal = ''
            if (!Array.isArray(p.habitGoals)) p.habitGoals = []
          }
        }
        if (version < 18) {
          if (typeof state.motivationEnabled !== 'boolean') state.motivationEnabled = false
          if (!Array.isArray(state.motivationTimes)) state.motivationTimes = ['09:00', '14:00', '20:00']
          if (!Array.isArray(state.motivationDays)) state.motivationDays = [0, 1, 2, 3, 4, 5, 6]
          if (typeof state.examModeEnabled !== 'boolean') state.examModeEnabled = false
          if (!Array.isArray(state.recentMotivationIds)) state.recentMotivationIds = []
          if (typeof state.ambientSoundEnabled !== 'boolean') state.ambientSoundEnabled = false
          if (typeof state.ambientSoundVolume !== 'number') state.ambientSoundVolume = 0.5
          if (typeof state.prominentDisclosureAccepted !== 'boolean') state.prominentDisclosureAccepted = false
          if (typeof state.showExamCountdownOnStartup !== 'boolean') state.showExamCountdownOnStartup = false
          if (Array.isArray(state.exams)) {
            state.exams = (state.exams as any[]).map((e) => ({
              ...e, isPinned: typeof e.isPinned === 'boolean' ? e.isPinned : false,
            }))
          }
        }
        if (version < 19) {
          if (state.musicSettings && typeof state.musicSettings === 'object') {
            state.musicSettings = { ...state.musicSettings, autoPlay: true }
          }
        }
        if (version < 20) {
          if (typeof state.syllabus === 'undefined') state.syllabus = null
          if (typeof state.examDate === 'undefined') state.examDate = null
        }
        if (version < 21) {
          // Migrate syllabus: subject.topics → subject.chapters[].topics
          const s = state.syllabus as any
          if (s && Array.isArray(s.subjects)) {
            s.subjects = s.subjects.map((sub: any) => {
              if (Array.isArray(sub.chapters)) return sub
              const topics = Array.isArray(sub.topics) ? sub.topics : []
              const { topics: _, ...rest } = sub
              return { ...rest, chapters: [{ id: `ch-${sub.id}-general`, name: 'General', topics }] }
            })
          }
        }
        if (version < 22) {
          // v22: user-maintained website block list (bare hostnames).
          if (!Array.isArray(state.blockedWebsites)) state.blockedWebsites = []
        }
        if (version < 23) {
          // v23: short-form video (Shorts/Reels) budget per focus session.
          if (typeof state.shortFormAllowance !== 'number') state.shortFormAllowance = 1
        }
        if (version < 24) {
          // v24: Deep Lock onboarding flag. Mark existing users complete when
          // they already configured a policy, so we never re-onboard them.
          if (typeof state.deepLockOnboardingComplete !== 'boolean') {
            const hasPolicy =
              (Array.isArray(state.alwaysBlockedApps) && state.alwaysBlockedApps.length > 0) ||
              (Array.isArray(state.sessionBlockedApps) && state.sessionBlockedApps.length > 0) ||
              (Array.isArray(state.breakBlockedApps) && state.breakBlockedApps.length > 0) ||
              (Array.isArray(state.whitelistedApps) && state.whitelistedApps.length > 0) ||
              (Array.isArray(state.blockedWebsites) && state.blockedWebsites.length > 0)
            state.deepLockOnboardingComplete = hasPolicy
          }
        }
        if (version < 25) {
          // v25: TikTok / Snapchat content filters. Default OFF — a new filter
          // must never start rejecting content for an existing user without
          // their explicit opt-in via the Deep Lock settings switch.
          if (typeof state.blockTikTok !== 'boolean') state.blockTikTok = false
          if (typeof state.blockSnapchat !== 'boolean') state.blockSnapchat = false
        }
        if (version < 26) {
          // v26: YouTube Study Mode (gate the Home feed; search + videos allowed).
          // Off by default — opt-in, like the other content filters.
          if (typeof state.youtubeStudyMode !== 'boolean') state.youtubeStudyMode = false
        }
        if (version < 27) {
          // v27: session-only blocklist. Onboarding used to write every picked
          // app into `alwaysBlockedApps` (24/7). Existing users keep their
          // current lists untouched — the 24/7 box can move apps to session-only.
          if (!Array.isArray(state.sessionBlockedApps)) state.sessionBlockedApps = []
        }
        if (version < 28) {
          // v28: every filter that reads accessibility *content* is OFF by default.
          // Shorts / Reels / adult-website / YouTube Study Mode are the only rules
          // that inspect the view tree (node text, content descriptions, view IDs,
          // on-screen bounds) to detect a fragment player or an address bar. That is
          // content inspection, not package/class matching, so it needs an explicit
          // opt-in. They used to default ON (or arrive ON from older builds), which
          // meant every install inspected screens without consent.
          state.blockShorts = false
          state.blockReels = false
          state.blockAdultWebsites = false
          state.youtubeStudyMode = false
          if (typeof state.shortFormAllowance !== 'number') state.shortFormAllowance = 1
        }

        return state
}
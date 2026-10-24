import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { computeRecomputedProgress } from '../analytics-engine'
import { getTodayStr, calculateStreak, calculateLongestStreak, calculateCompletionRate } from '../habits/utils'
import { syncHabitAlarms, syncPlannerAlarms } from '../scheduling'
import { getStorageStats, cleanupOldData } from '../storage-quota'

 
import { STORAGE_KEY, CURRENT_SCHEMA_VERSION } from './constants'
import { storage, autoRepair, validateSessions, safeDeserialize } from './storage'
import { checkStorageQuota } from './helpers'
import { migrateStoreState } from './migrations'
import { createUserSlice } from './slices/user-slice'
import { createProgressSlice } from './slices/progress-slice'
import { createTaskSlice } from './slices/task-slice'
import { createHabitSlice } from './slices/habit-slice'
import { createFocusSlice } from './slices/focus-slice'
import { createEventSlice } from './slices/event-slice'
import { createShieldSlice } from './slices/shield-slice'
import { createContentSlice } from './slices/content-slice'
import { createAcademicSlice } from './slices/academic-slice'
import { createNavigationSlice } from './slices/navigation-slice'
import { createSyllabusSlice } from './slices/syllabus-slice'
import type { UserSlice } from './slices/user-slice'
import type { ProgressSlice } from './slices/progress-slice'
import type { TaskSlice } from './slices/task-slice'
import type { HabitSlice } from './slices/habit-slice'
import type { FocusSlice } from './slices/focus-slice'
import type { EventSlice } from './slices/event-slice'
import type { ShieldSlice } from './slices/shield-slice'
import type { ContentSlice } from './slices/content-slice'
import type { AcademicSlice } from './slices/academic-slice'
import type { NavigationSlice } from './slices/navigation-slice'
import type { SyllabusSlice } from './slices/syllabus-slice'

export type StudentOSState = UserSlice & ProgressSlice & TaskSlice & HabitSlice & FocusSlice & EventSlice & ShieldSlice & ContentSlice & AcademicSlice & NavigationSlice & SyllabusSlice

// Layer 2: Pre-hydration validation — belt-and-suspenders check
// that runs before Zustand even tries to hydrate.
function preValidateStorage(key: string): void {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return

    try {
      const parsed = JSON.parse(raw)
      if (typeof parsed !== 'object' || parsed === null) {
        throw new Error('Not an object')
      }
      if (!('state' in parsed) && !('version' in parsed)) {
        throw new Error('Missing state/version')
      }
    } catch {
      // Back up corrupted data before clearing (truncate to 10KB)
      try {
        localStorage.setItem(`${key}-corrupted-backup`,
          localStorage.getItem(key)?.slice(0, 10000) ?? '')
      } catch { /* best-effort */ }
      localStorage.removeItem(key)
    }
  } catch { /* localStorage unavailable */ }
}

// Run BEFORE store creation
if (typeof window !== 'undefined') {
  preValidateStorage(STORAGE_KEY)
}

export const useStore = create<StudentOSState>()(
  persist(
    (...a) => ({
      ...createUserSlice(...a),
      ...createProgressSlice(...a),
      ...createTaskSlice(...a),
      ...createHabitSlice(...a),
      ...createFocusSlice(...a),
      ...createEventSlice(...a),
      ...createShieldSlice(...a),
      ...createContentSlice(...a),
      ...createAcademicSlice(...a),
      ...createNavigationSlice(...a),
      ...createSyllabusSlice(...a),
    }),
    {
      name: STORAGE_KEY,
      version: CURRENT_SCHEMA_VERSION,
      storage,
      partialize: (state) => ({
        userName: state.userName,
        onboardingComplete: state.onboardingComplete,
        prominentDisclosureAccepted: state.prominentDisclosureAccepted,
        avatar: state.avatar,
        theme: state.theme,
        progress: state.progress,
        dailyGoal: state.dailyGoal,
        studyStyle: state.studyStyle,
        academicGoal: state.academicGoal,
        preferredFocusDuration: state.preferredFocusDuration,
        timerSettings: state.timerSettings,
        onboardingProfile: state.onboardingProfile,
        reducedMotion: state.reducedMotion,
        plannerSettings: state.plannerSettings,
        plannerSetupComplete: state.plannerSetupComplete,
        tasks: state.tasks,
        habits: state.habits,
        focusSessions: state.focusSessions,
        events: state.events,
        focusShieldMode: state.focusShieldMode,
        accessibilityServiceEnabled: state.accessibilityServiceEnabled,
        accentColor: state.accentColor,
        fontSize: state.fontSize,
        autoStartFocus: state.autoStartFocus,
        exams: state.exams,
        assignments: state.assignments,
        dashboardLayout: state.dashboardLayout,
        compactMode: state.compactMode,
        habitSettings: state.habitSettings,
        achievements: state.achievements,
        distractions: state.distractions,
        whitelistedApps: state.whitelistedApps,
        breakBlockingEnabled: state.breakBlockingEnabled,
        blockShorts: state.blockShorts,
        blockReels: state.blockReels,
        blockTikTok: state.blockTikTok,
        blockSnapchat: state.blockSnapchat,
        youtubeStudyMode: state.youtubeStudyMode,
        blockAdultWebsites: state.blockAdultWebsites,
        blockedWebsites: state.blockedWebsites,
        shortFormAllowance: state.shortFormAllowance,
        alwaysBlockedApps: state.alwaysBlockedApps,
        sessionBlockedApps: state.sessionBlockedApps,
        breakBlockedApps: state.breakBlockedApps,
        deepLockOnboardingComplete: state.deepLockOnboardingComplete,
        autoDnd: state.autoDnd,
        motivationEnabled: state.motivationEnabled,
        motivationTimes: state.motivationTimes,
        motivationDays: state.motivationDays,
        recentMotivationIds: state.recentMotivationIds,
        examModeEnabled: state.examModeEnabled,
        ambientSoundEnabled: state.ambientSoundEnabled,
        ambientVolume: state.ambientVolume,
        showExamCountdownOnStartup: state.showExamCountdownOnStartup,
        productivityScore: state.productivityScore,
        motivationCategories: state.motivationCategories,
        musicSettings: state.musicSettings,
        mainChallenge: state.mainChallenge,
        motivationStyle: state.motivationStyle,
        syllabus: state.syllabus,
        examDate: state.examDate,
      }),
      migrate: migrateStoreState,
      onRehydrateStorage: () => {
        return (state, error) => {
          if (error) {
            console.warn('Student OS: Storage rehydration failed:', error)

            // Layer 3B: Hydration failed — clear storage and reload ONCE.
            // Use sessionStorage flag to prevent infinite reload loop.
            const reloadKey = 'student-os-hydration-reload'
            try {
              const alreadyReloaded = sessionStorage.getItem(reloadKey)

              if (!alreadyReloaded) {
                sessionStorage.setItem(reloadKey, '1')
                try { localStorage.removeItem(STORAGE_KEY) } catch { /* best-effort */ }
                window.location.reload()
                return
              } else {
                // Already reloaded once — give up on reload, use default state
                sessionStorage.removeItem(reloadKey)
                // Continue below — Zustand will use slice defaults
              }
            } catch {
              // sessionStorage unavailable — skip reload guard, just continue
            }
          }

          if (typeof window !== 'undefined') {
            window.addEventListener('storage-data-cleaned', () => useStore.persist.rehydrate())
          }
          setTimeout(() => {
            if (state && state.focusSessions) {
              try {
                const store = useStore.getState()
                const recomputed = computeRecomputedProgress(state.focusSessions, store.progress)
                useStore.setState({ progress: recomputed })
              } catch (e) { console.warn('Progress recomputation failed:', e) }
            }
            if (state && Array.isArray(state.habits)) {
              try {
                const updatedHabits = state.habits.map((h: any) => {
                  const dates = Array.isArray(h.completedDates) ? h.completedDates : []
                  const streak = calculateStreak(dates)
                  const best = Math.max(calculateLongestStreak(dates), h.longestStreak ?? h.bestStreak ?? 0, streak)
                  return { ...h, streak, bestStreak: best, currentStreak: h.currentStreak ?? streak, longestStreak: h.longestStreak ?? best, completionRate: h.completionRate ?? calculateCompletionRate(dates), completedCount: h.completedCount ?? dates.length }
                })
                useStore.setState({ habits: updatedHabits })
              } catch (e) { console.warn('Habit streak recomputation failed:', e) }
            }
            if (state && state.achievements) {
              try { useStore.getState().checkAchievements() } catch (e) { console.warn('Achievement check failed on rehydration:', e) }
            }
            if (state && Array.isArray(state.habits)) { syncHabitAlarms(state.habits) }
            if (state && Array.isArray(state.events)) { syncPlannerAlarms(state.events) }
            const stats = getStorageStats()
            if (stats.isCritical) {
              window.dispatchEvent(new CustomEvent('storage-quota-warning', { detail: { stats } }))
              try { cleanupOldData() } catch {}
            }
          }, 0)
        }
      },
    }
  )
)

// Layer 3: Hydration timeout safety net — if something slips through
// Layers 1 and 2, force hydration completion after 3 seconds so the app
// loads with default state instead of hanging forever.
if (typeof window !== 'undefined') {
  setTimeout(() => {
    if (!useStore.persist.hasHydrated()) {
      console.warn('[Store] Hydration timeout after 3s — forcing completion')
      try {
        useStore.persist.rehydrate()
      } catch { /* best-effort */ }
      // If still not hydrated after rehydrate, force finish
      setTimeout(() => {
        if (!useStore.persist.hasHydrated()) {
          try {
            // Manually signal hydration complete so useHydrated() unblocks
            useStore.persist.onFinishHydration(() => {})()
          } catch { /* best-effort */ }
        }
      }, 500)
    }
  }, 3000)
}

export { STORAGE_KEY } from './constants'
export type {
  Exam, Assignment, Achievement, ProductivityScore,
  Task, Habit, FocusSession,
  PlannerReminder, PlannerTaskHistoryEntry, StudyEvent, PlannerSettings,
  UserProgress, TimerSettings, OnboardingProfile,
  FocusShieldMode, DistractionRecord,
} from './types'
export { exportBackup, importBackup, validateSessions, repairFocusSessions } from './storage'

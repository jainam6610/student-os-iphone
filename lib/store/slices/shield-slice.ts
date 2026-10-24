import type { FocusShieldMode, DistractionRecord } from '../types'
import type { MotivationCategory } from '../../motivation/messages'
import { normalizeDomain, normalizeDomainList } from '../../deep-lock/websites'

export type AppBlockMode = 'allow' | 'default' | 'focus' | 'always'

export interface ShieldSlice {
  focusShieldMode: FocusShieldMode
  setFocusShieldMode: (mode: FocusShieldMode) => void
  distractions: DistractionRecord[]
  addDistraction: (record: DistractionRecord) => void
  whitelistedApps: string[]
  addWhitelistedApp: (packageName: string) => void
  removeWhitelistedApp: (packageName: string) => void
  deepLockActive: boolean
  setDeepLockActive: (active: boolean) => void
  deepLockError: string | null
  setDeepLockError: (message: string | null) => void
  accessibilityServiceEnabled: boolean | null
  setAccessibilityServiceEnabled: (enabled: boolean) => void
  showAccessibilityPrompt: boolean
  setShowAccessibilityPrompt: (show: boolean) => void
  showAccessibilityDisclosure: boolean
  setShowAccessibilityDisclosure: (show: boolean) => void
  showFocusShieldSetup: boolean
  setShowFocusShieldSetup: (show: boolean) => void
  /** True once the user has finished (or skipped) the 2-page Deep Lock onboarding. */
  deepLockOnboardingComplete: boolean
  setDeepLockOnboardingComplete: (complete: boolean) => void
  autoDnd: boolean
  setAutoDnd: (enabled: boolean) => void
  breakBlockingEnabled: boolean
  setBreakBlockingEnabled: (enabled: boolean) => void
  blockShorts: boolean
  setBlockShorts: (enabled: boolean) => void
  blockReels: boolean
  setBlockReels: (enabled: boolean) => void
  /** Block TikTok's whole short-form feed (opt-in, whole app). */
  blockTikTok: boolean
  setBlockTikTok: (enabled: boolean) => void
  /** Block Snapchat Spotlight + Stories (opt-in, keeps Chat/Snaps working). */
  blockSnapchat: boolean
  setBlockSnapchat: (enabled: boolean) => void
  /** Gate YouTube's Home/browse feed; Search and videos stay usable (opt-in). */
  youtubeStudyMode: boolean
  setYoutubeStudyMode: (enabled: boolean) => void
  blockAdultWebsites: boolean
  setBlockAdultWebsites: (enabled: boolean) => void
  /** User-defined domains always blocked in browsers (e.g. "instagram.com"). */
  blockedWebsites: string[]
  addBlockedWebsite: (domain: string) => void
  removeBlockedWebsite: (domain: string) => void
  setBlockedWebsites: (domains: string[]) => void
  /** Shorts/Reels allowed per focus session before the player is closed (0 = block on open). */
  shortFormAllowance: number
  setShortFormAllowance: (value: number) => void
  /**
   * Blocked during focus sessions only. CategoryType.BLOCKED native-side: the
   * engine blocks it in STANDARD mode (session ACTIVE) and allows it when no
   * session is running. This is where onboarding puts the apps the user picks.
   */
  sessionBlockedApps: string[]
  addSessionBlockedApp: (packageName: string) => void
  removeSessionBlockedApp: (packageName: string) => void
  setSessionBlockedApps: (packages: string[]) => void
  /**
   * One atomic write for the 3-way "Allow / During focus / 24/7" control.
   * `allow` whitelists the package, `default` leaves it on no list at all, and
   * every mode clears the other three lists so a package can never appear on
   * two lists at once (they share one native row each).
   */
  setAppBlockMode: (packageName: string, mode: AppBlockMode) => void
  alwaysBlockedApps: string[]
  addAlwaysBlockedApp: (packageName: string) => void
  removeAlwaysBlockedApp: (packageName: string) => void
  setAlwaysBlockedApps: (packages: string[]) => void
  breakBlockedApps: string[]
  addBreakBlockedApp: (packageName: string) => void
  removeBreakBlockedApp: (packageName: string) => void
  setBreakBlockedApps: (packages: string[]) => void
  motivationEnabled: boolean
  motivationTimes: string[]
  motivationDays: number[]
  motivationCategories: MotivationCategory[]
  recentMotivationIds: string[]
  examModeEnabled: boolean
  setMotivationEnabled: (v: boolean) => void
  setMotivationTimes: (times: string[]) => void
  setMotivationDays: (days: number[]) => void
  addRecentMotivationId: (id: string) => void
  setExamModeEnabled: (v: boolean) => void
}

export const createShieldSlice = (set: any, _get?: any, _api?: any): ShieldSlice => ({
  focusShieldMode: 'normal' as FocusShieldMode,
  setFocusShieldMode: (mode) => {
    if (['normal', 'deep-lock'].includes(mode)) set({ focusShieldMode: mode as FocusShieldMode })
  },
  distractions: [],
  addDistraction: (record) => set((state: any) => ({ distractions: [...state.distractions, record] })),
  whitelistedApps: [],
  addWhitelistedApp: (packageName) => set((state: any) => {
    // Deny wins: an app the user explicitly blocked can never be whitelisted.
    // The native side enforces this too, but keeping the store consistent means
    // the UI never shows a package in both lists at once.
    if (
      state.alwaysBlockedApps.includes(packageName) ||
      state.breakBlockedApps.includes(packageName) ||
      state.sessionBlockedApps.includes(packageName)
    ) {
      return state
    }
    return {
      whitelistedApps: state.whitelistedApps.includes(packageName) ? state.whitelistedApps : [...state.whitelistedApps, packageName],
    }
  }),
  removeWhitelistedApp: (packageName) => set((state: any) => ({
    whitelistedApps: state.whitelistedApps.filter((p: string) => p !== packageName),
  })),
  deepLockActive: false,
  setDeepLockActive: (active) => set({ deepLockActive: active }),
  // Transient: last native start failure. Shown as a banner in Focus mode so a
  // session never silently runs without enforcement.
  deepLockError: null,
  setDeepLockError: (message) => set({ deepLockError: message }),
  accessibilityServiceEnabled: null,
  setAccessibilityServiceEnabled: (enabled) => set({ accessibilityServiceEnabled: enabled }),
  showAccessibilityPrompt: false,
  setShowAccessibilityPrompt: (show) => set({ showAccessibilityPrompt: show }),
  // Deliberately transient: this controls the one in-app disclosure immediately
  // before Android Accessibility Settings; it is never persisted as consent.
  showAccessibilityDisclosure: false,
  setShowAccessibilityDisclosure: (show) => set({ showAccessibilityDisclosure: show }),
  showFocusShieldSetup: false,
  setShowFocusShieldSetup: (show) => set({ showFocusShieldSetup: show }),
  // Not persisted before v24. The migration marks it complete for anyone who
  // already configured a policy so we never re-onboard an existing user.
  deepLockOnboardingComplete: false,
  setDeepLockOnboardingComplete: (complete) => set({ deepLockOnboardingComplete: complete }),
  autoDnd: false,
  setAutoDnd: (enabled) => set({ autoDnd: enabled }),
  // Whole-app blocking is opt-in — the lists start empty and nothing is
  // rejected unless the user picks it.
  //
  // v28: Shorts/Reels are OFF by default too. Unlike every other rule these two
  // read the accessibility view tree (node text, content descriptions, view IDs,
  // on-screen bounds) because the player is a fragment inside the host activity.
  // That is content inspection, so it needs an explicit opt-in; turning it on by
  // default meant every install inspected screens without consent.
  breakBlockingEnabled: true,
  setBreakBlockingEnabled: (enabled) => set({ breakBlockingEnabled: enabled }),
  blockShorts: false,
  setBlockShorts: (enabled) => set({ blockShorts: enabled }),
  blockReels: false,
  setBlockReels: (enabled) => set({ blockReels: enabled }),
  // Opt-in like whole-app blocking: a new filter must never silently start
  // rejecting content for an existing user. Only surfaces (not whole apps) are
  // the exception; TikTok/Snapchat are whole-app-ish so they default off.
  blockTikTok: false,
  setBlockTikTok: (enabled) => set({ blockTikTok: enabled }),
  blockSnapchat: false,
  setBlockSnapchat: (enabled) => set({ blockSnapchat: enabled }),
  youtubeStudyMode: false,
  setYoutubeStudyMode: (enabled) => set({ youtubeStudyMode: enabled }),
  blockAdultWebsites: false,
  setBlockAdultWebsites: (enabled) => set({ blockAdultWebsites: enabled }),
  blockedWebsites: [],
  addBlockedWebsite: (domain) => {
    const normalized = normalizeDomain(domain)
    if (!normalized) return
    set((state: any) => ({
      blockedWebsites: state.blockedWebsites.includes(normalized)
        ? state.blockedWebsites
        : [...state.blockedWebsites, normalized],
    }))
  },
  removeBlockedWebsite: (domain) => {
    const normalized = normalizeDomain(domain) ?? domain
    set((state: any) => ({
      blockedWebsites: state.blockedWebsites.filter((d: string) => d !== normalized),
    }))
  },
  setBlockedWebsites: (domains) => set({ blockedWebsites: normalizeDomainList(domains) }),
  shortFormAllowance: 1,
  setShortFormAllowance: (value) => set({
    shortFormAllowance: Number.isFinite(value) ? Math.min(10, Math.max(0, Math.round(value))) : 1,
  }),
  alwaysBlockedApps: [],
  addAlwaysBlockedApp: (packageName) => set((state: any) => {
    if (state.alwaysBlockedApps.includes(packageName)) return state
    // Promote: a package lives on exactly one block list. Moving it to 24/7
    // clears it from the weaker session/break lists (one row per package in the
    // native DB, and ALWAYS_BLOCKED is the strongest category).
    return {
      alwaysBlockedApps: [...state.alwaysBlockedApps, packageName],
      sessionBlockedApps: state.sessionBlockedApps.filter((p: string) => p !== packageName),
      breakBlockedApps: state.breakBlockedApps.filter((p: string) => p !== packageName),
      whitelistedApps: state.whitelistedApps.filter((p: string) => p !== packageName),
    }
  }),
  removeAlwaysBlockedApp: (packageName) => set((state: any) => ({
    alwaysBlockedApps: state.alwaysBlockedApps.filter((p: string) => p !== packageName),
  })),
  setAlwaysBlockedApps: (packages) => set((state: any) => ({
    alwaysBlockedApps: packages,
    sessionBlockedApps: state.sessionBlockedApps.filter((p: string) => !packages.includes(p)),
    breakBlockedApps: state.breakBlockedApps.filter((p: string) => !packages.includes(p)),
    whitelistedApps: state.whitelistedApps.filter((p: string) => !packages.includes(p)),
  })),
  sessionBlockedApps: [],
  addSessionBlockedApp: (packageName) => set((state: any) => {
    // Stronger lists win: never duplicate a 24/7 or break block.
    if (
      state.alwaysBlockedApps.includes(packageName) ||
      state.breakBlockedApps.includes(packageName)
    ) {
      return state
    }
    if (state.sessionBlockedApps.includes(packageName)) return state
    return {
      sessionBlockedApps: [...state.sessionBlockedApps, packageName],
      whitelistedApps: state.whitelistedApps.filter((p: string) => p !== packageName),
    }
  }),
  removeSessionBlockedApp: (packageName) => set((state: any) => ({
    sessionBlockedApps: state.sessionBlockedApps.filter((p: string) => p !== packageName),
  })),
  setSessionBlockedApps: (packages) => set((state: any) => ({
    // A package already on a stronger list keeps its stronger category.
    sessionBlockedApps: packages.filter(
      (p) => !state.alwaysBlockedApps.includes(p) && !state.breakBlockedApps.includes(p),
    ),
    whitelistedApps: state.whitelistedApps.filter((p: string) => !packages.includes(p)),
  })),
  setAppBlockMode: (packageName, mode) => set((state: any) => {
    const without = (list: string[] | undefined) =>
      (list ?? []).filter((p: string) => p !== packageName)
    const next = {
      whitelistedApps: without(state.whitelistedApps),
      sessionBlockedApps: without(state.sessionBlockedApps),
      alwaysBlockedApps: without(state.alwaysBlockedApps),
      breakBlockedApps: without(state.breakBlockedApps),
    }
    if (mode === 'allow') next.whitelistedApps = [...next.whitelistedApps, packageName]
    else if (mode === 'focus') next.sessionBlockedApps = [...next.sessionBlockedApps, packageName]
    else if (mode === 'always') next.alwaysBlockedApps = [...next.alwaysBlockedApps, packageName]
    // 'default' → on no list at all (plain "not blocked")
    return next
  }),
  breakBlockedApps: [],
  addBreakBlockedApp: (packageName) => set((state: any) => ({
    breakBlockedApps: state.breakBlockedApps.includes(packageName) ? state.breakBlockedApps : [...state.breakBlockedApps, packageName],
    // Break blocking is stronger than session-only: drop the duplicate.
    sessionBlockedApps: state.sessionBlockedApps.filter((p: string) => p !== packageName),
    // ...and deny wins over allow, same as the other block lists.
    whitelistedApps: state.whitelistedApps.filter((p: string) => p !== packageName),
  })),
  removeBreakBlockedApp: (packageName) => set((state: any) => ({
    breakBlockedApps: state.breakBlockedApps.filter((p: string) => p !== packageName),
  })),
  setBreakBlockedApps: (packages) => set((state: any) => ({
    breakBlockedApps: packages,
    sessionBlockedApps: state.sessionBlockedApps.filter((p: string) => !packages.includes(p)),
    whitelistedApps: state.whitelistedApps.filter((p: string) => !packages.includes(p)),
  })),
  motivationEnabled: true,
  motivationTimes: ['09:00', '14:00', '20:00'],
  motivationDays: [0, 1, 2, 3, 4, 5, 6],
  motivationCategories: ['start', 'grind', 'streak', 'comeback', 'exam', 'late_night'],
  recentMotivationIds: [],
  examModeEnabled: false,
  setMotivationEnabled: (v) => set({ motivationEnabled: v }),
  setMotivationTimes: (times) => set({ motivationTimes: times }),
  setMotivationDays: (days) => set({ motivationDays: days }),
  addRecentMotivationId: (id) => set((state: any) => ({
    recentMotivationIds: [id, ...state.recentMotivationIds].slice(0, 10)
  })),
  setExamModeEnabled: (v) => set({ examModeEnabled: v }),
})


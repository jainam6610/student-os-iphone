import { useStore } from '@/lib/store'
import { deepLockPlugin } from './plugin'

/**
 * Single place that pushes the persisted shield settings from the Zustand
 * store down to the native FocusShield engine.
 *
 * Why this exists: the accessibility service keeps its own in-memory copy of
 * the block lists and content filters. Before this module each list was pushed
 * from exactly one React component, which meant a cold start (process death /
 * reboot) left native on defaults — every content filter silently OFF until the
 * user happened to open Deep Lock → Smart Content Blocker again. That is the
 * "content blocker does not work" bug. Now the app pushes the full snapshot on
 * boot, on visibility change, and after every edit.
 */
export interface ShieldSyncResult {
  /** True when native accepted the snapshot (accessibility service bound). */
  applied: boolean
  /** False when the accessibility service is not running — settings persist but cannot be enforced. */
  serviceRunning: boolean
  blockedWebsites: number
  error?: string
}

const NOT_RUNNING: ShieldSyncResult = {
  applied: false,
  serviceRunning: false,
  blockedWebsites: 0,
  error: 'Accessibility service is not running',
}

let queue: Promise<ShieldSyncResult> = Promise.resolve(NOT_RUNNING)

/** One-time native → store adoption per app-session. See pullNativeLists(). */
let listsAdopted = false

async function waitForStoreHydration(): Promise<void> {
  try {
    if (useStore.persist.hasHydrated()) return
    await new Promise<void>((resolve) => {
      let settled = false
      const finish = () => {
        if (settled) return
        settled = true
        unsubscribe?.()
        resolve()
      }
      const unsubscribe = useStore.persist.onFinishHydration?.(finish)
      setTimeout(finish, 2000)
    })
  } catch {
    // Non-fatal: proceed with whatever the store currently holds.
  }
}

/**
 * Wait until Zustand has rehydrated the persisted shield lists. Toggling before
 * that used to be silently overwritten when rehydration replaced the state.
 */
export function waitForShieldStoreHydration(): Promise<void> {
  return waitForStoreHydration()
}

async function pullNativeLists(): Promise<void> {
  // Native is only the last writer for the FIRST sync of an app-session (e.g. a
  // reinstall with app-data restore). Re-adopting on every sync made "unblock
  // all" impossible: the native list was read straight back into the store
  // before the empty list could ever be pushed. One adoption per app-session,
  // then the JS store is authoritative.
  if (listsAdopted) return
  try {
    const state = useStore.getState()
    const [always, brk, session] = await Promise.all([
      deepLockPlugin.getAlwaysBlockedList().catch(() => ({ packages: [] as string[] })),
      deepLockPlugin.getBreakBlockedList().catch(() => ({ packages: [] as string[] })),
      deepLockPlugin.getSessionBlockedList().catch(() => ({ packages: [] as string[], success: false })),
    ])
    if (state.alwaysBlockedApps.length === 0 && always.packages.length > 0) {
      state.setAlwaysBlockedApps(always.packages)
    }
    if (state.breakBlockedApps.length === 0 && brk.packages.length > 0) {
      state.setBreakBlockedApps(brk.packages)
    }
    if (session.success && state.sessionBlockedApps.length === 0 && session.packages.length > 0) {
      state.setSessionBlockedApps(session.packages)
    }
    listsAdopted = true
  } catch {
    // Native unavailable (web) — retry on the next sync.
  }
}

export function syncShieldSettingsToNative(): Promise<ShieldSyncResult> {
  // Serialise pushes: a toggle fired while a previous sync is still crossing the
  // bridge must re-read the store afterwards, otherwise the newest value is lost.
  const next = queue.catch(() => NOT_RUNNING).then(() => runSync())
  queue = next
  return next
}

async function runSync(): Promise<ShieldSyncResult> {
  try {
    // A cold start can render Deep Lock before Zustand rehydrates. Pushing the
    // default (empty) lists in that window wiped the user's native selections —
    // the "my blocked apps disappeared" bug. Wait for hydration first.
    await waitForStoreHydration()

    // Native is the last writer when the web store is empty (e.g. reinstall with
    // app-data restore). Merge its lists into the store instead of overwriting.
    await pullNativeLists()

    const s = useStore.getState()
    const blockedWebsites = Array.isArray(s.blockedWebsites) ? s.blockedWebsites : []

    // Lists first: they decide whether an app is blocked at all, and the
    // filters below only switch behaviour on top of them.
    await deepLockPlugin.updateAlwaysBlockedList(s.alwaysBlockedApps ?? [])
    await deepLockPlugin.updateBreakBlockedList(s.breakBlockedApps ?? [])
    // Break-time blocking must stay on while there is anything to keep blocked.
    // Pushing the bare `breakBlockingEnabled` toggle (default false) turned the
    // native break-blocker off even though 24/7 apps were selected, so pausing a
    // session let those apps through.
    const breakBlockingActive =
      Boolean(s.breakBlockingEnabled) ||
      (s.alwaysBlockedApps?.length ?? 0) > 0 ||
      (s.breakBlockedApps?.length ?? 0) > 0
    await deepLockPlugin.setBreakBlockingEnabled(breakBlockingActive)

    const res = await deepLockPlugin.setContentFilters({
      blockShorts: Boolean(s.blockShorts),
      blockReels: Boolean(s.blockReels),
      blockTikTok: Boolean(s.blockTikTok),
      blockSnapchat: Boolean(s.blockSnapchat),
      youtubeStudyMode: Boolean(s.youtubeStudyMode),
      blockAdultUrls: Boolean(s.blockAdultWebsites),
      blockedWebsites,
      shortFormAllowance: typeof s.shortFormAllowance === 'number' ? s.shortFormAllowance : 1,
    })

    // The 10-minute "Are you on track?" check-in is driven by the Motivation
    // Engine toggle (no separate setting). Push it alongside the filters.
    await deepLockPlugin.setCheckInEnabled(Boolean(s.motivationEnabled))

    // The push persists the snapshot even when the accessibility service is
    // down, so ask native whether anything is actually being enforced right now.
    const state = await deepLockPlugin.getContentFilters()

    return {
      applied: res.success,
      serviceRunning: state.serviceRunning,
      blockedWebsites: blockedWebsites.length,
      error: state.serviceRunning ? undefined : NOT_RUNNING.error,
    }
  } catch (e: any) {
    return { ...NOT_RUNNING, error: e?.message ?? 'Sync failed' }
  }
}


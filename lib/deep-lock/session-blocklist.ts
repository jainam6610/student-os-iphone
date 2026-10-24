import { useStore } from '@/lib/store'
import { deepLockPlugin } from './plugin'
import { waitForShieldStoreHydration } from './shield-sync'

/**
 * Session-only blocklist (native `CategoryType.BLOCKED`).
 *
 * These apps are blocked while a focus session is ACTIVE and allowed again the
 * moment it ends. Two writers exist:
 *
 *  - the JS "App Blocking" card / onboarding (this module), and
 *  - the native "Manage Apps" screen, which writes `BLOCKED` rows straight to
 *    the Room DB with no bridge back to JS.
 *
 * Because of that second writer we intentionally do NOT push this list from the
 * generic `syncShieldSettingsToNative()` snapshot: doing so would wipe whatever
 * the user toggled in Manage Apps moments earlier. Instead:
 *
 *  - `refreshSessionBlockList()` pulls native → store when the card mounts or
 *    the app becomes visible again,
 *  - `persistSessionBlockList()` pushes only on an explicit JS edit.
 */

/** Mirror the native BLOCKED rows into the store for the dashboard UI. */
export async function refreshSessionBlockList(): Promise<string[]> {
  await waitForShieldStoreHydration()
  try {
    const { packages, success } = await deepLockPlugin.getSessionBlockedList()
    const current = useStore.getState().sessionBlockedApps
    // `success: false` means "no native bridge" (web/PWA). Never let a missing
    // platform wipe a list the user configured in the browser.
    if (!success) return current
    // Only write when it actually changed — avoids a re-render loop when the
    // card refreshes on every visibility change.
    if (packages.length !== current.length || packages.some((p) => !current.includes(p))) {
      useStore.getState().setSessionBlockedApps(packages)
    }
    return useStore.getState().sessionBlockedApps
  } catch {
    return useStore.getState().sessionBlockedApps
  }
}

/** Persist an explicit edit made in the JS UI (store first, then native). */
export async function persistSessionBlockList(packages: string[]): Promise<void> {
  useStore.getState().setSessionBlockedApps(packages)
  try {
    // Re-read: setSessionBlockedApps applies deny-wins against the stronger
    // 24/7 / break lists, so push exactly what the store ended up holding.
    await deepLockPlugin.updateSessionBlockedList(useStore.getState().sessionBlockedApps)
  } catch {
    // Web or native unavailable — the persisted store still holds the list.
  }
}

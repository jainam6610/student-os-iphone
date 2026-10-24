import { useStore } from '@/lib/store'
import type { AppBlockMode } from '@/lib/store/slices/shield-slice'
import { deepLockPlugin } from './plugin'
import { syncShieldSettingsToNative } from './shield-sync'

/**
 * Single write path for the 3-way block control (Allow / During focus / 24/7).
 *
 * Why one helper: the four lists share a single native row per package, so a
 * mode change has to reach native as a set of mutually exclusive writes. Doing
 * that from each component is how the lists drifted apart before (a package
 * could be whitelisted and blocked at once).
 *
 *  - allow-list → USER_ALLOWED rows (also what Strict mode keeps usable)
 *  - focus     → BLOCKED rows (blocked only while a session is ACTIVE)
 *  - 24/7      → ALWAYS_BLOCKED rows (blocked with or without a session)
 *  - default   → on no list (plain "not blocked")
 */

/** Push all three lists so native matches the store exactly. */
export async function pushShieldLists(): Promise<void> {
  const s = useStore.getState()
  await Promise.allSettled([
    deepLockPlugin.updateWhitelist(s.whitelistedApps ?? []),
    // Session list is written explicitly here (not by the generic sync) so the
    // native "Manage Apps" screen can never be silently overwritten by a
    // background sync — see lib/deep-lock/session-blocklist.ts.
    deepLockPlugin.updateSessionBlockedList(s.sessionBlockedApps ?? []),
  ])
  // Pushes 24/7 + break lists and the content filters.
  await syncShieldSettingsToNative()
}

/** Set one app's mode and mirror it to native. Store first → instant UI. */
export async function applyAppMode(packageName: string, mode: AppBlockMode): Promise<void> {
  useStore.getState().setAppBlockMode(packageName, mode)
  await pushShieldLists()
}

import { deepLockPlugin, type InstalledApp } from './plugin'

/**
 * Installed-app list cache.
 *
 * `getInstalledApps()` walks PackageManager and, when icons are requested,
 * base64-encodes one bitmap per app. Both are expensive enough that the app
 * picker and the onboarding checklist should share a single call per session.
 * The cache is keyed by `withIcons` because the onboarding list skips icons
 * (smaller payload) while the picker needs them.
 */
const cache = new Map<boolean, InstalledApp[]>()
const inflight = new Map<boolean, Promise<InstalledApp[]>>()

export function loadInstalledApps(withIcons = false): Promise<InstalledApp[]> {
  const cached = cache.get(withIcons)
  if (cached) return Promise.resolve(cached)

  const pending = inflight.get(withIcons)
  if (pending) return pending

  const request = deepLockPlugin
    .getInstalledApps({ withIcons })
    .then((res) => {
      const apps = res.apps.filter((a) => Boolean(a?.packageName))
      cache.set(withIcons, apps)
      return apps
    })
    .catch(() => [] as InstalledApp[])
    .finally(() => {
      inflight.delete(withIcons)
    })

  inflight.set(withIcons, request)
  return request
}

/** Drop the cache (e.g. after an install/uninstall while the app is open). */
export function invalidateInstalledApps(): void {
  cache.clear()
  inflight.clear()
}

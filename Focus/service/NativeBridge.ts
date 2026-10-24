let deepLockPlugin: any = null
try {
  deepLockPlugin = require('@/lib/deep-lock/plugin').deepLockPlugin
} catch {}

function describeStartError(code?: string): string {
  switch (code) {
    case 'accessibility_service_not_running':
    case 'service_not_running':
      return 'Deep Lock could not start because the accessibility service is off. Your timer is running without app blocking.'
    case 'strict_allowlist_empty':
      return 'Strict mode needs at least one allowed app. Add one under Deep Lock → Allowed Apps, or turn Strict off.'
    case 'session_not_active':
      return 'Deep Lock could not start the protected session in time. Your timer is running without app blocking.'
    case 'blocking_not_armed_timeout':
      return 'Deep Lock did not finish arming the blocklist. Your timer is running without app blocking.'
    default:
      return 'Deep Lock could not arm the blocklist. Your timer is running without app blocking.'
  }
}

export interface NativeFocusSessionState {
  hasActiveSession: boolean
  sessionId?: string
  status?: string
  startTime?: number
  endTime?: number
  plannedDuration?: number
  remainingMs?: number
  strict?: boolean
}

export interface NativeFocusCompletion {
  sessionId: string
  actualDurationMs: number
  plannedDuration: number
  startTime?: number
  endTime?: number
}

export const nativeBridge = {
  async start(
    durationMinutes: number,
    whitelist: string[] = [],
    strict = false,
    breakBlockedPackages: string[] = [],
    mode: 'deep-lock' | 'break' = 'deep-lock',
  ): Promise<string | null> {
    if (!deepLockPlugin?.startDeepLock) return null
    try {
      const result = await deepLockPlugin.startDeepLock(whitelist, mode, durationMinutes, strict, breakBlockedPackages)
      if (result?.success === false) {
        const message = describeStartError(result.error)
        console.warn('[NativeBridge] Deep Lock start failed:', result.error)
        try { require('@/lib/store').useStore.getState().setDeepLockError(message) } catch {}
        return null
      }
      try { require('@/lib/store').useStore.getState().setDeepLockError(null) } catch {}
      return result?.sessionId ?? null
    } catch (e) {
      console.warn('[NativeBridge] Deep Lock start failed:', e)
      try { require('@/lib/store').useStore.getState().setDeepLockError(describeStartError()) } catch {}
    }
    return null
  },

  async updateAlwaysBlockedList(packages: string[]): Promise<boolean> {
    if (!deepLockPlugin?.updateAlwaysBlockedList) return false
    try {
      const res = await deepLockPlugin.updateAlwaysBlockedList(packages)
      return res?.success ?? false
    } catch (e) {
      console.warn('[NativeBridge] updateAlwaysBlockedList failed:', e)
      return false
    }
  },

  async updateBreakBlockedList(packages: string[]): Promise<boolean> {
    if (!deepLockPlugin?.updateBreakBlockedList) return false
    try {
      const res = await deepLockPlugin.updateBreakBlockedList(packages)
      return res?.success ?? false
    } catch (e) {
      console.warn('[NativeBridge] updateBreakBlockedList failed:', e)
      return false
    }
  },

  async setContentFilters(filters: { blockShorts: boolean; blockReels: boolean; blockAdultUrls: boolean; blockedWebsites?: string[]; shortFormAllowance?: number }): Promise<boolean> {
    if (!deepLockPlugin?.setContentFilters) return false
    try {
      const res = await deepLockPlugin.setContentFilters(filters)
      return res?.success ?? false
    } catch (e) {
      console.warn('[NativeBridge] setContentFilters failed:', e)
      return false
    }
  },

  async setBreakBlockingEnabled(enabled: boolean): Promise<boolean> {
    if (!deepLockPlugin?.setBreakBlockingEnabled) return false
    try {
      const res = await deepLockPlugin.setBreakBlockingEnabled(enabled)
      return res?.success ?? false
    } catch (e) {
      console.warn('[NativeBridge] setBreakBlockingEnabled failed:', e)
      return false
    }
  },

  async getAlwaysBlockedList(): Promise<string[]> {
    if (!deepLockPlugin?.getAlwaysBlockedList) return []
    try {
      const res = await deepLockPlugin.getAlwaysBlockedList()
      return res?.packages ?? []
    } catch (e) {
      console.warn('[NativeBridge] getAlwaysBlockedList failed:', e)
      return []
    }
  },

  async pause(): Promise<void> {
    if (!deepLockPlugin?.pauseDeepLock) return
    try { await deepLockPlugin.pauseDeepLock() } catch (e) { console.warn('[NativeBridge] pause failed:', e) }
  },

  async resume(): Promise<void> {
    if (!deepLockPlugin?.resumeDeepLock) return
    try { await deepLockPlugin.resumeDeepLock() } catch (e) { console.warn('[NativeBridge] resume failed:', e) }
  },

  async stop(): Promise<void> {
    if (!deepLockPlugin?.stopDeepLock) return
    try { await deepLockPlugin.stopDeepLock() } catch (e) { console.warn('[NativeBridge] stop failed:', e) }
  },

  async getSessionState(): Promise<NativeFocusSessionState | null> {
    if (!deepLockPlugin?.getSessionState) return null
    try { return await deepLockPlugin.getSessionState() } catch (e) {
      console.warn('[NativeBridge] session state unavailable:', e)
      return null
    }
  },

  onSessionCompleted(handler: (completion: NativeFocusCompletion) => void): () => void {
    if (!deepLockPlugin?.addListener) return () => {}
    try {
      const listener = deepLockPlugin.addListener('sessionCompleted', handler)
      return () => listener?.remove?.()
    } catch (e) {
      console.warn('[NativeBridge] completion listener unavailable:', e)
      return () => {}
    }
  },

  /**
   * Fires when the native session state changes (e.g. Pause/Stop tapped on the
   * persistent notification). Keeps the in-app timer authoritative-synced.
   */
  onSessionStateChanged(handler: (state: string) => void): () => void {
    if (!deepLockPlugin?.addListener) return () => {}
    try {
      const listener = deepLockPlugin.addListener('focusSessionStateChanged', (data: { state?: string }) => {
        handler(data?.state ?? '')
      })
      return () => listener?.remove?.()
    } catch (e) {
      console.warn('[NativeBridge] session state listener unavailable:', e)
      return () => {}
    }
  },

  /**
   * Drains completions finished natively while React was unmounted. Idempotent
   * with the live `sessionCompleted` listener — callers dedupe by sessionId.
   */
  async consumePendingCompletion(): Promise<NativeFocusCompletion[]> {
    if (!deepLockPlugin?.consumePendingCompletion) return []
    try {
      const res = await deepLockPlugin.consumePendingCompletion()
      const arr = res?.completions
      if (!Array.isArray(arr)) return []
      return arr
        .map((c: any): NativeFocusCompletion => ({
          sessionId: typeof c?.sessionId === 'string' ? c.sessionId : '',
          actualDurationMs: Number(c?.actualDurationMs) || 0,
          plannedDuration: Number(c?.plannedDuration) || 0,
          startTime: c?.startTime != null ? Number(c.startTime) : undefined,
          endTime: c?.endTime != null ? Number(c.endTime) : undefined,
        }))
        .filter((c) => c.sessionId)
    } catch (e) {
      console.warn('[NativeBridge] pending completion drain failed:', e)
      return []
    }
  },
}

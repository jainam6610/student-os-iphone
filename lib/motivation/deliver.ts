import { Capacitor } from '@capacitor/core'
import { useStore } from '@/lib/store'
import Alarm from '@/lib/plugins/alarm-plugin'
import { pickMessage, type MotivationCategory } from './messages'

/**
 * Motivation is notification-only.
 *
 * The old implementation rendered a full-screen in-app popup (`MotivationPopup`)
 * and hijacked the app. Contextual triggers (win / start / grind / comeback) and
 * the Preview button now post a calm local notification through the existing
 * native `ThoughtNotifier` (via `Alarm.postThought`).
 *
 * Web/PWA has no ThoughtNotifier, so delivery is a no-op there.
 */

// Global throttle (not per-instance). The Preview button bypasses it.
const THROTTLE_KEY = 'student-os-last-popup'
const THROTTLE_MS = 30 * 60 * 1000

function isThrottled(): boolean {
  try {
    const last = parseInt(localStorage.getItem(THROTTLE_KEY) ?? '0', 10)
    return Date.now() - last < THROTTLE_MS
  } catch {
    return false
  }
}

function stampThrottle(): void {
  try {
    localStorage.setItem(THROTTLE_KEY, String(Date.now()))
  } catch {
    /* storage unavailable — throttle simply never engages */
  }
}

/**
 * @param force true only for the Preview button: bypasses the throttle *and*
 *   the enabled gate so the user can always see what a thought looks like.
 */
export async function showMotivation(
  category: MotivationCategory,
  force = false,
): Promise<void> {
  if (!Capacitor.isNativePlatform()) return

  const {
    motivationEnabled,
    recentMotivationIds,
    addRecentMotivationId,
  } = useStore.getState()

  if (!motivationEnabled && !force) return
  if (!force && isThrottled()) return

  const recentIds = recentMotivationIds ?? []
  const msg = pickMessage(category, recentIds)
  addRecentMotivationId(msg.id)

  if (!force) stampThrottle()

  try {
    await Alarm.postThought({
      entityId: `motivation-${Date.now()}`,
      title: `${msg.emoji} ${msg.title}`,
      body: msg.body,
    })
  } catch (e) {
    // Never let a failed nudge surface as an app error.
    if (process.env.NODE_ENV === 'development') {
      console.warn('[Motivation] postThought failed:', e)
    }
  }
}

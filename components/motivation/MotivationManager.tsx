'use client'

import { useEffect } from 'react'
import { Capacitor } from '@capacitor/core'
import { useStore } from '@/lib/store'
import { syncMotivationAlarms } from '@/lib/motivation/scheduler'
import { showMotivation } from '@/lib/motivation/deliver'

/** Days without a focus session before the "comeback" nudge fires. */
const COMEBACK_GAP_DAYS = 3
const DAY_MS = 86_400_000

/**
 * Motivation is notification-only now: no popup, no in-app takeover.
 * This component keeps the scheduled thoughts alive and fires the contextual
 * "comeback" trigger. Everything else (win / start / grind) is triggered where
 * the event happens, and always lands as a native notification.
 */
export function MotivationManager() {
  // Recreate one-shot native alarms whenever the app starts if enabled.
  useEffect(() => {
    const {
      motivationEnabled,
      motivationTimes,
      motivationDays,
      recentMotivationIds,
    } = useStore.getState()

    if (motivationEnabled && motivationTimes.length > 0) {
      void syncMotivationAlarms(
        motivationTimes,
        motivationDays,
        motivationEnabled,
        recentMotivationIds,
      )
    }
  }, [])

  // Comeback trigger: first launch after a real gap in studying.
  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return
    const sessions = useStore.getState().focusSessions
    if (sessions.length === 0) return
    let lastStart = 0
    for (const s of sessions) {
      const t = s.startTime instanceof Date ? s.startTime.getTime() : new Date(s.startTime).getTime()
      if (Number.isFinite(t) && t > lastStart) lastStart = t
    }
    if (!lastStart) return
    if (Date.now() - lastStart >= COMEBACK_GAP_DAYS * DAY_MS) {
      void showMotivation('comeback')
    }
  }, [])

  return null
}

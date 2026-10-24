'use client'

import { useState, useEffect, useCallback } from 'react'
import type { FocusSession, CompletionData } from '../service/FocusTypes'
import { focusService } from '../service/FocusService'
import { focusEventBus } from '../service/FocusEvents'
import { useStore } from '@/lib/store'

/** Long-break length for each offered short-break tier (5 / 15 / 30 minutes). */
const LONG_BREAK_MINUTES: Record<number, number> = { 5: 15, 10: 20, 15: 30, 20: 30 }

export function useFocus() {
  const [session, setSession] = useState<FocusSession | null>(() => focusService.getSnapshot())
  const [completionData, setCompletionData] = useState<CompletionData | null>(null)
  const [isOnBreak, setIsOnBreak] = useState(false)

  const timerSettings = useStore(s => s.timerSettings)
  const focusSessions = useStore(s => s.focusSessions)

  useEffect(() => {
    setSession(focusService.getSnapshot())
    focusService.syncFromService()

    const unsubscribe = focusService.subscribe(setSession)

    void focusService.reconcileNativeState().then(() => {
      focusService.syncFromService()
      setSession(focusService.getSnapshot())
    })

    const unsubs = [
      focusEventBus.on('sessionStarted', (e) => {
        setCompletionData(null)
        setIsOnBreak(e.session.kind === 'break')
      }),
      focusEventBus.on('sessionCompleted', (e) => {
        const s = e.session
        const score = s.plannedDuration > 0
          ? Math.min(100, Math.round((s.elapsed / s.plannedDuration) * 100))
          : 0
        const breakMinutes = Math.round(s.breakMinutes ?? 0)

        // A completed break just ends the cycle — never auto-start another break.
        if (s.kind === 'break') {
          setIsOnBreak(false)
          setCompletionData({ duration: Math.round(s.elapsed / 60), score, interrupted: false, breakMinutes })
          return
        }

        if (timerSettings.autoBreak) {
          // Only count focus sessions toward the long-break cadence.
          const completedCount = focusSessions.filter(fs => fs.completed && fs.type !== 'break').length
          const isLongBreak = timerSettings.longBreakFrequency > 0
            && completedCount > 0
            && completedCount % timerSettings.longBreakFrequency === 0
          const breakMinutes = isLongBreak ? LONG_BREAK_MINUTES[timerSettings.breakDuration] ?? Math.min(60, timerSettings.breakDuration * 2) : timerSettings.breakDuration
          setIsOnBreak(true)
          focusService.start(breakMinutes, 'break')
          return
        }

        setCompletionData({ duration: Math.round(s.elapsed / 60), score, interrupted: false, breakMinutes })
      }),
      focusEventBus.on('sessionStopped', (e) => {
        const s = e.session
        const score = s.plannedDuration > 0
          ? Math.min(100, Math.round((s.elapsed / s.plannedDuration) * 100))
          : 0
        setIsOnBreak(false)
        setCompletionData({
          duration: Math.round(s.elapsed / 60),
          score,
          interrupted: true,
          breakMinutes: Math.round(s.breakMinutes ?? 0),
        })
      }),
    ]
    return () => {
      unsubscribe()
      unsubs.forEach(u => u())
    }
  }, [timerSettings, focusSessions])

  const start = useCallback((durationMinutes: number) => {
    setIsOnBreak(false)
    focusService.start(durationMinutes)
  }, [])

  /** Start a standalone break session (5 / 15 / 30 min). */
  const startBreak = useCallback((minutes: number) => {
    if (focusService.getSnapshot()) return
    setIsOnBreak(true)
    focusService.start(minutes, 'break')
  }, [])

  const pause = useCallback(() => {
    focusService.pause()
  }, [])

  const resume = useCallback(() => {
    focusService.resume()
  }, [])

  const stop = useCallback(() => {
    focusService.stop()
  }, [])

  const dismissCompletion = useCallback(() => {
    setCompletionData(null)
  }, [])

  const skipBreak = useCallback(() => {
    focusService.stop()
    setIsOnBreak(false)
  }, [])

  const status = session?.status ?? 'idle'
  const isRunning = status === 'running'
  const isPaused = status === 'paused'
  const isIdle = status === 'idle'
  const hasActiveSession = isRunning || isPaused
  const secondsRemaining = session?.remaining ?? 0
  const progressPercent = session && session.plannedDuration > 0
    ? ((session.plannedDuration - secondsRemaining) / session.plannedDuration) * 100
    : 0

  return {
    session,
    status,
    isRunning,
    isPaused,
    isIdle,
    hasActiveSession,
    isOnBreak,
    secondsRemaining,
    progressPercent,
    completionData,
    start,
    startBreak,
    pause,
    resume,
    stop,
    skipBreak,
    dismissCompletion,
  }
}

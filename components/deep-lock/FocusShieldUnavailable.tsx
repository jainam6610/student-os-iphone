'use client'

import { Card } from '@/components/ui/glass-card'
import { ShieldOff, Timer, CalendarClock, Smartphone } from 'lucide-react'
import { isIOS, isStandalone } from '@/lib/platform'

/**
 * Shown instead of the Focus Shield dashboard on any platform that cannot run
 * an Android Accessibility Service — iOS, desktop, and plain browser tabs.
 *
 * Being explicit is better than a dashboard where every button fails with
 * "Not on native Android platform".
 */
export function FocusShieldUnavailable({ compact = false }: { compact?: boolean }) {
  const ios = isIOS()
  const installed = isStandalone()

  const reason = ios
    ? installed
      ? 'iOS does not allow an app to block other apps. Deep Lock needs Android\'s Accessibility Service, so it is only available in the Android app.'
      : 'iOS does not allow an app to block other apps. Deep Lock is only available in the Android app.'
    : 'Deep Lock needs Android\'s Accessibility Service. Open Student OS in the Android app to use it.'

  if (compact) {
    return (
      <div className="rounded-xl border border-border/60 bg-secondary/40 p-3">
        <div className="flex items-start gap-2.5">
          <ShieldOff className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
          <div className="min-w-0">
            <p className="text-xs font-medium text-foreground">App blocking is Android-only</p>
            <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{reason}</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-2xl space-y-4 pb-8">
      <Card className="p-6">
        <div className="flex items-start gap-4">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-secondary">
            <ShieldOff className="h-5 w-5 text-muted-foreground" />
          </div>
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-foreground">Deep Lock is Android-only</h1>
            <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{reason}</p>
          </div>
        </div>
      </Card>

      <Card className="p-5">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          What still works here
        </p>
        <ul className="mt-3 space-y-3">
          <li className="flex items-start gap-3">
            <Timer className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
            <span className="text-sm leading-relaxed text-muted-foreground">
              <span className="font-medium text-foreground">Focus timer</span> — full sessions,
              streaks, XP and analytics all run normally.
            </span>
          </li>
          <li className="flex items-start gap-3">
            <CalendarClock className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
            <span className="text-sm leading-relaxed text-muted-foreground">
              <span className="font-medium text-foreground">Reminders via Calendar</span> — export
              your routines and study blocks as a calendar file so alarms still reach you when the
              app is closed.
            </span>
          </li>
          {ios && (
            <li className="flex items-start gap-3">
              <Smartphone className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
              <span className="text-sm leading-relaxed text-muted-foreground">
                <span className="font-medium text-foreground">Screen Time</span> — Apple&apos;s own
                Screen Time limits are the closest equivalent to app blocking on iPhone.
              </span>
            </li>
          )}
        </ul>
      </Card>
    </div>
  )
}

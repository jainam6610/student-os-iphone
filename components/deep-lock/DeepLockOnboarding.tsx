'use client'

import { useState, useEffect, useMemo, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { useStore } from '@/lib/store'
import { deepLockPlugin, type DeepLockStatus, type InstalledApp } from '@/lib/deep-lock/plugin'
import { persistSessionBlockList } from '@/lib/deep-lock/session-blocklist'
import { syncShieldSettingsToNative } from '@/lib/deep-lock/shield-sync'
import { useToast } from '@/components/ui/toast'
import { cn } from '@/lib/utils'
import {
  Shield, ShieldCheck, Eye, Activity, ArrowRight, ArrowLeft, Search,
  Check, X, Smartphone, Sparkles,
} from 'lucide-react'

const TEAL = 'oklch(72% 0.11 185)'

interface Props {
  onClose: () => void
}

/**
 * Two-page first-run guide for Deep Lock.
 *
 * Page 1 explains what Deep Lock does and verifies the three things that must
 * be true for a block to actually happen (accessibility bound, usage access,
 * foreground service running). If any are missing it hands off to the existing
 * permission wizard (`FocusShieldSetup`, mounted globally).
 *
 * Page 2 lets the user pick which installed apps to block, then writes the
 * policy, turns on the Shorts/Reels surface filters, and pushes the snapshot to
 * native. It deliberately does NOT auto-start a focus timer — starting an
 * untracked native session with no JS timer leaves the dashboard in a phantom
 * "active" state.
 */
export function DeepLockOnboarding({ onClose }: Props) {
  const { showToast } = useToast()
  const setShowFocusShieldSetup = useStore((s) => s.setShowFocusShieldSetup)
  const sessionBlockedApps = useStore((s) => s.sessionBlockedApps)
  const whitelistedApps = useStore((s) => s.whitelistedApps)
  const removeWhitelistedApp = useStore((s) => s.removeWhitelistedApp)
  const setDeepLockOnboardingComplete = useStore((s) => s.setDeepLockOnboardingComplete)

  const [page, setPage] = useState<1 | 2>(1)
  const [status, setStatus] = useState<DeepLockStatus | null>(null)
  const [apps, setApps] = useState<InstalledApp[]>([])
  const [loadingApps, setLoadingApps] = useState(false)
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)

  // Live permission/service check — page 1 must tell the truth about whether a
  // block can actually happen, not just show a happy illustration.
  useEffect(() => {
    let cancelled = false
    const check = async () => {
      try {
        const s = await deepLockPlugin.getDeepLockStatus()
        if (!cancelled) setStatus(s)
      } catch { /* best-effort */ }
    }
    void check()
    const id = setInterval(check, 2500)
    return () => { cancelled = true; clearInterval(id) }
  }, [])

  // Load the installed-app list when page 2 opens.
  useEffect(() => {
    if (page !== 2 || apps.length > 0) return
    let cancelled = false
    setLoadingApps(true)
    deepLockPlugin
      .getInstalledApps()
      .then((res) => {
        if (!cancelled) setApps(res.apps)
      })
      .catch(() => { /* best-effort */ })
      .finally(() => {
        if (!cancelled) setLoadingApps(false)
      })
    return () => { cancelled = true }
  }, [page, apps.length])

  // Only these two are required to start Deep Lock. Accessibility cannot be
  // granted without the in-app API disclosure (it cannot be skipped) and Usage
  // Access identifies the foreground app. The other three are optional.
  const requiredReady =
    status?.accessibilityEnabled === true &&
    status?.usageAccessGranted === true

  const filteredApps = useMemo(() => {
    const q = search.trim().toLowerCase()
    const list = q
      ? apps.filter(
          (a) =>
            a.appName.toLowerCase().includes(q) ||
            a.packageName.toLowerCase().includes(q),
        )
      : apps
    // Real (non-system) apps first — those are the distractions users want to block.
    return [...list].sort((a, b) => {
      if (a.isSystemApp !== b.isSystemApp) return a.isSystemApp ? 1 : -1
      return a.appName.localeCompare(b.appName)
    })
  }, [apps, search])

  const toggleApp = useCallback((pkg: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(pkg)) next.delete(pkg)
      else next.add(pkg)
      return next
    })
  }, [])

  const handleFinish = useCallback(async () => {
    if (saving) return
    setSaving(true)
    try {
      // Onboarding picks are SESSION-ONLY blocks: active while a focus session
      // runs, allowed outside one. 24/7 blocking is opt-in and lives in the
      // "24/7 Always Blocked" box on the Deep Lock dashboard — never here.
      const nativeSession = await deepLockPlugin
        .getSessionBlockedList()
        .catch(() => ({ packages: [] as string[] }))
      const merged = Array.from(
        new Set([...sessionBlockedApps, ...nativeSession.packages, ...selected]),
      )
      await persistSessionBlockList(merged)
      // Deny wins: a newly blocked app must not stay on the allow list.
      for (const pkg of selected) {
        if (whitelistedApps.includes(pkg)) removeWhitelistedApp(pkg)
      }
      // Deliberately NOT enabling Shorts/Reels here. Those two filters inspect
      // the accessibility view tree (node text, content descriptions, view IDs,
      // bounds) rather than just package + class name, so they are opt-in only —
      // setup must never switch content inspection on for the user.
      const result = await syncShieldSettingsToNative()
      setDeepLockOnboardingComplete(true)
      showToast({
        type: result.serviceRunning ? 'success' : 'info',
        title: 'Deep Lock is ready',
        description: result.serviceRunning
          ? merged.length > 0
            ? `${merged.length} app${merged.length === 1 ? '' : 's'} will lock during focus sessions. Shorts/Reels filters stay off until you turn them on in Deep Lock.`
            : 'Session blocking is ready. Add apps any time under App Blocking; the Shorts/Reels filters stay off until you enable them.'
          : 'Settings saved. Enable the accessibility service so Deep Lock can enforce them.',
      })
      onClose()
    } catch (e: any) {
      showToast({ type: 'error', title: 'Could not save', description: e?.message ?? 'Please try again.' })
    } finally {
      setSaving(false)
    }
  }, [
    saving, sessionBlockedApps, whitelistedApps, removeWhitelistedApp, selected,
    setDeepLockOnboardingComplete, showToast, onClose,
  ])

  const statusRow = (
    label: string,
    ok: boolean | null,
    icon: React.ElementType,
  ) => {
    const Icon = icon
    return (
      <div className="flex items-center gap-2.5 rounded-xl bg-secondary/20 border border-border/40 px-3 py-2.5">
        <span
          className={cn(
            'w-7 h-7 rounded-lg flex items-center justify-center shrink-0',
            ok === true ? 'bg-green-500/10' : ok === false ? 'bg-amber-500/10' : 'bg-secondary',
          )}
        >
          <Icon
            className={cn(
              'w-3.5 h-3.5',
              ok === true ? 'text-green-400' : ok === false ? 'text-amber-400' : 'text-muted-foreground',
            )}
          />
        </span>
        <span className="flex-1 text-sm text-foreground">{label}</span>
        <span
          className={cn(
            'text-[10px] font-medium px-2 py-0.5 rounded-full font-mono uppercase tracking-wider',
            ok === true ? 'bg-green-500/10 text-green-400' : ok === false ? 'bg-amber-500/10 text-amber-400' : 'bg-secondary text-muted-foreground',
          )}
        >
          {ok === true ? 'Ready' : ok === false ? 'Missing' : 'Checking'}
        </span>
      </div>
    )
  }

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="fixed inset-0 z-[9990] bg-background overflow-y-auto"
    >
      <div className="absolute inset-0 pointer-events-none overflow-hidden">
        <div
          className="absolute top-1/4 left-1/2 -translate-x-1/2 w-[520px] h-[520px] rounded-full blur-[120px] opacity-[0.07]"
          style={{ backgroundColor: TEAL }}
        />
        <div
          className="absolute bottom-1/4 right-1/4 w-[320px] h-[320px] rounded-full blur-[100px] opacity-[0.06]"
          style={{ backgroundColor: TEAL }}
        />
      </div>

      <div className="relative z-10 w-full max-w-md mx-auto px-6 py-8 min-h-full flex flex-col justify-center">
        <button
          onClick={onClose}
          className="absolute top-5 right-5 p-2.5 rounded-xl text-muted-foreground hover:text-foreground hover:bg-secondary/40 transition-colors min-h-[44px] min-w-[44px] flex items-center justify-center"
          aria-label="Close onboarding"
        >
          <X className="w-5 h-5" />
        </button>

        <AnimatePresence mode="wait">
          {page === 1 && (
            <motion.div
              key="page-1"
              initial={{ opacity: 0, x: 24 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -24 }}
              transition={{ duration: 0.3 }}
            >
              <div className="w-20 h-20 rounded-3xl flex items-center justify-center mb-6" style={{ backgroundColor: `${TEAL}14` }}>
                <Shield className="w-10 h-10" style={{ color: TEAL }} />
              </div>
              <h2 className="text-2xl font-bold text-foreground mb-2 tracking-tight">Meet Deep Lock</h2>
              <p className="text-sm text-muted-foreground leading-relaxed mb-6">
                Deep Lock blocks the apps you choose during focus sessions, and keeps Shorts,
                Reels, TikTok and Snapchat Spotlight out of reach whenever they&apos;re open — no
                session needed. Everything happens on your device.
              </p>

              <div className="space-y-2 mb-4">
                {statusRow('Accessibility service', status?.accessibilityEnabled ?? null, Eye)}
                {statusRow('Usage access', status?.usageAccessGranted ?? null, Activity)}
                {statusRow('Focus Shield service', status?.serviceRunning ?? null, ShieldCheck)}
              </div>

              <div className="flex items-start gap-2 p-3 rounded-xl bg-secondary/20 border border-border/40 mb-7">
                <Sparkles className="w-4 h-4 shrink-0 mt-0.5" style={{ color: TEAL }} />
                <p className="text-xs text-muted-foreground leading-relaxed">
                  {requiredReady
                    ? 'Everything is ready. Next, pick which apps should be blocked.'
                    : 'A permission is missing. Fix it now and return here — the checks update live.'}
                </p>
              </div>

              {requiredReady ? (
                <button
                  onClick={() => setPage(2)}
                  className="w-full py-4 rounded-2xl text-white font-semibold text-base flex items-center justify-center gap-2 min-h-[52px]"
                  style={{ backgroundColor: TEAL, boxShadow: `0 8px 32px ${TEAL}30` }}
                >
                  Continue <ArrowRight className="w-4 h-4" />
                </button>
              ) : (
                <button
                  onClick={() => setShowFocusShieldSetup(true)}
                  className="w-full py-4 rounded-2xl font-semibold text-base flex items-center justify-center gap-2 min-h-[52px]"
                  style={{ backgroundColor: `${TEAL}1a`, color: TEAL }}
                >
                  Fix permissions <ArrowRight className="w-4 h-4" />
                </button>
              )}
            </motion.div>
          )}

          {page === 2 && (
            <motion.div
              key="page-2"
              initial={{ opacity: 0, x: 24 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -24 }}
              transition={{ duration: 0.3 }}
            >
              <div className="w-20 h-20 rounded-3xl flex items-center justify-center mb-6" style={{ backgroundColor: `${TEAL}14` }}>
                <Smartphone className="w-10 h-10" style={{ color: TEAL }} />
              </div>
              <h2 className="text-2xl font-bold text-foreground mb-2 tracking-tight">What should be blocked during focus?</h2>
              <p className="text-sm text-muted-foreground leading-relaxed mb-5">
                Tap the apps that pull you off track. They stay locked while a focus session
                runs — and only then. Shorts &amp; Reels are blocked automatically whenever
                they&apos;re open.
              </p>

              <div className="relative mb-3">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground/60" />
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search apps"
                  className="w-full pl-9 pr-3 py-3 rounded-xl bg-secondary/30 text-sm text-foreground border border-border/50 placeholder:text-muted-foreground/40 focus:outline-none focus:ring-2 focus:ring-primary/30"
                />
              </div>

              <div className="max-h-[42vh] overflow-y-auto no-scrollbar space-y-1 mb-5">
                {loadingApps && (
                  <div className="text-center text-sm text-muted-foreground py-8 font-mono">Loading apps...</div>
                )}
                {!loadingApps && filteredApps.length === 0 && (
                  <div className="text-center text-sm text-muted-foreground py-8">
                    {apps.length === 0 ? 'No apps found.' : 'No matches.'}
                  </div>
                )}
                {filteredApps.map((app) => {
                  const checked = selected.has(app.packageName)
                  return (
                    <button
                      key={app.packageName}
                      onClick={() => toggleApp(app.packageName)}
                      className={cn(
                        'w-full flex items-center gap-3 px-3 py-2.5 rounded-xl border text-left transition-colors min-h-[52px]',
                        checked
                          ? 'bg-red-500/8 border-red-500/25'
                          : 'bg-secondary/15 border-border/40 hover:bg-secondary/30',
                      )}
                    >
                      <span
                        className={cn(
                          'w-5 h-5 rounded-md border-2 flex items-center justify-center shrink-0 transition-all',
                          checked ? 'bg-red-500 border-red-500' : 'border-muted-foreground/30',
                        )}
                      >
                        {checked && <Check className="w-3 h-3 text-white" />}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className={cn('block text-sm font-medium truncate', checked ? 'text-red-300' : 'text-foreground')}>
                          {app.appName}
                        </span>
                        <span className="block text-[10px] text-muted-foreground/50 truncate font-mono">
                          {app.packageName}
                        </span>
                      </span>
                      {app.isSystemApp && (
                        <span className="text-[9px] font-mono uppercase tracking-wider text-muted-foreground/40 shrink-0">System</span>
                      )}
                    </button>
                  )
                })}
              </div>

              <div className="flex flex-col gap-2.5">
                <p className="text-[11px] text-muted-foreground/70 leading-relaxed flex items-start gap-1.5">
                  <Sparkles className="w-3.5 h-3.5 shrink-0 mt-0.5" style={{ color: TEAL }} />
                  <span>
                    Need them gone all day? After this, add them under{' '}
                    <span className="text-foreground font-medium">24/7</span> in Deep Lock.
                    Nothing is blocked outside a session by default.
                  </span>
                </p>
                <button
                  onClick={handleFinish}
                  disabled={saving}
                  className="w-full py-4 rounded-2xl text-white font-semibold text-base flex items-center justify-center gap-2 min-h-[52px] disabled:opacity-60"
                  style={{ backgroundColor: TEAL, boxShadow: `0 8px 32px ${TEAL}30` }}
                >
                  {saving
                    ? 'Saving...'
                    : selected.size > 0
                      ? `Block ${selected.size} app${selected.size === 1 ? '' : 's'} during focus`
                      : 'Finish'}
                </button>
                <button
                  onClick={() => setPage(1)}
                  className="w-full py-3 text-sm text-muted-foreground hover:text-foreground transition-colors flex items-center justify-center gap-1.5 min-h-[44px]"
                >
                  <ArrowLeft className="w-4 h-4" /> Back
                </button>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        <div className="flex items-center justify-center gap-2 mt-7">
          <span className={cn('h-1.5 rounded-full transition-all', page === 1 ? 'w-6 bg-primary' : 'w-1.5 bg-muted')} />
          <span className={cn('h-1.5 rounded-full transition-all', page === 2 ? 'w-6 bg-primary' : 'w-1.5 bg-muted')} />
        </div>
      </div>
    </motion.div>
  )
}

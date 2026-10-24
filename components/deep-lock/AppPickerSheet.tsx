'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import {
  Check,
  ChevronDown,
  Clock,
  LayoutGrid,
  Loader2,
  Lock,
  Plus,
  Search,
  ShieldCheck,
  X,
} from 'lucide-react'
import { useStore } from '@/lib/store'
import type { AppBlockMode } from '@/lib/store/slices/shield-slice'
import type { InstalledApp } from '@/lib/deep-lock/plugin'
import { loadInstalledApps } from '@/lib/deep-lock/installed-apps'
import { applyAppMode } from '@/lib/deep-lock/app-mode'
import { FAILSAFE_PACKAGES } from '@/lib/deep-lock/default-whitelist'
import { cn } from '@/lib/utils'

const TEAL = 'oklch(72% 0.11 185)'

type Filter = 'all' | 'allow' | 'focus' | 'always'

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'allow', label: 'Allowed' },
  { key: 'focus', label: 'Focus' },
  { key: 'always', label: '24/7' },
]

const MODES: { key: AppBlockMode; label: string; activeClass: string }[] = [
  {
    key: 'allow',
    label: 'Allow',
    activeClass: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
  },
  {
    key: 'focus',
    label: 'Focus',
    activeClass:
      'bg-[oklch(72%_0.11_185)]/15 text-[oklch(72%_0.11_185)] border-[oklch(72%_0.11_185)]/30',
  },
  {
    key: 'always',
    label: '24/7',
    activeClass: 'bg-red-500/15 text-red-300 border-red-500/30',
  },
]

function initialOf(name: string): string {
  return (name.trim().charAt(0) || '?').toUpperCase()
}

interface Props {
  open: boolean
  onClose: () => void
}

/**
 * Full-screen installed-app browser with a 3-way mode control per app
 * (Allow / During focus / 24/7). Replaces the old "type a package name" input.
 *
 * Every tap is an atomic mode change (`setAppBlockMode`): the package is removed
 * from every other list before it is added to the chosen one, so the allow-list,
 * session list and 24/7 list can never disagree.
 */
export function AppPickerSheet({ open, onClose }: Props) {
  const sessionBlockedApps = useStore((s) => s.sessionBlockedApps)
  const alwaysBlockedApps = useStore((s) => s.alwaysBlockedApps)
  const breakBlockedApps = useStore((s) => s.breakBlockedApps)
  const whitelistedApps = useStore((s) => s.whitelistedApps)

  const [apps, setApps] = useState<InstalledApp[]>([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [showSystem, setShowSystem] = useState(false)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [customPackage, setCustomPackage] = useState('')
  const [hint, setHint] = useState<string | null>(null)

  const applyList = useCallback((list: InstalledApp[]) => {
    setApps(list)
    setLoadError(list.length === 0)
    setLoading(false)
  }, [])

  // Retry path (event handler): show the spinner again, then refetch.
  const load = useCallback(() => {
    setLoading(true)
    setLoadError(false)
    void loadInstalledApps(true).then(applyList)
  }, [applyList])

  // Open path: fetch and apply in a promise callback so opening the sheet never
  // triggers a synchronous state update inside the effect.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    void loadInstalledApps(true).then((list) => {
      if (!cancelled) applyList(list)
    })
    return () => {
      cancelled = true
    }
  }, [open, applyList])

  // Reset the transient controls on the way out instead of on the way in, so
  // opening the sheet never triggers a state-update cascade.
  const handleClose = useCallback(() => {
    setSearch('')
    setFilter('all')
    setHint(null)
    setAdvancedOpen(false)
    setCustomPackage('')
    onClose()
  }, [onClose])

  // Escape closes; body scroll locked while open.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose()
    }
    document.addEventListener('keydown', onKey)
    document.body.classList.add('scroll-locked')
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.classList.remove('scroll-locked')
    }
  }, [open, handleClose])

  const modeOf = useCallback(
    (pkg: string): AppBlockMode => {
      if (alwaysBlockedApps.includes(pkg)) return 'always'
      if (sessionBlockedApps.includes(pkg)) return 'focus'
      return 'allow'
    },
    [alwaysBlockedApps, sessionBlockedApps],
  )

  const setMode = useCallback((pkg: string, mode: AppBlockMode) => {
    setHint(null)
    void applyAppMode(pkg, mode)
  }, [])

  const handleCustomAdd = useCallback(
    (mode: 'focus' | 'always') => {
      const pkg = customPackage.trim()
      if (!pkg || !pkg.includes('.')) {
        setHint('Enter a package id, e.g. com.example.game')
        return
      }
      setMode(pkg, mode)
      setCustomPackage('')
      setHint(`${pkg} → ${mode === 'focus' ? 'During focus' : '24/7'}`)
    },
    [customPackage, setMode],
  )

  const counts = useMemo(
    () => ({
      allow: whitelistedApps.length,
      focus: sessionBlockedApps.length,
      always: alwaysBlockedApps.length,
    }),
    [whitelistedApps.length, sessionBlockedApps.length, alwaysBlockedApps.length],
  )

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase()
    return apps
      .filter((app) => {
        const explicitlyListed =
          whitelistedApps.includes(app.packageName) ||
          sessionBlockedApps.includes(app.packageName) ||
          alwaysBlockedApps.includes(app.packageName)
        if (!showSystem && app.isSystemApp && !explicitlyListed) return false
        if (filter === 'allow' && !whitelistedApps.includes(app.packageName)) return false
        if (filter === 'focus' && !sessionBlockedApps.includes(app.packageName)) return false
        if (filter === 'always' && !alwaysBlockedApps.includes(app.packageName)) return false
        if (!q) return true
        return (
          app.appName.toLowerCase().includes(q) ||
          app.packageName.toLowerCase().includes(q)
        )
      })
      .sort((a, b) => {
        if (a.isSystemApp !== b.isSystemApp) return a.isSystemApp ? 1 : -1
        return a.appName.localeCompare(b.appName)
      })
  }, [apps, search, filter, showSystem, whitelistedApps, sessionBlockedApps, alwaysBlockedApps])

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.18 }}
          className="fixed inset-0 z-modal-backdrop flex items-end sm:items-center justify-center bg-background/70 backdrop-blur-md p-0 sm:p-4"
          onClick={handleClose}
          role="dialog"
          aria-modal="true"
          aria-label="Choose what each app is allowed to do"
        >
          <motion.div
            initial={{ y: 24, opacity: 0, scale: 0.98 }}
            animate={{ y: 0, opacity: 1, scale: 1 }}
            exit={{ y: 24, opacity: 0, scale: 0.98 }}
            transition={{ type: 'spring', stiffness: 340, damping: 30 }}
            onClick={(e) => e.stopPropagation()}
            className="w-full sm:max-w-xl h-[92dvh] sm:h-[85dvh] bg-background/95 backdrop-blur-3xl border border-border/50 rounded-t-3xl sm:rounded-3xl shadow-2xl flex flex-col overflow-hidden"
          >
            {/* Header */}
            <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-border/40 shrink-0">
              <div className="flex items-center gap-2.5 min-w-0">
                <div className="w-8 h-8 rounded-lg bg-red-500/10 flex items-center justify-center shrink-0">
                  <LayoutGrid className="w-4 h-4 text-red-400" />
                </div>
                <div className="min-w-0">
                  <h2 className="text-sm font-semibold text-foreground truncate">All apps</h2>
                  <p className="text-[10px] text-muted-foreground truncate">
                    Allow · block during focus · block 24/7
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={handleClose}
                className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-secondary/60 transition-colors"
                aria-label="Close app list"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Search + filters */}
            <div className="px-4 pt-3 space-y-2 shrink-0">
              <div className="relative">
                <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground/60" />
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search apps"
                  className="w-full pl-9 pr-3 py-2.5 rounded-xl bg-secondary/30 border border-border/50 text-sm text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/30"
                />
              </div>
              <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar pb-1">
                {FILTERS.map((f) => {
                  const active = filter === f.key
                  const count = f.key === 'all' ? apps.length : counts[f.key]
                  return (
                    <button
                      key={f.key}
                      type="button"
                      onClick={() => setFilter(f.key)}
                      aria-pressed={active}
                      className={cn(
                        'shrink-0 px-2.5 py-1.5 rounded-lg text-[11px] font-medium border transition-colors min-h-[34px] flex items-center gap-1',
                        active
                          ? 'bg-foreground/10 text-foreground border-border'
                          : 'bg-secondary/30 text-muted-foreground border-transparent hover:bg-secondary/60',
                      )}
                    >
                      {f.label}
                      <span className="font-mono text-[9px] opacity-60">{count}</span>
                    </button>
                  )
                })}
                <button
                  type="button"
                  onClick={() => setShowSystem((v) => !v)}
                  aria-pressed={showSystem}
                  className={cn(
                    'ml-auto shrink-0 px-2.5 py-1.5 rounded-lg text-[11px] font-medium border transition-colors min-h-[34px]',
                    showSystem
                      ? 'bg-foreground/10 text-foreground border-border'
                      : 'bg-secondary/30 text-muted-foreground border-transparent hover:bg-secondary/60',
                  )}
                >
                  System
                </button>
              </div>
            </div>

            {/* List */}
            <div className="flex-1 overflow-y-auto px-4 py-3 space-y-1.5">
              {loading && (
                <div className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
                  <Loader2 className="w-4 h-4 animate-spin" /> Loading apps…
                </div>
              )}

              {!loading && loadError && (
                <div className="text-center py-10 space-y-3">
                  <p className="text-sm text-muted-foreground">
                    Couldn&apos;t read the app list on this device.
                  </p>
                  <button
                    type="button"
                    onClick={() => {
                      load()
                    }}
                    className="px-4 py-2.5 rounded-xl bg-secondary/60 text-sm text-foreground min-h-[44px]"
                  >
                    Try again
                  </button>
                </div>
              )}

              {!loading && !loadError && visible.length === 0 && (
                <p className="text-center text-sm text-muted-foreground py-10">
                  {apps.length === 0
                    ? 'No apps found.'
                    : filter === 'all'
                      ? 'No matches.'
                      : `Nothing in ${FILTERS.find((f) => f.key === filter)?.label} yet.`}
                </p>
              )}

              {!loading && !loadError && visible.map((app) => {
                const mode = modeOf(app.packageName)
                const isFailsafe = FAILSAFE_PACKAGES.has(app.packageName)
                const onBreak = breakBlockedApps.includes(app.packageName)
                const isWhitelisted = whitelistedApps.includes(app.packageName)
                return (
                  <div
                    key={app.packageName}
                    className="rounded-xl border border-border/40 bg-secondary/15 px-3 py-2.5"
                  >
                    <div className="flex items-center gap-3">
                      {app.icon ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img
                          src={app.icon}
                          alt=""
                          loading="lazy"
                          className="w-9 h-9 rounded-lg shrink-0 bg-secondary/40 object-cover"
                        />
                      ) : (
                        <span className="w-9 h-9 rounded-lg shrink-0 bg-secondary/60 flex items-center justify-center text-xs font-semibold text-foreground/80">
                          {initialOf(app.appName)}
                        </span>
                      )}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5 min-w-0">
                          <p className="text-sm font-medium text-foreground truncate">{app.appName}</p>
                          {onBreak && (
                            <span className="shrink-0 text-[9px] font-mono uppercase tracking-wider px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-300">
                              break
                            </span>
                          )}
                        </div>
                        <p className="text-[10px] text-muted-foreground/50 truncate font-mono">
                          {app.packageName}
                        </p>
                      </div>
                    </div>

                    {isFailsafe ? (
                      <p className="mt-2 flex items-center gap-1.5 text-[11px] text-emerald-300/90">
                        <ShieldCheck className="w-3.5 h-3.5" /> Always allowed — system app
                      </p>
                    ) : (
                      <div
                        role="group"
                        aria-label={`Blocking mode for ${app.appName}`}
                        className="grid grid-cols-3 gap-1 mt-2"
                      >
                        {MODES.map((m) => {
                          const active = mode === m.key
                          // "Allow" is the resting state for every unblocked app;
                          // only an explicit allow-list entry gets the filled style.
                          const activeClass =
                            m.key === 'allow' && !isWhitelisted
                              ? 'bg-secondary/40 text-foreground/80 border-border/50'
                              : m.activeClass
                          return (
                            <button
                              key={m.key}
                              type="button"
                              aria-pressed={active}
                              title={
                                m.key === 'allow' && !isWhitelisted
                                  ? 'Not blocked — tap to also add it to your allow-list'
                                  : undefined
                              }
                              onClick={() => setMode(app.packageName, m.key)}
                              className={cn(
                                'flex items-center justify-center gap-1 rounded-lg border text-[11px] font-semibold transition-colors min-h-[40px]',
                                active
                                  ? activeClass
                                  : 'bg-secondary/25 text-muted-foreground border-transparent hover:bg-secondary/50',
                              )}
                            >
                              {m.key === 'allow' && <Check className="w-3 h-3" />}
                              {m.key === 'focus' && <Clock className="w-3 h-3" />}
                              {m.key === 'always' && <Lock className="w-3 h-3" />}
                              {m.label}
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>

            {/* Footer: advanced package entry */}
            <div className="border-t border-border/40 px-4 py-3 pb-[max(env(safe-area-inset-bottom,0px),12px)] shrink-0 space-y-2">
              {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
              <button
                type="button"
                onClick={() => setAdvancedOpen((v) => !v)}
                aria-expanded={advancedOpen}
                className="flex items-center gap-1.5 text-[11px] text-muted-foreground hover:text-foreground transition-colors min-h-[32px]"
              >
                <ChevronDown
                  className={cn('w-3.5 h-3.5 transition-transform', advancedOpen && 'rotate-180')}
                />
                Advanced — add by package name
              </button>
              {advancedOpen && (
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={customPackage}
                    onChange={(e) => setCustomPackage(e.target.value)}
                    placeholder="com.example.game"
                    className="flex-1 min-w-0 px-3 py-2 rounded-lg bg-secondary/30 border border-border/50 text-xs text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/30"
                  />
                  <button
                    type="button"
                    onClick={() => handleCustomAdd('focus')}
                    className="px-3 py-2 rounded-lg bg-[oklch(72%_0.11_185)]/15 text-[oklch(72%_0.11_185)] text-[11px] font-semibold min-h-[40px]"
                    style={{ color: TEAL }}
                  >
                    Focus
                  </button>
                  <button
                    type="button"
                    onClick={() => handleCustomAdd('always')}
                    className="px-3 py-2 rounded-lg bg-red-500/15 text-red-300 text-[11px] font-semibold min-h-[40px]"
                  >
                    24/7
                  </button>
                </div>
              )}
              <div className="flex items-center gap-3 text-[10px] font-mono uppercase tracking-wider text-muted-foreground/60">
                <span className="flex items-center gap-1"><Check className="w-3 h-3" />{counts.allow} allow</span>
                <span className="flex items-center gap-1"><Clock className="w-3 h-3" />{counts.focus} focus</span>
                <span className="flex items-center gap-1"><Lock className="w-3 h-3" />{counts.always} 24/7</span>
                <span className="ml-auto normal-case tracking-normal text-muted-foreground/50">
                  <Plus className="w-3 h-3 inline mr-0.5" />
                  {apps.length} apps
                </span>
              </div>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

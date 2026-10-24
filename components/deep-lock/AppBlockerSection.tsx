'use client'

import { useState, useCallback, useEffect, useRef } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { useStore } from '@/lib/store'
import { deepLockPlugin } from '@/lib/deep-lock/plugin'
import { syncShieldSettingsToNative, waitForShieldStoreHydration } from '@/lib/deep-lock/shield-sync'
import { refreshSessionBlockList } from '@/lib/deep-lock/session-blocklist'
import { applyAppMode, pushShieldLists } from '@/lib/deep-lock/app-mode'
import { normalizeDomain } from '@/lib/deep-lock/websites'
import { Card } from '@/components/ui/glass-card'
import { Modal } from '@/components/modal'
import { AppPickerSheet } from './AppPickerSheet'
import { cn } from '@/lib/utils'
import {
  Film,
  Video,
  Music2,
  Ghost,
  GraduationCap,
  ExternalLink,
  ShieldAlert,
  Coffee,
  Lock,
  Clock,
  LayoutGrid,
  Plus,
  X,
  Check,
  ChevronDown,
  ChevronUp,
  Globe,
  Sparkles,
  AlertTriangle,
} from 'lucide-react'

const TEAL = 'oklch(72% 0.11 185)'

const POPULAR_DISTRACTING_APPS = [
  { name: 'Instagram', package: 'com.instagram.android', icon: '📸' },
  { name: 'YouTube', package: 'com.google.android.youtube', icon: '▶️' },
  { name: 'TikTok', package: 'com.zhiliaoapp.musically', icon: '🎵' },
  { name: 'Snapchat', package: 'com.snapchat.android', icon: '👻' },
  { name: 'Twitter / X', package: 'com.twitter.android', icon: '🐦' },
  { name: 'Reddit', package: 'com.reddit.frontpage', icon: '🤖' },
  { name: 'Netflix', package: 'com.netflix.mediaclient', icon: '🎬' },
  { name: 'Facebook', package: 'com.facebook.katana', icon: '📘' },
  { name: 'Telegram', package: 'org.telegram.messenger', icon: '✈️' },
  { name: 'Discord', package: 'com.discord', icon: '💬' },
]

/** Human name for a package: installed-app name → popular name → last id segment. */
function resolveAppLabel(pkg: string, names: Record<string, string>): string {
  return (
    names[pkg] ||
    POPULAR_DISTRACTING_APPS.find((a) => a.package === pkg)?.name ||
    pkg.split('.').filter(Boolean).pop() ||
    pkg
  )
}

function resolveAppIcon(pkg: string): string {
  return POPULAR_DISTRACTING_APPS.find((a) => a.package === pkg)?.icon ?? '📦'
}

function Switch({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      onClick={() => onChange(!checked)}
      className={cn(
        'w-11 h-6 rounded-full p-0.5 transition-colors relative focus:outline-none shrink-0',
        checked ? 'bg-[oklch(72%_0.11_185)]' : 'bg-muted'
      )}
      role="switch"
      aria-checked={checked}
    >
      <motion.div
        layout
        transition={{ type: 'spring', stiffness: 500, damping: 30 }}
        className={cn(
          'w-5 h-5 rounded-full bg-white shadow-sm',
          checked ? 'ml-auto' : ''
        )}
      />
    </button>
  )
}

export function AppBlockerSection() {
  const breakBlockingEnabled = useStore((s) => s.breakBlockingEnabled)
  const setBreakBlockingEnabled = useStore((s) => s.setBreakBlockingEnabled)
  const blockShorts = useStore((s) => s.blockShorts)
  const setBlockShorts = useStore((s) => s.setBlockShorts)
  const blockReels = useStore((s) => s.blockReels)
  const setBlockReels = useStore((s) => s.setBlockReels)
  const blockTikTok = useStore((s) => s.blockTikTok)
  const setBlockTikTok = useStore((s) => s.setBlockTikTok)
  const blockSnapchat = useStore((s) => s.blockSnapchat)
  const setBlockSnapchat = useStore((s) => s.setBlockSnapchat)
  const youtubeStudyMode = useStore((s) => s.youtubeStudyMode)
  const setYoutubeStudyMode = useStore((s) => s.setYoutubeStudyMode)
  const blockAdultWebsites = useStore((s) => s.blockAdultWebsites)
  const setBlockAdultWebsites = useStore((s) => s.setBlockAdultWebsites)
  const shortFormAllowance = useStore((s) => s.shortFormAllowance)
  const setShortFormAllowance = useStore((s) => s.setShortFormAllowance)
  const blockedWebsites = useStore((s) => s.blockedWebsites)
  const addBlockedWebsite = useStore((s) => s.addBlockedWebsite)
  const removeBlockedWebsite = useStore((s) => s.removeBlockedWebsite)
  const alwaysBlockedApps = useStore((s) => s.alwaysBlockedApps)
  const setAlwaysBlockedApps = useStore((s) => s.setAlwaysBlockedApps)
  const sessionBlockedApps = useStore((s) => s.sessionBlockedApps)
  const breakBlockedApps = useStore((s) => s.breakBlockedApps)
  const addBreakBlockedApp = useStore((s) => s.addBreakBlockedApp)
  const removeBreakBlockedApp = useStore((s) => s.removeBreakBlockedApp)

  const [activeList, setActiveList] = useState<'session' | 'always'>('session')
  const [showBreakBlocked, setShowBreakBlocked] = useState(false)
  const [confirmUnblockAll, setConfirmUnblockAll] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [appNames, setAppNames] = useState<Record<string, string>>({})
  const namesLoadedRef = useRef(false)
  const [websiteInput, setWebsiteInput] = useState('')
  const [websiteError, setWebsiteError] = useState<string | null>(null)
  const [serviceRunning, setServiceRunning] = useState<boolean | null>(null)
  const websiteInputRef = useRef<HTMLInputElement>(null)

  // Push the whole persisted snapshot once on mount so the UI never diverges
  // from the engine (a previous session may have edited the lists, and a cold
  // start leaves native on defaults until something re-pushes them).
  useEffect(() => {
    let cancelled = false
    void syncShieldSettingsToNative().then((result) => {
      if (!cancelled) setServiceRunning(result.serviceRunning)
    })
    return () => { cancelled = true }
  }, [])

  const applySyncResult = useCallback((result: { serviceRunning: boolean }) => {
    setServiceRunning(result.serviceRunning)
  }, [])

  // Refresh the session list from native on mount and whenever the app becomes
  // visible again — the native "Manage Apps" screen also writes it.
  useEffect(() => {
    void refreshSessionBlockList()
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshSessionBlockList()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])

  // Resolve package names once, so apps picked during onboarding (which are not
  // in POPULAR_DISTRACTING_APPS) show their real name instead of a package id.
  useEffect(() => {
    const total = sessionBlockedApps.length + alwaysBlockedApps.length
    if (total === 0 || namesLoadedRef.current) return
    namesLoadedRef.current = true
    let cancelled = false
    deepLockPlugin
      .getInstalledApps()
      .then((res) => {
        if (cancelled) return
        const map: Record<string, string> = {}
        for (const app of res.apps) map[app.packageName] = app.appName
        setAppNames(map)
      })
      .catch(() => { namesLoadedRef.current = false })
    return () => { cancelled = true }
  }, [sessionBlockedApps.length, alwaysBlockedApps.length])

  const handleToggleShorts = useCallback((val: boolean) => {
    setBlockShorts(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setBlockShorts, applySyncResult])

  const handleToggleReels = useCallback((val: boolean) => {
    setBlockReels(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setBlockReels, applySyncResult])

  const handleToggleTikTok = useCallback((val: boolean) => {
    setBlockTikTok(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setBlockTikTok, applySyncResult])

  const handleToggleSnapchat = useCallback((val: boolean) => {
    setBlockSnapchat(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setBlockSnapchat, applySyncResult])

  const handleToggleStudyMode = useCallback((val: boolean) => {
    setYoutubeStudyMode(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setYoutubeStudyMode, applySyncResult])

  const [openingStudy, setOpeningStudy] = useState(false)
  const [studyOpenError, setStudyOpenError] = useState<string | null>(null)
  const handleOpenStudyYouTube = useCallback(async () => {
    setOpeningStudy(true)
    setStudyOpenError(null)
    try {
      // The player is always distraction-free; also enable the system-level
      // YouTube gate so leaving the player cannot reopen the browse feed.
      if (!useStore.getState().youtubeStudyMode) {
        setYoutubeStudyMode(true)
        const syncResult = await syncShieldSettingsToNative()
        setServiceRunning(syncResult.serviceRunning)
      }
      const result = await deepLockPlugin.openStudyYouTube()
      if (!result.success) {
        setStudyOpenError(result.error === 'Not available on web'
          ? 'Study YouTube is available in the Android app.'
          : result.error || 'Could not open Study YouTube. Try again.')
      }
    } catch {
      setStudyOpenError('Could not open Study YouTube. Try again.')
    } finally {
      setOpeningStudy(false)
    }
  }, [setYoutubeStudyMode])

  const handleToggleAdult = useCallback((val: boolean) => {
    setBlockAdultWebsites(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setBlockAdultWebsites, applySyncResult])

  const handleAllowanceChange = useCallback((value: number) => {
    setShortFormAllowance(value)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setShortFormAllowance, applySyncResult])

  const handleToggleBreakBlocking = useCallback((val: boolean) => {
    setBreakBlockingEnabled(val)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [setBreakBlockingEnabled, applySyncResult])

  // ── App blocking lists (allow / during focus / 24/7) ──────────────────────
  // Every edit goes through `applyAppMode`, which writes the store atomically
  // and then mirrors the whitelist + session list + 24/7 list to native. The
  // generic sync deliberately does not push the session list because the native
  // "Manage Apps" screen writes it too.

  /** Toggle an app in/out of the "During focus" list. */
  const toggleSession = useCallback(async (pkg: string) => {
    await waitForShieldStoreHydration()
    await applyAppMode(pkg, sessionBlockedApps.includes(pkg) ? 'default' : 'focus')
  }, [sessionBlockedApps])

  /** Toggle an app in/out of the "24/7" list. */
  const toggleAlways = useCallback(async (pkg: string) => {
    await waitForShieldStoreHydration()
    await applyAppMode(pkg, alwaysBlockedApps.includes(pkg) ? 'default' : 'always')
  }, [alwaysBlockedApps])

  const handleMoveAllToAlways = useCallback(async () => {
    await waitForShieldStoreHydration()
    // setAlwaysBlockedApps clears the weaker lists and the allow-list itself.
    setAlwaysBlockedApps(Array.from(new Set([...alwaysBlockedApps, ...sessionBlockedApps])))
    await pushShieldLists()
  }, [alwaysBlockedApps, sessionBlockedApps, setAlwaysBlockedApps])

  const handleMoveAllToSession = useCallback(async () => {
    await waitForShieldStoreHydration()
    const merged = Array.from(new Set([...sessionBlockedApps, ...alwaysBlockedApps]))
    setAlwaysBlockedApps([])
    useStore.getState().setSessionBlockedApps(merged)
    await pushShieldLists()
  }, [alwaysBlockedApps, sessionBlockedApps, setAlwaysBlockedApps])

  const handleUnblockAllAlways = useCallback(async () => {
    await waitForShieldStoreHydration()
    setAlwaysBlockedApps([])
    setConfirmUnblockAll(false)
    await pushShieldLists()
  }, [setAlwaysBlockedApps])

  const handleToggleBreakBlockedApp = useCallback(async (pkg: string) => {
    await waitForShieldStoreHydration()
    if (breakBlockedApps.includes(pkg)) {
      removeBreakBlockedApp(pkg)
    } else {
      addBreakBlockedApp(pkg)
    }
    void syncShieldSettingsToNative()
  }, [breakBlockedApps, addBreakBlockedApp, removeBreakBlockedApp])

  const handleAddWebsite = useCallback(() => {
    const raw = websiteInput.trim()
    if (!raw) return
    const normalized = normalizeDomain(raw)
    if (!normalized) {
      setWebsiteError('Enter a website like instagram.com')
      websiteInputRef.current?.focus()
      return
    }
    if (blockedWebsites.includes(normalized)) {
      setWebsiteError(`${normalized} is already blocked`)
      return
    }
    addBlockedWebsite(normalized)
    setWebsiteInput('')
    setWebsiteError(null)
    void syncShieldSettingsToNative().then(applySyncResult)
  }, [websiteInput, blockedWebsites, addBlockedWebsite, applySyncResult])

  const handleRemoveWebsite = useCallback((domain: string) => {
    removeBlockedWebsite(domain)
    void syncShieldSettingsToNative()
  }, [removeBlockedWebsite])

  return (
    <div className="space-y-4">
      {sessionBlockedApps.length === 0 && alwaysBlockedApps.length === 0 && breakBlockedApps.length === 0 && (
        <Card className="p-3 flex items-start gap-3 border-amber-500/30 bg-amber-500/5">
          <AlertTriangle className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
          <p className="text-xs text-muted-foreground leading-relaxed">
            <span className="text-foreground font-medium">No apps selected.</span>{' '}
            Deep Lock only blocks apps you pick under App Blocking — with an empty
            list, a focus session blocks nothing.
          </p>
        </Card>
      )}
      {/* Smart Content Filter (Regain / Opal style) */}
      <Card className="p-4 space-y-4">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl flex items-center justify-center shrink-0" style={{ backgroundColor: `${TEAL}15` }}>
            <Sparkles className="w-4 h-4" style={{ color: TEAL }} />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-foreground">Smart Content Blocker</h3>
            <p className="text-xs text-muted-foreground">Block algorithmic addiction traps without losing useful tools</p>
          </div>
        </div>

        <div className="space-y-3 pt-2">
          {/* YouTube Shorts */}
          <div className="flex items-start justify-between gap-3 p-3 rounded-xl bg-secondary/30 border border-border/40">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-red-500/10 flex items-center justify-center shrink-0 mt-0.5">
                <Film className="w-4 h-4 text-red-400" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">Block YouTube Shorts</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full font-mono bg-red-500/10 text-red-400 uppercase">Shorts only</span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Works whenever YouTube is open — no session needed. Lets you watch{' '}
                  {shortFormAllowance === 1 ? 'one Short' : `${shortFormAllowance} Shorts`}, then closes the player the
                  moment you swipe to the next one. Long-form videos keep working.
                </p>
              </div>
            </div>
            <Switch checked={blockShorts} onChange={handleToggleShorts} />
          </div>

          {/* YouTube Study Mode */}
          <div className={cn(
            'rounded-xl border overflow-hidden transition-colors',
            youtubeStudyMode ? 'border-red-400/40 bg-red-500/[0.06]' : 'border-border/40 bg-secondary/30'
          )}>
            <div className="flex items-start justify-between gap-3 p-3">
              <div className="flex items-start gap-3">
                <div className="w-8 h-8 rounded-lg bg-red-500/10 flex items-center justify-center shrink-0 mt-0.5">
                  <GraduationCap className="w-4 h-4 text-red-300" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-foreground">YouTube Study Mode</span>
                    <span className={cn(
                      'text-[10px] px-1.5 py-0.5 rounded-full font-mono uppercase',
                      youtubeStudyMode ? 'bg-red-500/20 text-red-300' : 'bg-secondary/60 text-muted-foreground'
                    )}>
                      {youtubeStudyMode ? 'On' : 'Off'}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    No Home feed — you only see videos you search for. Shorts, comments and
                    related videos are hidden inside our distraction-free player.
                  </p>
                </div>
              </div>
              <Switch checked={youtubeStudyMode} onChange={handleToggleStudyMode} />
            </div>
            <button
              type="button"
              onClick={handleOpenStudyYouTube}
              disabled={openingStudy}
              className="w-full flex items-center justify-center gap-2 py-3 min-h-[44px] border-t border-border/40 text-xs font-semibold uppercase tracking-wider text-red-300 hover:bg-secondary/50 active:bg-secondary/60 disabled:opacity-60 transition-colors"
            >
              <ExternalLink size={14} />
              {openingStudy ? 'Opening…' : 'Open Study YouTube'}
            </button>
            {studyOpenError && (
              <p className="px-3 pb-3 text-xs text-amber-300" role="status">{studyOpenError}</p>
            )}
          </div>

          {/* Instagram Reels */}
          <div className="flex items-start justify-between gap-3 p-3 rounded-xl bg-secondary/30 border border-border/40">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-pink-500/10 flex items-center justify-center shrink-0 mt-0.5">
                <Video className="w-4 h-4 text-pink-400" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">Block Instagram Reels</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full font-mono bg-pink-500/10 text-pink-400 uppercase">Reels only</span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Works whenever Instagram is open. Lets you watch{' '}
                  {shortFormAllowance === 1 ? 'one Reel' : `${shortFormAllowance} Reels`}, then closes the player on the
                  next swipe. Feed, stories and DMs keep working.
                </p>
              </div>
            </div>
            <Switch checked={blockReels} onChange={handleToggleReels} />
          </div>

          {/* TikTok */}
          <div className="flex items-start justify-between gap-3 p-3 rounded-xl bg-secondary/30 border border-border/40">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-cyan-500/10 flex items-center justify-center shrink-0 mt-0.5">
                <Music2 className="w-4 h-4 text-cyan-400" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">Block TikTok</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full font-mono bg-cyan-500/10 text-cyan-400 uppercase">Whole app</span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Works whenever TikTok is open. The entire app is a short-form feed, so the whole app is closed instead
                  of just one surface.
                </p>
              </div>
            </div>
            <Switch checked={blockTikTok} onChange={handleToggleTikTok} />
          </div>

          {/* Snapchat */}
          <div className="flex items-start justify-between gap-3 p-3 rounded-xl bg-secondary/30 border border-border/40">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-yellow-500/10 flex items-center justify-center shrink-0 mt-0.5">
                <Ghost className="w-4 h-4 text-yellow-400" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">Block Snapchat Spotlight &amp; Stories</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full font-mono bg-yellow-500/10 text-yellow-400 uppercase">Spotlight + Stories</span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Works whenever Snapchat is open. Closes the Spotlight and Stories feeds only — Chat and Snaps keep
                  working. Detection is best-effort since Snapchat hides its view ids.
                </p>
              </div>
            </div>
            <Switch checked={blockSnapchat} onChange={handleToggleSnapchat} />
          </div>

          {/* Per-open allowance (Regain-style study mode) */}
          {(blockShorts || blockReels) && (
            <div className="p-3 rounded-xl bg-secondary/30 border border-border/40 space-y-2">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <span className="text-sm font-medium text-foreground">Allowed each visit</span>
                  <p className="text-[11px] text-muted-foreground mt-0.5">
                    After this many, the next swipe closes the player and shows the block screen.
                  </p>
                </div>
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-full bg-teal-500/10 uppercase shrink-0" style={{ color: TEAL }}>
                  {shortFormAllowance === 0 ? 'Strict' : `${shortFormAllowance} / app`}
                </span>
              </div>
              <div className="grid grid-cols-4 gap-1.5" role="group" aria-label="Shorts and Reels allowed per session">
                {[0, 1, 2, 3].map((value) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => handleAllowanceChange(value)}
                    aria-pressed={shortFormAllowance === value}
                    className={cn(
                      'min-h-[44px] rounded-lg text-xs font-medium transition-colors',
                      shortFormAllowance === value
                        ? 'bg-[oklch(72%_0.11_185)]/20 border border-[oklch(72%_0.11_185)]/40'
                        : 'bg-secondary/40 border border-transparent text-muted-foreground hover:bg-secondary/70'
                    )}
                    style={shortFormAllowance === value ? { color: TEAL } : undefined}
                  >
                    {value === 0 ? 'None' : value}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Adult Websites */}
          <div className="flex items-start justify-between gap-3 p-3 rounded-xl bg-secondary/30 border border-border/40">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-amber-500/10 flex items-center justify-center shrink-0 mt-0.5">
                <ShieldAlert className="w-4 h-4 text-amber-400" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">Block Adult & 18+ Websites</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full font-mono bg-amber-500/10 text-amber-400 uppercase">
                    {blockAdultWebsites ? 'Browser Safe' : 'Off'}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Known NSFW domains are blocked in Chrome, Brave, Edge, Firefox and Samsung Internet while the shield is
                  active. Detection reads the browser address bar, so it is best-effort rather than a guarantee.
                </p>
              </div>
            </div>
            <Switch checked={blockAdultWebsites} onChange={handleToggleAdult} />
          </div>

          {/* Custom website block list */}
          <div className="pt-3 border-t border-border/40 space-y-3">
            <div className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-sky-500/10 flex items-center justify-center shrink-0 mt-0.5">
                <Globe className="w-4 h-4 text-sky-400" />
              </div>
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground">Blocked Websites</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full font-mono bg-sky-500/10 text-sky-400 uppercase">
                    {blockedWebsites.length} {blockedWebsites.length === 1 ? 'site' : 'sites'}
                  </span>
                </div>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Always blocked in browsers while the shield is active — during focus sessions and protected breaks.
                  Subdomains and every path are covered.
                </p>
              </div>
            </div>

            <div className="flex gap-2">
              <input
                ref={websiteInputRef}
                type="text"
                inputMode="url"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                placeholder="instagram.com"
                value={websiteInput}
                onChange={(e) => { setWebsiteInput(e.target.value); setWebsiteError(null) }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleAddWebsite() } }}
                className="flex-1 min-w-0 min-h-[44px] px-3 rounded-lg bg-secondary/30 border border-border/50 text-xs text-foreground placeholder:text-muted-foreground/60 outline-none focus:border-sky-400"
                aria-label="Website to block"
              />
              <button
                onClick={handleAddWebsite}
                disabled={!websiteInput.trim()}
                className="px-3 min-h-[44px] min-w-[44px] rounded-lg bg-sky-500/15 text-sky-400 text-xs font-medium flex items-center justify-center gap-1 hover:bg-sky-500/25 transition-colors disabled:opacity-40"
              >
                <Plus className="w-3.5 h-3.5" /> Add
              </button>
            </div>

            {websiteError && (
              <p className="text-[11px] text-red-400">{websiteError}</p>
            )}

            {blockedWebsites.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {blockedWebsites.map((domain) => (
                  <span
                    key={domain}
                    className="flex items-center gap-1 pl-2.5 pr-1 py-1 rounded-lg bg-sky-500/10 border border-sky-500/20 text-xs text-sky-200"
                  >
                    <span className="truncate max-w-[160px]">{domain}</span>
                    <button
                      onClick={() => handleRemoveWebsite(domain)}
                      className="min-h-[32px] min-w-[32px] flex items-center justify-center rounded-md hover:bg-sky-500/20 transition-colors"
                      aria-label={`Stop blocking ${domain}`}
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </span>
                ))}
              </div>
            )}

            {serviceRunning === false && (
              <div className="flex items-start gap-2 p-2.5 rounded-lg bg-amber-500/10 border border-amber-500/25">
                <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0 mt-0.5" />
                <div className="min-w-0">
                  <p className="text-[11px] text-amber-300 font-medium">Accessibility service isn&apos;t running</p>
                  <p className="text-[11px] text-muted-foreground">
                    Settings are saved, but nothing can be enforced until the Focus Shield service is enabled.
                  </p>
                  <button
                    onClick={() => void deepLockPlugin.openAccessibilitySettings()}
                    className="mt-1.5 text-[11px] font-medium text-amber-400 underline underline-offset-2"
                  >
                    Open accessibility settings
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </Card>

      {/* Break-Time Protection */}
      <Card className="p-4 space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className="w-9 h-9 rounded-xl bg-amber-500/10 flex items-center justify-center shrink-0">
              <Coffee className="w-4 h-4 text-amber-400" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-semibold text-foreground">Keep Blocked in Breaks</h3>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 uppercase">Anti-Relapse</span>
              </div>
              <p className="text-xs text-muted-foreground mt-0.5">
                Prevents 5-minute Pomodoro breaks from turning into 40-minute social media doomscrolling spirals.
              </p>
            </div>
          </div>
          <Switch checked={breakBlockingEnabled} onChange={handleToggleBreakBlocking} />
        </div>

        {breakBlockingEnabled && (
          <div className="pt-2 border-t border-border/40 space-y-3">
            <button
              onClick={() => setShowBreakBlocked(!showBreakBlocked)}
              className="w-full flex items-center justify-between text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              <span>{breakBlockedApps.length} apps blocked during break</span>
              {showBreakBlocked ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
            </button>

            <AnimatePresence>
              {showBreakBlocked && (
                <motion.div
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: 'auto' }}
                  exit={{ opacity: 0, height: 0 }}
                  className="overflow-hidden space-y-2 pt-1"
                >
                  <p className="text-[11px] text-muted-foreground/70">
                    Tap to toggle apps that stay locked during breaks:
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {POPULAR_DISTRACTING_APPS.map((app) => {
                      const isBlocked = breakBlockedApps.includes(app.package)
                      return (
                        <button
                          key={app.package}
                          onClick={() => handleToggleBreakBlockedApp(app.package)}
                          className={cn(
                            'px-2.5 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1.5 transition-all',
                            isBlocked
                              ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                              : 'bg-secondary/40 text-muted-foreground hover:bg-secondary/70 border border-transparent'
                          )}
                        >
                          <span>{app.icon}</span>
                          <span>{app.name}</span>
                          {isBlocked && <Check className="w-3 h-3 text-amber-400" />}
                        </button>
                      )
                    })}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        )}
      </Card>

      {/* App Blocking — session-only vs 24/7 */}
      <Card className="p-4 space-y-3">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-red-500/10 flex items-center justify-center shrink-0">
              <Lock className="w-4 h-4 text-red-400" />
            </div>
            <div className="min-w-0">
              <h3 className="text-sm font-semibold text-foreground">App Blocking</h3>
              <p className="text-xs text-muted-foreground truncate">
                Only the apps you pick are ever blocked
              </p>
            </div>
          </div>
          <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-secondary/60 text-muted-foreground uppercase shrink-0">
            {sessionBlockedApps.length + alwaysBlockedApps.length} blocked
          </span>
        </div>

        {/* Mode tabs */}
        <div role="tablist" aria-label="When to block apps" className="grid grid-cols-2 gap-1.5 p-1 rounded-xl bg-secondary/30">
          <button
            role="tab"
            aria-selected={activeList === 'session'}
            onClick={() => setActiveList('session')}
            className={cn(
              'flex items-center justify-center gap-1.5 py-2.5 rounded-lg text-xs font-semibold transition-colors min-h-[44px]',
              activeList === 'session'
                ? 'bg-background shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
            )}
            style={activeList === 'session' ? { color: TEAL } : undefined}
          >
            <Clock className="w-3.5 h-3.5" />
            During focus
            <span className="text-[10px] font-mono opacity-70">{sessionBlockedApps.length}</span>
          </button>
          <button
            role="tab"
            aria-selected={activeList === 'always'}
            onClick={() => setActiveList('always')}
            className={cn(
              'flex items-center justify-center gap-1.5 py-2.5 rounded-lg text-xs font-semibold transition-colors min-h-[44px]',
              activeList === 'always'
                ? 'bg-background text-red-300 shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            <Lock className="w-3.5 h-3.5" />
            24/7
            <span className="text-[10px] font-mono opacity-70">{alwaysBlockedApps.length}</span>
          </button>
        </div>

        {activeList === 'always' ? (
          <p className="text-[11px] leading-relaxed flex items-start gap-1.5 rounded-lg bg-amber-500/5 border border-amber-500/20 px-3 py-2 text-amber-200/90">
            <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5 text-amber-400" />
            <span>Blocked even when no focus session is running. Undo it here.</span>
          </p>
        ) : (
          <p className="text-[11px] leading-relaxed flex items-start gap-1.5 rounded-lg bg-secondary/20 border border-border/40 px-3 py-2 text-muted-foreground">
            <Clock className="w-3.5 h-3.5 shrink-0 mt-0.5" style={{ color: TEAL }} />
            <span>Locked only while a focus session runs — free the rest of the day.</span>
          </p>
        )}

        {/* Browse installed apps — the primary way to pick what gets blocked */}
        <button
          type="button"
          onClick={() => setPickerOpen(true)}
          className="w-full flex items-center gap-3 rounded-xl border border-border/50 bg-secondary/30 hover:bg-secondary/60 transition-colors px-3 py-3 min-h-[56px] text-left"
        >
          <span className="w-9 h-9 rounded-lg bg-red-500/10 flex items-center justify-center shrink-0">
            <LayoutGrid className="w-4 h-4 text-red-400" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-semibold text-foreground">Browse all apps</span>
            <span className="block text-[11px] text-muted-foreground">
              Choose Allow / During focus / 24/7 for any app
            </span>
          </span>
          <ChevronDown className="w-4 h-4 -rotate-90 text-muted-foreground shrink-0" />
        </button>

        {/* Current list — every package, not just the popular ones */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <p className="text-[10px] font-mono uppercase tracking-wider text-muted-foreground/60">
              {activeList === 'session' ? 'During focus' : '24/7'} apps
            </p>
            <span className="text-[10px] font-mono text-muted-foreground/60">
              {(activeList === 'session' ? sessionBlockedApps : alwaysBlockedApps).length}
            </span>
          </div>

          {(activeList === 'session' ? sessionBlockedApps : alwaysBlockedApps).length === 0 ? (
            <div className="rounded-xl border border-dashed border-border/50 px-3 py-4 text-center">
              <p className="text-xs text-muted-foreground leading-relaxed">
                {activeList === 'session'
                  ? 'Nothing blocked during focus yet — a session would block nothing. Shorts & Reels filters still apply.'
                  : 'No 24/7 blocks. Most people do not need any.'}
              </p>
            </div>
          ) : (
            <div className="max-h-[45vh] overflow-y-auto no-scrollbar space-y-1">
              {(activeList === 'session' ? sessionBlockedApps : alwaysBlockedApps).map((pkg) => (
                <div
                  key={pkg}
                  className="flex items-center gap-2.5 rounded-xl border border-border/40 bg-secondary/15 px-3 py-2 min-h-[52px]"
                >
                  <span className="text-base shrink-0" aria-hidden>{resolveAppIcon(pkg)}</span>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-foreground truncate">
                      {resolveAppLabel(pkg, appNames)}
                    </p>
                    <p className="text-[10px] text-muted-foreground/50 truncate font-mono">{pkg}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      void (activeList === 'session' ? toggleAlways(pkg) : toggleSession(pkg))
                    }}
                    className={cn(
                      'px-2 py-1.5 rounded-lg text-[10px] font-mono uppercase tracking-wider shrink-0 min-h-[36px] transition-colors',
                      activeList === 'session'
                        ? 'bg-red-500/10 text-red-300 hover:bg-red-500/20'
                        : 'bg-[oklch(72%_0.11_185)]/10 text-[oklch(72%_0.11_185)] hover:bg-[oklch(72%_0.11_185)]/20',
                    )}
                    aria-label={activeList === 'session' ? `Move ${pkg} to 24/7` : `Move ${pkg} to during focus`}
                  >
                    {activeList === 'session' ? 'Move to 24/7' : 'Move to focus'}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      void (activeList === 'session' ? toggleSession(pkg) : toggleAlways(pkg))
                    }}
                    className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-secondary/40 transition-colors shrink-0"
                    aria-label={`Remove ${resolveAppLabel(pkg, appNames)}`}
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Bulk actions */}
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-2 border-t border-border/40">
          {activeList === 'session' ? (
            <button
              type="button"
              onClick={() => { void handleMoveAllToAlways() }}
              disabled={sessionBlockedApps.length === 0}
              className="text-xs text-muted-foreground hover:text-foreground transition-colors min-h-[44px] disabled:opacity-40"
            >
              Move all to 24/7
            </button>
          ) : (
            <>
              <button
                type="button"
                onClick={() => { void handleMoveAllToSession() }}
                disabled={alwaysBlockedApps.length === 0}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors min-h-[44px] disabled:opacity-40"
              >
                Move all to focus-only
              </button>
              <button
                type="button"
                onClick={() => setConfirmUnblockAll(true)}
                disabled={alwaysBlockedApps.length === 0}
                className="text-xs text-red-400 hover:text-red-300 transition-colors min-h-[44px] disabled:opacity-40"
              >
                Unblock all
              </button>
            </>
          )}
        </div>
      </Card>

      <Modal
        open={confirmUnblockAll}
        onClose={() => setConfirmUnblockAll(false)}
        title="Unblock all 24/7 apps?"
        footer={
          <div className="flex gap-2 justify-end">
            <button
              onClick={() => setConfirmUnblockAll(false)}
              className="px-4 py-2.5 rounded-xl bg-secondary/60 text-sm text-foreground min-h-[44px]"
            >
              Cancel
            </button>
            <button
              onClick={() => { void handleUnblockAllAlways() }}
              className="px-4 py-2.5 rounded-xl bg-red-500/20 text-red-300 text-sm font-medium min-h-[44px]"
            >
              Unblock
            </button>
          </div>
        }
      >
        <p className="text-sm text-muted-foreground leading-relaxed">
          {alwaysBlockedApps.length} app{alwaysBlockedApps.length === 1 ? '' : 's'} will be allowed
          to open outside focus sessions. Session blocks are not affected.
        </p>
      </Modal>

      <AppPickerSheet open={pickerOpen} onClose={() => setPickerOpen(false)} />
    </div>
  )
}

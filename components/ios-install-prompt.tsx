'use client'

import { useCallback, useEffect, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { X, Copy, Check, ExternalLink, Plus } from 'lucide-react'
import { getIOSInstallState, type IOSInstallState } from '@/lib/platform'
import { useReducedMotion } from '@/hooks/use-reduced-motion'
import { Button } from '@/components/ui/button'

const DISMISS_KEY = 'ios-install-dismissed'
const OPEN_EVENT = 'show-ios-install-guide'

/**
 * Lets any other component (e.g. Settings → "Install on iPhone") reopen the
 * guide after it has been dismissed, without adding another store field.
 * Usage: openIOSInstallGuide()
 */
export function openIOSInstallGuide(): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(OPEN_EVENT))
}

/** iOS-style share glyph (square with an arrow leaving the top). */
function IOSShareGlyph({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 3v11" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M8.5 6.5 12 3l3.5 3.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8 10H6.5A1.5 1.5 0 0 0 5 11.5v8A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 1.5-1.5v-8A1.5 1.5 0 0 0 17.5 10H16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

function Step({ index, children }: { index: number; children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-2.5">
      <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/15 text-[11px] font-semibold text-primary">
        {index}
      </span>
      <span className="text-xs leading-relaxed text-muted-foreground">{children}</span>
    </li>
  )
}

/**
 * Add-to-Home-Screen guide for iPhone.
 *
 * Safari never fires `beforeinstallprompt`, so the Chromium InstallPrompt in
 * components/install-prompt.tsx never appears for iOS users. Worse, links
 * shared through WhatsApp/Instagram open in an in-app webview that cannot
 * install a home-screen app at all — those visitors need to be told to reopen
 * the link in Safari, otherwise they just see "a website" and give up.
 */
export function IOSInstallPrompt() {
  const reducedMotion = useReducedMotion()
  const [state, setState] = useState<IOSInstallState>(() => getIOSInstallState())
  const [visible, setVisible] = useState(false)
  const [copied, setCopied] = useState(false)

  const dismiss = useCallback(() => {
    setVisible(false)
    try {
      localStorage.setItem(DISMISS_KEY, 'true')
    } catch {
      /* storage unavailable — the sheet simply reappears next launch */
    }
  }, [])

  useEffect(() => {
    const current = getIOSInstallState()
    if (current !== 'ready' && current !== 'in-app-browser' && current !== 'other-browser') return

    let dismissed = false
    try {
      dismissed = localStorage.getItem(DISMISS_KEY) === 'true'
    } catch {
      /* ignore */
    }
    if (dismissed) return

    // Let the app paint first so the sheet does not fight the intro animation.
    const timer = window.setTimeout(() => setVisible(true), 2200)
    return () => window.clearTimeout(timer)
  }, [])

  useEffect(() => {
    const handler = () => {
      setState(getIOSInstallState())
      setVisible(true)
    }
    window.addEventListener(OPEN_EVENT, handler)
    return () => window.removeEventListener(OPEN_EVENT, handler)
  }, [])

  const copyLink = useCallback(async () => {
    const url = typeof window === 'undefined' ? '' : window.location.origin + '/'
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      /* Clipboard API needs a secure context + user gesture; the link stays visible */
    }
  }, [])

  const needsSafari = state === 'in-app-browser' || state === 'other-browser'

  return (
    <AnimatePresence>
      {visible && (state === 'ready' || needsSafari) && (
        <motion.div
          role="dialog"
          aria-modal="false"
          aria-labelledby="ios-install-title"
          initial={reducedMotion ? false : { opacity: 0, y: 40, scale: 0.96 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={reducedMotion ? { opacity: 0 } : { opacity: 0, y: 20, scale: 0.96 }}
          transition={reducedMotion ? { duration: 0 } : { type: 'spring', stiffness: 300, damping: 26 }}
          className="fixed bottom-[calc(56px+max(env(safe-area-inset-bottom,0px),8px))] left-4 right-4 z-install-prompt mx-auto max-w-sm"
        >
          <div className="glass-panel p-4">
            <div className="flex items-start gap-3">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary/10">
                <IOSShareGlyph className="h-4 w-4 text-primary" />
              </div>

              <div className="min-w-0 flex-1">
                <p id="ios-install-title" className="text-sm font-medium text-foreground">
                  {needsSafari ? 'Open this in Safari' : 'Install Student OS'}
                </p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  {needsSafari
                    ? 'This link is open inside another app, which cannot add apps to your home screen.'
                    : 'Add it to your home screen — full screen, offline, no account.'}
                </p>
              </div>

              <button
                onClick={dismiss}
                aria-label="Dismiss"
                className="-mr-1 flex min-h-[44px] min-w-[44px] shrink-0 items-center justify-center rounded hover:bg-secondary transition-colors"
              >
                <X className="h-3.5 w-3.5 text-muted-foreground" />
              </button>
            </div>

            {needsSafari ? (
              <div className="mt-3 space-y-2">
                <p className="text-xs leading-relaxed text-muted-foreground">
                  Tap <span className="font-medium text-foreground">•••</span> (or the share icon) at
                  the top, choose <span className="font-medium text-foreground">Open in Safari</span>,
                  then follow the Add to Home Screen steps.
                </p>
                <div className="flex items-center gap-2">
                  <Button size="sm" onClick={copyLink} className="min-h-[44px]">
                    {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
                    {copied ? 'Copied' : 'Copy link'}
                  </Button>
                  {typeof navigator !== 'undefined' && typeof navigator.share === 'function' && (
                    <Button
                      size="sm"
                      variant="secondary"
                      className="min-h-[44px]"
                      onClick={() => {
                        navigator
                          .share({ url: window.location.origin + '/', title: 'Student OS' })
                          .catch(() => {})
                      }}
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                      Share
                    </Button>
                  )}
                </div>
              </div>
            ) : (
              <ol className="mt-3 space-y-2">
                <Step index={1}>
                  Tap the <IOSShareGlyph className="mx-0.5 inline h-3.5 w-3.5 align-[-2px] text-primary" />{' '}
                  <span className="font-medium text-foreground">Share</span> button in Safari&apos;s toolbar.
                </Step>
                <Step index={2}>
                  Scroll down and tap{' '}
                  <span className="inline-flex items-center gap-1 font-medium text-foreground">
                    <Plus className="h-3 w-3" /> Add to Home Screen
                  </span>
                  .
                </Step>
                <Step index={3}>
                  Tap <span className="font-medium text-foreground">Add</span> — Student OS opens
                  full screen and keeps working offline.
                </Step>
              </ol>
            )}

            <div className="mt-3 flex items-center justify-end gap-2">
              <Button size="sm" onClick={dismiss} className="min-h-[44px]">
                {needsSafari ? 'Got it' : 'Got it — I\'ll add it'}
              </Button>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

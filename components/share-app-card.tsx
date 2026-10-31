'use client'

import { useCallback, useEffect, useState } from 'react'
import { Check, Copy, QrCode, Share2, Smartphone } from 'lucide-react'
import { Card } from '@/components/ui/glass-card'
import { Button } from '@/components/ui/button'
import { getIOSInstallState, isNativeApp } from '@/lib/platform'

/** Canonical web build — the address advertised in the README, reachable from any phone. */
const PUBLIC_SITE_URL = 'https://jainam6610.github.io/student-os-iphone/'

/**
 * The URL to put in the link box and inside the QR code.
 *
 * `window.location.origin` alone is not enough:
 *   - the Android shell runs on https://localhost, which means nothing on
 *     another phone;
 *   - on GitHub Pages the app is served from /<repo>/, so an origin-only
 *     link drops the path and lands on a 404.
 */
function resolveAppUrl(): string {
  if (typeof window === 'undefined') return ''
  if (isNativeApp() || window.location.protocol === 'file:') return PUBLIC_SITE_URL

  const configuredBase = process.env.NEXT_PUBLIC_BASE_PATH || ''
  const path = (configuredBase || window.location.pathname)
    .replace(/\/index\.html?$/i, '/')
    .replace(/^([^/])/, '/$1')

  return window.location.origin + (path.endsWith('/') ? path : `${path}/`)
}

/**
 * Share card: the one place to grab the app URL or point a friend's camera at
 * the QR code. The QR is rendered lazily from the resolved app URL, so it
 * always matches the address the app is actually deployed on — base path
 * included — with no build step and no stale hardcoded link.
 */
export function ShareAppCard() {
  const [qr, setQr] = useState<string | null>(null)
  const [url] = useState(resolveAppUrl)
  const [copied, setCopied] = useState(false)
  const [canShare] = useState(
    () => typeof navigator !== 'undefined' && typeof navigator.share === 'function',
  )

  useEffect(() => {
    if (!url) return

    let cancelled = false
    // Lazy: keeps the QR encoder out of the initial Settings chunk.
    import('qrcode')
      .then(({ default: QRCode }) =>
        QRCode.toDataURL(url, {
          errorCorrectionLevel: 'M',
          margin: 1,
          width: 320,
          color: { dark: '#0a0a14', light: '#ffffff' },
        }),
      )
      .then((dataUrl) => {
        if (!cancelled) setQr(dataUrl)
      })
      .catch(() => {
        /* QR is a convenience — the link and share button still work */
      })

    return () => {
      cancelled = true
    }
  }, [url])

  const copyLink = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      /* clipboard needs a secure context; the URL stays visible below */
    }
  }, [url])

  const share = useCallback(async () => {
    if (typeof navigator === 'undefined' || typeof navigator.share !== 'function') return
    try {
      await navigator.share({
        title: 'Student OS',
        text: 'Open this in Safari, then tap Share → Add to Home Screen.',
        url,
      })
    } catch {
      /* dismissed */
    }
  }, [url])

  const installState = getIOSInstallState()

  return (
    <Card className="p-5">
      <div className="flex items-start gap-4">
        <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-primary/10">
          <QrCode className="h-5 w-5 text-primary" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="text-base font-semibold text-foreground">Share Student OS</h3>
          <p className="mt-0.5 text-xs text-muted-foreground">
            Have a friend scan this or open the link on their phone.
          </p>
        </div>
      </div>

      <div className="mt-4 flex flex-col items-center gap-3 sm:flex-row sm:items-start">
        <div className="flex h-[152px] w-[152px] shrink-0 items-center justify-center rounded-2xl bg-white p-2">
          {qr ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={qr} alt="QR code linking to Student OS" width={136} height={136} className="h-full w-full" />
          ) : (
            <div className="h-full w-full animate-pulse rounded-xl bg-neutral-200" />
          )}
        </div>

        <div className="min-w-0 flex-1">
          <p className="break-all rounded-lg bg-secondary/60 px-3 py-2 font-mono text-[11px] leading-relaxed text-muted-foreground">
            {url || '—'}
          </p>

          <div className="mt-2 flex flex-wrap gap-2">
            <Button size="sm" onClick={copyLink} className="min-h-[44px]">
              {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
              {copied ? 'Copied' : 'Copy link'}
            </Button>
            {canShare && (
              <Button size="sm" variant="secondary" onClick={share} className="min-h-[44px]">
                <Share2 className="h-3.5 w-3.5" />
                Share
              </Button>
            )}
          </div>

          <p className="mt-2.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-muted-foreground">
            <Smartphone className="mt-0.5 h-3 w-3 shrink-0 text-primary" />
            <span>
              <span className="text-foreground/80">On iPhone:</span> the link must be opened in
              Safari — inside Instagram or WhatsApp it cannot be installed
              {installState === 'installed' ? '. You are already running the installed app.' : '.'}
            </span>
          </p>
        </div>
      </div>
    </Card>
  )
}

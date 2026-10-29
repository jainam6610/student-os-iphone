// ─────────────────────────────────────────────────────────────
//  Generates a static share QR for the deployed app.
//
//  The app also renders this QR live in Settings → Share Student OS
//  (from window.location.origin), which is always correct. This script
//  exists for the cases where you need the image as a file: printing it
//  on a poster, pasting it into a group chat, or committing it to the repo.
//
//  Usage:
//    node scripts/make-qr.mjs https://your-app.pages.dev
//    APP_URL=https://your-app.pages.dev node scripts/make-qr.mjs
//
//  Emits: public/install-qr.svg  +  public/install-qr.png
// ─────────────────────────────────────────────────────────────

import QRCode from 'qrcode'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT_DIR = path.join(ROOT, 'public')

function resolveUrl() {
  const raw = process.argv[2] || process.env.APP_URL || process.env.NEXT_PUBLIC_APP_URL
  if (!raw) {
    console.error('Missing URL.\n')
    console.error('  node scripts/make-qr.mjs https://your-app.pages.dev')
    console.error('  APP_URL=https://your-app.pages.dev node scripts/make-qr.mjs\n')
    process.exit(1)
  }

  let parsed
  try {
    parsed = new URL(raw)
  } catch {
    console.error(`Not a valid URL: ${raw}`)
    process.exit(1)
  }

  if (parsed.protocol !== 'https:' && parsed.hostname !== 'localhost') {
    // A QR pointing at http:// is useless on a phone: iOS will not install a
    // service worker (or a home-screen app) from an insecure origin.
    console.error(`Refusing to encode a non-HTTPS URL: ${raw}`)
    console.error('The phone cannot install a PWA over plain http unless it is localhost.')
    process.exit(1)
  }

  return parsed.toString()
}

const OPTIONS = {
  errorCorrectionLevel: 'M',
  margin: 2,
  color: { dark: '#0a0a14', light: '#ffffff' },
}

async function main() {
  const url = resolveUrl()
  await mkdir(OUT_DIR, { recursive: true })

  await QRCode.toFile(path.join(OUT_DIR, 'install-qr.png'), url, { ...OPTIONS, width: 1024 })
  await QRCode.toFile(path.join(OUT_DIR, 'install-qr.svg'), url, { ...OPTIONS, type: 'svg', width: 1024 })

  console.log(`QR encoded: ${url}`)
  console.log('  public/install-qr.png')
  console.log('  public/install-qr.svg')
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})

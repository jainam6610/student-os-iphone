// ─────────────────────────────────────────────────────────────
//  Generates every icon asset from the brand mark.
//
//  Run:  node scripts/make-icons.mjs
//  Emits: public/icon.svg            (favicon, transparent corners)
//         public/icon-512.svg        (notification icon)
//         public/icon-512x512.png    (PWA / maskable)
//         public/icon-192x192.png    (PWA / notification badge)
//         public/apple-icon.png      (Apple touch icon, full bleed)
//         public/icon-light-32x32.png
//         public/icon-dark-32x32.png
// ─────────────────────────────────────────────────────────────

import sharp from 'sharp'
import { writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { brandSvg } from './brand-mark.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PUBLIC = path.join(ROOT, 'public')

const png = async (size, file, options) => {
  await sharp(Buffer.from(brandSvg(options))).resize(size, size).png().toFile(path.join(PUBLIC, file))
  console.log(`  ${file.padEnd(24)} ${size}×${size}`)
}

async function main() {
  // Opaque behaviour matches the launcher-safe assets that shipped before:
  // dark plate behind the rounded tile, so alpha never exposes the wallpaper.
  await png(512, 'icon-512x512.png', { plate: true })
  await png(192, 'icon-192x192.png', { plate: true })
  await png(180, 'apple-icon.png', { rounded: false })
  await png(32, 'icon-light-32x32.png', { plate: true })
  await png(32, 'icon-dark-32x32.png', { plate: true })

  // SVGs stay transparent outside the tile so browser tabs / notifications
  // composite them cleanly on any background.
  await writeFile(path.join(PUBLIC, 'icon.svg'), brandSvg(), 'utf8')
  await writeFile(path.join(PUBLIC, 'icon-512.svg'), brandSvg(), 'utf8')
  console.log('  icon.svg, icon-512.svg')

  console.log('\nGenerated 7 icon assets from scripts/brand-mark.mjs')
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})

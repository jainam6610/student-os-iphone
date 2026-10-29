// ─────────────────────────────────────────────────────────────
//  Generates manifest.json with correct basePath for GitHub Pages.
//  Run after build (or as part of build) when deploying to GH Pages.
// ─────────────────────────────────────────────────────────────

import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PUBLIC_MANIFEST = path.join(ROOT, 'public', 'manifest.json')
const OUT_MANIFEST = path.join(ROOT, 'out', 'manifest.json')

const BASE_PATH = process.env.GITHUB_PAGES_REPO ? `/${process.env.GITHUB_PAGES_REPO}` : ''

function prefix(url) {
  if (!BASE_PATH) return url
  if (url.startsWith('/')) return BASE_PATH + url
  return url
}

async function main() {
  const raw = await readFile(PUBLIC_MANIFEST, 'utf8')
  const manifest = JSON.parse(raw)

  // Update paths that need the base prefix
  manifest.start_url = prefix(manifest.start_url)
  manifest.scope = prefix(manifest.scope)
  manifest.id = prefix(manifest.id)

  for (const icon of manifest.icons) {
    icon.src = prefix(icon.src)
  }

  for (const screenshot of manifest.screenshots) {
    screenshot.src = prefix(screenshot.src)
  }

  for (const shortcut of manifest.shortcuts) {
    shortcut.url = prefix(shortcut.url)
    for (const icon of shortcut.icons) {
      icon.src = prefix(icon.src)
    }
  }

  await writeFile(OUT_MANIFEST, JSON.stringify(manifest, null, 2), 'utf8')
  console.log(`manifest.json written with basePath: ${BASE_PATH || '(none)'}`)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
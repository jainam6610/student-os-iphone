// ─────────────────────────────────────────────────────────────
//  Rewrites out/sw.js for a GitHub Pages project subpath.
//
//  public/sw.js is authored for a root deployment (Cloudflare Pages,
//  Capacitor) and therefore uses root-absolute URLs like '/manifest.json'
//  and path checks like url.pathname === '/'. Served from
//  https://<user>.github.io/<repo>/sw.js those URLs point at the domain
//  root, so cache.addAll() 404s and the install handler rejects — the PWA
//  then never works offline.
//
//  This script prefixes every root-absolute string literal with the
//  basePath, so the same source produces a correct subpath worker.
//  Source stays untouched; only out/sw.js is modified.
// ─────────────────────────────────────────────────────────────

import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT_SW = path.join(ROOT, 'out', 'sw.js')
const BASE_PATH = process.env.GITHUB_PAGES_REPO ? `/${process.env.GITHUB_PAGES_REPO}` : ''

if (!BASE_PATH) {
  console.log('sw.js: no GITHUB_PAGES_REPO set — leaving worker as-is (root deploy)')
  process.exit(0)
}

const raw = await readFile(OUT_SW, 'utf8')

// Prefix every single-quoted root-absolute literal: '/' -> '/repo/',
// '/manifest.json' -> '/repo/manifest.json', etc.
// Skip literals that already carry the basePath (idempotent re-runs).
// Restrict the captured value to plausible URL characters so the transform
// never leaks into markup (e.g. SVG path data inside the offline fallback).
let rewritten = 0
const out = raw.replace(/(['"])(\/(?!\/)[\w./?=&%#-]*)\1/g, (match, quote, url) => {
  if (url === BASE_PATH || url.startsWith(BASE_PATH + '/')) return match
  rewritten++
  return `${quote}${BASE_PATH}${url}${quote}`
})

await writeFile(OUT_SW, out, 'utf8')
console.log(`sw.js: prefixed ${rewritten} root-absolute URL(s) with ${BASE_PATH}`)

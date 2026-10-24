#!/usr/bin/env node
/**
 * verify-release.mjs
 * Run before every Play Store submission:
 *   node scripts/verify-release.mjs
 *
 * Checks:
 * 1. versionCode and versionName in build.gradle match package.json
 * 2. Manifest does not contain forbidden permissions
 * 3. Manifest contains required permissions
 * 4. Schema version in store constants matches expected
 * 5. No debug flags in production config
 */

import { readFileSync } from 'fs'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '..')

let passed = 0
let failed = 0

function pass(msg) {
  console.log(`  ✓  ${msg}`)
  passed++
}

function fail(msg) {
  console.error(`  ✗  ${msg}`)
  failed++
}

function check(label, fn) {
  console.log(`\n[ ${label} ]`)
  try { fn() } catch (e) { fail(`threw: ${e.message}`) }
}

// ── 1. Version consistency ──────────────────────────────────
check('Version consistency', () => {
  const pkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'))
  const gradle = readFileSync(
    resolve(root, 'android/app/build.gradle'), 'utf8')

  const pkgVersion = pkg.version
  const [, gradleCode] = gradle.match(/versionCode\s+(\d+)/) ?? []
  const [, gradleName] = gradle.match(/versionName\s+"([^"]+)"/) ?? []

  if (gradleName === pkgVersion) {
    pass(`versionName "${gradleName}" matches package.json "${pkgVersion}"`)
  } else {
    fail(`versionName "${gradleName}" ≠ package.json "${pkgVersion}"`)
  }

  if (gradleCode && parseInt(gradleCode) > 0) {
    pass(`versionCode ${gradleCode} is valid`)
  } else {
    fail(`versionCode "${gradleCode}" is invalid`)
  }
})

// ── 2. Forbidden permissions ────────────────────────────────
check('No forbidden permissions', () => {
  const manifest = readFileSync(
    resolve(root, 'android/app/src/main/AndroidManifest.xml'), 'utf8')

  const forbidden = [
    'SYSTEM_ALERT_WINDOW',
    'QUERY_ALL_PACKAGES',
    'READ_CONTACTS',
    'READ_SMS',
    'ACCESS_FINE_LOCATION',
    'ACCESS_COARSE_LOCATION',
    'RECORD_AUDIO',
    'CAMERA',
    'REQUEST_INSTALL_PACKAGES',
  ]

  for (const perm of forbidden) {
    if (manifest.includes(perm)) {
      fail(`Forbidden permission found: ${perm}`)
    } else {
      pass(`${perm} not present`)
    }
  }
})

// ── 3. Required permissions ─────────────────────────────────
check('Required permissions present', () => {
  const manifest = readFileSync(
    resolve(root, 'android/app/src/main/AndroidManifest.xml'), 'utf8')

  const required = [
    'BIND_ACCESSIBILITY_SERVICE',
    'PACKAGE_USAGE_STATS',
    'SCHEDULE_EXACT_ALARM',
    'POST_NOTIFICATIONS',
    'RECEIVE_BOOT_COMPLETED',
    'FOREGROUND_SERVICE',
  ]

  for (const perm of required) {
    if (manifest.includes(perm)) {
      pass(`${perm} present`)
    } else {
      fail(`Required permission missing: ${perm}`)
    }
  }
})

// ── 4. Schema version ───────────────────────────────────────
check('Store schema version', () => {
  const constants = readFileSync(
    resolve(root, 'lib/store/constants.ts'), 'utf8')
  const [, version] = constants.match(
    /CURRENT_SCHEMA_VERSION\s*=\s*(\d+)/) ?? []

  const expected = 18 // bump this manually when schema changes
  if (parseInt(version) === expected) {
    pass(`CURRENT_SCHEMA_VERSION = ${version}`)
  } else if (parseInt(version) > expected) {
    pass(`CURRENT_SCHEMA_VERSION = ${version} (update expected in script)`)
  } else {
    fail(`CURRENT_SCHEMA_VERSION = ${version}, expected >= ${expected}`)
  }
})

// ── 5. No debug flags in production config ──────────────────
check('No debug flags', () => {
  const capConfig = readFileSync(
    resolve(root, 'capacitor.config.ts'), 'utf8')

  if (capConfig.includes('webContentsDebuggingEnabled: true')) {
    fail('webContentsDebuggingEnabled is true — must be false for release')
  } else {
    pass('webContentsDebuggingEnabled is not true')
  }

  if (capConfig.includes('loggingBehavior')) {
    pass('loggingBehavior is configured')
  }
})

// ── 6. Service worker version matches its registration URL (R9) ──
check('Service worker version consistency', () => {
  const sw = readFileSync(resolve(root, 'public/sw.js'), 'utf8')
  const layout = readFileSync(resolve(root, 'app/layout.tsx'), 'utf8')

  const [, swVersion] = sw.match(/SW_VERSION\s*=\s*(\d+)/) ?? []
  const [, regVersion] = layout.match(/serviceWorker\.register\('\/sw\.js\?v=(\d+)'\)/) ?? []

  if (!swVersion) {
    fail('public/sw.js is missing SW_VERSION')
    return
  }
  if (!regVersion) {
    fail("app/layout.tsx is missing the '/sw.js?v=N' registration")
    return
  }
  if (swVersion === regVersion) {
    pass(`SW_VERSION ${swVersion} matches registration ?v=${regVersion}`)
  } else {
    fail(`SW_VERSION ${swVersion} ≠ registration ?v=${regVersion} — bump both together`)
  }
})

// ── 7. About build number comes from the installed package (R10) ──
check('About build number source', () => {
  const settings = readFileSync(resolve(root, 'components/settings.tsx'), 'utf8')
  const gradle = readFileSync(resolve(root, 'android/app/build.gradle'), 'utf8')
  const [, versionCode] = gradle.match(/versionCode\s+(\d+)/) ?? []

  if (!versionCode) {
    fail('android/app/build.gradle has no versionCode')
    return
  }
  // The About card must read the real package build (App.getInfo().build), so it
  // can never drift from versionCode; a hardcoded string would.
  if (settings.includes('App.getInfo()')) {
    pass(`About reads App.getInfo() (build = versionCode ${versionCode})`)
  } else {
    fail('About does not read App.getInfo() — build number can drift from versionCode')
  }
})

// ── 8. Service worker install fails when the shell is missing (R7) ──
check('Service worker shell integrity', () => {
  const sw = readFileSync(resolve(root, 'public/sw.js'), 'utf8')
  // Only the install handler matters: activate() legitimately settles cache
  // deletions individually.
  const installMatch = sw.match(/addEventListener\('install'[\s\S]*?\n\}\)/)
  const install = installMatch ? installMatch[0] : ''

  if (!install) {
    fail('public/sw.js has no install handler')
  } else if (/allSettled/.test(install) || /\.catch\(\(\)\s*=>/.test(install)) {
    fail('install swallows cache failures — a missing shell file would still activate')
  } else if (install.includes('cache.addAll(PRECACHE_URLS)')) {
    pass('install uses cache.addAll(PRECACHE_URLS) — a missing shell file fails the install')
  } else {
    fail('install does not precache the shell with cache.addAll')
  }

  // Every precached URL must exist in the exported build (run after `pnpm build`).
  let urls = []
  try {
    const outIndex = readFileSync(resolve(root, 'out/index.html'))
    if (outIndex) {
      const listMatch = sw.match(/const PRECACHE_URLS = \[([\s\S]*?)\]/)
      urls = listMatch ? [...listMatch[1].matchAll(/'([^']+)'/g)].map((m) => m[1]) : []
      const missing = urls.filter((u) => {
        const rel = u === '/' ? 'index.html' : u.replace(/^\//, '')
        try { readFileSync(resolve(root, 'out', rel)); return false } catch { return true }
      })
      if (missing.length > 0) {
        fail(`precached shell file(s) missing from out/: ${missing.join(', ')}`)
      } else {
        pass(`all ${urls.length} precached shell files exist in out/`)
      }
    }
  } catch {
    console.log('  ·  out/ not built — skipping shell file existence check')
  }
})

// ── Result ──────────────────────────────────────────────────
console.log(`\n${'─'.repeat(48)}`)
console.log(`  ${passed} passed   ${failed} failed`)
console.log('─'.repeat(48))

if (failed > 0) {
  console.error('\nRelease verification FAILED. Fix issues before submitting.\n')
  process.exit(1)
} else {
  console.log('\nRelease verification PASSED. Safe to submit.\n')
}

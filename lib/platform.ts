import { Capacitor } from '@capacitor/core'

/**
 * Platform capability helpers.
 *
 * Student OS ships as (1) an Android Capacitor app and (2) a plain web app.
 * The web build is what iPhone users install to their home screen, and the
 * browser it runs in cannot do everything the Android shell can:
 *
 *   - No app blocking        → Accessibility Service is Android-only.
 *   - No scheduled reminders → Safari has no notification-trigger API, so
 *                              timers only fire while the tab is open.
 *   - Only Safari can add a  → Chrome/Firefox/Edge on iOS are WebKit skins and
 *     site to the home screen   cannot install PWAs; in-app browsers (Instagram,
 *                              WhatsApp…) cannot either.
 *
 * Everything here is SSR-safe: the app is statically exported and components
 * render on the server first, so `navigator` and `window` may be absent.
 */

function userAgent(): string {
  if (typeof navigator === 'undefined') return ''
  return navigator.userAgent || ''
}

/** True on iPhone/iPad/iPod, including iPadOS 13+ which reports as a Mac. */
export function isIOS(): boolean {
  if (typeof navigator === 'undefined') return false
  const ua = userAgent()
  if (/iPad|iPhone|iPod/.test(ua)) return true
  // iPadOS 13+ masquerades as desktop Safari; touch points give it away.
  const maxTouchPoints = (navigator as Navigator & { maxTouchPoints?: number }).maxTouchPoints ?? 0
  return ua.includes('Macintosh') && maxTouchPoints > 1
}

/** True only inside the Android Capacitor shell. */
export function isAndroidApp(): boolean {
  try {
    return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
  } catch {
    return false
  }
}

/** True on any Capacitor native shell (Android today, iOS if it ever ships). */
export function isNativeApp(): boolean {
  try {
    return Capacitor.isNativePlatform()
  } catch {
    return false
  }
}

/** Running from a home-screen icon / installed app window. */
export function isStandalone(): boolean {
  if (typeof window === 'undefined') return false
  try {
    if (window.matchMedia?.('(display-mode: standalone)').matches) return true
  } catch {
    /* matchMedia unavailable — fall through to the iOS-only flag */
  }
  return (navigator as Navigator & { standalone?: boolean }).standalone === true
}

/** Focus Shield (app blocking) requires the Android Accessibility Service. */
export function canUseFocusShield(): boolean {
  return isAndroidApp()
}

/**
 * Reminders can only be delivered in the background by the Android alarm
 * pipeline. On iOS the web build falls back to in-tab timers, and the Calendar
 * (.ics) export is the way to get real alarms.
 */
export function canScheduleAlarms(): boolean {
  return isAndroidApp()
}

/** In-app browsers shipped inside social apps — these cannot install a PWA. */
const IN_APP_BROWSER_PATTERNS = [
  /FBAN/i, // Facebook
  /FBAV/i, // Facebook
  /Instagram/i,
  /Line\//i,
  /LinkedInApp/i,
  /WhatsApp/i,
  /Snapchat/i,
  /Twitter/i,
  /Pinterest/i,
  /GSA\//i, // Google app
  /MicroMessenger/i,
]

/** WebKit skins on iOS that are real browsers but still cannot install a PWA. */
const NON_SAFARI_IOS_PATTERNS = [
  /CriOS/i, // Chrome
  /FxiOS/i, // Firefox
  /EdgiOS/i, // Edge
  /OPiOS/i, // Opera
  /DuckDuckGo/i,
  /YaBrowser/i,
]

export function isInAppBrowser(): boolean {
  if (typeof navigator === 'undefined') return false
  const ua = userAgent()
  if (IN_APP_BROWSER_PATTERNS.some((pattern) => pattern.test(ua))) return true
  // A WKWebView with no Safari token is an embedded webview.
  if (isIOS() && !/Safari\//i.test(ua)) return true
  return false
}

export type IOSInstallState =
  /** Safari on iOS, not yet installed — the Add to Home Screen guide applies. */
  | 'ready'
  /** Already running from the home screen. */
  | 'installed'
  /** Chrome/Firefox/Edge on iOS — must be reopened in Safari. */
  | 'other-browser'
  /** Instagram/WhatsApp/etc. webview — must be reopened in Safari. */
  | 'in-app-browser'
  /** Not an iOS device — the Android/Chromium path applies instead. */
  | 'not-ios'

/**
 * Single source of truth for what the install guidance should say.
 * Only Safari on iOS can add a site to the home screen.
 */
export function getIOSInstallState(): IOSInstallState {
  if (!isIOS()) return 'not-ios'
  if (isStandalone()) return 'installed'
  if (isInAppBrowser()) return 'in-app-browser'
  if (NON_SAFARI_IOS_PATTERNS.some((pattern) => pattern.test(userAgent()))) return 'other-browser'
  if (isNativeApp()) return 'installed'
  return 'ready'
}

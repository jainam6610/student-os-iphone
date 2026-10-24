/**
 * Helpers for the user-maintained website block list.
 *
 * The list is stored as bare hostnames ("instagram.com") — never full URLs —
 * so that a single entry also covers subdomains (m.instagram.com,
 * www.instagram.com) and any path on the site. The native side
 * (BlockingEngine.isUrlBlocked) uses the exact same normalisation rules.
 */

const MAX_DOMAIN_LENGTH = 120

/**
 * Turn arbitrary user input into a bare hostname.
 * Returns null when the input cannot be a domain.
 *
 * "https://www.Instagram.com/reels/?x=1" → "instagram.com"
 * "youtube.com"                        → "youtube.com"
 * "localhost"                          → null  (no dot, too risky to block)
 */
export function normalizeDomain(raw: string): string | null {
  let value = (raw ?? '').trim().toLowerCase()
  if (!value) return null

  // Strip any scheme (https://, about:, chrome://) and everything after a path.
  value = value.replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
  value = value.split(/[/?#]/)[0]
  value = value.replace(/:\d+$/, '')      // drop port
  value = value.replace(/^www\./, '')     // drop www prefix
  value = value.replace(/\.+$/, '')       // drop trailing dots

  if (!value || value.length > MAX_DOMAIN_LENGTH) return null
  if (!value.includes('.')) return null
  // Hostname-safe characters only (no spaces, no protocol leftovers).
  if (!/^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$/.test(value)) return null

  return value
}

/** Normalise + de-duplicate a list of domains, keeping insertion order. */
export function normalizeDomainList(list: readonly string[] | undefined | null): string[] {
  const out: string[] = []
  for (const entry of list ?? []) {
    const domain = normalizeDomain(entry)
    if (domain && !out.includes(domain)) out.push(domain)
  }
  return out
}

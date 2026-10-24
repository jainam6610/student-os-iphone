package com.jainam.studentos.focusshield.feature.blocking

/**
 * Pure domain/URL matching used by the browser (website) filters.
 *
 * Kept free of Android dependencies so it can be unit tested on the JVM.
 * The normalisation rules mirror `lib/deep-lock/websites.ts` — a domain typed
 * in the UI must match exactly what the engine compares at runtime.
 */
object UrlMatcher {

    /** Known adult domains (substring match, intentionally broad). */
    val ADULT_PATTERNS: List<String> = listOf(
        "pornhub", "xvideos", "xnxx", "redtube", "youporn", "tube8",
        "brazzers", "bangbros", "naughtyamerica", "realitykings",
        "xhamster", "chaturbate", "onlyfans", "stripchat", "livejasmin",
        "cam4", "myfreecams", "bongacams", "manyvids", "clips4sale"
    )

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://")
    private val PORT = Regex(":\\d+$")
    private val WWW = Regex("^www\\.")
    private val TRAILING_DOTS = Regex("\\.+$")
    private val HOSTNAME = Regex("^[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}$")

    /**
     * "https://www.Instagram.com/reels/?x=1" → "instagram.com".
     * Returns null for anything that is not a plausible hostname.
     */
    fun normalizeDomain(raw: String): String? {
        var value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        value = SCHEME.replace(value, "")
        value = value.split('/', '?', '#').firstOrNull() ?: return null
        value = PORT.replace(value, "")
        value = WWW.replace(value, "")
        value = TRAILING_DOTS.replace(value, "")
        if (value.isEmpty() || value.length > 120) return null
        if (!value.contains('.')) return null
        if (!HOSTNAME.matches(value)) return null
        return value
    }

    fun normalizeDomainList(list: Iterable<String>): Set<String> =
        list.mapNotNull { normalizeDomain(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * Does [host] match [domain]? A blocked entry also covers its subdomains,
     * but never a lookalike: blocking "youtube.com" must not match
     * "notyoutube.com" or "youtube.com.evil.io".
     */
    fun hostMatches(host: String, domain: String): Boolean {
        if (domain.isEmpty() || host.isEmpty()) return false
        var index = host.indexOf(domain)
        while (index >= 0) {
            val before = if (index == 0) null else host[index - 1]
            val afterIndex = index + domain.length
            val after = if (afterIndex >= host.length) null else host[afterIndex]

            val beforeOk = before == null || !(before.isLetterOrDigit() || before == '-')
            val afterOk = after == null || !(after.isLetterOrDigit() || after == '-' || after == '.')

            if (beforeOk && afterOk) return true
            index = host.indexOf(domain, index + 1)
        }
        return false
    }

    /** Extract the hostname of an address-bar string, or null. */
    fun extractHost(text: String): String? {
        val trimmed = text.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        return try {
            java.net.URI(withScheme).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when [text] (address bar / page title / URL) hits the user's block
     * list or, when enabled, the adult-domain patterns.
     */
    fun isBlocked(text: String?, blockedWebsites: Set<String>, blockAdultUrls: Boolean): Boolean {
        if (text.isNullOrBlank()) return false
        if (blockedWebsites.isEmpty() && !blockAdultUrls) return false

        val lower = text.lowercase()

        if (blockedWebsites.isNotEmpty()) {
            val host = extractHost(lower)
            for (domain in blockedWebsites) {
                if (host != null && hostMatches(host, domain)) return true
                if (hostMatches(lower, domain)) return true
            }
        }

        if (blockAdultUrls && ADULT_PATTERNS.any { lower.contains(it) }) return true
        return false
    }
}

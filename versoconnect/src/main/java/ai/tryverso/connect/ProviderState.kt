package ai.tryverso.connect

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * What the provider's login leaves in the WebView, and how to remove it
 * without touching anything else. Android keeps one cookie jar and one web
 * storage per app, shared with the host app's own WebViews: clearing them
 * whole signs the user out of the host app. Only the provider's domains are
 * cleared: `cookieDomain` from the start response, the login and session
 * pages' domains, and the hosts the flow visited under them.
 */
internal object ProviderState {

    /** The registrable domain, taken as the last two labels: "auth.openai.com" gives "openai.com". */
    fun apex(host: String): String {
        val labels = host.trimStart('.').split('.').filter { it.isNotEmpty() }
        return labels.takeLast(2).joinToString(".")
    }

    /**
     * The hosts whose cookies and storage belong to the provider: the apex of
     * each of [domains], the domains themselves, and every host in [visited]
     * under one of those apexes. Hosts the flow passed through that belong to
     * someone else (a Google or Apple sign-in) are left alone.
     */
    fun hostsToClear(domains: Collection<String>, visited: Collection<String>): List<String> {
        val apexes = domains.map { apex(it) }.filter { it.isNotEmpty() }.distinct()
        val under = visited.filter { host -> apexes.any { apex -> host == apex || host.endsWith(".$apex") } }
        return (apexes + domains.map { it.trimStart('.') } + under).filter { it.isNotEmpty() }.distinct()
    }

    /** The cookie names in a `Cookie:` header, as [CookieManager.getCookie] returns it. */
    fun cookieNames(header: String?): List<String> =
        header.orEmpty().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            (if (i < 0) part else part.substring(0, i)).trim().takeIf { it.isNotEmpty() }
        }.distinct()

    /**
     * The `Set-Cookie` values that expire the cookie [name] on [host] whatever
     * Domain attribute it was set with: none (host-only), or any domain from
     * the host up to its apex. A cookie is identified by its name, domain and
     * path (and partition, when it has one), so an expired copy replaces the
     * live one. `Secure` is required for `__Secure-` and `__Host-` names and
     * harmless for the others. Only `Path=/` cookies are reached, which is
     * what the provider sets.
     */
    fun expiring(name: String, host: String): List<String> {
        val base = "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure"
        val labels = host.split('.')
        val domains = (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
        val plain = listOf(base) + domains.map { "$base; Domain=$it" }
        // A partitioned cookie (Cloudflare's cf_clearance) is only matched by
        // a partitioned copy; the WebView keys it on the page's own site.
        return plain + plain.map { "$it; SameSite=None; Partitioned" }
    }

    /**
     * Expires the provider's cookies and deletes its web storage for [hosts].
     * Returns the hosts that still answer with cookies afterwards, with the
     * cookie names (a cookie on another path, for instance), for the caller
     * to log.
     */
    fun clear(cookies: CookieManager, storage: WebStorage, hosts: Collection<String>): List<String> {
        for (host in hosts) {
            val url = "https://$host"
            for (name in cookieNames(cookies.getCookie(url))) {
                for (value in expiring(name, host)) cookies.setCookie(url, value)
            }
            storage.deleteOrigin(url)
        }
        cookies.flush()
        return hosts.mapNotNull { host ->
            cookieNames(cookies.getCookie("https://$host")).takeIf { it.isNotEmpty() }?.let { "$host (${it.joinToString(", ")})" }
        }
    }
}

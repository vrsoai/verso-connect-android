package ai.tryverso.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderStateTest {

    @Test
    fun `apex keeps the last two labels`() {
        assertEquals("openai.com", ProviderState.apex("auth.openai.com"))
        assertEquals("chatgpt.com", ProviderState.apex("chatgpt.com"))
        assertEquals("chatgpt.com", ProviderState.apex(".chatgpt.com"))
        assertEquals("chatgpt.com", ProviderState.apex("ab.cdn.chatgpt.com"))
    }

    @Test
    fun `hosts to clear are the provider's apexes, domains and visited subdomains, never others`() {
        val hosts = ProviderState.hostsToClear(
            domains = listOf("chatgpt.com", "auth.openai.com", "chatgpt.com"),
            visited = listOf("chatgpt.com", "auth.openai.com", "ab.chatgpt.com", "accounts.google.com", "appleid.apple.com", "partner.example"),
        )
        assertEquals(listOf("chatgpt.com", "openai.com", "auth.openai.com", "ab.chatgpt.com"), hosts)
    }

    @Test
    fun `hosts to clear before any navigation are the provider domains alone`() {
        assertEquals(
            listOf("chatgpt.com", "openai.com", "auth.openai.com"),
            ProviderState.hostsToClear(listOf("chatgpt.com", "auth.openai.com"), emptyList()),
        )
    }

    @Test
    fun `cookie names come out of a Cookie header`() {
        assertEquals(
            listOf("__Secure-next-auth.session-token", "oai-did", "flag"),
            ProviderState.cookieNames("__Secure-next-auth.session-token=abc; oai-did=x=y; flag; oai-did=z"),
        )
        assertEquals(emptyList<String>(), ProviderState.cookieNames(null))
        assertEquals(emptyList<String>(), ProviderState.cookieNames(""))
    }

    @Test
    fun `expiring covers host-only and every domain up to the apex`() {
        val values = ProviderState.expiring("sid", "auth.openai.com")
        assertEquals(6, values.size)
        assertEquals("sid=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure", values[0])
        assertEquals(values[0] + "; Domain=auth.openai.com", values[1])
        assertEquals(values[0] + "; Domain=openai.com", values[2])
        assertEquals(values[0] + "; SameSite=None; Partitioned", values[3])
        assertEquals(values[2] + "; SameSite=None; Partitioned", values[5])
        assertEquals(4, ProviderState.expiring("sid", "chatgpt.com").size)
    }
}

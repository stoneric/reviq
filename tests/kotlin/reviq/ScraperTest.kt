package reviq

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScraperTest {
    private val html=javaClass.getResource("/google-play-page.html")!!.readText()
    private fun scraper(status: Int=200,body: String=html,type: String="text/html")=GooglePlayScraper(PageFetcher { _,_,_,_ -> FetchedPage(status,body,type) })
    @Test fun `only strict Google Play app URLs are accepted and all fetch targets are rebuilt`() {
        assertEquals("https://play.google.com/store/apps/details?id=com.spotify.music&hl=en&gl=US",GooglePlayScraper.canonicalUrl("https://play.google.com/store/apps/details?gl=CA&id=com.spotify.music&hl=fr&pcampaignid=web_share&pli=1"))
        listOf("http://play.google.com/store/apps/details?id=com.spotify.music","https://127.0.0.1/store/apps/details?id=x.y","https://[::1]/store/apps/details?id=x.y","https://play.google.com.evil.example/store/apps/details?id=x.y","https://play.google.com@127.0.0.1/store/apps/details?id=x.y","https://play.google.com:444/store/apps/details?id=x.y","https://play.google.com/store/apps/../details?id=x.y","https://play.google.com/store/apps/details?id=x.y&id=a.b","https://play.google.com/store/apps/details?id=x.y%26target%3D127.0.0.1","https://play.google.com/store/apps/details?id=x.y#fragment","https://play.google.com/store/apps/details?id=x.y&url=https://127.0.0.1","https://www.trustpilot.com/review/example.com").forEach {
            val error=assertThrows(ApiError::class.java) { GooglePlayScraper.canonicalUrl(it) }; assertEquals("unsupported_url",error.code,it)
        }
    }
    @Test fun `reserved private loopback and transition addresses cannot reach connector`() {
        listOf("0.0.0.0","10.1.2.3","127.0.0.1","169.254.169.254","172.16.1.1","192.168.1.1","100.64.0.1","192.0.2.1","198.51.100.1","203.0.113.1","198.18.0.1","224.0.0.1","240.0.0.1","::","::1","fe80::1","fc00::1","2001:db8::1","2002:7f00:0001::1","::ffff:127.0.0.1","3fff::1").forEach { assertFalse(GooglePlayScraper.publicAddress(InetAddress.getByName(it)),it) }
        listOf("142.250.191.78","172.217.1.14","2607:f8b0:4005:805::200e").forEach { assertTrue(GooglePlayScraper.publicAddress(InetAddress.getByName(it)),it) }
        val client=PublicPageFetcher.client(System.nanoTime()+TimeUnit.SECONDS.toNanos(15))
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
    }
    @Test fun `parser preserves original decoded review text and source supplied dates`() {
        val (name,rows)=GooglePlayScraper.parse(html,GooglePlayScraper.EXAMPLE)
        assertEquals("Fixture Music",name); assertEquals(2,rows.size)
        assertEquals("The playlist order changed & I can't undo it.\nPlease restore the setting.",rows[0]["text"])
        assertEquals("2026-10-05",rows[0]["date"]); assertEquals("2",rows[0]["rating"])
        assertFalse(rows.any { it["text"]!!.contains("Thanks for the feedback") })
    }
    @Test fun `identifiable cards with missing text reconcile as rejected`() {
        val incomplete=html.replace("</body>","<div class=\"EGFGHd\"><span class=\"X5PpBb\">Missing text</span></div></body>")
        val data=scraper(body=incomplete).scrape(GooglePlayScraper.EXAMPLE)
        assertEquals(3,data.summary.observed); assertEquals(2,data.summary.accepted); assertEquals(1,data.summary.rejected)
    }
    @Test fun `real ingestion fields distinguish captured page from full review corpus`() {
        val started=Instant.now(); val data=scraper().scrape(GooglePlayScraper.EXAMPLE)
        assertEquals("scraped",data.mode); assertFalse(data.synthetic); assertEquals("Google Play",data.platform)
        assertNotNull(data.captured_at); assertFalse(Instant.parse(data.captured_at).isBefore(started))
        assertEquals(mapOf("attempted" to 1,"fetched" to 1,"parsed" to 1),data.summary.pages)
        assertEquals("visible_page_sample",data.summary.stopping_reason); assertFalse(data.summary.scanned_total_known)
        assertEquals(2,data.summary.observed); assertEquals(2,data.summary.accepted)
        assertTrue(data.summary.warnings.any { it.contains("total unseen review count is unknown") })
        assertTrue(data.reviews.all { it.source_url==data.source_url })
    }
    @Test fun `redirect block notfound and changed parsers fail with recovery guidance`() {
        listOf(302 to "scrape_redirect",403 to "scrape_blocked",429 to "scrape_blocked",404 to "scrape_not_found",500 to "scrape_source_error").forEach { (status,code) ->
            val error=assertThrows(ApiError::class.java) { scraper(status).scrape(GooglePlayScraper.EXAMPLE) }; assertEquals(code,error.code); assertTrue(error.message.contains("CSV"))
        }
        assertEquals("scrape_parse_failure",assertThrows(ApiError::class.java) { scraper(body="<p>unexpected page</p>").scrape(GooglePlayScraper.EXAMPLE) }.code)
        assertEquals("scrape_no_reviews",assertThrows(ApiError::class.java) { scraper(body="<h1>App</h1><p>No visible reviews</p>").scrape(GooglePlayScraper.EXAMPLE) }.code)
        assertEquals("scrape_parse_failure",assertThrows(ApiError::class.java) { scraper(type="application/json").scrape(GooglePlayScraper.EXAMPLE) }.code)
    }
    @Test fun `decompressed byte cap rejects whole truncated response`() {
        assertArrayEquals("small".toByteArray(),PublicPageFetcher.readBounded(ByteArrayInputStream("small".toByteArray()),5))
        val error=assertThrows(ApiError::class.java) { PublicPageFetcher.readBounded(ByteArrayInputStream(ByteArray(1025)),1024) }
        assertEquals("scrape_body_limit",error.code); assertTrue(error.message.contains("No partial page"))
    }
    @Test fun `deadline bounds entire fetch and parser job`() {
        val slow=GooglePlayScraper(PageFetcher { _,_,_,_ -> Thread.sleep(5000); FetchedPage(200,html) },100)
        val start=System.nanoTime(); val error=assertThrows(ApiError::class.java) { slow.scrape(GooglePlayScraper.EXAMPLE) }
        assertEquals("scrape_timeout",error.code); assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000)
    }
    @Test fun `cancelled imports cannot return a dataset`() {
        val error=assertThrows(ApiError::class.java) { scraper().scrape(GooglePlayScraper.EXAMPLE,AtomicBoolean(true)) }
        assertEquals("cancelled",error.code)
    }
    @Test fun `disconnect interrupts an in flight fetch without waiting for deadline`() {
        val cancelled=AtomicBoolean(false)
        val scheduler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val slow=GooglePlayScraper(PageFetcher { _,_,_,_ -> Thread.sleep(5000); FetchedPage(200,html) })
        try {
            scheduler.schedule({ cancelled.set(true) },30,TimeUnit.MILLISECONDS)
            val started=System.nanoTime()
            assertEquals("cancelled",assertThrows(ApiError::class.java) { slow.scrape(GooglePlayScraper.EXAMPLE,cancelled) }.code)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
        } finally { scheduler.shutdownNow() }
    }
    @Test @EnabledIfEnvironmentVariable(named="RUN_LIVE_SCRAPE_TESTS",matches="true")
    fun `Spotify and WhatsApp pages produce real normalized samples`() {
        for(id in listOf("com.spotify.music","com.whatsapp")) {
            val start=System.nanoTime(); val data=GooglePlayScraper().scrape("https://play.google.com/store/apps/details?id=$id")
            assertEquals("scraped",data.mode); assertFalse(data.synthetic); assertTrue(data.reviews.isNotEmpty())
            assertNotNull(data.captured_at); assertEquals(1,data.summary.pages["fetched"]); assertEquals(1,data.summary.pages["parsed"])
            assertTrue(data.entity_name.lowercase().contains(if(id=="com.spotify.music") "spotify" else "whatsapp"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<15000)
            println("Live Google Play parser verified: $id, ${data.reviews.size} reviews, captured ${data.captured_at}")
        }
    }
}

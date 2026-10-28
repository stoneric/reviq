package reviq

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GooglePlayPaginationTest {
    private val url="https://play.google.com/store/apps/details?id=com.fixture.app"
    private val metadata="""<html><link rel="canonical" href="$url"><script type="application/ld+json">{"@type":"SoftwareApplication","name":"Fixture App","url":"https://play.google.com/store/apps/details/Fixture_App?id=com.fixture.app"}</script><h1>Fixture App</h1><p>No teaser review cards.</p></html>"""
    private fun rows(count: Int)= (1..count).map { mapOf("text" to "Complete synthetic review number $it.","rating" to "4","date" to "2026-10-20","source_url" to GooglePlayScraper.canonicalUrl(url)) }
    private fun scraper(source: GooglePlayReviewSource)=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,metadata) },googlePlaySource=source)

    @Test fun `metadata without teaser reviews can import two hundred paginated records`() {
        val started=Instant.now()
        val data=scraper(GooglePlayReviewSource { target,_,_,_ ->
            assertEquals(GooglePlayScraper.canonicalUrl(url),target)
            GooglePlayCollection(rows(200),2,"google_play_target_reached")
        }).scrape(url)
        assertEquals(200,data.reviews.size); assertEquals(200,data.summary.observed)
        assertEquals("R200",data.reviews.last().id)
        assertEquals("Complete synthetic review number 200.",data.reviews.last().text)
        assertEquals("Fixture App",data.entity_name); assertEquals("Google Play",data.platform)
        assertEquals("direct",data.retrieval_provider); assertEquals("live",data.retrieval_freshness)
        assertFalse(Instant.parse(data.captured_at).isBefore(started))
        assertEquals(3,data.summary.pages["attempted"]); assertEquals(3,data.summary.pages["fetched"]); assertEquals(3,data.summary.pages["parsed"])
        assertEquals(2,data.summary.pages["review_batches"]); assertEquals(200,data.summary.pages["requested_reviews"]); assertEquals(200,data.summary.pages["batch_size"])
        assertEquals("newest_first",data.summary.pages["review_order"])
        assertEquals("google_play_target_reached",data.summary.stopping_reason)
        assertTrue(data.summary.warnings.any { it.contains("newest first") && it.contains("English/US") })
        assertFalse(data.summary.warnings.any { it.contains("No review pagination") })
        assertFalse(data.summary.scanned_total_known)
        assertEquals(0,data.summary.excluded)
        val input=Analyst().input(data,emptyList(),"What does review R200 say?")
        assertTrue(input.contains("Complete synthetic review number 200."))
        assertTrue(Tokens.count(input)+Tokens.count(SYSTEM_PROMPT)+128<=30000)
    }

    @Test fun `exhaustion below requested count is explicit and source overlap is disclosed`() {
        val data=scraper(GooglePlayReviewSource { _,_,_,_ -> GooglePlayCollection(rows(83),2,"google_play_source_exhausted",2) }).scrape(url)
        assertEquals(83,data.reviews.size); assertEquals(83,data.summary.observed)
        assertEquals("google_play_source_exhausted",data.summary.stopping_reason)
        assertTrue(data.summary.warnings.any { it.contains("83 review records") && it.contains("no further page") })
        assertTrue(data.summary.warnings.any { it.contains("2 repeated source review IDs") })
        assertFalse(data.summary.scanned_total_known)
    }

    @Test fun `paging failures cannot fall back to preview cards or Exa`() {
        val failingScraper=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,metadata) },googlePlaySource=GooglePlayReviewSource { _,_,_,_ -> throw ApiError(422,"google_play_pagination_stalled","No progress") },
            exaFetcher=ExaPageFetcher { _,_,_,_ -> error("Paging failure must not become another sample") },reserveExa={ error("No paid request expected") })
        assertEquals("google_play_pagination_stalled",assertThrows(ApiError::class.java) { failingScraper.scrape(url) }.code)
        assertEquals("google_play_no_reviews",assertThrows(ApiError::class.java) { scraper(GooglePlayReviewSource { _,_,_,_ -> GooglePlayCollection(emptyList(),1,"google_play_source_exhausted") }).scrape(url) }.code)
    }

    @Test fun `pagination uses original absolute deadline and cancellation`() {
        val source=GooglePlayReviewSource { _,end,_,_ ->
            assertTrue(end>System.nanoTime()); Thread.sleep(5000)
            GooglePlayCollection(rows(200),2,"google_play_target_reached")
        }
        val slow=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,metadata) },timeoutMillis=100,googlePlaySource=source)
        val started=System.nanoTime()
        assertEquals("scrape_timeout",assertThrows(ApiError::class.java) { slow.scrape(url) }.code)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
        assertEquals("cancelled",assertThrows(ApiError::class.java) { scraper(source).scrape(url,AtomicBoolean(true)) }.code)
    }

    @Test fun `normalization exclusions remain honest after collection reaches the target`() {
        val records=rows(201).toMutableList()
        records[0]=records[0]+("text" to "oversized synthetic evidence ".repeat(10000))
        records[1]=records[2]
        val data=scraper(GooglePlayReviewSource { _,_,_,_ -> GooglePlayCollection(records,2,"google_play_target_reached") }).scrape(url)
        assertEquals(201,data.summary.observed); assertEquals(199,data.reviews.size)
        assertEquals(1,data.summary.excluded); assertEquals(1,data.summary.duplicates)
        assertEquals("token_limit",data.summary.stopping_reason)
        assertEquals(data.summary.observed,data.summary.accepted+data.summary.rejected+data.summary.duplicates+data.summary.excluded)
        assertTrue(data.summary.warnings.any { it.contains("201 review records") && it.contains("200 distinct written reviews") })
    }

    @Test fun `app metadata must bind the requested package and cannot be a challenge heading`() {
        val invalid=listOf(
            "<h1>Verify you are human</h1>",
            metadata.replace("com.fixture.app", "com.wrong.app"),
            metadata.replace("details/Fixture_App?id=com.fixture.app", "details/Fixture_App?id=com.wrong.app"),
            metadata.replace("details/Fixture_App?id=com.fixture.app", "details/Fixture_App?id=com.fixture.app&id=com.wrong.app"),
            metadata.replace("SoftwareApplication", "Organization")
        )
        for(html in invalid) {
            val source=GooglePlayReviewSource { _,_,_,_ -> error("Unverified identity must not trigger review requests") }
            val importer=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,html) },googlePlaySource=source,
                exaFetcher=ExaPageFetcher { _,_,_,_ -> error("No provider fallback") })
            assertEquals("google_play_identity",assertThrows(ApiError::class.java) { importer.scrape(url) }.code)
        }
    }

    @Test fun `blocked or oversized app metadata cannot silently downgrade to three provider reviews`() {
        for(fetcher in listOf(PageFetcher { _,_,_,_ -> FetchedPage(403,"") },PageFetcher { _,_,_,_ -> throw ApiError(413,"scrape_body_limit","Too large") })) {
            val importer=ReviewScraper(fetcher,googlePlaySource=GooglePlayReviewSource { _,_,_,_ -> error("No paging after failed metadata") },
                exaFetcher=ExaPageFetcher { _,_,_,_ -> error("No provider downgrade") },reserveExa={ error("No paid call") })
            assertTrue(assertThrows(ApiError::class.java) { importer.scrape(url) }.code in setOf("scrape_blocked","scrape_body_limit"))
        }
    }

    @Test fun `an app redirect cannot change the package being collected`() {
        var calls=0
        val importer=ReviewScraper(PageFetcher { _,_,_,_ ->
            calls++
            if(calls==1) FetchedPage(302,"",location="https://play.google.com/store/apps/details?id=com.other.app") else FetchedPage(200,metadata)
        },googlePlaySource=GooglePlayReviewSource { _,_,_,_ -> error("No paging for another app") })
        assertEquals("google_play_source_mismatch",assertThrows(ApiError::class.java) { importer.scrape(url) }.code)
    }

    @Test fun `explicit Google Play cache selection still bypasses live pagination`() {
        val html=javaClass.getResource("/google-play-page.html")!!.readText()
        val importer=ReviewScraper(PageFetcher { _,_,_,_ -> error("No direct request in cache mode") },
            googlePlaySource=GooglePlayReviewSource { _,_,_,_ -> error("No paging in cache mode") },
            exaCachedFetcher=ExaPageFetcher { target,_,_,_ -> ExaPage(html,target,null,"cached",0.001) },reserveExa={ "reservation" })
        val data=importer.scrape(url,cacheOnly=true)
        assertEquals("cached",data.retrieval_freshness);assertNull(data.captured_at)
        assertEquals("exa",data.retrieval_provider);assertNull(data.summary.pages["review_batches"])
    }

    @Test @EnabledIfEnvironmentVariable(named="RUN_LIVE_SCRAPE_TESTS",matches="true")
    fun `real paginated OnePageCRM and Spotify imports exceed the preview`() {
        for(id in listOf("com.onepagecrm","com.spotify.music")) {
            val data=ReviewScraper(googlePlaySource=GooglePlayReviews).scrape("https://play.google.com/store/apps/details?id=$id")
            assertTrue(data.reviews.size in 4..200)
            assertEquals(200,data.summary.pages["requested_reviews"]); assertEquals(200,data.summary.pages["batch_size"])
            assertEquals("newest_first",data.summary.pages["review_order"])
            assertTrue((data.summary.pages["review_batches"] as Int)>=1)
            assertTrue(data.summary.stopping_reason in setOf("google_play_source_exhausted","google_play_target_reached","token_limit"))
            assertEquals(data.summary.observed,data.summary.accepted+data.summary.rejected+data.summary.duplicates+data.summary.excluded)
            if(id=="com.spotify.music") { assertTrue(data.summary.observed>=200); assertTrue(data.reviews.size>100) }
            println("Live paginated Google Play verified: $id, observed=${data.summary.observed}, accepted=${data.reviews.size}, batches=${data.summary.pages["review_batches"]}, stop=${data.summary.stopping_reason}")
        }
    }
}

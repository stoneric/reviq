package reviq

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ExaFallbackTest {
    private val url="https://shop.example.com/product"
    private val html="""<h1>Fixture Product</h1><script type="application/ld+json">{"@type":"Product","name":"Fixture Product","review":{"@type":"Review","reviewBody":"Original customer wording.","reviewRating":{"ratingValue":4}}}</script>"""
    private fun blocked()=PageFetcher { _,_,_,_ -> FetchedPage(403,"") }

    @Test fun `blocked page uses fresh provider evidence with budget and provenance`() {
        val events=mutableListOf<String>(); var directDeadline=0L
        val direct=PageFetcher { _,deadline,_,_ -> directDeadline=deadline; FetchedPage(403,"") }
        val fallback=ExaPageFetcher { target,deadline,_,_ ->
            events+="fetch"; assertEquals(url,target); assertEquals(directDeadline,deadline)
            ExaPage(html,url,"provider-fixture","crawled",0.001)
        }
        val data=ReviewScraper(direct,exaFetcher=fallback,reserveExa={ events+="reserve"; "reservation" },reconcileExa={ id,cost ->
            assertEquals("reservation",id); assertEquals(0.001,cost); events+="reconcile"
        }).scrape(url)
        assertEquals(listOf("reserve","fetch","reconcile"),events)
        assertEquals("Original customer wording.",data.reviews.single().text)
        assertEquals("exa",data.retrieval_provider); assertEquals("shop.example.com",data.platform)
        assertEquals(url,data.source_url); assertFalse(data.summary.scanned_total_known)
        assertTrue(data.summary.warnings.any { it.contains("Exa") && it.contains("fresh") })
    }

    @Test fun `successful direct imports do not consume provider budget`() {
        val data=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,html) },exaFetcher=ExaPageFetcher { _,_,_,_ -> error("Provider must not be called") },reserveExa={ error("Budget must not be reserved") }).scrape(url)
        assertEquals("direct",data.retrieval_provider)
    }

    @Test fun `provider budget rejection prevents outgoing provider request`() {
        val calls=AtomicInteger()
        val scraper=ReviewScraper(blocked(),exaFetcher=ExaPageFetcher { _,_,_,_ -> calls.incrementAndGet(); ExaPage(html,url,null,"crawled",0.001) },reserveExa={ throw ApiError(429,"exa_daily_budget","Allowance exhausted") })
        assertEquals("exa_daily_budget",assertThrows(ApiError::class.java) { scraper.scrape(url) }.code)
        assertEquals(0,calls.get())
    }

    @Test fun `unsafe targets and ambiguous sources never become paid retrievals`() {
        val fallback=ExaPageFetcher { _,_,_,_ -> error("Provider must not be called") }
        val scraper=ReviewScraper(blocked(),exaFetcher=fallback,reserveExa={ error("Budget must not be reserved") })
        assertEquals("unsupported_url",assertThrows(ApiError::class.java) { scraper.scrape("http://127.0.0.1/") }.code)
        val ambiguous="""<h1>Two products</h1><script type="application/ld+json">[{"@type":"Product","name":"One","review":{"reviewBody":"First"}},{"@type":"Product","name":"Two","review":{"reviewBody":"Second"}}]</script>"""
        val mixed=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,ambiguous) },exaFetcher=fallback,reserveExa={ error("Budget must not be reserved") })
        assertEquals("scrape_ambiguous_reviews",assertThrows(ApiError::class.java) { mixed.scrape(url) }.code)
    }

    @Test fun `provider page prose cannot substitute for identifiable reviews`() {
        var reconciled=false
        val scraper=ReviewScraper(blocked(),exaFetcher=ExaPageFetcher { _,_,_,_ -> ExaPage("<h1>Product</h1><p>Customers love our products.</p>",url,null,"crawled",0.001) },reserveExa={ "reservation" },reconcileExa={ _,_ -> reconciled=true })
        assertEquals("scrape_no_reviews",assertThrows(ApiError::class.java) { scraper.scrape(url) }.code)
        assertTrue(reconciled,"Known incurred provider cost must be accounted even when no reviews can be parsed")
    }

    @Test fun `source identity mismatch does not trigger a paid fallback`() {
        val html="""<h1>Wrong business</h1><script id="__NEXT_DATA__">{"props":{"pageProps":{"businessUnit":{"identifyingName":"different.example"},"reviews":[]}}}</script>"""
        val scraper=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(200,html) },exaFetcher=ExaPageFetcher { _,_,_,_ -> error("Provider must not be called") },reserveExa={ error("Budget must not be reserved") })
        assertEquals("scrape_source_mismatch",assertThrows(ApiError::class.java) { scraper.scrape("https://www.trustpilot.com/review/expected.example") }.code)
    }

    @Test fun `fallback does not restart an exhausted deadline`() {
        val scraper=ReviewScraper(blocked(),timeoutMillis=100,exaFetcher=ExaPageFetcher { _,_,_,_ -> error("Provider must not be called") },reserveExa={ error("Budget must not be reserved") })
        assertEquals("scrape_timeout",assertThrows(ApiError::class.java) { scraper.scrape(url) }.code)
    }
}

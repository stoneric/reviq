package reviq

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ExaContentsTest {
    private val url="https://www.trustpilot.com/review/fixture.example"
    private fun response(status: String="success",source: String="crawled",resultUrl: String=url,statusId: String=url,text: String="<article><p>Original fixture review.</p></article>",cost: Any?=0.001): String = mapper.writeValueAsString(mapOf(
        "requestId" to "fixture-request-id",
        "statuses" to listOf(mapOf("id" to statusId,"status" to status,"source" to source)),
        "results" to listOf(mapOf("url" to resultUrl,"text" to text,"publishedDate" to "2001-01-01")),
        "costDollars" to mapOf("total" to cost)
    ))
    @Test fun freshTextOnlyContractRetainsTimeForLocalParsing() {
        val body=mapper.readTree(ExaContentsClient.requestBody(url+"#reviews",15000))
        assertEquals(url,body.path("urls")[0].asText())
        assertTrue(body.path("text").path("includeHtmlTags").asBoolean())
        assertEquals("full",body.path("text").path("verbosity").asText())
        assertEquals(200000,body.path("text").path("maxCharacters").asInt())
        assertEquals(0,body.path("maxAgeHours").asInt()); assertEquals(0,body.path("subpages").asInt())
        assertEquals(12000,body.path("livecrawlTimeout").asInt())
        assertFalse(body.has("summary")); assertFalse(body.has("highlights")); assertFalse(body.has("livecrawl"))
        val shorter=mapper.readTree(ExaContentsClient.requestBody(url,6000))
        assertEquals(4500,shorter.path("livecrawlTimeout").asInt())
        assertEquals("exa_timeout",assertThrows(ApiError::class.java) { ExaContentsClient.requestBody(url,1999) }.code)
    }
    @Test fun confirmedFreshSingleUrlContentPreservesExactProviderText() {
        val html="<p>Original  fixture\nreview &amp; quoted text.</p>"
        val page=ExaContentsClient.parseResponse(response(text=html),url)
        assertEquals(html,page.html); assertEquals(url,page.url); assertEquals("crawled",page.freshness)
        assertEquals("fixture-request-id",page.requestId); assertEquals(0.001,page.costUsd)
        assertNull(ExaContentsClient.parseResponse(response(cost=null),url).costUsd)
    }
    @Test fun perUrlFailureCachedMissingOrMismatchedSourcesNeverBecomeSuccess() {
        assertEquals("exa_source_unavailable",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(status="error"),url) }.code)
        assertEquals("exa_not_fresh",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(source="cached"),url) }.code)
        assertEquals("exa_not_fresh",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(source=""),url) }.code)
        listOf("https://another.example.com/reviews","http://127.0.0.1/","https://user:secret@www.trustpilot.com/review/fixture.example").forEach { wrong ->
            assertEquals("exa_source_mismatch",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(resultUrl=wrong),url) }.code)
            assertEquals("exa_source_mismatch",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(statusId=wrong),url) }.code)
        }
        assertEquals("exa_invalid_response",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse("{}",url) }.code)
    }
    @Test fun emptyTruncatedOversizeMalformedOrInvalidCostResponsesFailClosed() {
        assertEquals("exa_empty_content",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(text=" "),url) }.code)
        assertEquals("exa_text_limit",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(text="x".repeat(200000)),url) }.code)
        assertEquals("exa_body_limit",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse("x".repeat(2*1024*1024+1),url) }.code)
        assertEquals("exa_invalid_response",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse("not JSON",url) }.code)
        listOf(-0.001,"not a number").forEach { bad -> assertEquals("exa_invalid_response",assertThrows(ApiError::class.java) { ExaContentsClient.parseResponse(response(cost=bad),url) }.code) }
    }
    @Test fun missingCredentialsCancellationAndUnsafeTargetsDoNotMakeNetworkRequests() {
        assertFalse(ExaContentsClient("").enabled)
        assertEquals("exa_not_configured",assertThrows(ApiError::class.java) { ExaContentsClient("").fetch(url,System.nanoTime()+TimeUnit.SECONDS.toNanos(15),AtomicBoolean(false),AtomicReference()) }.code)
        assertEquals("cancelled",assertThrows(ApiError::class.java) { ExaContentsClient("synthetic-test-key").fetch(url,System.nanoTime()+TimeUnit.SECONDS.toNanos(15),AtomicBoolean(true),AtomicReference()) }.code)
        assertEquals("unsupported_url",assertThrows(ApiError::class.java) { ExaContentsClient("synthetic-test-key").fetch("http://127.0.0.1/",System.nanoTime()+TimeUnit.SECONDS.toNanos(15),AtomicBoolean(false),AtomicReference()) }.code)
    }
}

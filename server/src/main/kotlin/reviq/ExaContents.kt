package reviq

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ExaPage(val html: String, val url: String, val requestId: String?, val freshness: String, val costUsd: Double?)

/** Exa returns provider-extracted HTML, not a guaranteed copy of the source DOM. */
internal class ExaContentsClient(apiKey: String = System.getenv("EXA_API_KEY").orEmpty()) {
    private val key=apiKey.trim()
    val enabled: Boolean = key.isNotEmpty()
    fun fetch(url: String, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>, cacheOnly: Boolean = false): ExaPage {
        if(!enabled) throw ApiError(503,"exa_not_configured","Enhanced page retrieval is not configured. Use CSV upload or paste.")
        if(key.any { it.isISOControl() || it.isWhitespace() }) throw ApiError(503,"exa_credentials_invalid","The retrieval provider configuration is invalid. Use CSV upload or paste.")
        checkActive(deadline,cancelled)
        val canonical=ReviewScraper.canonicalUrl(url)
        val target=canonical.toHttpUrlOrNull() ?: throw ApiError(400,"unsupported_url","Use a public HTTP or HTTPS review page.")
        try {
            // Public-destination preflight only: Exa owns its subsequent DNS and redirect
            // handling. Our checked address objects do not pin Exa's remote crawler sockets.
            PublicPageFetcher.checkedDns(target.host,deadline).lookup(target.host)
        } catch(e: Exception) {
            checkActive(deadline,cancelled)
            throw ApiError(422,"exa_source_unavailable","The source could not be verified as a public destination. Use CSV upload or paste.")
        }
        checkActive(deadline,cancelled)
        val body=requestBody(canonical,TimeUnit.NANOSECONDS.toMillis(PublicPageFetcher.remaining(deadline)),cacheOnly)
        // Fixed provider endpoint, checked connector DNS, no proxy, redirects, or retries.
        val client=PublicPageFetcher.client(deadline,"api.exa.ai")
        try {
            val request=Request.Builder().url(ENDPOINT).header("x-api-key",key)
                .header("Accept","application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val call=client.newCall(request); activeCall.set(call)
            checkActive(deadline,cancelled)
            call.execute().use { response ->
                if(response.code!=200) throw httpFailure(response.code)
                val responseBody=response.body ?: throw invalidResponse()
                val contentType=responseBody.contentType()?.let { "${it.type}/${it.subtype}" }
                if(contentType!="application/json") throw invalidResponse()
                val bytes=try {
                    PublicPageFetcher.readBounded(responseBody.byteStream(),MAX_RESPONSE_BYTES) { checkActive(deadline,cancelled) }
                } catch(e: ApiError) {
                    if(e.code=="scrape_body_limit") throw ApiError(413,"exa_body_limit","The provider returned too much content. No partial extraction was analyzed; use CSV upload or paste.")
                    throw e
                }
                checkActive(deadline,cancelled)
                val page=parseResponse(bytes.toString(Charsets.UTF_8),canonical,cacheOnly)
                checkActive(deadline,cancelled)
                return page
            }
        } catch(e: ApiError) { throw e }
        catch(_: Exception) {
            checkActive(deadline,cancelled)
            throw ApiError(502,"exa_fetch_failed","The retrieval provider could not complete this request. Use CSV upload or paste.")
        } finally { activeCall.getAndSet(null)?.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun checkActive(deadline: Long, cancelled: AtomicBoolean) {
        if(cancelled.get() || Thread.currentThread().isInterrupted) throw ApiError(499,"cancelled","Import stopped.")
        if(System.nanoTime()>=deadline) throw ApiError(504,"exa_timeout","The live retrieval deadline was reached. Use CSV upload or paste.")
    }
    companion object {
        const val ENDPOINT="https://api.exa.ai/contents"
        const val MAX_RESPONSE_BYTES=2*1024*1024
        const val MAX_TEXT_CHARACTERS=200000
        private const val PARSE_MARGIN_MS=1500L
        internal fun requestBody(url: String, remainingMillis: Long, cacheOnly: Boolean = false): String {
            val canonical=ReviewScraper.canonicalUrl(url)
            val crawlMillis=minOf(12000L,remainingMillis-PARSE_MARGIN_MS)
            if(crawlMillis<500) throw ApiError(504,"exa_timeout","Too little time remains for page retrieval. Use CSV upload or paste.")
            return mapper.writeValueAsString(mapOf(
                "urls" to listOf(canonical),
                "text" to mapOf("includeHtmlTags" to true,"verbosity" to "full","maxCharacters" to MAX_TEXT_CHARACTERS),
                "maxAgeHours" to if(cacheOnly) -1 else 0,"livecrawlTimeout" to crawlMillis,"subpages" to 0
            ))
        }
        internal fun parseResponse(json: String, requestedUrl: String, cacheOnly: Boolean = false): ExaPage {
            if(json.toByteArray(Charsets.UTF_8).size>MAX_RESPONSE_BYTES) throw ApiError(413,"exa_body_limit","The provider response exceeded the size limit. No partial extraction was analyzed.")
            val root=try { mapper.readTree(json) } catch(_: Exception) { throw invalidResponse() }
            if(root==null || !root.isObject) throw invalidResponse()
            val requested=ReviewScraper.canonicalUrl(requestedUrl)
            fun canonical(node: JsonNode): String? = if(node.isTextual) try { ReviewScraper.canonicalUrl(node.asText()) } catch(_: Exception) { null } else null
            val statuses=root.path("statuses")
            if(!statuses.isArray || statuses.size()!=1) throw invalidResponse()
            val status=statuses[0]
            if(canonical(status.path("id"))!=requested) throw ApiError(502,"exa_source_mismatch","The provider response could not be matched to the requested source. No reviews were imported.")
            if(status.path("status").asText()!="success") {
                if(cacheOnly) throw ApiError(422,"exa_cache_unavailable","The provider has no usable cached snapshot for this page. Try fresh retrieval, upload a CSV file, or paste reviews.")
                val error=status.path("error")
                if(error.path("httpStatusCode").asInt()==403 || error.path("tag").asText()=="CRAWL_HTTP_403") {
                    throw ApiError(422,"exa_fresh_blocked","The provider could not retrieve a fresh copy because the source blocked access. You may explicitly try a cached snapshot, upload a CSV file, or paste reviews.")
                }
                throw ApiError(422,"exa_source_unavailable","The retrieval provider could not retrieve a fresh copy of this page. Use CSV upload or paste.")
            }
            val freshness=status.path("source").asText()
            if(cacheOnly && freshness!="cached") throw ApiError(422,"exa_not_cached","The provider did not confirm a cached snapshot. No reviews were imported; try fresh retrieval, upload a CSV file, or paste reviews.")
            if(!cacheOnly && freshness!="crawled") throw ApiError(422,"exa_not_fresh","The provider did not confirm freshly retrieved content. Cached content was not imported; use CSV upload or paste.")
            val results=root.path("results")
            if(!results.isArray || results.size()!=1) throw invalidResponse()
            val result=results[0]
            val source=canonical(result.path("url"))
            if(source!=requested) throw ApiError(502,"exa_source_mismatch","The provider returned a different or unsafe source URL. No reviews were imported.")
            val textNode=result.path("text")
            if(!textNode.isTextual || textNode.asText().isBlank()) throw ApiError(422,"exa_empty_content","The provider returned no usable page content. Use CSV upload or paste.")
            val text=textNode.asText()
            if(text.length>=MAX_TEXT_CHARACTERS) throw ApiError(413,"exa_text_limit","The provider text reached its extraction limit and may be incomplete. No partial review text was imported; use CSV upload or paste.")
            val costNode=root.path("costDollars").path("total")
            val cost=if(costNode.isMissingNode || costNode.isNull) null else {
                if(!costNode.isNumber) throw invalidResponse()
                costNode.asDouble().also { if(!it.isFinite() || it<0) throw invalidResponse() }
            }
            val requestId=root.path("requestId").takeIf { it.isTextual }?.asText()?.takeIf { Regex("[A-Za-z0-9_-]{1,128}").matches(it) }
            return ExaPage(text,source ?: throw invalidResponse(),requestId,freshness,cost)
        }
        private fun invalidResponse()=ApiError(502,"exa_invalid_response","The provider returned an invalid response. No reviews were imported; use CSV upload or paste.")
        private fun httpFailure(status: Int): ApiError = when(status) {
            401,403 -> ApiError(503,"exa_provider_access","The retrieval provider is not available with the configured credentials. Use CSV upload or paste.")
            402 -> ApiError(429,"exa_credits_exhausted","The retrieval provider allowance has been reached. Use CSV upload or paste.")
            429 -> ApiError(429,"exa_rate_limit","The retrieval provider is busy. Try again later, or use CSV upload or paste.")
            504 -> ApiError(504,"exa_timeout","The retrieval provider timed out. Use CSV upload or paste.")
            else -> ApiError(502,"exa_unavailable","The retrieval provider could not complete this request. Use CSV upload or paste.")
        }
    }
}

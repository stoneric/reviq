package reviq

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class GooglePlayCollection(
    val rows: List<Map<String,String>>,
    val batches: Int,
    val stoppingReason: String,
    val duplicateIds: Int = 0,
    val usableUniqueTexts: Int = rows.map { it["text"].orEmpty() }.filter { it.isNotBlank() }.map(Normalize::canonicalReviewText).distinct().size,
    val unknownTimestamps: Int = 0
)
internal fun interface GooglePlayReviewSource {
    fun collect(canonicalUrl: String, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>): GooglePlayCollection
}
internal data class GooglePlayReviewRecord(val sourceId: String, val row: Map<String,String>, val sourceTimestamp: Instant? = null)
internal data class GooglePlayReviewBatch(val reviews: List<GooglePlayReviewRecord>, val nextToken: String?)
internal fun interface GooglePlayBatchFetcher {
    fun fetch(appId: String, count: Int, token: String?, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>, maxBytes: Int): ByteArray
}

/** The public Play website's review RPC. It is not a supported Google developer API. */
internal object GooglePlayReviews: GooglePlayReviewSource {
    private const val ENDPOINT="https://play.google.com/_/PlayStoreUi/data/batchexecute?hl=en&gl=US"
    private const val RPC="oCPfdb"
    internal const val TARGET=200
    internal const val BATCH_SIZE=200
    private const val MAX_BATCHES=5
    private const val MAX_BYTES=2*1024*1024
    private const val MAX_TOKEN_LENGTH=4096
    private val packagePattern=Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    override fun collect(canonicalUrl: String, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>): GooglePlayCollection =
        collect(canonicalUrl,deadline,cancelled,activeCall,NetworkFetcher)

    internal fun collect(canonicalUrl: String, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>, fetcher: GooglePlayBatchFetcher): GooglePlayCollection {
        val source=GooglePlayScraper.canonicalUrl(canonicalUrl)
        val appId=source.toHttpUrlOrNull()?.queryParameter("id") ?: throw parseFailure()
        val records=linkedMapOf<String,GooglePlayReviewRecord>()
        val sourceOrder=mutableMapOf<String,Int>()
        val usableTexts=mutableSetOf<String>()
        val seenTokens=mutableSetOf<String>()
        var token: String?=null
        var bytes=0
        var duplicates=0
        var batches=0
        var encountered=0
        fun result(reason: String): GooglePlayCollection {
            // Keep the feed order of the chosen source version for equal timestamps.
            // Null source timestamps sort last and are explicitly reported as unknown.
            val ordered=records.values.sortedWith(compareByDescending<GooglePlayReviewRecord> { it.sourceTimestamp }.thenBy { sourceOrder.getValue(it.sourceId) })
            return GooglePlayCollection(ordered.map { it.row },batches,reason,duplicates,usableTexts.size,ordered.count { it.sourceTimestamp==null })
        }
        while(batches<MAX_BATCHES) {
            checkActive(deadline,cancelled)
            val remainingBytes=MAX_BYTES-bytes
            if(remainingBytes<=0) throw bodyLimit()
            val count=minOf(BATCH_SIZE,TARGET-usableTexts.size)
            val raw=fetcher.fetch(appId,count,token,deadline,cancelled,activeCall,remainingBytes)
            checkActive(deadline,cancelled)
            if(raw.size>remainingBytes) throw bodyLimit()
            bytes+=raw.size
            val batch=parseResponse(raw,source)
            batches++
            if(batch.reviews.size>count) throw parseFailure()
            if(batch.nextToken!=null && !seenTokens.add(batch.nextToken)) throw stalled()
            var progressed=false
            batch.reviews.forEach { record ->
                val order=encountered++
                val existing=records[record.sourceId]
                if(existing==null) { records[record.sourceId]=record; sourceOrder[record.sourceId]=order; progressed=true }
                else {
                    duplicates++
                    if(record.sourceTimestamp!=null && (existing.sourceTimestamp==null || record.sourceTimestamp>existing.sourceTimestamp)) {
                        records[record.sourceId]=record; sourceOrder[record.sourceId]=order; progressed=true
                    }
                }
            }
            // A newer version of a source ID can change its wording, including becoming
            // blank or matching another record. Recount rather than assuming only growth.
            usableTexts.clear()
            records.values.forEach { record ->
                val text=record.row["text"].orEmpty()
                if(text.isNotBlank()) usableTexts.add(Normalize.canonicalReviewText(text))
            }
            checkActive(deadline,cancelled)
            if(!progressed && batch.nextToken!=null) throw stalled()
            if(usableTexts.size>=TARGET) return result("google_play_target_reached")
            if(batch.nextToken==null) return result("google_play_source_exhausted")
            token=batch.nextToken
        }
        return result("google_play_page_limit")
    }

    internal fun requestBody(appId: String, count: Int, token: String? = null): String {
        if(appId.length>255 || !packagePattern.matches(appId) || count !in 1..BATCH_SIZE || (token!=null && !validToken(token))) throw parseFailure()
        val paging=if(token==null) listOf(count) else listOf(count,null,token)
        // Sort 2 is newest. No score/device filters; locale is fixed to English/US.
        val inner=listOf(null,listOf(2,2,paging,null,List<Any?>(9) { null }),listOf(appId,7))
        val rpc=listOf(listOf(listOf(RPC,mapper.writeValueAsString(inner),null,"generic")))
        return "f.req="+URLEncoder.encode(mapper.writeValueAsString(rpc),Charsets.UTF_8)
    }

    internal fun parseResponse(raw: ByteArray, canonicalUrl: String): GooglePlayReviewBatch {
        if(raw.size>MAX_BYTES) throw bodyLimit()
        val source=GooglePlayScraper.canonicalUrl(canonicalUrl)
        val frames=frames(raw)
        val calls=frames.flatMap { frame ->
            if(!frame.isArray) throw parseFailure()
            frame.toList().also { entries -> if(entries.any { !it.isArray }) throw parseFailure() }
        }.filter { it.path(0).asText()=="wrb.fr" }
        if(calls.size!=1 || calls[0].path(1).asText()!=RPC || !calls[0].path(2).isTextual) throw parseFailure()
        val payload=readJson(calls[0].path(2).asText().toByteArray(Charsets.UTF_8))
        if(!payload.isArray || payload.size()!=3 || !payload[0].isArray || !payload[2].isArray) throw parseFailure()
        val cursor=payload[1]
        val token=when {
            cursor.isNull -> null
            cursor.isArray && cursor.size()>0 -> cursor.last().let { last ->
                when {
                    last.isNull -> null
                    last.isTextual && validToken(last.asText()) -> last.asText()
                    else -> throw parseFailure()
                }
            }
            else -> throw parseFailure()
        }
        val records=payload[0].map { review ->
            if(!review.isArray || review.size()<6) throw parseFailure()
            val id=review.path(0).takeIf { it.isTextual }?.asText()
                ?.takeIf { it.isNotBlank() && it.length<=512 && it.none(Char::isISOControl) } ?: throw parseFailure()
            val textNode=review.path(4)
            if(!textNode.isTextual && !textNode.isNull) throw parseFailure()
            val row=linkedMapOf("text" to if(textNode.isTextual) textNode.asText() else "", "source_url" to source)
            review.path(1).path(0).takeIf { it.isTextual }?.asText()?.let { row["author"]=it }
            review.path(2).takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() in 1..5 }?.let { row["rating"]=it.asInt().toString() }
            val timestamp=sourceTimestamp(review.path(5))
            timestamp?.let { row["date"]=it.atZone(ZoneOffset.UTC).toLocalDate().toString() }
            // Index 7 is the developer response. It never becomes review evidence.
            GooglePlayReviewRecord(id,row,timestamp)
        }
        return GooglePlayReviewBatch(records,token)
    }

    private fun sourceTimestamp(node: JsonNode): Instant? {
        if(!node.isArray || !node.path(0).isIntegralNumber || !node.path(0).canConvertToLong()) return null
        val nanosNode=node.path(1)
        val nanos=when {
            nanosNode.isMissingNode || nanosNode.isNull -> 0L
            nanosNode.isIntegralNumber && nanosNode.canConvertToLong() && nanosNode.asLong() in 0..999999999L -> nanosNode.asLong()
            else -> return null
        }
        return try {
            Instant.ofEpochSecond(node[0].asLong(),nanos).takeIf { it.atZone(ZoneOffset.UTC).year in 1..9999 }
        } catch(_: Exception) { null }
    }

    /** Accept the observed bare JSON response and Google's byte-length framed variant. */
    private fun frames(raw: ByteArray): List<JsonNode> {
        val guard=")]}'".toByteArray(Charsets.US_ASCII)
        if(raw.size<guard.size || !raw.copyOfRange(0,guard.size).contentEquals(guard)) throw parseFailure()
        var index=guard.size
        val frames=mutableListOf<JsonNode>()
        fun whitespace(value: Byte)=value.toInt() in setOf(9,10,13,32)
        while(index<raw.size) {
            while(index<raw.size && whitespace(raw[index])) index++
            if(index==raw.size) break
            if(raw[index].toInt() in 48..57) {
                val start=index
                while(index<raw.size && raw[index].toInt() in 48..57) index++
                val length=raw.copyOfRange(start,index).toString(Charsets.US_ASCII).toIntOrNull() ?: throw parseFailure()
                if(index>=raw.size || raw[index].toInt()!=10 || length<=0 || length>raw.size-index-1) throw parseFailure()
                index++
                frames.add(readJson(raw.copyOfRange(index,index+length)))
                index+=length
            } else {
                frames.add(readJson(raw.copyOfRange(index,raw.size)))
                index=raw.size
            }
        }
        if(frames.isEmpty()) throw parseFailure()
        return frames
    }
    private fun readJson(bytes: ByteArray): JsonNode = try {
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text) ?: throw parseFailure()
    } catch(e: ApiError) { throw e } catch(_: Exception) { throw parseFailure() }
    private fun validToken(token: String)=token.isNotBlank() && token.length<=MAX_TOKEN_LENGTH && token.none { it.isISOControl() }
    private fun checkActive(deadline: Long, cancelled: AtomicBoolean) {
        if(cancelled.get() || Thread.currentThread().isInterrupted) throw ApiError(499,"cancelled","Import stopped.")
        if(System.nanoTime()>=deadline) throw timeout()
    }
    private fun parseFailure()=ApiError(422,"google_play_parse_failure","Google Play returned an unrecognized review response. No partial review sample was imported; try again later or upload a CSV file.")
    private fun stalled()=ApiError(422,"google_play_pagination_stalled","Google Play review pagination did not advance. No partial review sample was imported; try again later or upload a CSV file.")
    private fun bodyLimit()=ApiError(413,"google_play_body_limit","Google Play review responses exceeded the 2 MiB limit. No partial review sample was imported; upload a smaller review export.")
    private fun timeout()=ApiError(504,"google_play_timeout","Google Play review retrieval exceeded the import deadline. No partial review sample was imported; try again later.")

    private object NetworkFetcher: GooglePlayBatchFetcher {
        override fun fetch(appId: String, count: Int, token: String?, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>, maxBytes: Int): ByteArray {
            checkActive(deadline,cancelled)
            if(maxBytes<=0) throw bodyLimit()
            val client=try { PublicPageFetcher.client(deadline,"play.google.com") } catch(_: ApiError) { throw timeout() }
            try {
                val request=Request.Builder().url(ENDPOINT).header("User-Agent","Reviq/1.0 (+https://reviq.xyz)")
                    .header("Accept","application/json").post(requestBody(appId,count,token).toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())).build()
                val call=client.newCall(request); activeCall.set(call)
                checkActive(deadline,cancelled)
                call.execute().use { response ->
                    if(response.code in setOf(401,403,429)) throw ApiError(422,"google_play_blocked","Google Play blocked or rate-limited review retrieval. No partial review sample was imported; try again later.")
                    if(response.code!=200) throw ApiError(422,"google_play_fetch_failure","Google Play reviews could not be retrieved. No partial review sample was imported; try again later.")
                    val body=response.body ?: throw parseFailure()
                    if(body.contentType()?.let { "${it.type}/${it.subtype}" }!="application/json" || response.header("Content-Encoding")!=null) throw parseFailure()
                    return try { PublicPageFetcher.readBounded(body.byteStream(),minOf(MAX_BYTES,maxBytes)) { checkActive(deadline,cancelled) } }
                    catch(e: ApiError) { if(e.code=="scrape_body_limit") throw bodyLimit(); throw e }
                }
            } catch(e: ApiError) { throw e }
            catch(e: Exception) {
                checkActive(deadline,cancelled)
                if(e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException || e is java.util.concurrent.TimeoutException) throw timeout()
                throw ApiError(422,"google_play_fetch_failure","Google Play reviews could not be safely retrieved. No partial review sample was imported; try again later.")
            } finally { activeCall.getAndSet(null)?.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}

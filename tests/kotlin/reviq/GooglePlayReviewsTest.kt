package reviq

import okhttp3.Call
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GooglePlayReviewsTest {
    private val url = "https://play.google.com/store/apps/details?id=com.onepagecrm&hl=en&gl=US"
    private fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    private fun review(id: String, text: Any? = "Review $id", rating: Any? = 4, timestamp: Any? = 1793147400L): List<Any?> =
        listOf(id, listOf("Fixture author", null, null), rating, 0, text, listOf(timestamp, 0), null,
            listOf("Developer", "Developer reply must never become customer text.", listOf(1793147500L)), null, null, "1.0", null, null, null, null, null, null)
    private fun payload(rows: List<List<Any?>>, token: String? = null, padding: String = ""): String =
        mapper.writeValueAsString(listOf(rows, token?.let { listOf(null, it) }, listOf(padding)))
    private fun frame(rows: List<List<Any?>>, token: String? = null, framed: Boolean = true, rpc: String = "oCPfdb", padding: String = ""): ByteArray {
        val json = mapper.writeValueAsString(listOf(listOf("wrb.fr", rpc, payload(rows, token, padding), null, null, null, "generic")))
        return wrap(json, framed)
    }
    private fun wrap(json: String, framed: Boolean = true): ByteArray {
        val data = json.toByteArray(Charsets.UTF_8)
        val prefix = if (framed) ")]}'\n\n${data.size}\n" else ")]}'\n"
        return prefix.toByteArray(Charsets.UTF_8) + data + "\n".toByteArray()
    }
    private fun collect(fetcher: GooglePlayBatchFetcher, end: Long = deadline(), cancelled: AtomicBoolean = AtomicBoolean(false)) =
        GooglePlayReviews.collect(url, end, cancelled, AtomicReference<Call?>(null), fetcher)

    @Test fun `request serializes strict app identity fixed sort and opaque continuation without injection`() {
        val token = "opaque\"\\cursor+/%=&?https://127.0.0.1/"
        val body = GooglePlayReviews.requestBody("com.onepagecrm", 50, token)
        val outer = mapper.readTree(URLDecoder.decode(body.substringAfter("f.req=").substringBefore('&'), StandardCharsets.UTF_8))
        val rpc = outer[0][0]
        assertEquals("oCPfdb", rpc[0].asText())
        val request = mapper.readTree(rpc[1].asText())
        assertEquals("com.onepagecrm", request[2][0].asText())
        assertEquals(7, request[2][1].asInt())
        assertEquals(2, request[1][1].asInt(), "Reviews must use the declared newest-first ordering")
        assertEquals(50, request[1][2][0].asInt())
        assertEquals(token, request[1][2][2].asText(), "Cursor is encoded as data, never a URL or JSON fragment")
        for (id in listOf("com.onepagecrm&id=com.other", "com.onepagecrm\n", "../secret", "single")) {
            assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody(id, 50) }
        }
        for (count in listOf(0, -1, 51, 1000)) assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody("com.onepagecrm", count) }
        assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody("com.onepagecrm", 50, "cursor\nheader") }
        assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody("com.onepagecrm", 50, "x".repeat(100000)) }
    }

    @Test fun `framed and unframed RPC retain exact Unicode text and source metadata without developer reply`() {
        val text = "A & B <literal> &amp; \"quoted\" — 😀\nPlease keep  both spaces."
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            for (framed in listOf(true, false)) {
                val batch = GooglePlayReviews.parseResponse(frame(listOf(review("fixture-id", text, timestamp = Instant.parse("2026-10-28T00:30:00Z").epochSecond)), "next-token", framed), url)
                assertEquals("next-token", batch.nextToken)
                assertEquals(1, batch.reviews.size)
                assertEquals("fixture-id", batch.reviews.single().sourceId)
                val row = batch.reviews.single().row
                assertEquals(text, row["text"])
                assertEquals("4", row["rating"])
                assertEquals("2026-10-28", row["date"], "UTC date must not depend on the server timezone")
                assertEquals("Fixture author", row["author"])
                assertTrue(row["source_url"]!!.startsWith("https://play.google.com/store/apps/details?id=com.onepagecrm"))
                assertFalse(row["text"]!!.contains("Developer reply"))
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun `malformed framing wrong RPC duplicate RPC and nonreview payloads fail closed`() {
        val valid = frame(listOf(review("one")))
        val data = mapper.writeValueAsString(listOf(listOf("wrb.fr", "oCPfdb", payload(listOf(review("one"))), null)))
        val duplicated = mapper.writeValueAsString(listOf(listOf("wrb.fr", "oCPfdb", payload(listOf(review("one"))), null), listOf("wrb.fr", "oCPfdb", payload(listOf(review("two"))), null)))
        val badInputs = listOf(
            "<html>Verification required</html>".toByteArray(),
            frame(listOf(review("one")), rpc = "different-rpc"),
            wrap(duplicated),
            valid.copyOf(valid.size - 5),
            (")]}'\n\n${data.toByteArray().size + 30}\n$data\n").toByteArray(),
            wrap("[[\"wrb.fr\",\"oCPfdb\",\"{}\",null]]"),
            frame(listOf(review("one", text = mapOf("unexpected" to "object"))))
        )
        for (raw in badInputs) assertEquals("google_play_parse_failure", assertThrows(ApiError::class.java) { GooglePlayReviews.parseResponse(raw, url) }.code)
    }

    @Test fun `missing text remains rejectable and invalid optional metadata cannot invent ratings or dates`() {
        val batch = GooglePlayReviews.parseResponse(frame(listOf(review("one", null, "bad", "not-a-timestamp"))), url)
        assertEquals("", batch.reviews.single().row["text"])
        assertTrue(batch.reviews.single().row["rating"].isNullOrBlank())
        assertTrue(batch.reviews.single().row["date"].isNullOrBlank())
        assertEquals("google_play_parse_failure", assertThrows(ApiError::class.java) { GooglePlayReviews.parseResponse(frame(listOf(review("", "Body"))), url) }.code)
    }

    @Test fun `target stops after one hundred unique IDs and shares the absolute deadline across batches`() {
        val calls = AtomicInteger(); val end = deadline(); val cursors = mutableListOf<String?>()
        val result = collect(GooglePlayBatchFetcher { app, count, token, suppliedEnd, _, _, remainingBytes ->
            val call = calls.incrementAndGet(); assertEquals("com.onepagecrm", app); assertEquals(50, count)
            assertEquals(end, suppliedEnd); assertTrue(remainingBytes in 1..(2 * 1024 * 1024)); cursors += token
            frame((1..50).map { review("id-${(call - 1) * 50 + it}") }, if (call == 1) "next-page" else "unused-third-page")
        }, end)
        assertEquals(2, calls.get()); assertEquals(listOf(null, "next-page"), cursors)
        assertEquals(100, result.rows.size); assertEquals(2, result.batches)
        assertEquals("google_play_target_reached", result.stoppingReason); assertEquals(0, result.duplicateIds)
    }

    @Test fun `source exhaustion returns available eighty three reviews without inventing the requested hundred`() {
        val calls = AtomicInteger()
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
            if (calls.incrementAndGet() == 1) frame((1..50).map { review("id-$it") }, "next")
            else frame((51..83).map { review("id-$it") })
        })
        assertEquals(83, result.rows.size); assertEquals(2, result.batches)
        assertEquals("google_play_source_exhausted", result.stoppingReason)
    }

    @Test fun `overlapping source IDs retain first complete version while distinct identical texts reach normalizer`() {
        val calls = AtomicInteger()
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
            if (calls.incrementAndGet() == 1) frame(listOf(review("same-id", "Original text"), review("first-id", "Identical text")), "next")
            else frame(listOf(review("same-id", "Updated text must not create another customer"), review("second-id", "Identical text")))
        })
        assertEquals(3, result.rows.size); assertEquals(1, result.duplicateIds)
        assertEquals(1, result.rows.count { it["text"] == "Original text" })
        assertEquals(2, result.rows.count { it["text"] == "Identical text" })
        assertFalse(result.rows.any { it["text"]!!.contains("Updated") })
    }

    @Test fun `empty first page with no continuation is genuine exhaustion`() {
        val calls = AtomicInteger()
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ -> calls.incrementAndGet(); frame(emptyList()) })
        assertEquals(1, calls.get()); assertTrue(result.rows.isEmpty())
        assertEquals("google_play_source_exhausted", result.stoppingReason)
    }

    @Test fun `empty terminal page after prior reviews confirms source exhaustion`() {
        val calls = AtomicInteger()
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
            if (calls.incrementAndGet() == 1) frame(listOf(review("one"), review("two")), "terminal-page")
            else frame(emptyList())
        })
        assertEquals(2, calls.get()); assertEquals(2, result.rows.size); assertEquals(2, result.batches)
        assertEquals("google_play_source_exhausted", result.stoppingReason)
    }

    @Test fun `repeated continuation and pages without new IDs cannot loop or claim exhaustion`() {
        for (repeatCursor in listOf(true, false)) {
            val calls = AtomicInteger()
            val fetcher = GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
                val n = calls.incrementAndGet()
                frame(listOf(review(if (repeatCursor) "id-$n" else "same-id")), if (repeatCursor) "same-cursor" else "cursor-$n")
            }
            assertEquals("google_play_pagination_stalled", assertThrows(ApiError::class.java) { collect(fetcher) }.code)
            assertEquals(2, calls.get())
        }
    }

    @Test fun `fixed batch cap stops a slowly progressing feed with explicit provenance`() {
        val calls = AtomicInteger()
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
            val n = calls.incrementAndGet(); frame(listOf(review("id-$n")), "cursor-$n")
        })
        assertEquals(5, calls.get()); assertEquals(5, result.rows.size); assertEquals(5, result.batches)
        assertEquals("google_play_page_limit", result.stoppingReason)
    }

    @Test fun `cumulative decoded byte limit bounds all batches and rejects the whole collection`() {
        val calls = AtomicInteger(); val budgets = mutableListOf<Int>()
        val first = frame(listOf(review("one")), "next", padding = "x".repeat(2 * 1024 * 1024 - 2000))
        assertTrue(first.size < 2 * 1024 * 1024)
        val error = assertThrows(ApiError::class.java) { collect(GooglePlayBatchFetcher { _, _, _, _, _, _, budget ->
            budgets += budget
            if (calls.incrementAndGet() == 1) first else frame(listOf(review("two")), padding = "y".repeat(4000))
        }) }
        assertEquals("google_play_body_limit", error.code); assertEquals(2, calls.get())
        assertEquals(2 * 1024 * 1024, budgets[0]); assertEquals(budgets[0] - first.size, budgets[1])
    }

    @Test fun `unsafe source expired deadline and cancellation never fetch or save partial rows`() {
        val noCalls = GooglePlayBatchFetcher { _, _, _, _, _, _, _ -> error("No network request is permitted") }
        assertEquals("google_play_timeout", assertThrows(ApiError::class.java) { collect(noCalls, System.nanoTime() - 1) }.code)
        assertEquals("cancelled", assertThrows(ApiError::class.java) { collect(noCalls, cancelled = AtomicBoolean(true)) }.code)
        assertThrows(ApiError::class.java) { GooglePlayReviews.collect("https://play.google.com/store/apps/details?id=com.onepagecrm&id=com.other", deadline(), AtomicBoolean(false), AtomicReference(), noCalls) }
        val cancelled = AtomicBoolean(false); val calls = AtomicInteger()
        val error = assertThrows(ApiError::class.java) { collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
            calls.incrementAndGet(); cancelled.set(true); frame(listOf(review("one")), "next")
        }, cancelled = cancelled) }
        assertEquals("cancelled", error.code); assertEquals(1, calls.get())
    }

    @Test fun `later transport failure stays an atomic error instead of returning the first batch`() {
        val calls = AtomicInteger()
        val error = assertThrows(ApiError::class.java) { collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ ->
            if (calls.incrementAndGet() == 1) frame(listOf(review("one")), "next")
            else throw ApiError(422, "google_play_blocked", "Source blocked the next request")
        }) }
        assertEquals("google_play_blocked", error.code); assertEquals(2, calls.get())
    }
}

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
    private fun review(id: String, text: Any? = "Review $id", rating: Any? = 4, timestamp: Any? = 1793147400L, nanos: Any? = 0L): List<Any?> =
        listOf(id, listOf("Fixture author", null, null), rating, 0, text, listOf(timestamp, nanos), null,
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
        val body = GooglePlayReviews.requestBody("com.onepagecrm", 200, token)
        val outer = mapper.readTree(URLDecoder.decode(body.substringAfter("f.req=").substringBefore('&'), StandardCharsets.UTF_8))
        val rpc = outer[0][0]
        assertEquals("oCPfdb", rpc[0].asText())
        val request = mapper.readTree(rpc[1].asText())
        assertEquals("com.onepagecrm", request[2][0].asText())
        assertEquals(7, request[2][1].asInt())
        assertEquals(2, request[1][1].asInt(), "Reviews must use the declared newest-first ordering")
        assertEquals(200, request[1][2][0].asInt())
        assertEquals(token, request[1][2][2].asText(), "Cursor is encoded as data, never a URL or JSON fragment")
        for (id in listOf("com.onepagecrm&id=com.other", "com.onepagecrm\n", "../secret", "single")) {
            assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody(id, 200) }
        }
        for (count in listOf(0, -1, 201, 1000)) assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody("com.onepagecrm", count) }
        assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody("com.onepagecrm", 200, "cursor\nheader") }
        assertThrows(ApiError::class.java) { GooglePlayReviews.requestBody("com.onepagecrm", 200, "x".repeat(100000)) }
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

    @Test fun `first request asks for two hundred usable reviews and target stops further retrieval`() {
        val calls = AtomicInteger(); val end = deadline(); val cursors = mutableListOf<String?>()
        val result = collect(GooglePlayBatchFetcher { app, count, token, suppliedEnd, _, _, remainingBytes ->
            calls.incrementAndGet(); assertEquals("com.onepagecrm", app); assertEquals(200, count)
            assertEquals(end, suppliedEnd); assertTrue(remainingBytes in 1..(2 * 1024 * 1024)); cursors += token
            frame((1..200).map { review("id-$it") }, "unused-next-page")
        }, end)
        assertEquals(1, calls.get()); assertEquals(listOf<String?>(null), cursors)
        assertEquals(200, result.rows.size); assertEquals(200, result.usableUniqueTexts); assertEquals(1, result.batches)
        assertEquals("google_play_target_reached", result.stoppingReason); assertEquals(0, result.duplicateIds)
    }

    @Test fun `source exhaustion returns available eighty three reviews without inventing the requested two hundred`() {
        val calls = AtomicInteger()
        val result = collect(GooglePlayBatchFetcher { _, count, _, _, _, _, _ ->
            calls.incrementAndGet(); assertEquals(200, count)
            frame((1..83).map { review("id-$it") })
        })
        assertEquals(83, result.rows.size); assertEquals(1, result.batches); assertEquals(1, calls.get())
        assertEquals("google_play_source_exhausted", result.stoppingReason)
    }

    @Test fun `source ID overlaps request only the remaining unique review target`() {
        val calls = AtomicInteger(); val requested = mutableListOf<Int>(); val end = deadline()
        val result = collect(GooglePlayBatchFetcher { _, count, _, suppliedEnd, _, _, _ ->
            assertEquals(end, suppliedEnd)
            requested += count
            when(calls.incrementAndGet()) {
                1 -> frame((1..100).map { review("id-$it") }, "second")
                2 -> frame((91..190).map { review("id-$it") }, "third")
                3 -> frame((191..200).map { review("id-$it") }, "unused-fourth")
                else -> error("The target must stop further requests")
            }
        }, end)
        assertEquals(listOf(200, 100, 10), requested)
        assertEquals(200, result.rows.size); assertEquals(3, result.batches); assertEquals(10, result.duplicateIds)
        assertEquals("google_play_target_reached", result.stoppingReason)
    }

    @Test fun `duplicate normalized texts and blank reviews are topped up and retained for normalization counts`() {
        val calls = AtomicInteger(); val requested = mutableListOf<Int>()
        val initial = (1..196).map { review("id-$it") } + listOf(
            review("full-width", "Ｆｉｘｔｕｒｅ\t  word"), review("same-wording", "Fixture word"),
            review("empty", null), review("blank", " \t\n"))
        val result = collect(GooglePlayBatchFetcher { _, count, _, _, _, _, _ ->
            requested += count
            when(calls.incrementAndGet()) {
                1 -> frame(initial, "next")
                2 -> frame((201..203).map { review("id-$it") }, "unused")
                else -> error("Two hundred usable unique texts must stop further retrieval")
            }
        })
        assertEquals(listOf(200, 3), requested)
        assertEquals(203, result.rows.size); assertEquals(200, result.usableUniqueTexts)
        assertEquals("google_play_target_reached", result.stoppingReason)
        val normalized = Normalize.scraped("Fixture", result.rows, url, Instant.now().toString())
        assertEquals(203, normalized.summary.observed); assertEquals(200, normalized.summary.accepted)
        assertEquals(1, normalized.summary.duplicates); assertEquals(2, normalized.summary.rejected)
    }

    @Test fun `new source IDs with only duplicate or blank texts still allow useful later continuation`() {
        val calls = AtomicInteger(); val requested = mutableListOf<Int>()
        val result = collect(GooglePlayBatchFetcher { _, count, _, _, _, _, _ ->
            requested += count
            when(calls.incrementAndGet()) {
                1 -> frame(listOf(review("first", "Repeated wording")), "second")
                2 -> frame(listOf(review("duplicate", "Repeated\twording"), review("blank", " ")), "third")
                3 -> frame(listOf(review("new", "Later distinct wording")))
                else -> error("Exhausted source must stop")
            }
        })
        assertEquals(listOf(200, 199, 199), requested)
        assertEquals(4, result.rows.size); assertEquals(2, result.usableUniqueTexts)
        assertEquals("google_play_source_exhausted", result.stoppingReason)
    }

    @Test fun `newer version of a source ID can reduce unique text count and increase remaining request`() {
        val calls = AtomicInteger(); val requested = mutableListOf<Int>()
        val result = collect(GooglePlayBatchFetcher { _, count, _, _, _, _, _ ->
            requested += count
            when(calls.incrementAndGet()) {
                1 -> frame((1..198).map { review("id-$it") } + listOf(review("revised", "Original distinct wording")), "second")
                2 -> frame(listOf(review("revised", "Review id-1", timestamp = 1793147401L)), "third")
                3 -> frame(listOf(review("last-one"), review("last-two")), "unused")
                else -> error("Target reached")
            }
        })
        assertEquals(listOf(200, 1, 2), requested)
        assertEquals(201, result.rows.size); assertEquals(200, result.usableUniqueTexts); assertEquals(1, result.duplicateIds)
        assertFalse(result.rows.any { it["text"] == "Original distinct wording" })
        assertEquals("google_play_target_reached", result.stoppingReason)
    }

    @Test fun `full timestamps order newest first with nanoseconds stable ties and unknown dates last`() {
        val seconds = Instant.parse("2026-10-28T00:30:00Z").epochSecond
        val records = listOf(
            review("unknown-first", timestamp = null), review("earlier-second", timestamp = seconds, nanos = 999999999L),
            review("tie-first", timestamp = seconds + 1, nanos = 1L), review("latest", timestamp = seconds + 1, nanos = 999999999L),
            review("tie-second", timestamp = seconds + 1, nanos = 1L), review("unknown-second", timestamp = seconds, nanos = -1L))
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ -> frame(records) })
        assertEquals(listOf("latest", "tie-first", "tie-second", "earlier-second", "unknown-first", "unknown-second").map { "Review $it" }, result.rows.map { it["text"] })
        assertEquals(2, result.unknownTimestamps)
        assertTrue(result.rows.takeLast(2).all { it["date"] == null })
        val parsed = GooglePlayReviews.parseResponse(frame(listOf(review("timestamp", timestamp = seconds, nanos = 123456789L))), url)
        assertEquals(Instant.ofEpochSecond(seconds, 123456789L), parsed.reviews.single().sourceTimestamp)
    }

    @Test fun `newest source ID version wins while equal timestamps retain selected version feed order`() {
        val records = listOf(review("updated", "Old", timestamp = 1000L), review("peer", "Peer first", timestamp = 2000L),
            review("updated", "Newest", timestamp = 2000L), review("updated", "Ignored equal timestamp", timestamp = 2000L),
            review("unknown", "Unknown", timestamp = null), review("unknown", "Known date", timestamp = 1500L),
            review("updated", "Ignored older version", timestamp = 1999L))
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ -> frame(records) })
        assertEquals(listOf("Peer first", "Newest", "Known date"), result.rows.map { it["text"] })
        assertEquals(4, result.duplicateIds); assertEquals(0, result.unknownTimestamps)
    }

    @Test fun `chronological sorting happens before text deduplication and stable review ID assignment`() {
        val records = listOf(review("old", "Same wording", rating = 1, timestamp = 1000L),
            review("middle", "Different wording", rating = 3, timestamp = 1500L),
            review("new", "Same wording", rating = 5, timestamp = 2000L))
        val result = collect(GooglePlayBatchFetcher { _, _, _, _, _, _, _ -> frame(records) })
        val normalized = Normalize.scraped("Fixture", result.rows, url, Instant.now().toString())
        assertEquals(listOf("Same wording", "Different wording"), normalized.reviews.map { it.text })
        assertEquals("R001", normalized.reviews.first().id); assertEquals(5, normalized.reviews.first().rating)
        assertEquals(1, normalized.summary.duplicates)
    }

    @Test fun `invalid nanoseconds or years stay unknown rather than shifting timestamps`() {
        val records = listOf(review("negative-nanos", nanos = -1L), review("overflow-nanos", nanos = 1000000000L),
            review("string-nanos", nanos = "bad"), review("year-zero", timestamp = Instant.parse("0000-01-01T00:00:00Z").epochSecond),
            review("year-ten-thousand", timestamp = Instant.parse("+10000-01-01T00:00:00Z").epochSecond))
        val parsed = GooglePlayReviews.parseResponse(frame(records), url)
        assertTrue(parsed.reviews.all { it.sourceTimestamp == null && it.row["date"] == null })
    }

    @Test fun `oversized response cannot exceed either batch or remaining review limit`() {
        for(oversizedFirstBatch in listOf(true, false)) {
            val calls = AtomicInteger()
            val error = assertThrows(ApiError::class.java) { collect(GooglePlayBatchFetcher { _, count, _, _, _, _, _ ->
                when(calls.incrementAndGet()) {
                    1 -> frame((1..(if(oversizedFirstBatch) 201 else 100)).map { review("id-$it") }, "second")
                    2 -> frame((91..190).map { review("id-$it") }, "third")
                    3 -> { assertEquals(10, count); frame((191..201).map { review("id-$it") }) }
                    else -> error("A malformed response must stop collection")
                }
            }) }
            assertEquals("google_play_parse_failure", error.code)
            assertEquals(if(oversizedFirstBatch) 1 else 3, calls.get())
        }
    }

    @Test fun `equal timestamp source ID repeats retain first version while distinct identical texts reach normalizer`() {
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

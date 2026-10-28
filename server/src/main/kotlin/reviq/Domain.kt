package reviq

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import org.apache.commons.csv.CSVFormat
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

val mapper = jacksonObjectMapper()
class ApiError(val status: Int, val code: String, override val message: String) : RuntimeException(message)
data class Review(val id: String, val text: String, val rating: Int? = null, val title: String? = null, val date: String? = null, val author: String? = null, val source_url: String? = null)
data class Summary(val observed: Int, val accepted: Int, val rejected: Int, val duplicates: Int, val excluded: Int, val histogram: Map<String,Int>, val rating_count: Int, val average_rating: Double?, val missing: Map<String,Int>, val date_from: String?, val date_to: String?, val stopping_reason: String, val warnings: List<String>, val token_count: Int, val scanned_total_known: Boolean, val pages: Map<String,Any?> = mapOf("attempted" to null,"fetched" to null,"parsed" to null))
data class Dataset(val id: String, val version: Int = 1, val entity_name: String, val platform: String, val source_url: String?, val mode: String, val captured_at: String? = null, val ingested_at: String, val expires_at: String, val reviews: List<Review>, val summary: Summary, val content_hash: String, val synthetic: Boolean = false, val retrieval_provider: String? = null, val retrieval_freshness: String? = null, val retrieved_at: String? = null)
data class Message(val role: String, val content: String, val citations: List<String> = emptyList(), val mode: String = "evidence")
fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
object Tokens {
    private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE)
    fun count(value: String): Int = encoding.countTokens(value)
}
fun safeUrl(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() && it.length <= 2048 }?.let {
    try { val u = URI(it); val defaultPort=when(u.scheme) { "https" -> 443; "http" -> 80; else -> null }; if (defaultPort!=null && !u.host.isNullOrBlank() && u.rawUserInfo == null && (u.port == -1 || u.port == defaultPort)) it else null } catch (_: Exception) { null }
}
object Normalize {
    const val MAX_BYTES = 1048576
    internal fun canonicalReviewText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("\\s+")," ").trim()
    fun ingest(type: String, entity: String, platform: String, content: String, url: String? = null): Dataset {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ApiError(413,"input_too_large","Upload or paste must be no larger than 1 MiB.")
        if (type == "url") throw ApiError(422,"scraping_unavailable","Live scraping is unavailable. Export reviews as CSV or paste them to continue.")
        if (type !in setOf("csv","paste","demo")) throw ApiError(400,"invalid_type","Choose CSV, paste, or example data.")
        val entityName = if (type == "demo") "Northstar Headphones" else metadata(entity, "Entity name")
        val source = if (type == "demo") "Synthetic example" else metadata(platform, "Source declaration")
        if (!url.isNullOrBlank() && safeUrl(url) == null) throw ApiError(400,"invalid_url","Source links must be valid HTTP or HTTPS URLs without credentials or custom ports.")
        val (rows, cutoff) = when(type) {
            "csv" -> csv(content)
            "paste" -> { val all = content.replace("\r\n","\n").split(Regex("\n[\\t ]*\n+")).filter { it.isNotBlank() }; all.take(1000).map { mapOf("text" to it) } to (all.size > 1000) }
            else -> demoRows() to false
        }
        return normalizeRows(type,entityName,source,rows,cutoff,url)
    }
    fun scraped(entity: String, rows: List<Map<String,String>>, sourceUrl: String, capturedAt: String, platform: String = "Google Play", warnings: List<String> = emptyList(),
                pages: Map<String,Any?> = mapOf("attempted" to 1,"fetched" to 1,"parsed" to 1),
                stoppingReason: String = "visible_page_sample",
                coverageWarning: String = "Only identifiable reviews in the fetched public page were captured. This is a selected sample; the total unseen review count is unknown. No review pagination was attempted."): Dataset {
        val data=normalizeRows("scraped",metadata(entity,"Product or business name"),metadata(platform,"Source platform"),rows.take(1000),rows.size>1000,sourceUrl)
        return data.copy(mode="scraped",captured_at=capturedAt,retrieval_provider="direct",retrieval_freshness="live",retrieved_at=capturedAt,summary=data.summary.copy(
            stopping_reason=if(data.summary.stopping_reason=="source_exhausted") stoppingReason else data.summary.stopping_reason,
            scanned_total_known=false,
            pages=pages,
            warnings=(data.summary.warnings + warnings + coverageWarning).distinct()
        ))
    }
    private fun normalizeRows(type: String, entityName: String, source: String, rows: List<Map<String,String>>, cutoff: Boolean, url: String?): Dataset {
        if (rows.isEmpty()) throw ApiError(400,"empty_input","No reviews were found. Add a text column or paste at least one review.")
        var rejected = 0; var duplicates = 0; var excluded = 0
        var invalidRatings = 0; var invalidDates = 0; var invalidUrls = 0; var oversizedFields = 0
        var tokenCount = Tokens.count(entityName + source)
        val seen = mutableSetOf<String>(); val accepted = mutableListOf<Review>(); val reasons = mutableSetOf<String>()
        for (row in rows) {
            val text = row["text"].orEmpty()
            if (text.isBlank()) { rejected++; continue }
            val canonical = canonicalReviewText(text)
            if (!seen.add(hash(canonical))) { duplicates++; continue }
            if (accepted.size >= 200) { excluded++; reasons += "count_limit"; continue }
            fun optional(name: String, max: Int = 500): String? = row[name]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > max) oversizedFields++ }?.takeIf { it.length <= max }
            val rawRating = optional("rating",20)
            val rating = rawRating?.toDoubleOrNull()?.takeIf { it in 1.0..5.0 && it % 1.0 == 0.0 }?.toInt()
            if (rawRating != null && rating == null) invalidRatings++
            val rawDate = optional("date",50)
            val date = try { rawDate?.let { LocalDate.parse(it).toString() } } catch (_: Exception) { invalidDates++; null }
            val rawUrl = optional("source_url",2048); val sourceUrl = safeUrl(rawUrl)
            if (rawUrl != null && sourceUrl == null) invalidUrls++
            val review = Review("R%03d".format(accepted.size + 1),text,rating,optional("title"),date,optional("author"),sourceUrl)
            val tokens = Tokens.count(mapper.writeValueAsString(review))
            if (tokenCount + tokens > 24000) { excluded++; reasons += "token_limit"; continue }
            accepted += review; tokenCount += tokens
        }
        if (accepted.isEmpty()) throw ApiError(422,"no_usable_reviews","No usable reviews remain after validation. Check the text column and input limits.")
        val warnings = mutableListOf<String>()
        if (rejected > 0) warnings += "$rejected rows without review text were rejected."
        if (duplicates > 0) warnings += "$duplicates repeated texts were removed; identical wording does not prove a duplicate customer."
        if (excluded > 0) warnings += "$excluded reviews were excluded by count or token limits. The supplied sample is incomplete."
        if (invalidRatings > 0) warnings += "$invalidRatings invalid ratings were stored as missing."
        if (invalidDates > 0) warnings += "$invalidDates invalid dates were stored as missing."
        if (invalidUrls > 0) warnings += "$invalidUrls unsafe or invalid source links were omitted."
        if (oversizedFields > 0) warnings += "$oversizedFields oversized optional fields were stored as missing."
        if (cutoff) warnings += "Scanning stopped at 1,000 records; the unseen total is unknown."
        if (type == "demo") warnings += "Synthetic example reviews for demonstration; these are not real customer reviews."
        else if(type=="scraped") warnings += "Product or business name and review fields were extracted from the fetched public source page. Findings apply only to the captured sample."
        else warnings += "Entity and source are user-declared. Findings apply only to this supplied sample."
        val ratings = accepted.mapNotNull { it.rating }; val dates = accepted.mapNotNull { it.date }
        val missing = mapOf("text" to 0,"rating" to accepted.count { it.rating == null },"title" to accepted.count { it.title == null },"date" to accepted.count { it.date == null },"author" to accepted.count { it.author == null },"source_url" to accepted.count { it.source_url == null })
        val summary = Summary(rows.size,accepted.size,rejected,duplicates,excluded,(1..5).associate { "$it" to ratings.count { r -> r == it } },ratings.size,ratings.takeIf { it.isNotEmpty() }?.average(),missing,dates.minOrNull(),dates.maxOrNull(),if(cutoff) "scan_limit" else reasons.joinToString(", ").ifEmpty { "source_exhausted" },warnings,tokenCount,!cutoff)
        val now = Instant.now()
        val digest = hash(mapper.writeValueAsString(mapOf("entity" to entityName,"platform" to source,"url" to url,"reviews" to accepted)))
        return Dataset(UUID.randomUUID().toString(),entity_name=entityName,platform=source,source_url=safeUrl(url),mode=when(type){"csv"->"uploaded";"paste"->"pasted";"scraped"->"scraped";else->"cached_demo"},ingested_at=now.toString(),expires_at=now.plusSeconds(86400).toString(),reviews=accepted,summary=summary,content_hash=digest,synthetic=type=="demo")
    }
    private fun metadata(value: String, name: String): String {
        if (value.isBlank() || value.codePointCount(0,value.length) > 160 || value.any { it.isISOControl() }) throw ApiError(400,"invalid_metadata","$name is required and must be at most 160 characters without control characters.")
        return value.trim()
    }
    private fun csv(content: String): Pair<List<Map<String,String>>,Boolean> {
        try {
            val format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false).setTrim(false).get()
            format.parse(StringReader(content.removePrefix("\uFEFF"))).use { parser ->
                val keys = parser.headerNames.map { it.trim().lowercase() }
                if (keys.count { it == "text" } != 1 || keys.size != keys.distinct().size) throw ApiError(400,"invalid_csv","CSV requires one text column and unique column names.")
                val rows = mutableListOf<Map<String,String>>()
                val iter = parser.iterator()
                while (iter.hasNext() && rows.size < 1000) {
                    val row = iter.next()
                    rows += keys.mapIndexed { i,key -> key to if(i < row.size()) row[i] else "" }.toMap()
                }
                return rows to iter.hasNext()
            }
        } catch (e: ApiError) { throw e } catch (_: Exception) { throw ApiError(400,"invalid_csv","The CSV could not be parsed. Check quoted fields, commas, and line breaks.") }
    }
    private fun demoRows(): List<Map<String,String>> {
        val texts = listOf(
            "The sound is beautifully balanced. I can hear details in my favorite albums that I missed before." to 5,
            "My headphones arrived four days late. Tracking never updated and the box was crushed, although the headphones worked." to 2,
            "Very comfortable for my two-hour commute. The soft ear cushions make a real difference." to 5,
            "The left earcup started crackling after three weeks. Support replaced the pair within two days." to 3,
            "Delivery was fast and everything was neatly packed. Setup took less than a minute." to 5,
            "Sound quality is excellent, especially vocals. A little more bass would have made them perfect for me." to 4,
            "I waited nine days for delivery. Customer support could not give me an estimated arrival date." to 2,
            "The headband feels flimsy for the price. One hinge became loose after a month of normal use." to 2,
            "Support was kind and helpful when I had trouble pairing. They walked me through a reset and it worked." to 5,
            "Good sound and comfortable fit, but the delivery was delayed twice. Please improve the shipping updates." to 3,
            "The cushions get warm during longer listening sessions. Otherwise the sound is clear and enjoyable." to 4,
            "A replacement ear cushion was missing from my order. Support sent it straight away with no hassle." to 4,
            "The noise isolation helps me concentrate in a busy office. I am very pleased with the clear audio." to 5,
            "The parcel arrived on time, but the packaging was torn. I would prefer sturdier protection." to 3,
            "For this price I expected sturdier hinges. The sound is good but the build quality needs work." to 2,
            "Really easy to pair with my laptop and phone. Switching between them has been straightforward." to 5,
            "I contacted support three times about a refund and still have no clear answer after a week." to 1,
            "Bought these for music at home. The detailed sound and comfortable cushions make them a pleasure to use." to 5
        )
        return texts.mapIndexed { i,(text,rating) -> mapOf("text" to text,"rating" to "$rating","date" to LocalDate.of(2026,9,1).plusDays(i.toLong()*2).toString(),"author" to "Example reviewer ${i+1}","title" to listOf("Listening experience","Delivery experience","Product feedback")[i%3]) }
    }
}

package reviq

import com.fasterxml.jackson.databind.JsonNode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

internal data class ParsedReviewPage(
    val entity: String,
    val platform: String,
    val rows: List<Map<String, String>>,
    val warnings: List<String> = emptyList()
)

/** Extract only explicitly marked customer reviews, never arbitrary page prose. */
internal object ReviewPageParser {
    private const val MAX_RECORDS = 1001 // Normalize reconciles the first 1,000 and records the cutoff.
    private const val MAX_STRUCTURED_NODES = 5000
    private val reviewTypes = setOf("Review", "UserReview")

    fun parse(html: String, finalUrl: String): ParsedReviewPage {
        checkInterrupted()
        val host = URI(finalUrl).host.lowercase().removePrefix("www.")
        if (host == "play.google.com" && URI(finalUrl).path == "/store/apps/details") {
            val (entity, rows) = GooglePlayScraper.parse(html, finalUrl)
            return ParsedReviewPage(entity, "Google Play", rows,
                listOf("Google Play reviews were captured from the public English/US app page."))
        }
        val doc = Jsoup.parse(html, finalUrl)
        val platform = if (host == "trustpilot.com" || host.endsWith(".trustpilot.com")) "Trustpilot" else metadata(host)
        if (platform == "Trustpilot") trustpilot(doc, finalUrl)?.let { return it }
        structured(doc, finalUrl, platform)?.let { return it }
        microdata(doc, finalUrl, platform)?.let { return it }
        visibleWidgets(doc, finalUrl, platform)?.let { return it }
        embeddedWidget(doc, finalUrl, platform)?.let { return it }
        throw ApiError(422, "scrape_no_reviews", "No identifiable customer reviews were found in the downloaded page. Reviews may require JavaScript or sign-in, or use an unsupported layout. Upload a CSV or paste the reviews instead.")
    }

    /** Provider extraction may retain semantic headings/images while removing original DOM attributes. */
    fun parseProvider(html: String, finalUrl: String): ParsedReviewPage {
        try { return parse(html, finalUrl) }
        catch (error: ApiError) {
            val host = URI(finalUrl).host.lowercase().removePrefix("www.")
            if (error.code != "scrape_no_reviews" || (host != "trustpilot.com" && !host.endsWith(".trustpilot.com"))) throw error
            return providerTrustpilot(Jsoup.parse(html, finalUrl), finalUrl) ?: throw error
        }
    }

    private fun providerTrustpilot(doc: Document, url: String): ParsedReviewPage? {
        if (!URI(url).path.startsWith("/review/")) return null
        val heading = doc.selectFirst("h1")?.text().orEmpty()
        val entity = Regex("^(.+?)\\s+Reviews(?:\\s+[\\d,.KkMm]+)?$").matchEntire(heading.trim())?.groupValues?.get(1)?.let(::metadata)
            ?.takeIf { it.isNotBlank() } ?: return null
        val blocks = doc.select("h1, h2, h3, h4, p")
        val starts = blocks.indices.filter { blocks[it].tagName() == "h2" && blocks[it].text().trim().equals("All reviews", true) }
        if (starts.isEmpty()) return null
        fun ambiguous(): Nothing = throw ApiError(422, "scrape_ambiguous_reviews", "The extracted review sections have incomplete or unclear boundaries. No partial reviews were imported. Upload a CSV or paste reviews instead.")
        if (starts.size != 1) ambiguous()
        val footers = setOf("is this your company?", "the trustpilot experience", "company details", "contact info", "people also looked at", "choose country", "about")
        val sectionStart = starts.single() + 1
        val sectionEnd = (sectionStart until blocks.size).firstOrNull { index ->
            blocks[index].tagName().startsWith("h") && blocks[index].text().trim().lowercase() in footers
        } ?: blocks.size
        fun rating(index: Int): String? {
            val block = blocks[index]
            if (block.tagName() != "p" || block.wholeText().isNotBlank()) return null
            return block.select("img[alt]").mapNotNull { image ->
                Regex("(?i)^Rated ([1-5]) out of 5 stars?$").matchEntire(image.attr("alt").trim())?.groupValues?.get(1)
            }.singleOrNull()
        }
        val markers = (sectionStart until sectionEnd).filter { rating(it) != null }
        if (markers.isEmpty()) return null
        val dateFormat = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM d, uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
        val rows = mutableListOf<Map<String, String>>()
        var titleOnly = false
        for (marker in markers.take(MAX_RECORDS)) {
            checkInterrupted()
            var index = marker + 1
            while (index < sectionEnd && blocks[index].tagName() == "p" && blocks[index].wholeText().isBlank() && blocks[index].select("img").isEmpty()) index++
            if (index >= sectionEnd || blocks[index].tagName() !in setOf("h2", "h3")) ambiguous()
            val title = blocks[index++].wholeText()
            if (title.isBlank() || title.trim().lowercase().startsWith("reply from")) ambiguous()
            val paragraphs = mutableListOf<String>()
            var experienceDate: String? = null
            while (index < sectionEnd) {
                checkInterrupted()
                val block = blocks[index]
                if (rating(index) != null || block.tagName() != "p") ambiguous()
                val dateMatch = Regex("(?i)^Date of experience:\\s*(.+)$").matchEntire(block.text().trim())
                if (dateMatch != null) {
                    experienceDate = try { LocalDate.parse(dateMatch.groupValues[1], dateFormat).toString() } catch (_: Exception) { ambiguous() }
                    break
                }
                if (block.text().trim().matches(Regex("(?i)^(?:Reply|Response) from\\b.*")) || block.select("img").isNotEmpty()) ambiguous()
                val text = block.wholeText()
                if (text.isNotBlank()) paragraphs += text
                index++
            }
            if (experienceDate == null) ambiguous()
            val text = if (paragraphs.isEmpty()) { titleOnly = true; title } else paragraphs.joinToString("\n")
            rows += row(text, rating(marker)!!, title, experienceDate, "", url)
        }
        val warnings = mutableListOf("Reviews were identified from rating, title, body and date boundaries in provider-extracted Trustpilot HTML. Author names were not inferred from combined profile text.",
            "Review dates in this extraction are the explicitly labeled dates of experience, not publication dates.")
        if (titleOnly) warnings += "Reviews without a separate body use their published review title as the review text."
        return ParsedReviewPage(entity, "Trustpilot", rows, warnings)
    }

    private fun trustpilot(doc: Document, url: String): ParsedReviewPage? {
        val pageProps = doc.select("script#__NEXT_DATA__").firstNotNullOfOrNull { script ->
            readJson(script.data())?.path("props")?.path("pageProps")?.takeUnless { it.isMissingNode }
        }
        val business = pageProps?.path("businessUnit")
        val expected = URI(url).path.removePrefix("/review/").trimEnd('/').lowercase()
        val identity = business?.path("identifyingName")?.asText().orEmpty().lowercase()
        if (identity.isNotBlank() && expected.isNotBlank() && identity != expected) {
            throw ApiError(422, "scrape_source_mismatch", "The review page identifies a different business from the requested URL. Upload a CSV or paste reviews instead.")
        }
        val entity = metadata(business?.path("displayName")?.asText().orEmpty()).ifBlank {
            metadata(doc.selectFirst("h1 [data-business-unit-name]")?.text()
                ?: doc.selectFirst("h1")?.text().orEmpty()).replace(Regex("\\s+Reviews(?:\\s+[\\d,.]+)?$"), "").trim()
        }.ifBlank { pageName(doc, url).removeSuffix(" Reviews").trim() }
        val cards = doc.select("[data-service-review-card-paper], article[data-service-review-card]")
            .filter { card -> card.parents().none { it.hasAttr("data-service-review-card-paper") || it.hasAttr("data-service-review-card") } }
            .take(MAX_RECORDS)
        if (cards.isNotEmpty()) {
            var titleOnly = false
            val rows = cards.map { card ->
                checkInterrupted()
                val title = card.selectFirst("[data-service-review-title-typography]")?.wholeText().orEmpty()
                val body = card.selectFirst("[data-service-review-text-typography]")?.wholeText().orEmpty()
                val text = body.ifBlank { if (title.isNotBlank()) titleOnly = true; title }
                val ratingNode = card.selectFirst("[data-service-review-rating]")
                val rawRating = ratingNode?.attr("data-service-review-rating").orEmpty()
                val rating = rawRating.ifBlank {
                    val label = ratingNode?.selectFirst("img[alt]")?.attr("alt")
                        ?: card.selectFirst("img[alt^=Rated]")?.attr("alt").orEmpty()
                    Regex("(?i)^Rated ([1-5]) out of 5 stars?\\b").find(label)?.groupValues?.get(1).orEmpty()
                }
                val published = card.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
                val author = card.selectFirst("[data-consumer-name-typography]")?.wholeText().orEmpty()
                val reviewLink = card.selectFirst("a[href^=/reviews/]")?.attr("href")
                row(text, rating, title, published, author, sourceLink(reviewLink, url))
            }
            return ParsedReviewPage(entity, "Trustpilot", rows, if (titleOnly) listOf("Reviews without a separate body use their published review title as the review text.") else emptyList())
        }
        val reviews = pageProps?.path("reviews")?.takeIf { it.isArray } ?: return null
        if (reviews.isEmpty) return null
        val rows = reviews.take(MAX_RECORDS).map { review ->
            checkInterrupted()
            val title = scalar(review.path("title"))
            val text = scalar(review.path("text")).ifBlank { title }
            val id = scalar(review.path("id"))
            val source = if (Regex("[A-Za-z0-9_-]{1,100}").matches(id)) sourceLink("/reviews/$id", url) else url
            row(text, scalar(review.path("rating")), title,
                scalar(review.path("dates").path("publishedDate")), scalar(review.path("consumer").path("displayName")), source)
        }
        return ParsedReviewPage(entity, "Trustpilot", rows, listOf("Reviews were extracted from the public page's embedded review data; reviews loaded by additional requests were not fetched."))
    }

    private data class ReviewGroup(val owner: JsonNode?, val reviews: List<JsonNode>)

    private fun structured(doc: Document, url: String, platform: String): ParsedReviewPage? {
        val nodes = mutableListOf<JsonNode>()
        var visited = 0
        fun walk(node: JsonNode, depth: Int) {
            checkInterrupted()
            if (++visited > MAX_STRUCTURED_NODES || depth > 20) return
            when {
                node.isArray -> node.forEach { if (visited <= MAX_STRUCTURED_NODES) walk(it, depth + 1) }
                node.isObject -> {
                    nodes.add(node)
                    // Deliberately exclude recommendations, ItemList, offers and arbitrary nested products.
                    node.get("@graph")?.let { walk(it, depth + 1) }
                    node.get("mainEntity")?.let { walk(it, depth + 1) }
                }
            }
        }
        doc.select("script[type=application/ld+json]").take(100).forEach { script ->
            readJson(script.data())?.let { walk(it, 0) }
        }
        if (nodes.isEmpty()) return null
        val byId = nodes.mapNotNull { node -> scalar(node.path("@id")).takeIf { it.isNotBlank() }?.let { it to node } }.toMap()
        fun resolve(node: JsonNode): JsonNode = if (node.isObject && node.size() == 1 && node.has("@id")) byId[scalar(node.path("@id"))] ?: node else node
        fun reviewsFrom(node: JsonNode): List<JsonNode> = (if (node.isArray) node.toList() else listOf(node))
            .take(MAX_RECORDS).map(::resolve).filter { it.isObject && (isReview(it) || it.has("reviewBody")) }
        val ownerGroups = nodes.filter { !isReview(it) && it.has("review") }.mapNotNull { owner ->
            reviewsFrom(owner.path("review")).takeIf { it.isNotEmpty() }?.let { ReviewGroup(owner, it) }
        }.distinctBy { it.owner?.toString() }
        val selected = if (ownerGroups.isNotEmpty()) selectGroup(ownerGroups, doc, url) else {
            val standalone = nodes.filter(::isReview)
            if (standalone.isEmpty()) return null
            val groups = standalone.groupBy { review ->
                val item = resolve(review.path("itemReviewed"))
                scalar(item.path("@id")).ifBlank { scalar(item.path("url")) }.ifBlank { scalar(item.path("name")) }
            }.map { (_, reviews) -> ReviewGroup(resolve(reviews.first().path("itemReviewed")), reviews) }
            selectGroup(groups, doc, url)
        } ?: return null
        val entity = scalar(selected.owner?.path("name")).let(::metadata).ifBlank { pageName(doc, url) }
        val rows = selected.reviews.take(MAX_RECORDS).map { review ->
            checkInterrupted()
            val rating = review.path("reviewRating")
            val rawRating = scalar(rating.path("ratingValue"))
            val maxRating = scalar(rating.path("bestRating"))
            val minRating = scalar(rating.path("worstRating"))
            // Preserve only a declared/default 1–5 scale; other scales must not become stars.
            val usableRating = if ((maxRating.isBlank() || maxRating.toDoubleOrNull() == 5.0) && (minRating.isBlank() || minRating.toDoubleOrNull() == 1.0)) rawRating else ""
            row(scalar(review.path("reviewBody")), usableRating, scalar(review.path("name")),
                scalar(review.path("datePublished")), personName(review.path("author")), sourceLink(scalar(review.path("url")), url))
        }
        if (rows.none { it["text"].orEmpty().isNotBlank() }) return null
        return ParsedReviewPage(entity, platform, rows,
            listOf("Reviews were extracted from review metadata published in this page. Ratings on scales other than 1–5 are left missing."))
    }

    private fun selectGroup(groups: List<ReviewGroup>, doc: Document, url: String): ReviewGroup? {
        if (groups.size == 1) return groups.single().takeIf { group ->
            val ownerUrl = scalar(group.owner?.path("url")).ifBlank { scalar(group.owner?.path("mainEntityOfPage")) }
            ownerUrl.isBlank() || samePage(ownerUrl, url) || sameName(scalar(group.owner?.path("name")), doc.selectFirst("h1")?.text().orEmpty())
        }
        val exact = groups.filter { group ->
            val owner = group.owner
            listOf(scalar(owner?.path("url")), scalar(owner?.path("@id")), scalar(owner?.path("mainEntityOfPage"))).any { samePage(it, url) }
        }
        if (exact.size == 1) return exact.single()
        val heading = doc.selectFirst("h1")?.text().orEmpty()
        val named = groups.filter { sameName(scalar(it.owner?.path("name")), heading) }
        if (named.size == 1) return named.single()
        throw ApiError(422, "scrape_ambiguous_reviews", "This page contains reviews for several products or businesses, so they could not be safely combined. Open a specific product or business review page, or upload a CSV.")
    }

    private fun microdata(doc: Document, url: String, platform: String): ParsedReviewPage? {
        val reviews = doc.select("[itemscope][itemtype]").filter { el ->
            el.attr("itemtype").split(Regex("\\s+")).any { it.trimEnd('/').substringAfterLast('/') in reviewTypes }
        }.take(MAX_RECORDS)
        if (reviews.isEmpty()) return null
        fun owner(review: Element): Element? = review.select("[itemprop][itemscope]").firstOrNull { "itemReviewed" in it.attr("itemprop").split(Regex("\\s+")) }
            ?: review.parents().firstOrNull { it.hasAttr("itemscope") && it.attr("itemtype").isNotBlank() }
        val groups = reviews.groupBy { owner(it) }
        val owner: Element?
        val selected: List<Element>
        if (groups.size == 1) {
            owner = groups.keys.single(); selected = groups.values.single()
        } else {
            val heading = doc.selectFirst("h1")?.text().orEmpty()
            val match = groups.entries.filter { (candidate, _) ->
                candidate != null && (sameName(prop(candidate, "name"), heading) || samePage(prop(candidate, "url"), url))
            }
            if (match.size != 1) throw ApiError(422, "scrape_ambiguous_reviews", "This page contains reviews for several products or businesses. Open a specific review page, or upload a CSV.")
            owner = match.single().key; selected = match.single().value
        }
        val rows = selected.map { review ->
            checkInterrupted()
            val ratingScope = review.select("[itemprop]").firstOrNull { "reviewRating" in it.attr("itemprop").split(' ') }
            val best = ratingScope?.let { prop(it, "bestRating") }.orEmpty()
            val worst = ratingScope?.let { prop(it, "worstRating") }.orEmpty()
            val rating = if ((best.isBlank() || best.toDoubleOrNull() == 5.0) && (worst.isBlank() || worst.toDoubleOrNull() == 1.0)) ratingScope?.let { prop(it, "ratingValue") }.orEmpty() else ""
            val authorScope = review.select("[itemprop]").firstOrNull { "author" in it.attr("itemprop").split(' ') }
            val author = authorScope?.let { prop(it, "name").ifBlank { elementValue(it) } }.orEmpty()
            row(prop(review, "reviewBody"), rating, prop(review, "name"), prop(review, "datePublished"), author, sourceLink(prop(review, "url"), url))
        }
        if (rows.none { it["text"].orEmpty().isNotBlank() }) return null
        return ParsedReviewPage(owner?.let { metadata(prop(it, "name")) }?.takeIf { it.isNotBlank() } ?: pageName(doc, url), platform, rows,
            listOf("Reviews were extracted from the page's schema.org review markup. Ratings on scales other than 1–5 are left missing."))
    }

    private fun embeddedWidget(doc: Document, url: String, platform: String): ParsedReviewPage? {
        // Some storefronts serialize their current product's public Judge.me widget in Next.js
        // page data. Read these explicit product fields only, never unrelated recommendation data.
        val props = doc.select("script#__NEXT_DATA__").firstNotNullOfOrNull { readJson(it.data())?.path("props")?.path("pageProps") } ?: return null
        val html = scalar(props.path("product").path("metafields").path("judgemeWidget")).ifBlank {
            scalar(props.path("metafields").path("judgemeWidget"))
        }
        if (html.isBlank() || html.length > 512 * 1024) return null
        val result = visibleWidgets(Jsoup.parse(html, url), url, platform) ?: return null
        return result.copy(entity = pageName(doc, url), warnings = listOf("Reviews were extracted from the current product's public embedded review widget; additional review requests were not fetched."))
    }

    private fun visibleWidgets(doc: Document, url: String, platform: String): ParsedReviewPage? {
        data class Widget(val cards: String, val body: String, val title: String, val author: String, val date: String, val rating: String)
        val widgets = listOf(
            Widget("#reviews .commentlist .review, #reviews ol.commentlist li", ".description", "", ".woocommerce-review__author, .meta strong", "time[datetime]", ".star-rating"),
            Widget(".jdgm-rev", ".jdgm-rev__body", ".jdgm-rev__title", ".jdgm-rev__author", ".jdgm-rev__timestamp", ".jdgm-rev__rating"),
            Widget("[data-hook=review]", "[data-hook=review-body]", "[data-hook=review-title]", ".a-profile-name", "[data-hook=review-date]", "[data-hook=review-star-rating], [data-hook=cmps-review-star-rating]")
        )
        for (widget in widgets) {
            val cards = doc.select(widget.cards).filter { it.selectFirst(widget.body) != null }.take(MAX_RECORDS)
            if (cards.isEmpty()) continue
            if (widget.cards == ".jdgm-rev") {
                // Multiple copies of one product widget are harmless (normalization deduplicates
                // their texts), but a storefront can also embed widgets for related products.
                val scopes = cards.map { card -> card.parents().firstOrNull { it.hasClass("jdgm-review-widget") } }
                val scopeKeys = scopes.map { scope ->
                    if (scope == null) "unscoped" else {
                        val productId = scope.attr("data-id").ifBlank { scope.attr("data-product-id") }
                        if (productId.isNotBlank()) "product:$productId" else "widget:${scopes.indexOf(scope)}"
                    }
                }.distinct()
                if (scopeKeys.size > 1) throw ApiError(422, "scrape_ambiguous_reviews", "This page contains review widgets for several products. Open a specific product review page, or upload a CSV.")
            }
            val rows = cards.map { card ->
                checkInterrupted()
                val star = card.selectFirst(widget.rating)
                val rating = when {
                    star?.hasAttr("data-score") == true -> star.attr("data-score")
                    star?.selectFirst(".rating") != null -> star.selectFirst(".rating")!!.text()
                    else -> Regex("(?i)([1-5](?:\\.0)?)\\s+(?:out of 5|of 5|stars?)(?:\\b|$)").find(star?.attr("aria-label").orEmpty().ifBlank { star?.text().orEmpty() })?.groupValues?.get(1).orEmpty()
                }
                val date = card.selectFirst(widget.date)?.let { if (it.hasAttr("datetime")) it.attr("datetime") else if (it.hasAttr("data-content")) it.attr("data-content") else "" }.orEmpty()
                row(card.selectFirst(widget.body)?.wholeText().orEmpty(), rating,
                    if (widget.title.isBlank()) "" else card.selectFirst(widget.title)?.wholeText().orEmpty(),
                    date, card.selectFirst(widget.author)?.wholeText().orEmpty(), url)
            }
            if (rows.any { it["text"].orEmpty().isNotBlank() }) return ParsedReviewPage(pageName(doc, url), platform, rows,
                listOf("Reviews were extracted from recognizable review cards in the downloaded page; dynamically loaded reviews were not fetched."))
        }
        return null
    }

    /** Item properties must belong to this scope, not a nested author/product/review. */
    private fun prop(scope: Element, property: String): String {
        val element = scope.select("[itemprop]").firstOrNull { element ->
            element != scope && property in element.attr("itemprop").split(Regex("\\s+")) &&
                element.parents().takeWhile { it != scope }.none { it.hasAttr("itemscope") }
        }
        return element?.let(::elementValue).orEmpty()
    }
    private fun elementValue(element: Element): String = when {
        element.hasAttr("content") -> element.attr("content")
        element.hasAttr("datetime") -> element.attr("datetime")
        element.tagName() == "a" || element.tagName() == "link" -> element.attr("href")
        else -> element.wholeText()
    }
    private fun row(text: String, rating: String, title: String, date: String, author: String, url: String) =
        mapOf("text" to text, "rating" to rating, "title" to title, "date" to isoDate(date), "author" to author, "source_url" to url)
    private fun isoDate(value: String): String {
        val candidate = value.trim()
        if (Regex("\\d{4}-\\d{2}-\\d{2}(?:T.*| \\d{2}:\\d{2}:\\d{2}(?: UTC)?)?").matches(candidate)) {
            try { return LocalDate.parse(candidate.take(10)).toString() } catch (_: Exception) { }
        }
        return candidate
    }
    private fun personName(value: JsonNode): String = if (value.isArray) value.take(5).map(::personName).filter { it.isNotBlank() }.joinToString(", ") else if (value.isObject) scalar(value.path("name")) else scalar(value)
    private fun isReview(node: JsonNode): Boolean {
        val types = node.path("@type")
        return (if (types.isArray) types.toList() else listOf(types)).any { scalar(it).trimEnd('/').substringAfterLast('/') in reviewTypes }
    }
    private fun scalar(node: JsonNode?): String = node?.takeIf { it.isTextual || it.isNumber }?.asText().orEmpty()
    private fun readJson(value: String): JsonNode? = try { mapper.readTree(value) } catch (_: Exception) { null }
    private fun pageName(doc: Document, url: String): String = metadata(doc.selectFirst("h1")?.text().orEmpty()).ifBlank {
        metadata(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
    }.ifBlank { metadata(doc.title()) }.ifBlank { metadata(URI(url).host.removePrefix("www.")) }
    private fun metadata(value: String): String = value.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().let { text ->
        if (text.codePointCount(0, text.length) <= 160) text else text.substring(0, text.offsetByCodePoints(0, 160))
    }
    private fun sameName(a: String, b: String): Boolean = a.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)
    private fun samePage(candidate: String, base: String): Boolean = try {
        val a = URI(base).resolve(candidate); val b = URI(base)
        candidate.isNotBlank() && a.host?.lowercase()?.removePrefix("www.") == b.host.lowercase().removePrefix("www.") && a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    } catch (_: Exception) { false }
    private fun sourceLink(candidate: String?, base: String): String = try {
        val target = URI(base).resolve(candidate.orEmpty())
        if (candidate.isNullOrBlank() || target.scheme !in setOf("http", "https") || target.rawUserInfo != null || target.port !in listOf(-1, 80, 443) || target.host?.lowercase() != URI(base).host.lowercase() || target.toString().length > 2048) base else target.toString()
    } catch (_: Exception) { base }
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw ApiError(499, "cancelled", "Import stopped.") }
}

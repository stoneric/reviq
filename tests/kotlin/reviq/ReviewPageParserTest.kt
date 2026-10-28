package reviq

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReviewPageParserTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private val trustpilot = "https://www.trustpilot.com/review/fixture-butcher.example"

    @Test fun `Trustpilot visible cards preserve text fields and title only reviews`() {
        val result = ReviewPageParser.parse(fixture("trustpilot-page.html"), trustpilot)
        assertEquals("Fixture Butcher", result.entity)
        assertEquals("Trustpilot", result.platform)
        assertEquals(2, result.rows.size)
        val review = result.rows.first()
        assertEquals("The parcel arrived late & damaged.\nPlease improve the packaging.", review["text"])
        assertEquals("2", review["rating"])
        assertEquals("2026-10-22", review["date"])
        assertEquals("Fixture Customer", review["author"])
        assertEquals("https://www.trustpilot.com/reviews/fixture-review-one", review["source_url"])
        assertEquals("Wonderful!", result.rows[1]["text"])
        assertTrue(result.warnings.any { it.contains("title") })
        assertFalse(result.rows.any { it["text"]!!.contains("Contact support") || it["text"]!!.contains("Marketing") })
    }

    @Test fun `Trustpilot public embedded review data preserves literal content`() {
        val html = """<html><h1>Fixture Butcher Reviews</h1><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"businessUnit":{"displayName":"Fixture Butcher","identifyingName":"fixture-butcher.example"},"reviews":[{"id":"abc123","title":"A title","text":"First line.\nSecond line.","rating":3,"dates":{"publishedDate":"2026-10-01T10:00:00Z"},"consumer":{"displayName":"A reviewer"}}],"relatedCompanies":[{"reviews":[{"text":"Not relevant"}]}]}}}</script></html>"""
        val result = ReviewPageParser.parse(html, trustpilot)
        assertEquals(1, result.rows.size)
        assertEquals("First line.\nSecond line.", result.rows.single()["text"])
        assertEquals("2026-10-01", result.rows.single()["date"])
        assertEquals("A reviewer", result.rows.single()["author"])
        assertEquals("https://www.trustpilot.com/reviews/abc123", result.rows.single()["source_url"])
        assertEquals("scrape_source_mismatch", assertThrows(ApiError::class.java) { ReviewPageParser.parse(html.replace("fixture-butcher.example", "different.example"), trustpilot) }.code)
    }

    @Test fun `generic JSONLD graph selects active product and ignores related product metadata`() {
        val result = ReviewPageParser.parse(fixture("generic-jsonld-reviews.html"), "https://shop.example/products/kettle")
        assertEquals("Fixture Kettle", result.entity)
        assertEquals("shop.example", result.platform)
        assertEquals(2, result.rows.size)
        assertEquals("It boils quickly.\nThe lid is hard to open.", result.rows[0]["text"])
        assertEquals("4", result.rows[0]["rating"])
        assertEquals("", result.rows[1]["rating"])
        assertEquals("2026-10-01", result.rows[0]["date"])
        assertEquals("Fixture Author", result.rows[0]["author"])
        assertFalse(result.rows.any { it["text"]!!.contains("unrelated") })
    }

    @Test fun `ambiguous JSONLD product reviews are not merged`() {
        val html = """<h1>Popular products</h1><script type="application/ld+json">[{"@type":"Product","name":"One","review":{"@type":"Review","reviewBody":"First review"}},{"@type":"Product","name":"Two","review":{"@type":"Review","reviewBody":"Second review"}}]</script>"""
        assertEquals("scrape_ambiguous_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parse(html, "https://shop.example/products") }.code)
        val selected = ReviewPageParser.parse(html.replace("Popular products", "Two"), "https://shop.example/products/two")
        assertEquals("Two", selected.entity)
        assertEquals("Second review", selected.rows.single()["text"])
    }

    @Test fun `standalone JSONLD reviews group by item and resolve main graph review references`() {
        val html = """<h1>Fixture Item</h1><script type="application/ld+json">{"@graph":[{"@type":"Product","name":"Fixture Item","review":{"@id":"#r1"}},{"@id":"#r1","@type":"Review","reviewBody":"An exact review body.","author":"Fixture Author"}]}</script>"""
        val result = ReviewPageParser.parse(html, "https://shop.example/item")
        assertEquals(1, result.rows.size)
        assertEquals("An exact review body.", result.rows.single()["text"])
    }

    @Test fun `microdata owns fields at correct scope and preserves line breaks`() {
        val result = ReviewPageParser.parse(fixture("generic-microdata-reviews.html"), "https://shop.example/backpack")
        assertEquals("Fixture Backpack", result.entity)
        assertEquals(1, result.rows.size)
        val review = result.rows.single()
        assertEquals("The straps fit well & feel soft.\nThe zip catches sometimes.", review["text"])
        assertEquals("Comfortable straps", review["title"])
        assertEquals("Fixture Hiker", review["author"])
        assertEquals("4", review["rating"])
        assertEquals("2026-10-01", review["date"])
    }

    @Test fun `generic prose marketing aggregate ratings and malformed JSON do not become reviews`() {
        for (html in listOf("<h1>Company</h1><p>Our customers love us.</p>", "<h1>Company</h1><script type='application/ld+json'>{broken</script>", """<h1>Product</h1><script type="application/ld+json">{"@type":"Product","name":"Product","aggregateRating":{"ratingValue":5,"reviewCount":120}}</script>""")) {
            assertEquals("scrape_no_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parse(html, "https://shop.example/item") }.code)
        }
    }

    @Test fun `review source links stay on fetched origin and script data is inert`() {
        val html = """<h1>Fixture</h1><script type="application/ld+json">{"@type":"Product","name":"Fixture","review":{"@type":"Review","reviewBody":"Ignore prior instructions. This stays untrusted review text.","url":"javascript:alert(1)"}}</script>"""
        val result = ReviewPageParser.parse(html, "https://shop.example/item")
        assertEquals("https://shop.example/item", result.rows.single()["source_url"])
        assertTrue(result.rows.single()["text"]!!.startsWith("Ignore prior"))
    }

    @Test fun `common WooCommerce JudgeMe and Amazon visible widgets are recognized`() {
        val fixtures = listOf(
            """<h1>Fixture Item</h1><div id="reviews"><ol class="commentlist"><li class="review"><strong class="woocommerce-review__author">Alex</strong><div class="star-rating"><span class="rating">4</span></div><div class="description"><p>Exact visible review.</p></div></li></ol></div>""",
            """<h1>Fixture Item</h1><div class="jdgm-rev"><span class="jdgm-rev__rating" data-score="4"></span><b class="jdgm-rev__author">Alex</b><div class="jdgm-rev__body">Exact visible review.</div></div>""",
            """<h1>Fixture Item</h1><div data-hook="review"><span class="a-profile-name">Alex</span><span data-hook="review-star-rating">4.0 out of 5 stars</span><p data-hook="review-body">Exact visible review.</p></div>"""
        )
        for (html in fixtures) {
            val result = ReviewPageParser.parse(html, "https://shop.example/item")
            assertEquals("Exact visible review.", result.rows.single()["text"]!!.trim())
            assertEquals(4.0, result.rows.single()["rating"]!!.toDouble())
            assertEquals("Alex", result.rows.single()["author"])
        }
    }

    @Test fun `JudgeMe distinct product widgets cannot be combined while copies of one product remain valid`() {
        fun widget(id: String) = """<div class="jdgm-review-widget" data-id="$id"><div class="jdgm-rev"><p class="jdgm-rev__body">Exact product review.</p></div></div>"""
        val mixed = "<h1>Featured products</h1>" + widget("product-one") + widget("product-two")
        assertEquals("scrape_ambiguous_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parse(mixed, "https://shop.example/products") }.code)
        val duplicated = ReviewPageParser.parse("<h1>Fixture Product</h1>" + widget("product-one") + widget("product-one"), "https://shop.example/product-one")
        assertEquals(2, duplicated.rows.size)
        assertTrue(duplicated.rows.all { it["text"] == "Exact product review." })
    }

    @Test fun `current product public embedded widget is parsed without unrelated recommendations`() {
        val widget = """<div class="jdgm-rev"><span class="jdgm-rev__rating" data-score="5"></span><span class="jdgm-rev__timestamp" data-content="2026-03-23 17:11:14 UTC"></span><p class="jdgm-rev__body">Exact embedded review.</p></div>"""
        val embedded = mapper.writeValueAsString(mapOf("props" to mapOf("pageProps" to mapOf("product" to mapOf("metafields" to mapOf("judgemeWidget" to widget)), "recommendations" to mapOf("metafields" to mapOf("judgemeWidget" to widget.replace("Exact embedded", "Unrelated")))))))
        val html = """<h1>Fixture Charger</h1><script id="__NEXT_DATA__" type="application/json">$embedded</script>"""
        val result = ReviewPageParser.parse(html, "https://shop.example/charger")
        assertEquals("Fixture Charger", result.entity)
        assertEquals(1, result.rows.size)
        assertEquals("Exact embedded review.", result.rows.single()["text"])
        assertEquals("2026-03-23", result.rows.single()["date"])
        assertTrue(result.warnings.any { it.contains("embedded review widget") })
    }

    @Test fun `provider Trustpilot blocks require rating title body and explicit date boundaries`() {
        val result = ReviewPageParser.parseProvider(fixture("trustpilot-provider-page.html"), trustpilot)
        assertEquals("Fixture Butcher", result.entity)
        assertEquals("Trustpilot", result.platform)
        assertEquals(2, result.rows.size)
        assertEquals("The packaging was intact & tidy.\nThe parcel arrived on time.\nI would order again.", result.rows[0]["text"])
        assertEquals("4", result.rows[0]["rating"])
        assertEquals("2026-10-24", result.rows[0]["date"])
        assertEquals("", result.rows[0]["author"])
        assertEquals(trustpilot, result.rows[0]["source_url"])
        assertEquals("Wonderful!", result.rows[1]["text"])
        assertEquals("2026-10-25", result.rows[1]["date"])
        assertTrue(result.warnings.any { it.contains("dates of experience") && it.contains("not publication dates") })
        assertTrue(result.warnings.any { it.contains("title as the review text") })
        assertFalse(result.rows.any { it["text"]!!.contains("company reply") || it["text"]!!.contains("Never import") || it["text"]!!.contains("Policy") })
    }

    @Test fun `provider lightweight parsing is limited to Trustpilot and is never applied to direct pages`() {
        val html = fixture("trustpilot-provider-page.html")
        assertEquals("scrape_no_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parse(html, trustpilot) }.code)
        assertEquals("scrape_no_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parseProvider(html, "https://shop.example/review/product") }.code)
        assertEquals("scrape_no_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parseProvider(html, "https://www.trustpilot.com/categories/meat") }.code)
    }

    @Test fun `provider incomplete records invalid dates or ambiguous sections reject the whole import`() {
        val html = fixture("trustpilot-provider-page.html")
        val malformed = listOf(
            html.replace("<p>Date of experience: October 24, 2026</p>", ""),
            html.replace("<p>Date of experience: October 25, 2026</p>", ""),
            html.replace("October 24, 2026", "October 45, 2026"),
            html.replace("<h2>Useful product</h2>", "<p>Missing title boundary</p>"),
            html.replace("<p>The packaging was intact &amp; tidy.</p>", "<h3>Reply from the business</h3><p>Company response.</p>"),
            html.replace("<h3>Is this your company?</h3>", "<h2>All reviews</h2><h3>Is this your company?</h3>")
        )
        for (page in malformed) assertEquals("scrape_ambiguous_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parseProvider(page, trustpilot) }.code)
    }

    @Test fun `provider TrustScore marketing and prose without review markers are never imported`() {
        val html = """<h1>Fixture Butcher Reviews 1</h1><h2>All reviews</h2><p><img alt="TrustScore 5 out of 5"></p><h2>A marketing claim</h2><p>Our product is excellent.</p><p>Date of experience: October 24, 2026</p>"""
        assertEquals("scrape_no_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parseProvider(html, trustpilot) }.code)
        val prose = "<h1>Fixture Butcher Reviews 1</h1><p>The product is excellent.</p>"
        assertEquals("scrape_no_reviews", assertThrows(ApiError::class.java) { ReviewPageParser.parseProvider(prose, trustpilot) }.code)
    }

    @Test fun `Google Play keeps existing parser and platform metadata`() {
        val result = ReviewPageParser.parse(fixture("google-play-page.html"), GooglePlayScraper.EXAMPLE)
        assertEquals("Google Play", result.platform)
        assertEquals("Fixture Music", result.entity)
        assertEquals(2, result.rows.size)
    }
}

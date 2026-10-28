package reviq

import okhttp3.Dns
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class PublicUrlNetworkTest {
    private val page="""<html><head><script type="application/ld+json">{"@type":"Product","name":"Fixture Product","review":[{"@type":"Review","reviewBody":"Original fixture review.","reviewRating":{"ratingValue":4},"author":{"name":"Fixture author"}}]}</script></head><body></body></html>"""
    private fun deadline()=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
    @Test fun publicDomainsHttpHttpsFragmentsAndStandardPortsAreAccepted() {
        assertEquals("https://www.trustpilot.com/review/allenbrothers.com",ReviewScraper.canonicalUrl("https://www.trustpilot.com/review/allenbrothers.com#reviews"))
        assertEquals("http://shop.example.com/reviews?q=coffee",ReviewScraper.canonicalUrl("http://shop.example.com:80/reviews?q=coffee#latest"))
        assertEquals("https://shop.example.com/reviews",ReviewScraper.canonicalUrl("https://shop.example.com:443/reviews"))
        assertEquals("http://8.8.8.8/",ReviewScraper.canonicalUrl("http://8.8.8.8"))
        assertEquals("https://[2606:4700:4700::1111]/",ReviewScraper.canonicalUrl("https://[2606:4700:4700::1111]/"))
        assertEquals("https://play.google.com/store/apps/details?id=com.spotify.music&hl=en&gl=US",ReviewScraper.canonicalUrl("https://play.google.com/store/apps/details?id=com.spotify.music#reviews"))
    }
    @Test fun privateAddressesAlternativeIpLiteralsAndCredentialsRejectBeforeFetch() {
        val invalid=listOf("http://localhost/","http://service.internal/","http://intranet/","http://127.0.0.1/","http://127.1/","http://2130706433/","http://0x7f000001/","http://0177.0.0.1/","http://0x7f.0.0.1/","http://127.0.0.1./","http://%31%32%37.0.0.1/","http://169.254.169.254/latest/meta-data/","http://10.1.2.3/","http://172.31.1.2/","http://192.168.1.1/","http://100.64.0.1/","http://198.18.1.1/","http://192.0.2.1/","http://198.51.100.1/","http://203.0.113.1/","http://224.0.0.1/","https://[::1]/","https://[::ffff:127.0.0.1]/","https://[fe80::1]/","https://[fe80::1%25eth0]/","https://[2001:db8::1]/","https://[2002:7f00:1::1]/","https://user:secret@shop.example.com/","https://@shop.example.com/","https://shop.example.com:8080/","http://shop.example.com:443/","https://shop.example.com:80/","ftp://shop.example.com/","file:///etc/passwd","https://shop.example.com\\@127.0.0.1/","https://shop.example.com/\nInjected")
        val calls=AtomicInteger()
        val scraper=ReviewScraper(PageFetcher { _,_,_,_ -> calls.incrementAndGet(); FetchedPage(200,page) })
        invalid.forEach { url -> val error=assertThrows(ApiError::class.java) { scraper.scrape(url) }; assertEquals("unsupported_url",error.code,url); assertEquals(400,error.status,url) }
        assertEquals(0,calls.get())
    }
    @Test fun checkedDnsPassesOnlyValidatedActualAddressesAndRejectsRebinding() {
        val public=InetAddress.getByName("93.184.215.14"); val private=InetAddress.getByName("127.0.0.1")
        val calls=AtomicInteger()
        val resolver=object: Dns { override fun lookup(hostname: String): List<InetAddress> = if(calls.incrementAndGet()==1) listOf(public) else listOf(private) }
        val dns=PublicPageFetcher.checkedDns("shop.example.com",deadline(),resolver)
        assertSame(public,dns.lookup("shop.example.com").single())
        assertThrows(UnknownHostException::class.java) { dns.lookup("shop.example.com") }
        assertEquals(2,calls.get())
        assertThrows(UnknownHostException::class.java) { dns.lookup("unexpected.example.com") }
        assertEquals(2,calls.get())
        val mixed=PublicPageFetcher.checkedDns("shop.example.com",deadline(),object: Dns { override fun lookup(hostname: String)=listOf(public,private) })
        assertThrows(UnknownHostException::class.java) { mixed.lookup("shop.example.com") }
    }
    @Test fun dnsResolutionHonorsTheSameOverallDeadline() {
        val started=System.nanoTime()
        val dns=PublicPageFetcher.checkedDns("shop.example.com",started+TimeUnit.MILLISECONDS.toNanos(100),object: Dns {
            override fun lookup(hostname: String): List<InetAddress> { Thread.sleep(5000); return listOf(InetAddress.getByName("93.184.215.14")) }
        })
        assertThrows(TimeoutException::class.java) { dns.lookup("shop.example.com") }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
    }
    @Test fun boundedRelativeAndCrossHostRedirectsUseFinalSourceProvenance() {
        val seen=mutableListOf<String>(); val deadlines=mutableSetOf<Long>()
        val scraper=ReviewScraper(PageFetcher { url,end,_,_ ->
            seen+=url; deadlines+=end
            when(seen.size) { 1->FetchedPage(301,"",location="/reviews"); 2->FetchedPage(302,"",location="https://reviews.example.com/product#recent"); else->FetchedPage(200,page) }
        })
        val data=scraper.scrape("http://shop.example.com/start")
        assertEquals(listOf("http://shop.example.com/start","http://shop.example.com/reviews","https://reviews.example.com/product"),seen)
        assertEquals(1,deadlines.size)
        assertEquals("https://reviews.example.com/product",data.source_url)
        assertEquals("reviews.example.com",data.platform)
        assertEquals("Original fixture review.",data.reviews.single().text)
        assertEquals(2,data.summary.pages["redirects"])
        assertEquals("scraped",data.mode); assertNotNull(data.captured_at)
    }
    @Test fun everyRedirectRejectsUnsafeDestinationsBeforeConnecting() {
        listOf("http://127.0.0.1/","http://169.254.169.254/","https://[::1]/","http://2130706433/","https://user:pass@shop.example.com/","http://shop.example.com:8080/").forEach { target ->
            val calls=AtomicInteger()
            val scraper=ReviewScraper(PageFetcher { _,_,_,_ -> calls.incrementAndGet(); FetchedPage(302,"",location=target) })
            assertEquals("unsupported_url",assertThrows(ApiError::class.java) { scraper.scrape("https://shop.example.com/reviews") }.code)
            assertEquals(1,calls.get(),target)
        }
    }
    @Test fun redirectHostnameResolvingPrivateNeverConnects() {
        val connections=AtomicInteger()
        val resolver=object: Dns { override fun lookup(hostname: String)=listOf(InetAddress.getByName(if(hostname=="shop.example.com") "93.184.215.14" else "10.0.0.1")) }
        val scraper=ReviewScraper(PageFetcher { url,end,_,_ ->
            val host=java.net.URI(url).host
            PublicPageFetcher.checkedDns(host,end,resolver).lookup(host)
            connections.incrementAndGet()
            FetchedPage(302,"",location="https://rebound.example.com/reviews")
        })
        assertEquals("scrape_fetch_failure",assertThrows(ApiError::class.java) { scraper.scrape("https://shop.example.com/reviews") }.code)
        assertEquals(1,connections.get())
    }
    @Test fun redirectLoopsAndExcessiveChainsAreBounded() {
        val looping=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(302,"",location="/reviews#loop") })
        assertEquals("scrape_redirect_loop",assertThrows(ApiError::class.java) { looping.scrape("https://shop.example.com/reviews") }.code)
        val calls=AtomicInteger()
        val chain=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(302,"",location="/next/${calls.incrementAndGet()}") })
        assertEquals("scrape_redirect_limit",assertThrows(ApiError::class.java) { chain.scrape("https://shop.example.com/reviews") }.code)
        assertEquals(4,calls.get())
    }
    @Test fun redirectHopsDoNotRestartDeadline() {
        val calls=AtomicInteger(); val deadlines=mutableSetOf<Long>()
        val scraper=ReviewScraper(PageFetcher { _,end,_,_ -> deadlines+=end; Thread.sleep(70); FetchedPage(302,"",location="/next/${calls.incrementAndGet()}") },170)
        val started=System.nanoTime()
        assertEquals("scrape_timeout",assertThrows(ApiError::class.java) { scraper.scrape("https://shop.example.com/reviews") }.code)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
        assertEquals(1,deadlines.size)
    }
    @Test fun blockedSourcesFailHonestlyWithoutParserOrFallbackContent() {
        val scraper=ReviewScraper(PageFetcher { _,_,_,_ -> FetchedPage(403,"<h1>Verifying connection</h1>") })
        val error=assertThrows(ApiError::class.java) { scraper.scrape("https://www.trustpilot.com/review/allenbrothers.com") }
        assertEquals("scrape_blocked",error.code); assertTrue(error.message.contains("cannot bypass"))
    }
}

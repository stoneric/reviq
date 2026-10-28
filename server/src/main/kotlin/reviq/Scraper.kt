package reviq

import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class FetchedPage(val status: Int, val body: String, val contentType: String = "text/html")
internal fun interface PageFetcher {
    fun fetch(url: String, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>): FetchedPage
}
internal object ScrapeRuntime {
    private fun pool(name: String)=ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,SynchronousQueue(),ThreadFactory { task -> Thread(task,name).apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    val jobs=pool("reviq-scrape")
    val dns=pool("reviq-scrape-dns")
}
internal object PublicPageFetcher: PageFetcher {
    const val MAX_BODY_BYTES=2*1024*1024
    fun remaining(deadline: Long): Long = (deadline-System.nanoTime()).also { if(it<=0) throw ApiError(504,"scrape_timeout","The source did not complete within 15 seconds. Try CSV upload or pasted reviews.") }
    fun client(deadline: Long): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .callTimeout(remaining(deadline),TimeUnit.NANOSECONDS)
        .connectTimeout(remaining(deadline),TimeUnit.NANOSECONDS)
        .readTimeout(remaining(deadline),TimeUnit.NANOSECONDS)
        .dns(object: Dns { override fun lookup(host: String): List<InetAddress> {
            if(host!="play.google.com") throw java.net.UnknownHostException("Host is not allowed")
            val task=try { ScrapeRuntime.dns.submit<List<InetAddress>> { Dns.SYSTEM.lookup(host) } } catch(_: RejectedExecutionException) { throw java.net.UnknownHostException("DNS resolver is busy") }
            val addresses=try { task.get(remaining(deadline),TimeUnit.NANOSECONDS) } finally { task.cancel(true) }
            if(addresses.isEmpty() || addresses.any { !GooglePlayScraper.publicAddress(it) }) throw java.net.UnknownHostException("Destination is not public")
            // These exact checked InetAddress values are handed to OkHttp's actual connector.
            return addresses
        } }).build()
    override fun fetch(url: String, deadline: Long, cancelled: AtomicBoolean, activeCall: AtomicReference<Call?>): FetchedPage {
        if(cancelled.get()) throw ApiError(499,"cancelled","Import stopped.")
        val client=client(deadline)
        try {
            val request=Request.Builder().url(url).header("User-Agent","Reviq/1.0 (+https://reviq.xyz)").header("Accept","text/html").build()
            val call=client.newCall(request); activeCall.set(call)
            if(cancelled.get()) { call.cancel(); throw ApiError(499,"cancelled","Import stopped.") }
            call.execute().use { response ->
                if(response.code!=200) return FetchedPage(response.code,"")
                val type=response.header("Content-Type").orEmpty()
                if(!type.lowercase().startsWith("text/html")) return FetchedPage(response.code,"",type)
                if(response.header("Content-Encoding")!=null) throw ApiError(422,"scrape_encoding","The source returned an unsupported response encoding. Try CSV upload or paste.")
                val body=response.body ?: throw ApiError(422,"scrape_empty","The source returned no page. Try CSV upload or paste.")
                // OkHttp transparently decompresses gzip; this bounds the DECOMPRESSED bytes.
                val bytes=readBounded(body.byteStream(),MAX_BODY_BYTES) { remaining(deadline); if(cancelled.get()) throw ApiError(499,"cancelled","Import stopped.") }
                return FetchedPage(response.code,bytes.toString(Charsets.UTF_8),type)
            }
        } finally { activeCall.set(null); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    fun readBounded(input: InputStream, limit: Int, check: ()->Unit = {}): ByteArray {
        val output=ByteArrayOutputStream(); val chunk=ByteArray(8192)
        while(true) {
            check(); val count=input.read(chunk); if(count<0) break
            if(output.size()+count>limit) throw ApiError(413,"scrape_body_limit","The source page exceeded the 2 MiB download limit. No partial page was analyzed. Try CSV upload or paste.")
            output.write(chunk,0,count)
        }
        return output.toByteArray()
    }
}
internal class GooglePlayScraper(private val fetcher: PageFetcher=PublicPageFetcher, private val timeoutMillis: Long=15000) {
    fun scrape(rawUrl: String, cancelled: AtomicBoolean = AtomicBoolean(false)): Dataset {
        val canonical=canonicalUrl(rawUrl)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val activeCall=AtomicReference<Call?>(null)
        val task=try { ScrapeRuntime.jobs.submit<Dataset> {
            val page=fetcher.fetch(canonical,deadline,cancelled,activeCall)
            checkActive(deadline,cancelled)
            if(page.status in 300..399) throw ApiError(422,"scrape_redirect","The source redirected this request. Redirects are not followed; use CSV upload or paste instead.")
            if(page.status==403 || page.status==429) throw ApiError(422,"scrape_blocked","Google Play blocked or limited this request. Try CSV upload or paste; no access restrictions were bypassed.")
            if(page.status==404) throw ApiError(422,"scrape_not_found","This app was not found in the US Google Play storefront. Check its app URL, or use CSV upload or paste.")
            if(page.status!=200) throw ApiError(422,"scrape_source_error","The review source is unavailable (HTTP ${page.status}). Try CSV upload or paste.")
            if(!page.contentType.lowercase().startsWith("text/html")) throw ApiError(422,"scrape_parse_failure","The source did not return an HTML app page. Try CSV upload or paste.")
            val captured=Instant.now().toString()
            val (entity,rows)=parse(page.body,canonical)
            checkActive(deadline,cancelled)
            val dataset=Normalize.scraped(entity,rows,canonical,captured)
            checkActive(deadline,cancelled)
            dataset
        } } catch(_: RejectedExecutionException) { throw ApiError(503,"scrape_busy","Two review imports are already running. Wait a moment, or use CSV upload or paste.") }
        try {
            while(true) {
                if(cancelled.get()) throw ApiError(499,"cancelled","Import stopped.")
                try { return task.get(minOf(PublicPageFetcher.remaining(deadline),TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS) }
                catch(_: TimeoutException) { if(System.nanoTime()>=deadline) throw timeout() }
            }
        } catch(e: ExecutionException) {
            val cause=e.cause
            if(cause is ApiError) throw cause
            if(cancelled.get()) throw ApiError(499,"cancelled","Import stopped.")
            if(System.nanoTime()>=deadline || cause is java.net.SocketTimeoutException || cause is java.io.InterruptedIOException || cause is TimeoutException) throw timeout()
            throw ApiError(422,"scrape_fetch_failure","The source could not be safely fetched. Try again, or use CSV upload or pasted reviews.")
        } catch(_: TimeoutException) { throw timeout() }
        catch(_: InterruptedException) { Thread.currentThread().interrupt(); throw ApiError(499,"cancelled","Import stopped.") }
        finally { activeCall.getAndSet(null)?.cancel(); task.cancel(true) }
    }
    private fun timeout()=ApiError(504,"scrape_timeout","The review import exceeded its 15-second deadline. Your previous dataset is unchanged. Try CSV upload or paste.")
    private fun checkActive(deadline: Long, cancelled: AtomicBoolean) {
        PublicPageFetcher.remaining(deadline)
        if(cancelled.get() || Thread.currentThread().isInterrupted) throw ApiError(499,"cancelled","Import stopped.")
    }
    companion object {
        const val EXAMPLE="https://play.google.com/store/apps/details?id=com.spotify.music"
        fun canonicalUrl(value: String): String {
            fun invalid(): Nothing=throw ApiError(400,"unsupported_url","Use a Google Play app URL such as https://play.google.com/store/apps/details?id=com.spotify.music. Other hosts and private addresses are not supported.")
            if(value.length>2048 || value.isBlank()) invalid()
            val uri=try { URI(value.trim()) } catch(_: Exception) { invalid() }
            if(uri.scheme!="https" || uri.host?.lowercase()!="play.google.com" || uri.rawUserInfo!=null || uri.port !in listOf(-1,443) || uri.rawPath!="/store/apps/details" || uri.rawFragment!=null) invalid()
            val pairs=try { uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair -> val p=pair.split('=',limit=2); URLDecoder.decode(p[0],StandardCharsets.UTF_8) to URLDecoder.decode(p.getOrElse(1){""},StandardCharsets.UTF_8) } } catch(_: Exception) { invalid() }
            val ids=pairs.filter { it.first=="id" }
            if(ids.size!=1 || pairs.any { it.first !in setOf("id","hl","gl","pcampaignid","pli") }) invalid()
            val id=ids.single().second
            if(id.length>255 || !Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(id)) invalid()
            return "https://play.google.com/store/apps/details?id=$id&hl=en&gl=US"
        }
        fun publicAddress(address: InetAddress): Boolean {
            if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b=address.address.map { it.toInt() and 255 }
            if(b.size==4) {
                val a=b[0]; val c=b[1]
                return !(a==0 || a==10 || a==127 || a>=224 || (a==100 && c in 64..127) || (a==169 && c==254) || (a==172 && c in 16..31) || (a==192 && c==168) || (a==192 && c==0) || (a==192 && c==88 && b[2]==99) || (a==198 && c in 18..19) || (a==198 && c==51 && b[2]==100) || (a==203 && c==0 && b[2]==113))
            }
            if(b.size!=16 || (b[0] and 224)!=32) return false // only global unicast 2000::/3
            if(b[0]==32 && b[1]==1 && (b[2]<2 || (b[2]==13 && b[3]==184))) return false // IETF special uses and documentation
            if(b[0]==32 && b[1]==2) return false // 6to4 can embed private IPv4
            if(b[0]==63 && b[1]==255 && (b[2] and 240)==0) return false // documentation
            return true
        }
        fun parse(html: String, canonical: String): Pair<String,List<Map<String,String>>> {
            val doc=Jsoup.parse(html,canonical)
            val name=doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
                try { val json=mapper.readTree(script.data()); if(json.path("@type").asText()=="SoftwareApplication") json.path("name").asText().takeIf { it.isNotBlank() } else null } catch(_: Exception) { null }
            } ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: throw ApiError(422,"scrape_parse_failure","The app name could not be verified from this page. Try CSV upload or paste.")
            val dateFormat=DateTimeFormatter.ofPattern("MMMM d, uuuu",Locale.US).withResolverStyle(ResolverStyle.STRICT)
            val rows=doc.select("div.EGFGHd").map { card ->
                val body=card.selectFirst(".h3YV2d")
                val ratingLabel=card.selectFirst("[role=img][aria-label^=Rated]")?.attr("aria-label").orEmpty()
                val rating=Regex("^Rated ([1-5]) stars? out of five stars$").find(ratingLabel)?.groupValues?.get(1).orEmpty()
                val rawDate=card.selectFirst(".bp9Aid")?.text().orEmpty()
                val date=try { LocalDate.parse(rawDate,dateFormat).toString() } catch(_: Exception) { rawDate }
                mapOf("text" to body?.wholeText().orEmpty(),"rating" to rating,"date" to date,"author" to card.selectFirst(".X5PpBb")?.text().orEmpty(),"source_url" to canonical)
            }
            if(rows.isEmpty()) throw ApiError(422,"scrape_no_reviews","No visible customer reviews were found on this app page. No reviews were invented. Try CSV upload or paste.")
            return name to rows
        }
    }
}

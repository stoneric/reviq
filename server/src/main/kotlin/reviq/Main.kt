package reviq

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.handler.FileSystemAccess
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun main() {
    val store = Store(); val analyst = Analyst(); val exa=ExaContentsClient()
    val scraper=ReviewScraper(exaFetcher=if(exa.enabled) ExaPageFetcher(exa::fetch) else null,reserveExa=store::reserveExa,reconcileExa=store::reconcile)
    val vertx = Vertx.vertx(VertxOptions().setWorkerPoolSize(12).setMaxWorkerExecuteTime(90_000_000_000))
    val router = Router.router(vertx)
    val origin = System.getenv("PUBLIC_ORIGIN") ?: System.getenv("APP_ORIGIN") ?: "https://reviq.xyz"
    val secure = System.getenv("COOKIE_SECURE") != "false"
    val random = SecureRandom()
    router.route().handler { ctx ->
        ctx.response().putHeader("X-Content-Type-Options","nosniff").putHeader("Cache-Control","no-store").putHeader("Referrer-Policy","same-origin")
        val incoming = ctx.request().getHeader("Origin")
        if(ctx.request().method().name() !in listOf("GET","HEAD","OPTIONS") && incoming!=null && incoming!=origin) error(ctx,ApiError(403,"origin_rejected","This request must come from the Reviq site."))
        else if(ctx.request().getHeader("Sec-Fetch-Site")=="cross-site") error(ctx,ApiError(403,"origin_rejected","Cross-site requests are not permitted."))
        else ctx.next()
    }
    router.get("/api/health").blockingHandler { ctx -> try { json(ctx,mapOf("status" to if(store.healthy()) "ok" else "unhealthy")) } catch(_: Exception) { error(ctx,ApiError(503,"unavailable","Service is temporarily unavailable.")) } }
    router.route("/api/*").handler(BodyHandler.create().setBodyLimit(2L*Normalize.MAX_BYTES).setHandleFileUploads(false))
    router.route("/api/*").blockingHandler({ ctx ->
        try {
            val cookie = ctx.request().getCookie("reviq_session")?.value
            var session = cookie?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }?.let { hash(it) }
            if(session==null || !store.sessionExists(session)) {
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
                session = hash(token); store.createSession(session)
                ctx.response().addCookie(Cookie.cookie("reviq_session",token).setPath("/").setHttpOnly(true).setSecure(secure).setSameSite(CookieSameSite.STRICT).setMaxAge(86400))
            }
            ctx.put("session",session); ctx.next()
        } catch(_: Exception) { error(ctx,ApiError(503,"database_unavailable","Review storage is temporarily unavailable. Please try again.")) }
    },false)
    router.get("/api/session").blockingHandler { ctx -> handle(ctx) {
        val data=store.dataset(session(ctx)); json(ctx,mapOf("dataset" to data,"history" to if(data==null) emptyList() else store.history(session(ctx),data.id),"capabilities" to mapOf("ai_enabled" to analyst.enabled,"chat_mode" to analyst.mode,"live_scraping" to true,"exa_enabled" to exa.enabled,"supported_platforms" to listOf("Google Play","Trustpilot","Other public review sites"),"supported_url_example" to "https://www.trustpilot.com/review/allenbrothers.com","max_reviews" to 200,"retention_hours" to 24,"prompt_version" to PROMPT_VERSION,"model" to if(analyst.enabled) analyst.model else null)))
    } }
    router.get("/api/dataset").blockingHandler { ctx -> handle(ctx) { json(ctx,mapOf("dataset" to store.dataset(session(ctx)))) } }
    router.post("/api/ingest").blockingHandler({ ctx -> handle(ctx) {
        val body = body(ctx)
        store.reserve("ingest",session(ctx),ip(ctx))
        val type = body.path("type").asText("")
        val cancelled=AtomicBoolean(false)
        ctx.response().closeHandler { cancelled.set(true) }
        val data=if(type=="url") scraper.scrape(body.path("url").asText(""),cancelled) else Normalize.ingest(type,body.path("entity").asText(body.path("entity_name").asText("")),body.path("platform").asText(""),body.path("content").asText(""),body.path("url").takeUnless { it.isMissingNode || it.isNull }?.asText())
        if(cancelled.get()) throw ApiError(499,"cancelled","Import stopped.")
        store.replace(session(ctx),data); json(ctx,data)
    } },false)
    router.delete("/api/dataset").blockingHandler { ctx -> handle(ctx) { store.clear(session(ctx)); json(ctx,mapOf("ok" to true)) } }
    router.delete("/api/chat").blockingHandler { ctx -> handle(ctx) { store.clearChat(session(ctx)); json(ctx,mapOf("ok" to true)) } }
    router.post("/api/chat").blockingHandler({ ctx ->
        var request: String? = null
        val cancelled=AtomicBoolean(false); val stream=AtomicReference<AutoCloseable?>(null)
        var timeout: Long?=null
        try {
            val body=body(ctx); val question=body.path("question").asText("")
            analyst.validateQuestion(question)
            val id=body.path("dataset_id").asText("")
            val data=store.dataset(session(ctx),id) ?: throw ApiError(404,"dataset_not_found","This dataset has expired or is not available in your session. Load reviews again.")
            val history=store.history(session(ctx),data.id)
            analyst.input(data,history,question)
            request=store.reserve("chat",session(ctx),ip(ctx),analyst.reservationCost())
            ctx.response().setChunked(true).putHeader("Content-Type","text/event-stream; charset=utf-8").putHeader("X-Accel-Buffering","no").putHeader("Cache-Control","no-cache, no-transform")
            fun cancel() { cancelled.set(true); try { stream.getAndSet(null)?.close() } catch(_: Exception) {} }
            ctx.response().closeHandler { cancel() }
            ctx.response().exceptionHandler { cancel() }
            timeout=vertx.setTimer(65000) { cancel() }
            fun send(event: String, value: Any) { if(!cancelled.get() && !ctx.response().ended()) ctx.response().write("event: $event\ndata: ${mapper.writeValueAsString(value)}\n\n") }
            send("meta",mapOf("dataset_id" to data.id,"mode" to analyst.mode,"draft" to true))
            val answer=analyst.answer(data,history,question,cancelled,stream) { send("delta",mapOf("text" to it)) }
            answer.actualCost?.let { store.reconcile(request!!,it) }
            if(!cancelled.get()) {
                store.saveExchange(session(ctx),data.id,question,answer.text,answer.citations,answer.mode)
                send("citations",mapOf("ids" to answer.citations))
                send("done",mapOf("answer" to answer.text,"citations" to answer.citations,"mode" to answer.mode,"verified" to answer.verified)); ctx.response().end()
            }
        } catch(e: Exception) {
            val problem = when(e) { is ApiError -> e; else -> { System.err.println("Chat failed: ${e.javaClass.simpleName}"); ApiError(502,"analysis_failed","Analysis could not complete. Your reviews are still available; please try again.") } }
            if(ctx.response().headWritten()) { if(!ctx.response().ended()) ctx.response().end("event: error\ndata: ${mapper.writeValueAsString(mapOf("code" to problem.code,"message" to problem.message))}\n\n") } else error(ctx,problem)
        } finally {
            timeout?.let { vertx.cancelTimer(it) }
            try { stream.getAndSet(null)?.close() } catch(_: Exception) {}
            request?.let { try { store.release(session(ctx),it) } catch(_: Exception) {} }
        }
    },false)
    router.route("/api/*").handler { ctx -> error(ctx,ApiError(404,"not_found","This API route does not exist.")) }
    val webRoot=Path.of(System.getenv("WEB_ROOT") ?: "web/dist").toAbsolutePath().normalize()
    if(Files.isRegularFile(webRoot.resolve("index.html"))) {
        router.route().handler { ctx ->
            if(ctx.normalizedPath().split('/').any { it.startsWith(".") }) error(ctx,ApiError(404,"not_found","Page not found.")) else ctx.next()
        }
        router.route().handler(StaticHandler.create(FileSystemAccess.ROOT,webRoot.toString()).setIncludeHidden(false).setCachingEnabled(false))
        listOf("/","/demo").forEach { path -> router.get(path).handler { it.response().sendFile(webRoot.resolve("index.html").toString()) } }
    }
    router.route().failureHandler { ctx -> error(ctx,ApiError(if(ctx.statusCode()==413) 413 else 400,"invalid_request",if(ctx.statusCode()==413) "The request is too large. Uploads and pasted text are limited to 1 MiB." else "The request could not be read.")) }
    vertx.setPeriodic(3600000) { vertx.executeBlocking(java.util.concurrent.Callable { store.cleanup(); true }) }
    vertx.createHttpServer(HttpServerOptions().setIdleTimeout(75).setMaxHeaderSize(8192)).requestHandler(router).listen((System.getenv("PORT") ?: "8088").toInt(),System.getenv("BIND_HOST") ?: "127.0.0.1").onSuccess { println("Reviq API listening on ${it.actualPort()} (chat: ${analyst.mode})") }.onFailure { System.err.println("HTTP server failed: ${it.javaClass.simpleName}"); System.exit(1) }
}
private fun body(ctx: RoutingContext) = try { mapper.readTree(ctx.body().asString()).also { if(!it.isObject) throw IllegalArgumentException() } } catch(_: Exception) { throw ApiError(400,"invalid_json","Send a valid JSON object.") }
private fun session(ctx: RoutingContext): String = ctx.get("session")
private fun ip(ctx: RoutingContext): String {
    val remote = ctx.request().remoteAddress().host()
    val trusted = if(remote=="127.0.0.1" || remote=="::1") ctx.request().getHeader("X-Reviq-Client-IP")?.takeIf { it.length <= 64 } else null
    return hash(trusted ?: remote)
}
private fun handle(ctx: RoutingContext, block: ()->Unit) { try { block() } catch(e: ApiError) { error(ctx,e) } catch(e: Exception) { System.err.println("Request failed: ${e.javaClass.simpleName}"); error(ctx,ApiError(503,"service_unavailable","Review storage is temporarily unavailable. Please try again.")) } }
private fun json(ctx: RoutingContext, value: Any) { ctx.response().putHeader("Content-Type","application/json; charset=utf-8").end(mapper.writeValueAsString(value)) }
private fun error(ctx: RoutingContext, e: ApiError) { if(!ctx.response().ended()) { ctx.response().setStatusCode(e.status); if(e.status==429) ctx.response().putHeader("Retry-After","60"); json(ctx,mapOf("error" to mapOf("code" to e.code,"message" to e.message))) } }

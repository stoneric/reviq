package reviq

import com.fasterxml.jackson.module.kotlin.readValue
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.math.BigDecimal

class Store : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = System.getenv("JDBC_DATABASE_URL") ?: "jdbc:postgresql://127.0.0.1:5432/reviq"
        username = System.getenv("DATABASE_USER") ?: "reviq"
        password = System.getenv("DATABASE_PASSWORD") ?: ""
        maximumPoolSize = 8; connectionTimeout = 5000; poolName = "reviq-db"
    })
    val rateMultiplier = (System.getenv("RATE_LIMIT_MULTIPLIER")?.toIntOrNull() ?: 1).coerceIn(1,100)
    init { pool.connection.use { c -> c.createStatement().use { it.execute(javaClass.getResource("/schema.sql")!!.readText()) } }; cleanup() }
    fun healthy(): Boolean = pool.connection.use { it.isValid(2) }
    private fun <T> transaction(block: (Connection)->T): T = pool.connection.use { c ->
        c.autoCommit=false
        try { val result = block(c); c.commit(); result } catch(e: Exception) { c.rollback(); throw e }
    }
    private fun execute(c: Connection, sql: String, vararg params: Any?): Int = c.prepareStatement(sql).use { s -> params.forEachIndexed { i,v -> s.setObject(i+1,v) }; s.executeUpdate() }
    fun sessionExists(id: String): Boolean = pool.connection.use { c -> c.prepareStatement("SELECT 1 FROM anonymous_sessions WHERE id=? AND expires_at>now()").use { s -> s.setString(1,id); s.executeQuery().use { it.next() } } }
    fun createSession(id: String) { pool.connection.use { execute(it,"INSERT INTO anonymous_sessions(id) VALUES(?)",id) } }
    fun dataset(session: String, id: String? = null): Dataset? = pool.connection.use { c ->
        val query = "SELECT data::text FROM datasets WHERE session_id=? AND expires_at>now()" + if(id!=null) " AND id=?::uuid" else " ORDER BY created_at DESC LIMIT 1"
        c.prepareStatement(query).use { s -> s.setString(1,session); if(id!=null) { try { UUID.fromString(id) } catch(_: Exception) { return null }; s.setString(2,id) }; s.executeQuery().use { r -> if(r.next()) mapper.readValue<Dataset>(r.getString(1)) else null } }
    }
    fun replace(session: String, data: Dataset) { transaction { c ->
        lockSession(c,session)
        execute(c,"DELETE FROM datasets WHERE session_id=?",session)
        execute(c,"INSERT INTO datasets(id,session_id,data,expires_at) VALUES(?::uuid,?,?::jsonb,?)",data.id,session,mapper.writeValueAsString(data),Timestamp.from(Instant.parse(data.expires_at)))
        c.prepareStatement("INSERT INTO reviews(session_id,dataset_id,review_id,text,rating,review_date,data) VALUES(?,?::uuid,?,?,?,?::date,?::jsonb)").use { s ->
            data.reviews.forEach { r -> listOf(session,data.id,r.id,r.text,r.rating,r.date,mapper.writeValueAsString(r)).forEachIndexed { i,v -> s.setObject(i+1,v) }; s.addBatch() }; s.executeBatch()
        }
    } }
    private fun lockSession(c: Connection, session: String) { c.prepareStatement("SELECT id FROM anonymous_sessions WHERE id=? FOR UPDATE").use { s -> s.setString(1,session); s.executeQuery().close() } }
    fun clear(session: String) { transaction { c -> lockSession(c,session); execute(c,"DELETE FROM datasets WHERE session_id=?",session) } }
    fun clearChat(session: String) { transaction { c ->
        lockSession(c,session)
        execute(c,"UPDATE datasets SET conversation_revision=conversation_revision+1 WHERE session_id=?",session)
        execute(c,"DELETE FROM chat_messages WHERE session_id=?",session)
    } }
    fun conversationRevision(session: String, dataset: String): Long? = pool.connection.use { c ->
        c.prepareStatement("SELECT conversation_revision FROM datasets WHERE session_id=? AND id=?::uuid AND expires_at>now()").use { s ->
            s.setString(1,session); s.setString(2,dataset); s.executeQuery().use { r -> if(r.next()) r.getLong(1) else null }
        }
    }
    fun history(session: String, dataset: String): List<Message> = pool.connection.use { c -> c.prepareStatement("SELECT role,content,citations::text,mode FROM chat_messages WHERE session_id=? AND dataset_id=?::uuid ORDER BY id").use { s ->
        s.setString(1,session); s.setString(2,dataset); s.executeQuery().use { r -> buildList { while(r.next()) add(Message(r.getString(1),r.getString(2),mapper.readValue(r.getString(3)),r.getString(4))) } }
    } }
    fun saveExchange(session: String, dataset: String, question: String, answer: String, citations: List<String>, mode: String, revision: Long): Boolean = transaction { c ->
        lockSession(c,session)
        // An import or clear during streaming must never resurrect the old conversation.
        val exists = c.prepareStatement("SELECT 1 FROM datasets WHERE session_id=? AND id=?::uuid AND expires_at>now() AND conversation_revision=?").use { s -> s.setString(1,session); s.setString(2,dataset); s.setLong(3,revision); s.executeQuery().use { it.next() } }
        if(exists) {
            execute(c,"INSERT INTO chat_messages(session_id,dataset_id,role,content,citations,mode) VALUES(?,?::uuid,'user',?,'[]',?)",session,dataset,question,mode)
            execute(c,"INSERT INTO chat_messages(session_id,dataset_id,role,content,citations,mode) VALUES(?,?::uuid,'assistant',?,?::jsonb,?)",session,dataset,answer,mapper.writeValueAsString(citations),mode)
        }
        exists
    }
    fun reserve(kind: String, session: String, ip: String, cost: Double = 0.0): String = transaction { c ->
        c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(782391147)") }
        val now = Instant.now(); val minute = now.epochSecond/60; val day = LocalDate.now(ZoneOffset.UTC)
        val minuteCap = if(kind=="chat") 5 else 2; val dayCap = if(kind=="chat") 30 else 20
        val counters = listOf("$kind:s:$session:m:$minute" to minuteCap,"$kind:i:$ip:m:$minute" to minuteCap,"$kind:s:$session:d:$day" to dayCap,"$kind:i:$ip:d:$day" to dayCap)
        for((key,limit) in counters) {
            val n = c.prepareStatement("SELECT count FROM quota_counters WHERE key=?").use { s -> s.setString(1,key); s.executeQuery().use { if(it.next()) it.getInt(1) else 0 } }
            if(n >= limit*rateMultiplier) throw ApiError(429,"rate_limit","This session or connection has reached the $kind limit. Try again after the quota window resets.")
        }
        val request = UUID.randomUUID().toString()
        if(kind=="chat") {
            val n = execute(c,"UPDATE anonymous_sessions SET active_until=now()+interval '90 seconds',active_request=? WHERE id=? AND (active_until IS NULL OR active_until<now())",request,session)
            if(n==0) throw ApiError(409,"chat_in_progress","Wait for the current answer to finish, or stop it before asking again.")
        }
        if(cost > 0.0) {
            val budget = System.getenv("DAILY_BUDGET_USD")?.toDoubleOrNull() ?: 2.0
            val key = "openai:global:$day"
            execute(c,"INSERT INTO quota_counters(key,expires_at) VALUES(?,now()+interval '2 days') ON CONFLICT DO NOTHING",key)
            val allowed = execute(c,"UPDATE quota_counters SET count=count+1,reserved_usd=reserved_usd+? WHERE key=? AND count<100 AND reserved_usd+?<=?",cost,key,cost,budget)
            if(allowed==0) throw ApiError(429,"daily_budget","Today's analysis allowance has been reached. You can still inspect your reviews.")
            execute(c,"INSERT INTO cost_reservations(request_id,quota_key,amount) VALUES(?,?,?)",request,key,cost)
        }
        counters.forEach { (key,_) -> execute(c,"INSERT INTO quota_counters(key,count,expires_at) VALUES(?,1,now()+interval '2 days') ON CONFLICT(key) DO UPDATE SET count=quota_counters.count+1",key) }
        request
    }
    /** Reserve one text-only Exa retrieval before the outbound provider request. */
    fun reserveExa(): String = reserveExa("global",System.getenv("EXA_DAILY_BUDGET_USD"))
    // Internal scope/config injection keeps tests away from shared production/day counters.
    internal fun reserveExa(scope: String, budgetConfig: String?): String {
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(scope))
        val budget=if(budgetConfig==null) BigDecimal("0.10") else budgetConfig.toBigDecimalOrNull()
        if(budget==null || budget.signum()<0 || !budget.toDouble().isFinite()) {
            throw ApiError(503,"exa_budget_configuration","Live page retrieval is disabled because its daily budget configuration is invalid.")
        }
        return reserveProvider("exa",scope,budget,BigDecimal("0.002"),100)
    }
    private fun reserveProvider(provider: String, scope: String, budget: BigDecimal, cost: BigDecimal, maxRequests: Int): String = transaction { c ->
        require(cost.signum()>0 && maxRequests>0)
        c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(782391147)") }
        val key="$provider:$scope:${LocalDate.now(ZoneOffset.UTC)}"
        val request=UUID.randomUUID().toString()
        execute(c,"INSERT INTO quota_counters(key,expires_at) VALUES(?,now()+interval '2 days') ON CONFLICT DO NOTHING",key)
        val allowed=execute(c,"UPDATE quota_counters SET count=count+1,reserved_usd=reserved_usd+? WHERE key=? AND count<? AND reserved_usd+?<=?",cost,key,maxRequests,cost,budget)
        if(allowed==0) throw ApiError(429,"${provider}_daily_budget","Today's live retrieval allowance has been reached. You can still upload CSV files or paste reviews.")
        execute(c,"INSERT INTO cost_reservations(request_id,quota_key,amount) VALUES(?,?,?)",request,key,cost)
        request
    }
    fun reconcile(request: String, actualCost: Double) { transaction { c ->
        if(!actualCost.isFinite() || actualCost<0) return@transaction
        c.prepareStatement("SELECT quota_key,amount FROM cost_reservations WHERE request_id=? AND NOT reconciled FOR UPDATE").use { s ->
            s.setString(1,request)
            s.executeQuery().use { r -> if(r.next()) {
                val key=r.getString(1); val reservation=r.getDouble(2)
                execute(c,"UPDATE quota_counters SET reserved_usd=GREATEST(0,reserved_usd+?) WHERE key=?",actualCost-reservation,key)
                execute(c,"UPDATE cost_reservations SET reconciled=true WHERE request_id=?",request)
            } }
        }
    } }
    fun release(session: String, request: String) { pool.connection.use { execute(it,"UPDATE anonymous_sessions SET active_until=NULL,active_request=NULL WHERE id=? AND active_request=?",session,request) } }
    override fun close() { pool.close() }
    fun cleanup() { pool.connection.use { c -> execute(c,"DELETE FROM anonymous_sessions WHERE expires_at<now()"); execute(c,"DELETE FROM datasets WHERE expires_at<now()"); execute(c,"DELETE FROM quota_counters WHERE expires_at<now()") } }
}

package reviq

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class StoreTest {
    private val stores=mutableListOf<Store>()
    private fun store()=Store().also { stores+=it }
    @AfterEach fun closePools() { stores.forEach { it.close() } }
    private fun scope()="test-${UUID.randomUUID()}"
    private fun usage(scope: String): Triple<Int,BigDecimal,Int>? {
        val url=System.getenv("JDBC_DATABASE_URL") ?: "jdbc:postgresql://127.0.0.1:5432/reviq"
        val key="exa:$scope:${LocalDate.now(ZoneOffset.UTC)}"
        return DriverManager.getConnection(url,System.getenv("DATABASE_USER") ?: "reviq",System.getenv("DATABASE_PASSWORD") ?: "").use { c ->
            c.prepareStatement("SELECT q.count,q.reserved_usd,(SELECT count(*) FROM cost_reservations r WHERE r.quota_key=q.key) FROM quota_counters q WHERE q.key=?").use { s ->
                s.setString(1,key); s.executeQuery().use { r -> if(r.next()) Triple(r.getInt(1),r.getBigDecimal(2),r.getInt(3)) else null }
            }
        }
    }

    @Test fun `session ownership blocks unrelated data and replacement clears history`() {
        val store=store(); val first=hash(UUID.randomUUID().toString()); val second=hash(UUID.randomUUID().toString())
        store.createSession(first); store.createSession(second)
        val d=Normalize.ingest("demo","","",""); store.replace(first,d)
        assertNotNull(store.dataset(first,d.id)); assertNull(store.dataset(second,d.id))
        store.saveExchange(first,d.id,"Question","Answer",listOf("R001"),"evidence")
        assertEquals(2,store.history(first,d.id).size); assertTrue(store.history(second,d.id).isEmpty())
        val next=Normalize.ingest("paste","New entity","Pasted","New review")
        store.replace(first,next)
        assertNull(store.dataset(first,d.id)); assertTrue(store.history(first,d.id).isEmpty())
        store.saveExchange(first,d.id,"Late question","Late answer",listOf("R001"),"evidence")
        assertTrue(store.history(first,d.id).isEmpty()); assertEquals(next.id,store.dataset(first)?.id)
        store.clear(first); assertNull(store.dataset(first))
    }
    @Test fun `concurrent request permits only one active chat per session`() {
        val store=store(); val session=hash(UUID.randomUUID().toString()); store.createSession(session)
        val executor=Executors.newFixedThreadPool(8)
        try {
            val attempts=executor.invokeAll((1..8).map { Callable { try { store.reserve("chat",session,hash("local test")) } catch(e: ApiError) { "denied:${e.code}" } } }).map { it.get() }
            assertEquals(1,attempts.count { !it.startsWith("denied:") })
            assertEquals(7,attempts.count { it=="denied:chat_in_progress" })
            store.release(session,attempts.first { !it.startsWith("denied:") })
        } finally { executor.shutdownNow() }
    }
    @Test fun `concurrent ingestion quota never exceeds atomic boundary`() {
        val store=store(); val session=hash(UUID.randomUUID().toString()); store.createSession(session)
        val ip=hash(UUID.randomUUID().toString()); val executor=Executors.newFixedThreadPool(8)
        try {
            val attempts=executor.invokeAll((1..8).map { Callable { try { store.reserve("ingest",session,ip); true } catch(e: ApiError) { if(e.code!="rate_limit") throw e; false } } }).map { it.get() }
            assertEquals(2,attempts.count { it })
        } finally { executor.shutdownNow() }
    }
    @Test fun concurrentProviderReservationsStopAtExactBudgetWithoutSessionOrIngestLocks() {
        val store=store(); val scope=scope(); val executor=Executors.newFixedThreadPool(8)
        try {
            val results=executor.invokeAll((1..16).map { Callable {
                try { store.reserveExa(scope,"0.010") } catch(e: ApiError) { if(e.code!="exa_daily_budget") throw e; null }
            } }).mapNotNull { it.get() }
            assertEquals(5,results.size); assertEquals(5,results.distinct().size)
            val usage=usage(scope)!!
            assertEquals(5,usage.first); assertEquals(0,usage.second.compareTo(BigDecimal("0.010"))); assertEquals(5,usage.third)
        } finally { executor.shutdownNow() }
    }
    @Test fun defaultProviderBudgetKeepsUnknownCostsReservedAndReconciliationIsIdempotent() {
        val store=store(); val scope=scope()
        val requests=(1..50).map { store.reserveExa(scope,null) }
        assertEquals("exa_daily_budget",assertThrows(ApiError::class.java) { store.reserveExa(scope,null) }.code)
        store.reconcile(requests[0],Double.NaN); store.reconcile(requests[0],-1.0)
        assertEquals(0,usage(scope)!!.second.compareTo(BigDecimal("0.10")))
        store.reconcile(requests[0],0.001); store.reconcile(requests[0],0.001)
        assertEquals(0,usage(scope)!!.second.compareTo(BigDecimal("0.099")))
        assertEquals("exa_daily_budget",assertThrows(ApiError::class.java) { store.reserveExa(scope,null) }.code)
        store.reconcile(requests[1],0.001)
        store.reserveExa(scope,null)
        val usage=usage(scope)!!
        assertEquals(51,usage.first); assertEquals(51,usage.third); assertEquals(0,usage.second.compareTo(BigDecimal("0.10")))
    }
    @Test fun providerRequestCeilingRemainsAfterCostsAreReconciledToZero() {
        val store=store(); val scope=scope(); val executor=Executors.newFixedThreadPool(8)
        try {
            val accepted=executor.invokeAll((1..108).map { Callable {
                try { val request=store.reserveExa(scope,"1.00"); store.reconcile(request,0.0); true }
                catch(e: ApiError) { if(e.code!="exa_daily_budget") throw e; false }
            } }).count { it.get() }
            assertEquals(100,accepted)
            val usage=usage(scope)!!
            assertEquals(100,usage.first); assertEquals(100,usage.third); assertEquals(0,usage.second.compareTo(BigDecimal.ZERO))
        } finally { executor.shutdownNow() }
    }
    @Test fun malformedNegativeAndNonfiniteProviderBudgetsFailClosedWithoutQuotaRows() {
        val store=store()
        listOf("","invalid","-0.01","NaN","Infinity","1e999999").forEach { config ->
            val scope=scope()
            assertEquals("exa_budget_configuration",assertThrows(ApiError::class.java) { store.reserveExa(scope,config) }.code)
            assertNull(usage(scope))
        }
        val disabled=scope()
        assertEquals("exa_daily_budget",assertThrows(ApiError::class.java) { store.reserveExa(disabled,"0") }.code)
        assertNull(usage(disabled))
    }
}

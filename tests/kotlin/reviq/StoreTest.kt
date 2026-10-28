package reviq

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class StoreTest {
    @Test fun `session ownership blocks unrelated data and replacement clears history`() {
        val store=Store(); val first=hash(UUID.randomUUID().toString()); val second=hash(UUID.randomUUID().toString())
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
        val store=Store(); val session=hash(UUID.randomUUID().toString()); store.createSession(session)
        val executor=Executors.newFixedThreadPool(8)
        try {
            val attempts=executor.invokeAll((1..8).map { Callable { try { store.reserve("chat",session,hash("local test")) } catch(e: ApiError) { "denied:${e.code}" } } }).map { it.get() }
            assertEquals(1,attempts.count { !it.startsWith("denied:") })
            assertEquals(7,attempts.count { it=="denied:chat_in_progress" })
            store.release(session,attempts.first { !it.startsWith("denied:") })
        } finally { executor.shutdownNow() }
    }
    @Test fun `concurrent ingestion quota never exceeds atomic boundary`() {
        val store=Store(); val session=hash(UUID.randomUUID().toString()); store.createSession(session)
        val ip=hash(UUID.randomUUID().toString()); val executor=Executors.newFixedThreadPool(8)
        try {
            val attempts=executor.invokeAll((1..8).map { Callable { try { store.reserve("ingest",session,ip); true } catch(e: ApiError) { if(e.code!="rate_limit") throw e; false } } }).map { it.get() }
            assertEquals(2,attempts.count { it })
        } finally { executor.shutdownNow() }
    }
}

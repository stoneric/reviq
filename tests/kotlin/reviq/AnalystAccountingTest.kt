package reviq

import com.openai.client.OpenAIClient
import com.openai.core.http.StreamResponse
import com.openai.models.responses.ResponseStreamEvent
import com.openai.services.blocking.ResponseService
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** SDK events are synthetic; these tests never contact a provider. */
internal class AnalystStreamFixture(text: String, completed: Boolean = true, withUsage: Boolean = true) {
    var closed=false
    private val events=buildList {
        add(mapper.readValue(mapper.writeValueAsString(mapOf(
            "type" to "response.output_text.delta", "item_id" to "synthetic", "output_index" to 0,
            "content_index" to 0, "sequence_number" to 1, "delta" to text)),ResponseStreamEvent::class.java))
        if(completed) {
            val response=mutableMapOf<String,Any>("id" to "synthetic")
            if(withUsage) response["usage"]=mapOf("input_tokens" to 100,"output_tokens" to 10,"total_tokens" to 110,
                "input_tokens_details" to mapOf("cached_tokens" to 0),"output_tokens_details" to mapOf("reasoning_tokens" to 0))
            add(mapper.readValue(mapper.writeValueAsString(mapOf("type" to "response.completed", "sequence_number" to 2, "response" to response)),ResponseStreamEvent::class.java))
        }
    }
    private val stream=object: StreamResponse<ResponseStreamEvent> {
        override fun stream(): Stream<ResponseStreamEvent> = events.stream()
        override fun close() { closed=true }
    }
    private val responses=Proxy.newProxyInstance(ResponseService::class.java.classLoader,arrayOf(ResponseService::class.java)) { _,method,_ ->
        check(method.name=="createStreaming") { "Unexpected SDK method: ${method.name}" }; stream
    } as ResponseService
    val analyst=Analyst(Proxy.newProxyInstance(OpenAIClient::class.java.classLoader,arrayOf(OpenAIClient::class.java)) { _,method,_ ->
        check(method.name=="responses") { "Unexpected SDK method: ${method.name}" }; responses
    } as OpenAIClient)
}

class AnalystAccountingTest {
    private val data=Normalize.ingest("paste","Fixture","Synthetic","Original synthetic evidence")

    @Test fun `known usage is reported before quotation citation and empty answer rejection`() {
        for((text,code) in listOf(
            "Invented: \"not in the review\" [R001]." to "unverified_quote",
            "Unknown source [R999]." to "unverified_citation",
            "Unsupported answer without citations." to "missing_citations",
            "" to "incomplete_answer")) {
            val fixture=AnalystStreamFixture(text); val costs=mutableListOf<Double>()
            val error=assertThrows(ApiError::class.java) {
                fixture.analyst.answer(data,emptyList(),"Summarize",AtomicBoolean(false),AtomicReference(),onUsage={ costs+=it }) {}
            }
            assertEquals(code,error.code); assertEquals(1,costs.size)
            assertTrue(costs.single()>0 && costs.single()<fixture.analyst.reservationCost())
            assertTrue(fixture.closed)
        }
    }
    @Test fun `successful answer reports usage once and preserves final answer accounting`() {
        val fixture=AnalystStreamFixture("Evidence: \"Original synthetic evidence\" [R001].")
        val costs=mutableListOf<Double>()
        val answer=fixture.analyst.answer(data,emptyList(),"Summarize",AtomicBoolean(false),AtomicReference(),onUsage={ costs+=it }) {}
        assertTrue(answer.verified); assertEquals(listOf("R001"),answer.citations)
        assertEquals(listOf(answer.actualCost),costs); assertTrue(fixture.closed)
    }
    @Test fun `unknown usage and interrupted streams cannot refund a reservation`() {
        val missingUsage=AnalystStreamFixture(SCOPE_REFUSAL,withUsage=false)
        val answer=missingUsage.analyst.answer(data,emptyList(),"Summarize",AtomicBoolean(false),AtomicReference(),onUsage={ fail<Unit>("No reported usage") }) {}
        assertNull(answer.actualCost)
        for(cancelled in listOf(false,true)) {
            val fixture=AnalystStreamFixture(SCOPE_REFUSAL,completed=false)
            val error=assertThrows(ApiError::class.java) {
                fixture.analyst.answer(data,emptyList(),"Summarize",AtomicBoolean(cancelled),AtomicReference(),onUsage={ fail<Unit>("No reported usage") }) {}
            }
            assertEquals(if(cancelled) "cancelled" else "incomplete_answer",error.code)
            assertTrue(fixture.closed)
        }
    }
    @Test fun `accounting failure closes the upstream stream without finalizing the answer`() {
        val fixture=AnalystStreamFixture(SCOPE_REFUSAL)
        assertThrows(IllegalStateException::class.java) {
            fixture.analyst.answer(data,emptyList(),"Summarize",AtomicBoolean(false),AtomicReference(),onUsage={ error("Simulated accounting outage") }) {}
        }
        assertTrue(fixture.closed)
    }
}

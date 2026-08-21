package dev.kviklet.kviklet.service.aireview

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class OpenRouterClientTest {

    private lateinit var server: MockWebServer
    private lateinit var objectMapper: ObjectMapper
    private lateinit var properties: AiReviewProperties
    private lateinit var client: OpenRouterClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        objectMapper = jacksonObjectMapper()
        properties = AiReviewProperties().apply {
            openrouter.apiKey = "test-api-key"
            openrouter.baseUrl = server.url("/api/v1").toString().trimEnd('/')
            timeout = Duration.ofSeconds(5)
            maxPromptChars = 50_000
        }
        client = OpenRouterClient(properties, objectMapper)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful review sends bearer auth models order and zdr provider settings`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(openRouterEnvelope(reviewJson())),
        )

        val result = client.review("system prompt", "user prompt")

        assertEquals(AiReviewAttemptStatus.APPROVED, result.verdict)
        assertEquals("Looks safe", result.summary)
        assertTrue(result.findings.isEmpty())
        assertEquals(null, result.suggestedSql)
        assertEquals("qwen/qwen3-coder-plus", result.model)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.endsWith("/chat/completions"))

        val body = objectMapper.readTree(recorded.body.readUtf8())
        val models = body.get("models").map { it.asText() }
        assertEquals(listOf("qwen/qwen3-coder-plus", "openai/gpt-4o"), models)

        val provider = body.get("provider")
        assertTrue(provider.get("zdr").asBoolean())
        assertEquals("deny", provider.get("data_collection").asText())
        assertTrue(provider.get("require_parameters").asBoolean())

        val responseFormat = body.get("response_format")
        assertEquals("json_schema", responseFormat.get("type").asText())
        assertEquals("ai_query_review", responseFormat.get("json_schema").get("name").asText())
        assertTrue(responseFormat.get("json_schema").get("strict").asBoolean())

        val messages = body.get("messages")
        assertEquals("system", messages[0].get("role").asText())
        assertEquals("system prompt", messages[0].get("content").asText())
        assertEquals("user", messages[1].get("role").asText())
        assertEquals("user prompt", messages[1].get("content").asText())
    }

    @Test
    fun `invalid json content maps to INVALID_RESPONSE`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(openRouterEnvelope("not-valid-json{{{")),
        )

        val ex = assertThrows(OpenRouterClientException::class.java) {
            client.review("system", "user")
        }
        assertEquals(AiReviewErrorCategory.INVALID_RESPONSE, ex.category)
    }

    @Test
    fun `not configured throws NOT_CONFIGURED`() {
        properties.openrouter.apiKey = null
        val unconfigured = OpenRouterClient(properties, objectMapper)

        val ex = assertThrows(OpenRouterClientException::class.java) {
            unconfigured.review("system", "user")
        }
        assertEquals(AiReviewErrorCategory.NOT_CONFIGURED, ex.category)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `prompt too large throws PROMPT_TOO_LARGE`() {
        properties.maxPromptChars = 10
        val limited = OpenRouterClient(properties, objectMapper)

        val ex = assertThrows(OpenRouterClientException::class.java) {
            limited.review("system-prompt", "user-prompt")
        }
        assertEquals(AiReviewErrorCategory.PROMPT_TOO_LARGE, ex.category)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `retries once on 429 then succeeds`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(openRouterEnvelope(reviewJson())),
        )

        val result = client.review("system", "user")

        assertEquals(AiReviewAttemptStatus.APPROVED, result.verdict)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http error after retry maps to HTTP_ERROR`() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(503))

        val ex = assertThrows(OpenRouterClientException::class.java) {
            client.review("system", "user")
        }
        assertEquals(AiReviewErrorCategory.HTTP_ERROR, ex.category)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `truncates oversized summary findings and suggestedSql`() {
        val longSummary = "S".repeat(5_000)
        val longField = "F".repeat(3_000)
        val longSql = "Q".repeat(60_000)
        val findings = (1..25).map { i ->
            mapOf(
                "severity" to "INFO",
                "category" to longField,
                "explanation" to longField,
                "fix" to longField,
            )
        }
        val content = objectMapper.writeValueAsString(
            mapOf(
                "verdict" to "APPROVED",
                "summary" to longSummary,
                "findings" to findings,
                "suggestedSql" to longSql,
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(openRouterEnvelope(content)),
        )

        val result = client.review("system", "user")

        assertEquals(4_000, result.summary.length)
        assertTrue(result.summary.endsWith("…"))
        assertEquals(20, result.findings.size)
        assertEquals(2_000, result.findings[0].category.length)
        assertTrue(result.findings[0].category.endsWith("…"))
        assertEquals(properties.maxPromptChars, result.suggestedSql!!.length)
        assertTrue(result.suggestedSql!!.endsWith("…"))
    }

    private fun reviewJson(): String = """
        {
          "verdict": "APPROVED",
          "summary": "Looks safe",
          "findings": [],
          "suggestedSql": null
        }
    """.trimIndent()

    private fun openRouterEnvelope(content: String): String {
        val escaped = objectMapper.writeValueAsString(content)
        return """
            {
              "id": "gen-1",
              "model": "qwen/qwen3-coder-plus",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": $escaped
                  }
                }
              ]
            }
        """.trimIndent()
    }
}

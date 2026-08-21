package dev.kviklet.kviklet.service.aireview

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.kviklet.kviklet.service.dto.AiFinding
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeUnit

enum class AiReviewErrorCategory {
    TIMEOUT,
    HTTP_ERROR,
    INVALID_RESPONSE,
    PROMPT_TOO_LARGE,
    NOT_CONFIGURED,
}

class OpenRouterClientException(
    val category: AiReviewErrorCategory,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class OpenRouterReviewResult(
    val model: String,
    val verdict: AiReviewAttemptStatus,
    val summary: String,
    val findings: List<AiFinding>,
    val suggestedSql: String?,
)

@Service
class OpenRouterClient(
    private val properties: AiReviewProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_SUMMARY_CHARS = 4_000
        private const val MAX_FINDING_FIELD_CHARS = 2_000
        private const val MAX_FINDINGS = 20
        private const val TRUNCATION_MARKER = "…"
    }

    fun review(system: String, user: String): OpenRouterReviewResult {
        if (!properties.isConfigured()) {
            throw OpenRouterClientException(
                AiReviewErrorCategory.NOT_CONFIGURED,
                "OpenRouter API key is not configured",
            )
        }

        val promptChars = system.length + user.length
        if (promptChars > properties.maxPromptChars) {
            throw OpenRouterClientException(
                AiReviewErrorCategory.PROMPT_TOO_LARGE,
                "Prompt exceeds maxPromptChars limit",
            )
        }

        val requestBody = buildRequestBody(system, user)
        val responseBody = executeWithRetry(requestBody)
        return parseResponse(responseBody)
    }

    private fun buildRequestBody(system: String, user: String): ObjectNode {
        val root = objectMapper.createObjectNode()
        val models = root.putArray("models")
        models.add(properties.primaryModel)
        models.add(properties.fallbackModel)

        val messages = root.putArray("messages")
        messages.addObject().put("role", "system").put("content", system)
        messages.addObject().put("role", "user").put("content", user)

        root.putObject("provider")
            .put("zdr", true)
            .put("data_collection", "deny")
            .put("require_parameters", true)

        val responseFormat = root.putObject("response_format")
        responseFormat.put("type", "json_schema")
        val jsonSchema = responseFormat.putObject("json_schema")
        jsonSchema.put("name", "ai_query_review")
        jsonSchema.put("strict", true)
        jsonSchema.set<JsonNode>("schema", reviewJsonSchema())
        return root
    }

    private fun reviewJsonSchema(): ObjectNode {
        val schema = objectMapper.createObjectNode()
        schema.put("type", "object")
        schema.put("additionalProperties", false)
        schema.putArray("required").apply {
            add("verdict")
            add("summary")
            add("findings")
            add("suggestedSql")
        }

        val propertiesNode = schema.putObject("properties")
        propertiesNode.putObject("verdict").apply {
            put("type", "string")
            putArray("enum").apply {
                add("APPROVED")
                add("APPROVED_WITH_NOTES")
                add("REJECTED")
            }
        }
        propertiesNode.putObject("summary").put("type", "string")

        val findings = propertiesNode.putObject("findings")
        findings.put("type", "array")
        val items = findings.putObject("items")
        items.put("type", "object")
        items.put("additionalProperties", false)
        items.putArray("required").apply {
            add("severity")
            add("category")
            add("explanation")
            add("fix")
        }
        val itemProps = items.putObject("properties")
        itemProps.putObject("severity").apply {
            put("type", "string")
            putArray("enum").apply {
                add("BLOCKER")
                add("WARNING")
                add("INFO")
            }
        }
        itemProps.putObject("category").put("type", "string")
        itemProps.putObject("explanation").put("type", "string")
        itemProps.putObject("fix").put("type", "string")

        propertiesNode.putObject("suggestedSql").putArray("type").apply {
            add("string")
            add("null")
        }
        return schema
    }

    private fun executeWithRetry(requestBody: ObjectNode): String {
        var attempt = 0
        var lastHttpError: OpenRouterClientException? = null

        while (attempt < 2) {
            attempt++
            try {
                val response = restClient()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer ${properties.openrouter.apiKey}")
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .toEntity(String::class.java)

                val status = response.statusCode.value()
                if (status in 200..299) {
                    return response.body
                        ?: throw OpenRouterClientException(
                            AiReviewErrorCategory.INVALID_RESPONSE,
                            "Empty OpenRouter response body",
                        )
                }

                if (isRetryable(status) && attempt == 1) {
                    logger.warn("OpenRouter returned {}; retrying once", status)
                    sleepRetryAfter(response.headers.getFirst("Retry-After"))
                    continue
                }

                throw OpenRouterClientException(
                    AiReviewErrorCategory.HTTP_ERROR,
                    "OpenRouter HTTP $status",
                )
            } catch (ex: OpenRouterClientException) {
                throw ex
            } catch (ex: RestClientResponseException) {
                val status = ex.statusCode.value()
                if (isRetryable(status) && attempt == 1) {
                    logger.warn("OpenRouter returned {}; retrying once", status)
                    sleepRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                    lastHttpError = OpenRouterClientException(
                        AiReviewErrorCategory.HTTP_ERROR,
                        "OpenRouter HTTP $status",
                        ex,
                    )
                    continue
                }
                throw OpenRouterClientException(
                    AiReviewErrorCategory.HTTP_ERROR,
                    "OpenRouter HTTP $status",
                    ex,
                )
            } catch (ex: Exception) {
                if (isTimeout(ex)) {
                    throw OpenRouterClientException(
                        AiReviewErrorCategory.TIMEOUT,
                        "OpenRouter request timed out",
                        ex,
                    )
                }
                throw OpenRouterClientException(
                    AiReviewErrorCategory.HTTP_ERROR,
                    "OpenRouter request failed",
                    ex,
                )
            }
        }

        throw lastHttpError ?: OpenRouterClientException(
            AiReviewErrorCategory.HTTP_ERROR,
            "OpenRouter request failed after retry",
        )
    }

    private fun parseResponse(responseBody: String): OpenRouterReviewResult {
        try {
            val root = objectMapper.readTree(responseBody)
            val model = root.path("model").asText(properties.primaryModel)
            val content = root.path("choices").path(0).path("message").path("content").asText(null)
                ?: throw OpenRouterClientException(
                    AiReviewErrorCategory.INVALID_RESPONSE,
                    "Missing message content",
                )

            val review = objectMapper.readTree(content)
            val modelVerdict = parseVerdict(review.path("verdict").asText(null))
            val summary = truncate(
                review.path("summary").asText(null)
                    ?: throw OpenRouterClientException(
                        AiReviewErrorCategory.INVALID_RESPONSE,
                        "Missing summary",
                    ),
                MAX_SUMMARY_CHARS,
            )
            val findings = parseFindings(review.path("findings"))
            val suggestedSql = review.get("suggestedSql")?.takeUnless { it.isNull }?.asText()
                ?.let { truncate(it, properties.maxPromptChars) }
            val verdict = AiReviewVerdictNormalizer.normalize(findings, modelVerdict)

            return OpenRouterReviewResult(
                model = model,
                verdict = verdict,
                summary = summary,
                findings = findings,
                suggestedSql = suggestedSql,
            )
        } catch (ex: OpenRouterClientException) {
            throw ex
        } catch (ex: Exception) {
            throw OpenRouterClientException(
                AiReviewErrorCategory.INVALID_RESPONSE,
                "Failed to parse OpenRouter response",
                ex,
            )
        }
    }

    private fun parseVerdict(raw: String?): AiReviewAttemptStatus {
        if (raw.isNullOrBlank()) {
            throw OpenRouterClientException(
                AiReviewErrorCategory.INVALID_RESPONSE,
                "Missing verdict",
            )
        }
        return try {
            AiReviewAttemptStatus.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            throw OpenRouterClientException(
                AiReviewErrorCategory.INVALID_RESPONSE,
                "Unknown verdict",
            )
        }
    }

    private fun parseFindings(node: JsonNode): List<AiFinding> {
        if (!node.isArray) {
            throw OpenRouterClientException(
                AiReviewErrorCategory.INVALID_RESPONSE,
                "Findings must be an array",
            )
        }
        return node.take(MAX_FINDINGS).map { finding ->
            val severityRaw = finding.path("severity").asText(null)
                ?: throw OpenRouterClientException(
                    AiReviewErrorCategory.INVALID_RESPONSE,
                    "Finding missing severity",
                )
            val severity = try {
                AiFindingSeverity.valueOf(severityRaw)
            } catch (_: IllegalArgumentException) {
                throw OpenRouterClientException(
                    AiReviewErrorCategory.INVALID_RESPONSE,
                    "Unknown finding severity",
                )
            }
            AiFinding(
                severity = severity,
                category = truncate(
                    finding.path("category").asText(null)
                        ?: throw OpenRouterClientException(
                            AiReviewErrorCategory.INVALID_RESPONSE,
                            "Finding missing category",
                        ),
                    MAX_FINDING_FIELD_CHARS,
                ),
                explanation = truncate(
                    finding.path("explanation").asText(null)
                        ?: throw OpenRouterClientException(
                            AiReviewErrorCategory.INVALID_RESPONSE,
                            "Finding missing explanation",
                        ),
                    MAX_FINDING_FIELD_CHARS,
                ),
                fix = truncate(
                    finding.path("fix").asText(null)
                        ?: throw OpenRouterClientException(
                            AiReviewErrorCategory.INVALID_RESPONSE,
                            "Finding missing fix",
                        ),
                    MAX_FINDING_FIELD_CHARS,
                ),
            )
        }
    }

    private fun truncate(value: String, maxChars: Int): String {
        if (value.length <= maxChars) {
            return value
        }
        if (maxChars <= 1) {
            return TRUNCATION_MARKER.take(maxChars)
        }
        return value.take(maxChars - 1) + TRUNCATION_MARKER
    }

    private fun restClient(): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.timeout)
        }
        return RestClient.builder()
            .baseUrl(properties.openrouter.baseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
            .build()
    }

    private fun isRetryable(status: Int): Boolean = status == 429 || status in 500..599

    private fun isTimeout(ex: Throwable): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            if (current is HttpTimeoutException || current is java.net.SocketTimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun sleepRetryAfter(retryAfter: String?) {
        val seconds = retryAfter?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        if (seconds > 0) {
            try {
                TimeUnit.SECONDS.sleep(seconds.coerceAtMost(properties.timeout.seconds.coerceAtLeast(1)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

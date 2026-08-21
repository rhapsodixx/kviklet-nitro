package dev.kviklet.kviklet.service.aireview

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "kviklet.ai-review")
class AiReviewProperties {
    var openrouter: OpenRouter = OpenRouter()
    var primaryModel: String = "qwen/qwen3-coder-plus"
    var fallbackModel: String = "openai/gpt-4o"
    var timeout: Duration = Duration.ofSeconds(60)
    var maxPromptChars: Int = 50_000

    fun isConfigured(): Boolean = !openrouter.apiKey.isNullOrBlank()

    class OpenRouter {
        var apiKey: String? = null
        var baseUrl: String = "https://openrouter.ai/api/v1"
    }
}

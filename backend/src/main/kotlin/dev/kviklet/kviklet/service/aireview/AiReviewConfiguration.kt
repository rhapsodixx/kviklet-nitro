package dev.kviklet.kviklet.service.aireview

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableConfigurationProperties(AiReviewProperties::class)
@EnableAsync
class AiReviewConfiguration {

    @Bean(name = ["aiReviewTaskExecutor"])
    fun aiReviewTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("ai-review-")
        executor.initialize()
        return executor
    }
}

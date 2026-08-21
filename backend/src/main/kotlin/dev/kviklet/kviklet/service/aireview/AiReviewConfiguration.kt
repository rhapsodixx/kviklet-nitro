package dev.kviklet.kviklet.service.aireview

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

@Configuration
@EnableConfigurationProperties(AiReviewProperties::class)
@EnableAsync
class AiReviewConfiguration

package dev.kviklet.kviklet.service.dto

import dev.kviklet.kviklet.service.aireview.AiFindingSeverity
import dev.kviklet.kviklet.service.aireview.AiReviewAttemptStatus
import java.time.LocalDateTime

data class AiFinding(
    val severity: AiFindingSeverity,
    val category: String,
    val explanation: String,
    val fix: String,
)

data class AiQueryReviewAttempt(
    val id: String? = null,
    val executionRequestId: ExecutionRequestId,
    val revisionFingerprint: String,
    val status: AiReviewAttemptStatus,
    val summary: String? = null,
    val findings: List<AiFinding>? = null,
    val suggestedSql: String? = null,
    val model: String? = null,
    val promptPolicyVersion: String? = null,
    val errorCategory: String? = null,
    val createdAt: LocalDateTime = utcTimeNow(),
    val completedAt: LocalDateTime? = null,
)

data class AiQueryReviewOverride(
    val id: String? = null,
    val executionRequestId: ExecutionRequestId,
    val revisionFingerprint: String,
    val actorId: String,
    val reason: String,
    val createdAt: LocalDateTime = utcTimeNow(),
)

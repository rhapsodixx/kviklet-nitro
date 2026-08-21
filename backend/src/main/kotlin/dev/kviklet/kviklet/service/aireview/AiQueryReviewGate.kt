package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.AiQueryReviewAttempt
import dev.kviklet.kviklet.service.dto.AiQueryReviewOverride
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.RequestType

enum class AiGateDecision {
    ALLOWED,
    BLOCKED_PENDING,
    BLOCKED_REJECTED,
    BLOCKED_FAILED,
    BLOCKED_MISSING,
    NOT_APPLICABLE,
}

object AiQueryReviewGate {

    fun isRelational(engine: DatasourceType?): Boolean = when (engine) {
        DatasourceType.POSTGRESQL,
        DatasourceType.MYSQL,
        DatasourceType.MARIADB,
        DatasourceType.MSSQL,
        -> true
        DatasourceType.MONGODB, null -> false
    }

    fun decide(
        mode: AiReviewMode,
        requestType: RequestType,
        engine: DatasourceType?,
        latestAttempt: AiQueryReviewAttempt?,
        override: AiQueryReviewOverride?,
        currentFingerprint: String,
    ): AiGateDecision {
        if (requestType != RequestType.SingleExecution || !isRelational(engine) || mode == AiReviewMode.DISABLED) {
            return AiGateDecision.NOT_APPLICABLE
        }
        if (mode == AiReviewMode.OPTIONAL) {
            return AiGateDecision.ALLOWED
        }

        val attempt = latestAttempt?.takeIf { it.revisionFingerprint == currentFingerprint }
        val matchingOverride = override?.takeIf { it.revisionFingerprint == currentFingerprint }

        if (attempt == null) {
            return AiGateDecision.BLOCKED_MISSING
        }

        if (matchingOverride != null && attempt.status == AiReviewAttemptStatus.FAILED) {
            return AiGateDecision.ALLOWED
        }

        return when (attempt.status) {
            AiReviewAttemptStatus.APPROVED,
            AiReviewAttemptStatus.APPROVED_WITH_NOTES,
            -> AiGateDecision.ALLOWED
            AiReviewAttemptStatus.PENDING -> AiGateDecision.BLOCKED_PENDING
            AiReviewAttemptStatus.REJECTED -> AiGateDecision.BLOCKED_REJECTED
            AiReviewAttemptStatus.FAILED -> AiGateDecision.BLOCKED_FAILED
        }
    }

    fun blocksExecution(decision: AiGateDecision): Boolean = when (decision) {
        AiGateDecision.BLOCKED_PENDING,
        AiGateDecision.BLOCKED_REJECTED,
        AiGateDecision.BLOCKED_FAILED,
        AiGateDecision.BLOCKED_MISSING,
        -> true
        AiGateDecision.ALLOWED,
        AiGateDecision.NOT_APPLICABLE,
        -> false
    }
}

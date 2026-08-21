package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.AiFinding

object AiReviewVerdictNormalizer {

    fun normalize(findings: List<AiFinding>, modelVerdict: AiReviewAttemptStatus): AiReviewAttemptStatus {
        if (findings.any { it.severity == AiFindingSeverity.BLOCKER }) {
            return AiReviewAttemptStatus.REJECTED
        }
        if (findings.any { it.severity == AiFindingSeverity.WARNING || it.severity == AiFindingSeverity.INFO }) {
            return AiReviewAttemptStatus.APPROVED_WITH_NOTES
        }
        return AiReviewAttemptStatus.APPROVED
    }
}

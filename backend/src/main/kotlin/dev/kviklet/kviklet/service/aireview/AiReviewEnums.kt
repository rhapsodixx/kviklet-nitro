package dev.kviklet.kviklet.service.aireview

enum class AiReviewMode {
    DISABLED,
    OPTIONAL,
    MANDATORY,
}

enum class AiReviewAttemptStatus {
    PENDING,
    APPROVED,
    APPROVED_WITH_NOTES,
    REJECTED,
    FAILED,
}

enum class AiFindingSeverity {
    BLOCKER,
    WARNING,
    INFO,
}

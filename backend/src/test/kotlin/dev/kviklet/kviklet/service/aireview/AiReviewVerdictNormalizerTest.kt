package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.AiFinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiReviewVerdictNormalizerTest {

    @Test
    fun `blocker forces rejected`() {
        val status = AiReviewVerdictNormalizer.normalize(
            listOf(AiFinding(AiFindingSeverity.BLOCKER, "safety", "unbounded delete", "add WHERE")),
            AiReviewAttemptStatus.APPROVED,
        )
        assertEquals(AiReviewAttemptStatus.REJECTED, status)
    }
}

package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.RequestRevisionChangedEvent
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class AiQueryReviewCoordinatorTest {

    @Test
    fun `AFTER_COMMIT handler enqueues async review for event id`() {
        val aiQueryReviewService = mockk<AiQueryReviewService>(relaxed = true)
        val coordinator = AiQueryReviewCoordinator(aiQueryReviewService)
        val requestId = ExecutionRequestId("req-42")

        coordinator.onRequestRevisionChanged(RequestRevisionChangedEvent(requestId))

        verify(exactly = 1) { aiQueryReviewService.enqueueReviewAsync(requestId) }
    }
}

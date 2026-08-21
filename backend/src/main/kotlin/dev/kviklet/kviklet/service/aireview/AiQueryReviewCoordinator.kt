package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.RequestRevisionChangedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * After the create/update transaction commits, kick off an async AI review for the new revision.
 */
@Component
class AiQueryReviewCoordinator(
    private val aiQueryReviewService: AiQueryReviewService,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onRequestRevisionChanged(event: RequestRevisionChangedEvent) {
        aiQueryReviewService.enqueueReviewAsync(event.executionRequestId)
    }
}

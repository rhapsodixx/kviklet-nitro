package dev.kviklet.kviklet.service

import dev.kviklet.kviklet.service.dto.ExecutionRequestId

/**
 * Published after a datasource request create/update commits so AI review can run
 * against the persisted revision fingerprint.
 */
data class RequestRevisionChangedEvent(
    val executionRequestId: ExecutionRequestId,
)

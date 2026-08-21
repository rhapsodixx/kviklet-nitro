package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.db.AiQueryReviewAdapter
import dev.kviklet.kviklet.db.ExecutionRequestAdapter
import dev.kviklet.kviklet.security.NoPolicy
import dev.kviklet.kviklet.service.dto.AiQueryReviewAttempt
import dev.kviklet.kviklet.service.dto.AiQueryReviewOverride
import dev.kviklet.kviklet.service.dto.DatasourceExecutionRequest
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ExecutionRequestDetails
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.RequestType
import dev.kviklet.kviklet.service.dto.utcTimeNow
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Snapshot of AI review state for the current request revision.
 *
 * Mode is read from [DatasourceConnection.aiReviewMode] unless an explicit
 * [AiReviewMode] overload is used (tests / callers that already resolved mode).
 */
data class AiReviewSnapshot(
    val mode: AiReviewMode,
    val currentFingerprint: String,
    val latestAttempt: AiQueryReviewAttempt?,
    val override: AiQueryReviewOverride?,
    val blocksExecution: Boolean,
    val gate: AiGateDecision,
)

@Service
class AiQueryReviewService(
    private val adapter: AiQueryReviewAdapter,
    private val openRouterClient: OpenRouterClient,
    private val executionRequestAdapter: ExecutionRequestAdapter,
) {

    companion object {
        private const val RETRY_COOLDOWN_MS = 3_000L
    }

    /** Called from async / wiring paths; HTTP auth is enforced on ExecutionRequestService. */
    @NoPolicy
    fun enqueueReview(details: ExecutionRequestDetails) {
        enqueueReview(details, resolveMode(details))
    }

    @Async
    fun enqueueReviewAsync(executionRequestId: ExecutionRequestId) {
        val details = executionRequestAdapter.getExecutionRequestDetails(executionRequestId)
        enqueueReview(details)
    }

    @NoPolicy
    fun enqueueReview(details: ExecutionRequestDetails, mode: AiReviewMode) {
        if (mode == AiReviewMode.DISABLED) {
            return
        }
        val context = reviewContextOrNull(details) ?: return
        if (adapter.hasInFlightPending(context.executionRequestId, context.fingerprint)) {
            return
        }
        val pending = adapter.createPending(context.executionRequestId, context.fingerprint)
        runReview(pending, context)
    }

    @NoPolicy
    fun retry(id: ExecutionRequestId): AiQueryReviewAttempt {
        val details = executionRequestAdapter.getExecutionRequestDetails(id)
        return retry(id, resolveMode(details))
    }

    @NoPolicy
    fun retry(id: ExecutionRequestId, mode: AiReviewMode): AiQueryReviewAttempt {
        if (mode == AiReviewMode.DISABLED) {
            throw IllegalArgumentException("AI review is disabled for this connection")
        }
        val details = executionRequestAdapter.getExecutionRequestDetails(id)
        val context = reviewContextOrNull(details)
            ?: throw IllegalArgumentException("Request is not eligible for AI review")
        if (adapter.hasInFlightPending(context.executionRequestId, context.fingerprint)) {
            return adapter.findLatestForRevision(context.executionRequestId, context.fingerprint)
                ?: throw IllegalStateException("In-flight AI review pending but attempt not found")
        }
        val latest = adapter.findLatestForRevision(context.executionRequestId, context.fingerprint)
        if (latest != null &&
            latest.status == AiReviewAttemptStatus.FAILED &&
            latest.completedAt != null &&
            Duration.between(latest.completedAt, utcTimeNow()).toMillis() < RETRY_COOLDOWN_MS
        ) {
            throw IllegalArgumentException(
                "AI review retry rate limited; wait a few seconds before retrying",
            )
        }
        val pending = adapter.createPending(context.executionRequestId, context.fingerprint)
        return runReview(pending, context)
    }

    @NoPolicy
    fun overrideFailed(id: ExecutionRequestId, actorId: String, reason: String): AiQueryReviewOverride {
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) {
            throw IllegalArgumentException("Override reason is required")
        }
        val details = executionRequestAdapter.getExecutionRequestDetails(id)
        val context = reviewContextOrNull(details)
            ?: throw IllegalArgumentException("Request is not eligible for AI review")
        val latest = adapter.findLatestForRevision(context.executionRequestId, context.fingerprint)
        if (latest == null || latest.status != AiReviewAttemptStatus.FAILED) {
            throw IllegalArgumentException("Override is only allowed when the current revision AI review FAILED")
        }
        return adapter.createOverride(
            executionRequestId = context.executionRequestId,
            fingerprint = context.fingerprint,
            actorId = actorId,
            reason = trimmedReason,
        )
    }

    @NoPolicy
    fun currentSnapshot(details: ExecutionRequestDetails): AiReviewSnapshot =
        currentSnapshot(details, resolveMode(details))

    @NoPolicy
    fun currentSnapshot(details: ExecutionRequestDetails, mode: AiReviewMode): AiReviewSnapshot {
        val context = reviewContextOrNull(details)
        if (context == null) {
            val gate = AiQueryReviewGate.decide(
                mode = mode,
                requestType = details.request.type,
                engine = (details.request as? DatasourceExecutionRequest)?.connection?.type,
                latestAttempt = null,
                override = null,
                currentFingerprint = "",
            )
            return AiReviewSnapshot(
                mode = mode,
                currentFingerprint = "",
                latestAttempt = null,
                override = null,
                blocksExecution = AiQueryReviewGate.blocksExecution(gate),
                gate = gate,
            )
        }

        val latestAttempt = adapter.findLatestForRevision(context.executionRequestId, context.fingerprint)
        val override = adapter.findLatestOverride(context.executionRequestId, context.fingerprint)
        val gate = AiQueryReviewGate.decide(
            mode = mode,
            requestType = context.requestType,
            engine = context.engine,
            latestAttempt = latestAttempt,
            override = override,
            currentFingerprint = context.fingerprint,
        )
        return AiReviewSnapshot(
            mode = mode,
            currentFingerprint = context.fingerprint,
            latestAttempt = latestAttempt,
            override = override,
            blocksExecution = AiQueryReviewGate.blocksExecution(gate),
            gate = gate,
        )
    }

    private fun runReview(pending: AiQueryReviewAttempt, context: ReviewContext): AiQueryReviewAttempt {
        val attemptId = pending.id
            ?: throw IllegalStateException("Pending AI review attempt is missing an id")
        val builtPrompt = AiReviewPromptBuilder.build(
            engine = context.engine,
            statement = context.statement,
            title = context.title,
            description = context.description,
        )
        return try {
            val result = openRouterClient.review(builtPrompt.system, builtPrompt.user)
            adapter.complete(
                attemptId = attemptId,
                status = result.verdict,
                summary = result.summary,
                findings = result.findings,
                suggestedSql = result.suggestedSql,
                model = result.model,
                promptPolicyVersion = builtPrompt.policyVersion,
                errorCategory = null,
            )
        } catch (ex: OpenRouterClientException) {
            adapter.complete(
                attemptId = attemptId,
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = builtPrompt.policyVersion,
                errorCategory = ex.category.name,
            )
        } catch (_: Exception) {
            adapter.complete(
                attemptId = attemptId,
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = builtPrompt.policyVersion,
                errorCategory = AiReviewErrorCategory.INVALID_RESPONSE.name,
            )
        }
    }

    private fun resolveMode(details: ExecutionRequestDetails): AiReviewMode {
        val connection = (details.request as? DatasourceExecutionRequest)?.connection
        return connection?.aiReviewMode ?: AiReviewMode.DISABLED
    }

    private fun reviewContextOrNull(details: ExecutionRequestDetails): ReviewContext? {
        val request = details.request as? DatasourceExecutionRequest ?: return null
        if (request.type != RequestType.SingleExecution) {
            return null
        }
        val engine = request.connection.type
        if (!AiQueryReviewGate.isRelational(engine)) {
            return null
        }
        val statement = request.statement?.takeIf { it.isNotBlank() } ?: return null
        val executionRequestId = request.id ?: return null
        val fingerprint = RevisionFingerprint.compute(
            engine = engine,
            statement = statement,
            title = request.title,
            description = request.description,
            requestType = request.type,
        )
        return ReviewContext(
            executionRequestId = executionRequestId,
            engine = engine,
            statement = statement,
            title = request.title,
            description = request.description,
            requestType = request.type,
            fingerprint = fingerprint,
        )
    }

    private data class ReviewContext(
        val executionRequestId: ExecutionRequestId,
        val engine: DatasourceType,
        val statement: String,
        val title: String,
        val description: String?,
        val requestType: RequestType,
        val fingerprint: String,
    )
}

package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.db.AiQueryReviewAdapter
import dev.kviklet.kviklet.db.ExecutionRequestAdapter
import dev.kviklet.kviklet.db.User
import dev.kviklet.kviklet.db.UserId
import dev.kviklet.kviklet.service.dto.AiQueryReviewAttempt
import dev.kviklet.kviklet.service.dto.AiQueryReviewOverride
import dev.kviklet.kviklet.service.dto.AuthenticationDetails
import dev.kviklet.kviklet.service.dto.AuthenticationType
import dev.kviklet.kviklet.service.dto.ConnectionId
import dev.kviklet.kviklet.service.dto.DatasourceConnection
import dev.kviklet.kviklet.service.dto.DatasourceExecutionRequest
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ExecutionRequestDetails
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.RequestType
import dev.kviklet.kviklet.service.dto.ReviewConfig
import dev.kviklet.kviklet.service.dto.utcTimeNow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

class AiQueryReviewServiceTest {

    private val adapter = mockk<AiQueryReviewAdapter>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val executionRequestAdapter = mockk<ExecutionRequestAdapter>()
    private val properties = AiReviewProperties().apply {
        timeout = Duration.ofSeconds(60)
    }

    private lateinit var service: AiQueryReviewService

    private val requestId = ExecutionRequestId("req-1")
    private val fingerprint = RevisionFingerprint.compute(
        engine = DatasourceType.POSTGRESQL,
        statement = "SELECT 1",
        title = "title",
        description = "desc",
        requestType = RequestType.SingleExecution,
    )

    @BeforeEach
    fun setUp() {
        service = AiQueryReviewService(adapter, openRouterClient, executionRequestAdapter, properties)
    }

    @Test
    fun `enqueueReview no-ops when DISABLED`() {
        service.enqueueReview(details(), AiReviewMode.DISABLED)

        verify(exactly = 0) { adapter.createPending(any(), any()) }
        verify(exactly = 0) { openRouterClient.review(any(), any()) }
    }

    @Test
    fun `enqueueReview no-ops when TemporaryAccess`() {
        service.enqueueReview(
            details(type = RequestType.TemporaryAccess),
            AiReviewMode.MANDATORY,
        )

        verify(exactly = 0) { adapter.createPending(any(), any()) }
    }

    @Test
    fun `enqueueReview no-ops when MongoDB`() {
        service.enqueueReview(
            details(engine = DatasourceType.MONGODB),
            AiReviewMode.MANDATORY,
        )

        verify(exactly = 0) { adapter.createPending(any(), any()) }
    }

    @Test
    fun `enqueueReview dedupes in-flight PENDING`() {
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns attempt(
            AiReviewAttemptStatus.PENDING,
            createdAt = utcTimeNow(),
        )

        service.enqueueReview(details(), AiReviewMode.MANDATORY)

        verify(exactly = 0) { adapter.createPending(any(), any()) }
        verify(exactly = 0) { openRouterClient.review(any(), any()) }
    }

    @Test
    fun `enqueueReview creates pending calls OpenRouter and completes`() {
        val pending = attempt(AiReviewAttemptStatus.PENDING)
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns null
        every { adapter.hasInFlightPending(requestId, fingerprint) } returns false
        every { adapter.createPending(requestId, fingerprint) } returns pending
        every { openRouterClient.review(any(), any()) } returns OpenRouterReviewResult(
            model = "qwen/qwen3-coder-plus",
            verdict = AiReviewAttemptStatus.APPROVED,
            summary = "ok",
            findings = emptyList(),
            suggestedSql = null,
        )
        every {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.APPROVED,
                summary = "ok",
                findings = emptyList(),
                suggestedSql = null,
                model = "qwen/qwen3-coder-plus",
                promptPolicyVersion = PROMPT_POLICY_VERSION,
                errorCategory = null,
            )
        } returns attempt(AiReviewAttemptStatus.APPROVED)

        service.enqueueReview(details(), AiReviewMode.OPTIONAL)

        verify(exactly = 1) { adapter.createPending(requestId, fingerprint) }
        verify(exactly = 1) { openRouterClient.review(any(), any()) }
        verify(exactly = 1) {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.APPROVED,
                summary = "ok",
                findings = emptyList(),
                suggestedSql = null,
                model = "qwen/qwen3-coder-plus",
                promptPolicyVersion = PROMPT_POLICY_VERSION,
                errorCategory = null,
            )
        }
    }

    @Test
    fun `enqueueReview marks FAILED with error category on OpenRouter exception`() {
        val pending = attempt(AiReviewAttemptStatus.PENDING)
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns null
        every { adapter.hasInFlightPending(requestId, fingerprint) } returns false
        every { adapter.createPending(requestId, fingerprint) } returns pending
        every { openRouterClient.review(any(), any()) } throws OpenRouterClientException(
            AiReviewErrorCategory.TIMEOUT,
            "timed out",
        )
        every {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = PROMPT_POLICY_VERSION,
                errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            )
        } returns attempt(AiReviewAttemptStatus.FAILED, errorCategory = AiReviewErrorCategory.TIMEOUT.name)

        service.enqueueReview(details(), AiReviewMode.MANDATORY)

        verify(exactly = 1) {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = PROMPT_POLICY_VERSION,
                errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            )
        }
    }

    @Test
    fun `retry creates PENDING and schedules continue without calling OpenRouter`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns null
        every { adapter.hasInFlightPending(requestId, fingerprint) } returns false
        every { adapter.createPending(requestId, fingerprint) } returns attempt(AiReviewAttemptStatus.PENDING)

        val result = service.retry(requestId, AiReviewMode.MANDATORY)

        assertEquals(AiReviewAttemptStatus.PENDING, result.attempt.status)
        assertTrue(result.scheduleContinue)
        verify(exactly = 1) { adapter.createPending(requestId, fingerprint) }
        verify(exactly = 0) { openRouterClient.review(any(), any()) }
    }

    @Test
    fun `retry returns in-flight pending attempt without creating another`() {
        val pending = attempt(AiReviewAttemptStatus.PENDING, createdAt = utcTimeNow())
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns pending

        val result = service.retry(requestId, AiReviewMode.MANDATORY)

        assertEquals(AiReviewAttemptStatus.PENDING, result.attempt.status)
        assertFalse(result.scheduleContinue)
        verify(exactly = 0) { adapter.createPending(any(), any()) }
        verify(exactly = 0) { openRouterClient.review(any(), any()) }
    }

    @Test
    fun `retry times out stuck PENDING then creates a new attempt`() {
        val stuck = attempt(
            status = AiReviewAttemptStatus.PENDING,
            createdAt = utcTimeNow().minusSeconds(61),
        )
        val failed = attempt(
            status = AiReviewAttemptStatus.FAILED,
            errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            completedAt = utcTimeNow(),
        )
        val newPending = attempt(AiReviewAttemptStatus.PENDING, id = "attempt-2")

        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returnsMany listOf(
            stuck,
            failed,
            failed,
            newPending,
        )
        every {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = null,
                errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            )
        } returns failed
        every { adapter.hasInFlightPending(requestId, fingerprint) } returns false
        every { adapter.createPending(requestId, fingerprint) } returns newPending

        val result = service.retry(requestId, AiReviewMode.MANDATORY)

        assertEquals("attempt-2", result.attempt.id)
        assertTrue(result.scheduleContinue)
        verify(exactly = 1) {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = null,
                errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            )
        }
        verify(exactly = 1) { adapter.createPending(requestId, fingerprint) }
    }

    @Test
    fun `retry rejects when last FAILED completed less than 3 seconds ago`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns attempt(
            status = AiReviewAttemptStatus.FAILED,
            completedAt = utcTimeNow(),
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.retry(requestId, AiReviewMode.MANDATORY)
        }

        assertTrue(ex.message!!.contains("rate limited"))
        verify(exactly = 0) { adapter.createPending(any(), any()) }
    }

    @Test
    fun `retry allows when last FAILED completed more than 3 seconds ago`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns attempt(
            status = AiReviewAttemptStatus.FAILED,
            completedAt = utcTimeNow().minusSeconds(4),
        )
        every { adapter.hasInFlightPending(requestId, fingerprint) } returns false
        every { adapter.createPending(requestId, fingerprint) } returns attempt(AiReviewAttemptStatus.PENDING)

        val result = service.retry(requestId, AiReviewMode.MANDATORY)

        assertEquals(AiReviewAttemptStatus.PENDING, result.attempt.status)
        assertTrue(result.scheduleContinue)
        verify(exactly = 1) { adapter.createPending(requestId, fingerprint) }
    }

    @Test
    fun `overrideFailed rejects blank reason`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.overrideFailed(requestId, "admin-1", "   ")
        }
        verify(exactly = 0) { adapter.createOverride(any(), any(), any(), any()) }
    }

    @Test
    fun `overrideFailed rejects when latest is not FAILED`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns
            attempt(AiReviewAttemptStatus.REJECTED)

        assertThrows(IllegalArgumentException::class.java) {
            service.overrideFailed(requestId, "admin-1", "need to ship")
        }
        verify(exactly = 0) { adapter.createOverride(any(), any(), any(), any()) }
    }

    @Test
    fun `overrideFailed creates override for current FAILED revision`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns
            attempt(AiReviewAttemptStatus.FAILED)
        every {
            adapter.createOverride(requestId, fingerprint, "admin-1", "provider outage")
        } returns AiQueryReviewOverride(
            id = "override-1",
            executionRequestId = requestId,
            revisionFingerprint = fingerprint,
            actorId = "admin-1",
            reason = "provider outage",
        )

        val result = service.overrideFailed(requestId, "admin-1", "provider outage")

        assertEquals("override-1", result.id)
        assertEquals(fingerprint, result.revisionFingerprint)
    }

    @Test
    fun `overrideFailed treats timed-out PENDING as FAILED`() {
        val stuck = attempt(
            status = AiReviewAttemptStatus.PENDING,
            createdAt = utcTimeNow().minusSeconds(90),
        )
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns stuck
        every {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = null,
                errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            )
        } returns attempt(
            status = AiReviewAttemptStatus.FAILED,
            errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            completedAt = utcTimeNow(),
        )
        every {
            adapter.createOverride(requestId, fingerprint, "admin-1", "stuck review")
        } returns AiQueryReviewOverride(
            id = "override-2",
            executionRequestId = requestId,
            revisionFingerprint = fingerprint,
            actorId = "admin-1",
            reason = "stuck review",
        )

        val result = service.overrideFailed(requestId, "admin-1", "stuck review")

        assertEquals("override-2", result.id)
        verify(exactly = 1) {
            adapter.complete(
                attemptId = "attempt-1",
                status = AiReviewAttemptStatus.FAILED,
                summary = null,
                findings = null,
                suggestedSql = null,
                model = null,
                promptPolicyVersion = null,
                errorCategory = AiReviewErrorCategory.TIMEOUT.name,
            )
        }
    }

    @Test
    fun `overrideFailed rejects fresh PENDING`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns attempt(
            status = AiReviewAttemptStatus.PENDING,
            createdAt = utcTimeNow(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.overrideFailed(requestId, "admin-1", "too soon")
        }
        verify(exactly = 0) { adapter.createOverride(any(), any(), any(), any()) }
    }

    @Test
    fun `currentSnapshot MANDATORY PENDING blocks execution`() {
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns
            attempt(AiReviewAttemptStatus.PENDING)
        every { adapter.findLatestOverride(requestId, fingerprint) } returns null

        val snapshot = service.currentSnapshot(details(), AiReviewMode.MANDATORY)

        assertEquals(AiGateDecision.BLOCKED_PENDING, snapshot.gate)
        assertTrue(snapshot.blocksExecution)
        assertEquals(fingerprint, snapshot.currentFingerprint)
        assertEquals(AiReviewMode.MANDATORY, snapshot.mode)
    }

    @Test
    fun `currentSnapshot OPTIONAL never blocks even when REJECTED`() {
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns
            attempt(AiReviewAttemptStatus.REJECTED)
        every { adapter.findLatestOverride(requestId, fingerprint) } returns null

        val snapshot = service.currentSnapshot(details(), AiReviewMode.OPTIONAL)

        assertEquals(AiGateDecision.ALLOWED, snapshot.gate)
        assertFalse(snapshot.blocksExecution)
    }

    @Test
    fun `currentSnapshot MANDATORY FAILED with override allows execution`() {
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns
            attempt(AiReviewAttemptStatus.FAILED)
        every { adapter.findLatestOverride(requestId, fingerprint) } returns AiQueryReviewOverride(
            id = "override-1",
            executionRequestId = requestId,
            revisionFingerprint = fingerprint,
            actorId = "admin-1",
            reason = "outage",
        )

        val snapshot = service.currentSnapshot(details(), AiReviewMode.MANDATORY)

        assertEquals(AiGateDecision.ALLOWED, snapshot.gate)
        assertFalse(snapshot.blocksExecution)
        assertEquals("override-1", snapshot.override?.id)
    }

    @Test
    fun `enqueueReviewAsync loads details and enqueues using connection mode`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns
            details(aiReviewMode = AiReviewMode.OPTIONAL)
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns null
        every { adapter.hasInFlightPending(requestId, fingerprint) } returns false
        every { adapter.createPending(requestId, fingerprint) } returns attempt(AiReviewAttemptStatus.PENDING)
        every { openRouterClient.review(any(), any()) } returns OpenRouterReviewResult(
            model = "model",
            verdict = AiReviewAttemptStatus.APPROVED,
            summary = "ok",
            findings = emptyList(),
            suggestedSql = null,
        )
        every {
            adapter.complete(any(), any(), any(), any(), any(), any(), any(), any())
        } returns attempt(AiReviewAttemptStatus.APPROVED)

        service.enqueueReviewAsync(requestId)

        verify(exactly = 1) { adapter.createPending(requestId, fingerprint) }
        verify(exactly = 1) { openRouterClient.review(any(), any()) }
    }

    @Test
    fun `continuePendingReview runs OpenRouter for current PENDING`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns
            details(aiReviewMode = AiReviewMode.MANDATORY)
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns attempt(
            AiReviewAttemptStatus.PENDING,
            createdAt = utcTimeNow(),
        )
        every { openRouterClient.review(any(), any()) } returns OpenRouterReviewResult(
            model = "model",
            verdict = AiReviewAttemptStatus.APPROVED,
            summary = "ok",
            findings = emptyList(),
            suggestedSql = null,
        )
        every {
            adapter.complete(any(), any(), any(), any(), any(), any(), any(), any())
        } returns attempt(AiReviewAttemptStatus.APPROVED)

        service.continuePendingReview(requestId)

        verify(exactly = 1) { openRouterClient.review(any(), any()) }
    }

    @Test
    fun `currentSnapshot without mode reads aiReviewMode from connection`() {
        every { adapter.findLatestForRevision(requestId, fingerprint) } returns
            attempt(AiReviewAttemptStatus.PENDING)
        every { adapter.findLatestOverride(requestId, fingerprint) } returns null

        val snapshot = service.currentSnapshot(details(aiReviewMode = AiReviewMode.MANDATORY))

        assertEquals(AiGateDecision.BLOCKED_PENDING, snapshot.gate)
        assertEquals(AiReviewMode.MANDATORY, snapshot.mode)
    }

    private fun details(
        type: RequestType = RequestType.SingleExecution,
        engine: DatasourceType = DatasourceType.POSTGRESQL,
        statement: String? = "SELECT 1",
        aiReviewMode: AiReviewMode = AiReviewMode.DISABLED,
    ): ExecutionRequestDetails {
        val connection = DatasourceConnection(
            id = ConnectionId("conn-1"),
            displayName = "db",
            description = "",
            reviewConfig = ReviewConfig(numTotalRequired = 1),
            maxExecutions = 1,
            databaseName = "app",
            authenticationType = AuthenticationType.USER_PASSWORD,
            auth = AuthenticationDetails.UserPassword("user", "pass"),
            port = 5432,
            hostname = "localhost",
            type = engine,
            protocol = engine.toProtocol(),
            additionalOptions = "",
            dumpsEnabled = false,
            temporaryAccessEnabled = false,
            explainEnabled = false,
            storeResults = false,
            dryRunEnabled = false,
            dryRunRequiresApproval = false,
            aiReviewMode = aiReviewMode,
        )
        val request = DatasourceExecutionRequest(
            id = requestId,
            connection = connection,
            title = "title",
            type = type,
            description = "desc",
            statement = statement,
            executionStatus = "EXECUTABLE",
            reviewStatus = "AWAITING_APPROVAL",
            author = User(id = UserId("author-1"), email = "a@example.com"),
            temporaryAccessDuration = null,
        )
        return ExecutionRequestDetails(request = request, events = mutableSetOf())
    }

    private fun attempt(
        status: AiReviewAttemptStatus,
        errorCategory: String? = null,
        completedAt: LocalDateTime? = null,
        createdAt: LocalDateTime = utcTimeNow(),
        id: String = "attempt-1",
    ) = AiQueryReviewAttempt(
        id = id,
        executionRequestId = requestId,
        revisionFingerprint = fingerprint,
        status = status,
        errorCategory = errorCategory,
        createdAt = createdAt,
        completedAt = completedAt,
    )
}

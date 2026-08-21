package dev.kviklet.kviklet.controller

import dev.kviklet.kviklet.db.User
import dev.kviklet.kviklet.db.UserAdapter
import dev.kviklet.kviklet.db.UserId
import dev.kviklet.kviklet.security.Permission
import dev.kviklet.kviklet.security.UserDetailsWithId
import dev.kviklet.kviklet.service.ExecutionRequestService
import dev.kviklet.kviklet.service.aireview.AiFindingSeverity
import dev.kviklet.kviklet.service.aireview.AiGateDecision
import dev.kviklet.kviklet.service.aireview.AiQueryReviewService
import dev.kviklet.kviklet.service.aireview.AiReviewAttemptStatus
import dev.kviklet.kviklet.service.aireview.AiReviewMode
import dev.kviklet.kviklet.service.aireview.AiReviewSnapshot
import dev.kviklet.kviklet.service.dto.AiFinding
import dev.kviklet.kviklet.service.dto.AiQueryReviewAttempt
import dev.kviklet.kviklet.service.dto.AiQueryReviewOverride
import dev.kviklet.kviklet.service.dto.AuthenticationDetails
import dev.kviklet.kviklet.service.dto.AuthenticationType
import dev.kviklet.kviklet.service.dto.ConnectionId
import dev.kviklet.kviklet.service.dto.DatabaseProtocol
import dev.kviklet.kviklet.service.dto.DatasourceConnection
import dev.kviklet.kviklet.service.dto.DatasourceExecutionRequest
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ExecutionRequestDetails
import dev.kviklet.kviklet.service.dto.ExecutionRequestDetailsWithRoles
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.RequestType
import dev.kviklet.kviklet.service.dto.ReviewConfig
import dev.kviklet.kviklet.service.dto.utcTimeNow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import dev.kviklet.kviklet.security.Policy as PolicyAnnotation

class AiReviewApiMappingTest {

    private val executionRequestService = mockk<ExecutionRequestService>()
    private val aiQueryReviewService = mockk<AiQueryReviewService>()
    private val userAdapter = mockk<UserAdapter>()

    private lateinit var controller: ExecutionRequestController

    private val requestId = ExecutionRequestId("req-1")

    @BeforeEach
    fun setUp() {
        controller = ExecutionRequestController(
            executionRequestService,
            aiQueryReviewService,
            userAdapter,
        )
    }

    @Test
    fun `detail response maps AI review fields from snapshot`() {
        val details = detailsWithRoles()
        val attempt = AiQueryReviewAttempt(
            id = "attempt-1",
            executionRequestId = requestId,
            revisionFingerprint = "fp",
            status = AiReviewAttemptStatus.REJECTED,
            summary = "risky",
            findings = listOf(
                AiFinding(AiFindingSeverity.BLOCKER, "drop", "drops table", "remove DROP"),
            ),
            suggestedSql = "SELECT 1",
            model = "model-x",
            promptPolicyVersion = "v1",
            errorCategory = null,
            createdAt = utcTimeNow(),
            completedAt = utcTimeNow(),
        )
        val override = AiQueryReviewOverride(
            id = "ov-1",
            executionRequestId = requestId,
            revisionFingerprint = "fp",
            actorId = "admin-1",
            reason = "outage",
        )
        every { executionRequestService.get(requestId) } returns details
        every { aiQueryReviewService.currentSnapshot(details.details) } returns AiReviewSnapshot(
            mode = AiReviewMode.MANDATORY,
            currentFingerprint = "fp",
            latestAttempt = attempt,
            override = override,
            blocksExecution = true,
            gate = AiGateDecision.BLOCKED_FAILED,
        )
        every { userAdapter.findById("admin-1") } returns User(
            id = UserId("admin-1"),
            email = "admin@example.com",
            fullName = "Admin User",
        )

        val response = controller.get(requestId) as DatasourceExecutionRequestDetailResponse

        assertEquals(AiReviewMode.MANDATORY, response.aiReviewMode)
        assertEquals("REJECTED", response.aiReview?.status)
        assertEquals(1, response.aiReview?.findings?.size)
        assertEquals("BLOCKER", response.aiReview?.findings?.first()?.severity)
        assertEquals("outage", response.aiReviewOverride?.reason)
        assertEquals("Admin User", response.aiReviewOverride?.actorName)
        assertTrue(response.aiReviewBlocksExecution)
    }

    @Test
    fun `retry endpoint returns mapped attempt after service retry`() {
        val details = detailsWithRoles().details
        val attempt = AiQueryReviewAttempt(
            id = "attempt-2",
            executionRequestId = requestId,
            revisionFingerprint = "fp",
            status = AiReviewAttemptStatus.APPROVED,
            summary = "ok",
            findings = emptyList(),
            createdAt = utcTimeNow(),
            completedAt = utcTimeNow(),
        )
        every { executionRequestService.retryAiReview(requestId) } returns details
        every { aiQueryReviewService.currentSnapshot(details) } returns AiReviewSnapshot(
            mode = AiReviewMode.OPTIONAL,
            currentFingerprint = "fp",
            latestAttempt = attempt,
            override = null,
            blocksExecution = false,
            gate = AiGateDecision.ALLOWED,
        )

        val response = controller.retryAiReview(requestId)

        assertEquals("APPROVED", response.status)
        assertEquals("ok", response.summary)
        verify(exactly = 1) { executionRequestService.retryAiReview(requestId) }
    }

    @Test
    fun `override endpoint returns mapped override with actor name`() {
        val details = detailsWithRoles().details
        val override = AiQueryReviewOverride(
            id = "ov-1",
            executionRequestId = requestId,
            revisionFingerprint = "fp",
            actorId = "admin-1",
            reason = "OpenRouter outage; manually verified",
        )
        every {
            executionRequestService.overrideAiReview(
                requestId,
                "admin-1",
                "OpenRouter outage; manually verified",
            )
        } returns details
        every { aiQueryReviewService.currentSnapshot(details) } returns AiReviewSnapshot(
            mode = AiReviewMode.MANDATORY,
            currentFingerprint = "fp",
            latestAttempt = AiQueryReviewAttempt(
                id = "attempt-1",
                executionRequestId = requestId,
                revisionFingerprint = "fp",
                status = AiReviewAttemptStatus.FAILED,
            ),
            override = override,
            blocksExecution = false,
            gate = AiGateDecision.ALLOWED,
        )
        every { userAdapter.findById("admin-1") } returns User(
            id = UserId("admin-1"),
            email = "admin@example.com",
            fullName = null,
        )

        val response = controller.overrideAiReview(
            requestId,
            AiReviewOverrideRequest("OpenRouter outage; manually verified"),
            UserDetailsWithId("admin-1", "admin", "pw", emptyList()),
        )

        assertEquals("OpenRouter outage; manually verified", response.reason)
        assertEquals("admin@example.com", response.actorName)
    }

    @Test
    fun `attempt response maps findings to empty list when null`() {
        val mapped = AiReviewAttemptResponse.fromDto(
            AiQueryReviewAttempt(
                id = "a1",
                executionRequestId = requestId,
                revisionFingerprint = "fp",
                status = AiReviewAttemptStatus.FAILED,
                findings = null,
                errorCategory = "TIMEOUT",
            ),
        )
        assertEquals(emptyList<AiReviewFindingResponse>(), mapped.findings)
        assertEquals("TIMEOUT", mapped.errorCategory)
        assertNull(mapped.summary)
    }

    @Test
    fun `retryAiReview service method is annotated with EXECUTION_REQUEST_GET`() {
        val method = ExecutionRequestService::class.java.getMethod(
            "retryAiReview",
            ExecutionRequestId::class.java,
        )
        assertPolicy(method, Permission.EXECUTION_REQUEST_GET)
    }

    @Test
    fun `overrideAiReview service method is annotated with OVERRIDE_AI_REVIEW`() {
        val method = ExecutionRequestService::class.java.getMethod(
            "overrideAiReview",
            ExecutionRequestId::class.java,
            String::class.java,
            String::class.java,
        )
        assertPolicy(method, Permission.EXECUTION_REQUEST_OVERRIDE_AI_REVIEW)
    }

    private fun assertPolicy(method: Method, expected: Permission) {
        val annotation = method.getAnnotation(PolicyAnnotation::class.java)
        assertEquals(expected, annotation.permission)
    }

    private fun detailsWithRoles(): ExecutionRequestDetailsWithRoles {
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
            type = DatasourceType.POSTGRESQL,
            protocol = DatabaseProtocol.POSTGRESQL,
            additionalOptions = "",
            dumpsEnabled = false,
            temporaryAccessEnabled = false,
            explainEnabled = false,
            storeResults = false,
            dryRunEnabled = false,
            dryRunRequiresApproval = false,
            aiReviewMode = AiReviewMode.MANDATORY,
        )
        val request = DatasourceExecutionRequest(
            id = requestId,
            connection = connection,
            title = "title",
            type = RequestType.SingleExecution,
            description = "desc",
            statement = "SELECT 1",
            executionStatus = "EXECUTABLE",
            reviewStatus = "AWAITING_APPROVAL",
            author = User(id = UserId("author-1"), email = "a@example.com"),
            temporaryAccessDuration = null,
        )
        val details = ExecutionRequestDetails(request = request, events = mutableSetOf())
        return ExecutionRequestDetailsWithRoles(
            details = details,
            resolvedRoles = emptyMap(),
            permissions = emptySet(),
        )
    }
}

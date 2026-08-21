package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.db.User
import dev.kviklet.kviklet.db.UserId
import dev.kviklet.kviklet.service.InvalidReviewException
import dev.kviklet.kviklet.service.raiseIfAiReviewBlocks
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
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AiReviewExecutionGateTest {

    private val aiQueryReviewService = mockk<AiQueryReviewService>()

    @ParameterizedTest
    @CsvSource(
        "BLOCKED_PENDING, AI query review is still pending",
        "BLOCKED_REJECTED, AI query review rejected this revision",
        "BLOCKED_FAILED, 'AI query review failed; retry or request an admin override'",
        "BLOCKED_MISSING, AI query review has not completed",
    )
    fun `raiseIfAiReviewBlocks throws distinct messages`(gate: AiGateDecision, expectedMessage: String) {
        every { aiQueryReviewService.currentSnapshot(any()) } returns snapshot(gate, blocks = true)

        val ex = assertThrows(InvalidReviewException::class.java) {
            details().raiseIfAiReviewBlocks(aiQueryReviewService)
        }
        assertEquals(expectedMessage, ex.message)
    }

    @Test
    fun `raiseIfAiReviewBlocks allows when not blocking`() {
        every { aiQueryReviewService.currentSnapshot(any()) } returns snapshot(AiGateDecision.ALLOWED, blocks = false)

        details().raiseIfAiReviewBlocks(aiQueryReviewService)
    }

    private fun snapshot(gate: AiGateDecision, blocks: Boolean) = AiReviewSnapshot(
        mode = AiReviewMode.MANDATORY,
        currentFingerprint = "fp",
        latestAttempt = null,
        override = null,
        blocksExecution = blocks,
        gate = gate,
    )

    private fun details(): ExecutionRequestDetails {
        val connection = DatasourceConnection(
            id = ConnectionId("conn-1"),
            displayName = "db",
            description = "",
            reviewConfig = ReviewConfig(numTotalRequired = 0),
            maxExecutions = 1,
            databaseName = "app",
            authenticationType = AuthenticationType.USER_PASSWORD,
            auth = AuthenticationDetails.UserPassword("user", "pass"),
            port = 5432,
            hostname = "localhost",
            type = DatasourceType.POSTGRESQL,
            protocol = DatasourceType.POSTGRESQL.toProtocol(),
            additionalOptions = "",
            dumpsEnabled = false,
            temporaryAccessEnabled = false,
            explainEnabled = false,
            storeResults = false,
            dryRunEnabled = false,
            dryRunRequiresApproval = false,
            aiReviewMode = AiReviewMode.MANDATORY,
        )
        return ExecutionRequestDetails(
            request = DatasourceExecutionRequest(
                id = ExecutionRequestId("req-1"),
                connection = connection,
                title = "title",
                type = RequestType.SingleExecution,
                description = "desc",
                statement = "SELECT 1",
                executionStatus = "EXECUTABLE",
                reviewStatus = "APPROVED",
                author = User(id = UserId("author-1"), email = "a@example.com"),
                temporaryAccessDuration = null,
            ),
            events = mutableSetOf(),
        )
    }
}

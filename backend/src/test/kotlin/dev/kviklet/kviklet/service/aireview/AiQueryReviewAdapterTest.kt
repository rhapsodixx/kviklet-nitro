package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.db.AiQueryReviewAdapter
import dev.kviklet.kviklet.helper.ConnectionHelper
import dev.kviklet.kviklet.helper.ExecutionRequestHelper
import dev.kviklet.kviklet.helper.UserHelper
import dev.kviklet.kviklet.service.dto.AiFinding
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.RequestType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class AiQueryReviewAdapterTest {

    @Autowired
    private lateinit var aiQueryReviewAdapter: AiQueryReviewAdapter

    @Autowired
    private lateinit var executionRequestHelper: ExecutionRequestHelper

    @Autowired
    private lateinit var connectionHelper: ConnectionHelper

    @Autowired
    private lateinit var userHelper: UserHelper

    @AfterEach
    fun cleanup() {
        aiQueryReviewAdapter.deleteAll()
        executionRequestHelper.deleteAll()
        connectionHelper.deleteAll()
        userHelper.deleteAll()
    }

    @Test
    fun `create pending, complete as approved, fetch latest, create override and fetch`() {
        val author = userHelper.createUser()
        val admin = userHelper.createUser()
        val connection = connectionHelper.createDummyConnection()
        val request = executionRequestHelper.createExecutionRequest(
            author = author,
            connection = connection,
            statement = "SELECT * FROM users;",
            description = "Review this query",
        )
        val executionRequestId = ExecutionRequestId(request.getId())
        val fingerprint = RevisionFingerprint.compute(
            engine = DatasourceType.POSTGRESQL,
            statement = "SELECT * FROM users;",
            title = "Test Execution",
            description = "Review this query",
            requestType = RequestType.SingleExecution,
        )

        val pending = aiQueryReviewAdapter.createPending(executionRequestId, fingerprint)
        assertNotNull(pending.id)
        assertEquals(AiReviewAttemptStatus.PENDING, pending.status)
        assertNull(pending.completedAt)
        assertTrue(aiQueryReviewAdapter.hasInFlightPending(executionRequestId, fingerprint))

        val findings = listOf(
            AiFinding(
                severity = AiFindingSeverity.BLOCKER,
                category = "security",
                explanation = "Avoid SELECT *",
                fix = "List explicit columns",
            ),
        )
        val completed = aiQueryReviewAdapter.complete(
            attemptId = pending.id!!,
            status = AiReviewAttemptStatus.APPROVED,
            summary = "Looks good",
            findings = findings,
            suggestedSql = "SELECT id FROM users;",
            model = "gpt-4",
            promptPolicyVersion = "v1",
            errorCategory = null,
        )
        assertEquals(AiReviewAttemptStatus.APPROVED, completed.status)
        assertEquals("Looks good", completed.summary)
        assertEquals(findings, completed.findings)
        assertEquals("SELECT id FROM users;", completed.suggestedSql)
        assertNotNull(completed.completedAt)
        assertFalse(aiQueryReviewAdapter.hasInFlightPending(executionRequestId, fingerprint))

        val latest = aiQueryReviewAdapter.findLatestForRevision(executionRequestId, fingerprint)
        assertNotNull(latest)
        assertEquals(completed.id, latest!!.id)
        assertEquals(AiReviewAttemptStatus.APPROVED, latest.status)

        val override = aiQueryReviewAdapter.createOverride(
            executionRequestId = executionRequestId,
            fingerprint = fingerprint,
            actorId = admin.getId()!!,
            reason = "Emergency deploy",
        )
        assertNotNull(override.id)
        assertEquals("Emergency deploy", override.reason)
        assertEquals(admin.getId(), override.actorId)

        val latestOverride = aiQueryReviewAdapter.findLatestOverride(executionRequestId, fingerprint)
        assertNotNull(latestOverride)
        assertEquals(override.id, latestOverride!!.id)
    }
}

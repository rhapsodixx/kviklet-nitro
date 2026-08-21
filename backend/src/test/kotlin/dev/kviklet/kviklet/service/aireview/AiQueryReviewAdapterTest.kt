package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.db.AiQueryReviewAdapter
import dev.kviklet.kviklet.db.AiQueryReviewAttemptEntity
import dev.kviklet.kviklet.db.AiQueryReviewAttemptRepository
import dev.kviklet.kviklet.db.AiQueryReviewOverrideEntity
import dev.kviklet.kviklet.db.AiQueryReviewOverrideRepository
import dev.kviklet.kviklet.db.ExecutionRequestEntity
import dev.kviklet.kviklet.db.ExecutionRequestRepository
import dev.kviklet.kviklet.db.UserEntity
import dev.kviklet.kviklet.db.UserRepository
import dev.kviklet.kviklet.service.dto.AiFinding
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.RequestType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

class AiQueryReviewAdapterTest {

    private val attemptRepository = mockk<AiQueryReviewAttemptRepository>()
    private val overrideRepository = mockk<AiQueryReviewOverrideRepository>()
    private val executionRequestRepository = mockk<ExecutionRequestRepository>()
    private val userRepository = mockk<UserRepository>()

    private lateinit var adapter: AiQueryReviewAdapter

    private val attempts = mutableMapOf<String, AiQueryReviewAttemptEntity>()
    private val overrides = mutableMapOf<String, AiQueryReviewOverrideEntity>()
    private val idSequence = AtomicInteger()

    private val executionRequestId = ExecutionRequestId("req-1")
    private val adminId = "user-admin"
    private lateinit var executionRequest: ExecutionRequestEntity
    private lateinit var admin: UserEntity

    @BeforeEach
    fun setUp() {
        attempts.clear()
        overrides.clear()
        idSequence.set(0)

        executionRequest = mockk {
            every { id } returns executionRequestId.toString()
        }
        admin = mockk {
            every { id } returns adminId
        }

        every { executionRequestRepository.findById(executionRequestId.toString()) } returns Optional.of(executionRequest)
        every { userRepository.findById(adminId) } returns Optional.of(admin)

        every { attemptRepository.save(any()) } answers {
            val entity = firstArg<AiQueryReviewAttemptEntity>()
            if (entity.id == null) {
                entity.id = "attempt-${idSequence.incrementAndGet()}"
            }
            attempts[entity.id!!] = entity
            entity
        }
        every { attemptRepository.findById(any()) } answers {
            Optional.ofNullable(attempts[firstArg()])
        }
        every {
            attemptRepository.findFirstByExecutionRequestIdAndRevisionFingerprintOrderByCreatedAtDesc(
                any(),
                any(),
            )
        } answers {
            val requestId = firstArg<String>()
            val fingerprint = secondArg<String>()
            attempts.values
                .filter { it.executionRequest.id == requestId && it.revisionFingerprint == fingerprint }
                .maxByOrNull { it.createdAt }
        }
        every {
            attemptRepository.existsByExecutionRequestIdAndRevisionFingerprintAndStatus(
                any(),
                any(),
                any(),
            )
        } answers {
            val requestId = firstArg<String>()
            val fingerprint = secondArg<String>()
            val status = thirdArg<AiReviewAttemptStatus>()
            attempts.values.any {
                it.executionRequest.id == requestId &&
                    it.revisionFingerprint == fingerprint &&
                    it.status == status
            }
        }

        every { overrideRepository.save(any()) } answers {
            val entity = firstArg<AiQueryReviewOverrideEntity>()
            if (entity.id == null) {
                entity.id = "override-${idSequence.incrementAndGet()}"
            }
            overrides[entity.id!!] = entity
            entity
        }
        every {
            overrideRepository.findFirstByExecutionRequestIdAndRevisionFingerprintOrderByCreatedAtDesc(
                any(),
                any(),
            )
        } answers {
            val requestId = firstArg<String>()
            val fingerprint = secondArg<String>()
            overrides.values
                .filter { it.executionRequest.id == requestId && it.revisionFingerprint == fingerprint }
                .maxByOrNull { it.createdAt }
        }

        adapter = AiQueryReviewAdapter(
            attemptRepository,
            overrideRepository,
            executionRequestRepository,
            userRepository,
        )
    }

    @Test
    fun `create pending, complete as approved, fetch latest, create override and fetch`() {
        val fingerprint = RevisionFingerprint.compute(
            engine = DatasourceType.POSTGRESQL,
            statement = "SELECT * FROM users;",
            title = "Test Execution",
            description = "Review this query",
            requestType = RequestType.SingleExecution,
        )

        val pending = adapter.createPending(executionRequestId, fingerprint)
        assertNotNull(pending.id)
        assertEquals(AiReviewAttemptStatus.PENDING, pending.status)
        assertNull(pending.completedAt)
        assertTrue(adapter.hasInFlightPending(executionRequestId, fingerprint))

        val findings = listOf(
            AiFinding(
                severity = AiFindingSeverity.BLOCKER,
                category = "security",
                explanation = "Avoid SELECT *",
                fix = "List explicit columns",
            ),
        )
        val completed = adapter.complete(
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
        assertFalse(adapter.hasInFlightPending(executionRequestId, fingerprint))

        val latest = adapter.findLatestForRevision(executionRequestId, fingerprint)
        assertNotNull(latest)
        assertEquals(completed.id, latest!!.id)
        assertEquals(AiReviewAttemptStatus.APPROVED, latest.status)

        val override = adapter.createOverride(
            executionRequestId = executionRequestId,
            fingerprint = fingerprint,
            actorId = adminId,
            reason = "Emergency deploy",
        )
        assertNotNull(override.id)
        assertEquals("Emergency deploy", override.reason)
        assertEquals(adminId, override.actorId)

        val latestOverride = adapter.findLatestOverride(executionRequestId, fingerprint)
        assertNotNull(latestOverride)
        assertEquals(override.id, latestOverride!!.id)
    }
}

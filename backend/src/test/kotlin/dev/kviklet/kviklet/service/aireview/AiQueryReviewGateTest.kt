package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.AiQueryReviewAttempt
import dev.kviklet.kviklet.service.dto.AiQueryReviewOverride
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.RequestType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class AiQueryReviewGateTest {

    private val fingerprint = "fp-current"
    private val requestId = ExecutionRequestId("req-1")

    @Test
    fun `DISABLED is NOT_APPLICABLE`() {
        assertEquals(
            AiGateDecision.NOT_APPLICABLE,
            decide(
                mode = AiReviewMode.DISABLED,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.POSTGRESQL,
                latestAttempt = attempt(AiReviewAttemptStatus.APPROVED),
                override = null,
            ),
        )
    }

    @Test
    fun `non SingleExecution is NOT_APPLICABLE`() {
        assertEquals(
            AiGateDecision.NOT_APPLICABLE,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.TemporaryAccess,
                engine = DatasourceType.POSTGRESQL,
                latestAttempt = null,
                override = null,
            ),
        )
    }

    @Test
    fun `MongoDB is NOT_APPLICABLE`() {
        assertEquals(
            AiGateDecision.NOT_APPLICABLE,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.MONGODB,
                latestAttempt = null,
                override = null,
            ),
        )
    }

    @Test
    fun `null engine is NOT_APPLICABLE`() {
        assertEquals(
            AiGateDecision.NOT_APPLICABLE,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = null,
                latestAttempt = null,
                override = null,
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionalAlwaysAllowed")
    fun `OPTIONAL is always ALLOWED`(
        status: AiReviewAttemptStatus?,
        hasOverride: Boolean,
    ) {
        assertEquals(
            AiGateDecision.ALLOWED,
            decide(
                mode = AiReviewMode.OPTIONAL,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.MYSQL,
                latestAttempt = status?.let { attempt(it) },
                override = if (hasOverride) override() else null,
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mandatoryMatrix")
    fun `MANDATORY gate matrix`(
        status: AiReviewAttemptStatus?,
        hasOverride: Boolean,
        expected: AiGateDecision,
    ) {
        assertEquals(
            expected,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.POSTGRESQL,
                latestAttempt = status?.let { attempt(it) },
                override = if (hasOverride) override() else null,
            ),
        )
    }

    @Test
    fun `MANDATORY stale attempt fingerprint is treated as missing`() {
        assertEquals(
            AiGateDecision.BLOCKED_MISSING,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.POSTGRESQL,
                latestAttempt = attempt(AiReviewAttemptStatus.APPROVED, fingerprint = "fp-old"),
                override = null,
            ),
        )
    }

    @Test
    fun `MANDATORY override for different fingerprint does not unlock FAILED`() {
        assertEquals(
            AiGateDecision.BLOCKED_FAILED,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.POSTGRESQL,
                latestAttempt = attempt(AiReviewAttemptStatus.FAILED),
                override = override(fingerprint = "fp-old"),
            ),
        )
    }

    @Test
    fun `MANDATORY override does not unlock REJECTED`() {
        assertEquals(
            AiGateDecision.BLOCKED_REJECTED,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = DatasourceType.POSTGRESQL,
                latestAttempt = attempt(AiReviewAttemptStatus.REJECTED),
                override = override(),
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("relationalEngines")
    fun `relational engines are eligible under MANDATORY`(engine: DatasourceType) {
        assertEquals(
            AiGateDecision.BLOCKED_MISSING,
            decide(
                mode = AiReviewMode.MANDATORY,
                requestType = RequestType.SingleExecution,
                engine = engine,
                latestAttempt = null,
                override = null,
            ),
        )
    }

    private fun decide(
        mode: AiReviewMode,
        requestType: RequestType,
        engine: DatasourceType?,
        latestAttempt: AiQueryReviewAttempt?,
        override: AiQueryReviewOverride?,
    ): AiGateDecision = AiQueryReviewGate.decide(
        mode = mode,
        requestType = requestType,
        engine = engine,
        latestAttempt = latestAttempt,
        override = override,
        currentFingerprint = fingerprint,
    )

    private fun attempt(
        status: AiReviewAttemptStatus,
        fingerprint: String = this.fingerprint,
    ) = AiQueryReviewAttempt(
        id = "attempt-1",
        executionRequestId = requestId,
        revisionFingerprint = fingerprint,
        status = status,
    )

    private fun override(fingerprint: String = this.fingerprint) = AiQueryReviewOverride(
        id = "override-1",
        executionRequestId = requestId,
        revisionFingerprint = fingerprint,
        actorId = "admin-1",
        reason = "provider outage",
    )

    companion object {
        @JvmStatic
        fun optionalAlwaysAllowed(): Stream<Arguments> = Stream.of(
            arguments(named("missing", null), false),
            arguments(named("PENDING", AiReviewAttemptStatus.PENDING), false),
            arguments(named("REJECTED", AiReviewAttemptStatus.REJECTED), false),
            arguments(named("FAILED", AiReviewAttemptStatus.FAILED), false),
            arguments(named("FAILED with override", AiReviewAttemptStatus.FAILED), true),
            arguments(named("APPROVED", AiReviewAttemptStatus.APPROVED), false),
        )

        @JvmStatic
        fun mandatoryMatrix(): Stream<Arguments> = Stream.of(
            arguments(named("missing", null), false, AiGateDecision.BLOCKED_MISSING),
            arguments(named("PENDING", AiReviewAttemptStatus.PENDING), false, AiGateDecision.BLOCKED_PENDING),
            arguments(named("REJECTED", AiReviewAttemptStatus.REJECTED), false, AiGateDecision.BLOCKED_REJECTED),
            arguments(named("FAILED", AiReviewAttemptStatus.FAILED), false, AiGateDecision.BLOCKED_FAILED),
            arguments(
                named("FAILED with override", AiReviewAttemptStatus.FAILED),
                true,
                AiGateDecision.ALLOWED,
            ),
            arguments(named("APPROVED", AiReviewAttemptStatus.APPROVED), false, AiGateDecision.ALLOWED),
            arguments(
                named("APPROVED_WITH_NOTES", AiReviewAttemptStatus.APPROVED_WITH_NOTES),
                false,
                AiGateDecision.ALLOWED,
            ),
        )

        @JvmStatic
        fun relationalEngines(): Stream<Arguments> = Stream.of(
            arguments(DatasourceType.POSTGRESQL),
            arguments(DatasourceType.MYSQL),
            arguments(DatasourceType.MARIADB),
            arguments(DatasourceType.MSSQL),
        )
    }
}

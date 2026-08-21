package dev.kviklet.kviklet.db

import dev.kviklet.kviklet.db.util.AiFindingsConverter
import dev.kviklet.kviklet.db.util.BaseEntity
import dev.kviklet.kviklet.service.EntityNotFound
import dev.kviklet.kviklet.service.aireview.AiReviewAttemptStatus
import dev.kviklet.kviklet.service.dto.AiFinding
import dev.kviklet.kviklet.service.dto.AiQueryReviewAttempt
import dev.kviklet.kviklet.service.dto.AiQueryReviewOverride
import dev.kviklet.kviklet.service.dto.ExecutionRequestId
import dev.kviklet.kviklet.service.dto.utcTimeNow
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.ColumnTransformer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Entity(name = "ai_query_review_attempt")
class AiQueryReviewAttemptEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_request_id", nullable = false)
    val executionRequest: ExecutionRequestEntity,

    @Column(name = "revision_fingerprint", nullable = false)
    val revisionFingerprint: String,

    @Enumerated(EnumType.STRING)
    var status: AiReviewAttemptStatus,

    var summary: String? = null,

    @Convert(converter = AiFindingsConverter::class)
    @Column(columnDefinition = "json")
    @ColumnTransformer(write = "?::json")
    var findings: List<AiFinding>? = null,

    @Column(name = "suggested_sql")
    var suggestedSql: String? = null,

    var model: String? = null,

    @Column(name = "prompt_policy_version")
    var promptPolicyVersion: String? = null,

    @Column(name = "error_category")
    var errorCategory: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = utcTimeNow(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
) : BaseEntity() {

    fun toDto(): AiQueryReviewAttempt = AiQueryReviewAttempt(
        id = id,
        executionRequestId = ExecutionRequestId(executionRequest.id!!),
        revisionFingerprint = revisionFingerprint,
        status = status,
        summary = summary,
        findings = findings,
        suggestedSql = suggestedSql,
        model = model,
        promptPolicyVersion = promptPolicyVersion,
        errorCategory = errorCategory,
        createdAt = createdAt,
        completedAt = completedAt,
    )
}

@Entity(name = "ai_query_review_override")
class AiQueryReviewOverrideEntity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_request_id", nullable = false)
    val executionRequest: ExecutionRequestEntity,

    @Column(name = "revision_fingerprint", nullable = false)
    val revisionFingerprint: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    val actor: UserEntity,

    @Column(nullable = false)
    val reason: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = utcTimeNow(),
) : BaseEntity() {

    fun toDto(): AiQueryReviewOverride = AiQueryReviewOverride(
        id = id,
        executionRequestId = ExecutionRequestId(executionRequest.id!!),
        revisionFingerprint = revisionFingerprint,
        actorId = actor.id!!,
        reason = reason,
        createdAt = createdAt,
    )
}

interface AiQueryReviewAttemptRepository : JpaRepository<AiQueryReviewAttemptEntity, String> {
    fun findFirstByExecutionRequestIdAndRevisionFingerprintOrderByCreatedAtDesc(
        executionRequestId: String,
        revisionFingerprint: String,
    ): AiQueryReviewAttemptEntity?

    fun existsByExecutionRequestIdAndRevisionFingerprintAndStatus(
        executionRequestId: String,
        revisionFingerprint: String,
        status: AiReviewAttemptStatus,
    ): Boolean
}

interface AiQueryReviewOverrideRepository : JpaRepository<AiQueryReviewOverrideEntity, String> {
    fun findFirstByExecutionRequestIdAndRevisionFingerprintOrderByCreatedAtDesc(
        executionRequestId: String,
        revisionFingerprint: String,
    ): AiQueryReviewOverrideEntity?
}

@Service
class AiQueryReviewAdapter(
    private val attemptRepository: AiQueryReviewAttemptRepository,
    private val overrideRepository: AiQueryReviewOverrideRepository,
    private val executionRequestRepository: ExecutionRequestRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun createPending(executionRequestId: ExecutionRequestId, fingerprint: String): AiQueryReviewAttempt {
        val executionRequest = executionRequestRepository.findByIdOrNull(executionRequestId.toString())
            ?: throw EntityNotFound(
                "Execution request not found",
                "Execution request with id $executionRequestId does not exist",
            )
        val entity = AiQueryReviewAttemptEntity(
            executionRequest = executionRequest,
            revisionFingerprint = fingerprint,
            status = AiReviewAttemptStatus.PENDING,
        )
        return attemptRepository.save(entity).toDto()
    }

    @Transactional
    fun complete(
        attemptId: String,
        status: AiReviewAttemptStatus,
        summary: String?,
        findings: List<AiFinding>?,
        suggestedSql: String?,
        model: String?,
        promptPolicyVersion: String?,
        errorCategory: String?,
    ): AiQueryReviewAttempt {
        val entity = attemptRepository.findByIdOrNull(attemptId)
            ?: throw EntityNotFound(
                "AI query review attempt not found",
                "Attempt with id $attemptId does not exist",
            )
        entity.status = status
        entity.summary = summary
        entity.findings = findings
        entity.suggestedSql = suggestedSql
        entity.model = model
        entity.promptPolicyVersion = promptPolicyVersion
        entity.errorCategory = errorCategory
        entity.completedAt = utcTimeNow()
        return attemptRepository.save(entity).toDto()
    }

    @Transactional(readOnly = true)
    fun findLatestForRevision(
        executionRequestId: ExecutionRequestId,
        fingerprint: String,
    ): AiQueryReviewAttempt? = attemptRepository
        .findFirstByExecutionRequestIdAndRevisionFingerprintOrderByCreatedAtDesc(
            executionRequestId.toString(),
            fingerprint,
        )
        ?.toDto()

    @Transactional(readOnly = true)
    fun hasInFlightPending(executionRequestId: ExecutionRequestId, fingerprint: String): Boolean =
        attemptRepository.existsByExecutionRequestIdAndRevisionFingerprintAndStatus(
            executionRequestId.toString(),
            fingerprint,
            AiReviewAttemptStatus.PENDING,
        )

    @Transactional
    fun createOverride(
        executionRequestId: ExecutionRequestId,
        fingerprint: String,
        actorId: String,
        reason: String,
    ): AiQueryReviewOverride {
        val executionRequest = executionRequestRepository.findByIdOrNull(executionRequestId.toString())
            ?: throw EntityNotFound(
                "Execution request not found",
                "Execution request with id $executionRequestId does not exist",
            )
        val actor = userRepository.findByIdOrNull(actorId)
            ?: throw EntityNotFound(
                "User not found",
                "User with id $actorId does not exist",
            )
        val entity = AiQueryReviewOverrideEntity(
            executionRequest = executionRequest,
            revisionFingerprint = fingerprint,
            actor = actor,
            reason = reason,
        )
        return overrideRepository.save(entity).toDto()
    }

    @Transactional(readOnly = true)
    fun findLatestOverride(
        executionRequestId: ExecutionRequestId,
        fingerprint: String,
    ): AiQueryReviewOverride? = overrideRepository
        .findFirstByExecutionRequestIdAndRevisionFingerprintOrderByCreatedAtDesc(
            executionRequestId.toString(),
            fingerprint,
        )
        ?.toDto()
}

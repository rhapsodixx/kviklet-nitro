package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.TLSCerts
import dev.kviklet.kviklet.controller.CreateDatasourceExecutionRequestRequest
import dev.kviklet.kviklet.controller.UpdateExecutionRequestRequest
import dev.kviklet.kviklet.db.ExecutionRequestAdapter
import dev.kviklet.kviklet.db.User
import dev.kviklet.kviklet.db.UserAdapter
import dev.kviklet.kviklet.db.UserId
import dev.kviklet.kviklet.security.PermissionResolver
import dev.kviklet.kviklet.service.ConnectionService
import dev.kviklet.kviklet.service.DryRunValidator
import dev.kviklet.kviklet.service.EventService
import dev.kviklet.kviklet.service.ExecutionRequestService
import dev.kviklet.kviklet.service.InvalidReviewException
import dev.kviklet.kviklet.service.JDBCExecutor
import dev.kviklet.kviklet.service.MongoDBExecutor
import dev.kviklet.kviklet.service.RequestCreatedEvent
import dev.kviklet.kviklet.service.RequestRevisionChangedEvent
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
import dev.kviklet.kviklet.shell.KubernetesApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Focused unit coverage for Task 7 wiring without SpringBootTest / Docker.
 * Full MockWebServer integration is deferred when Testcontainers is unavailable.
 */
class ExecutionRequestAiReviewWiringTest {

    private val executionRequestAdapter = mockk<ExecutionRequestAdapter>()
    private val jdbcExecutor = mockk<JDBCExecutor>(relaxed = true)
    private val eventService = mockk<EventService>(relaxed = true)
    private val kubernetesApi = mockk<KubernetesApi>(relaxed = true)
    private val applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val mongoDBExecutor = mockk<MongoDBExecutor>(relaxed = true)
    private val connectionService = mockk<ConnectionService>()
    private val userAdapter = mockk<UserAdapter>(relaxed = true)
    private val proxyTLSCerts = mockk<TLSCerts>(relaxed = true)
    private val dryRunValidator = mockk<DryRunValidator>(relaxed = true)
    private val roleAdapter = mockk<dev.kviklet.kviklet.db.RoleAdapter>(relaxed = true)
    private val permissionResolver = mockk<PermissionResolver>(relaxed = true)
    private val aiQueryReviewService = mockk<AiQueryReviewService>(relaxed = true)

    private lateinit var service: ExecutionRequestService

    private val requestId = ExecutionRequestId("req-1")
    private val connectionId = ConnectionId("conn-1")

    @BeforeEach
    fun setUp() {
        service = ExecutionRequestService(
            executionRequestAdapter,
            jdbcExecutor,
            eventService,
            kubernetesApi,
            applicationEventPublisher,
            mongoDBExecutor,
            connectionService,
            userAdapter,
            proxyTLSCerts,
            dryRunValidator,
            roleAdapter,
            permissionResolver,
            aiQueryReviewService,
        )
    }

    @Test
    fun `create publishes RequestRevisionChangedEvent for SingleExecution`() {
        val details = details()
        every { connectionService.getDatasourceConnection(connectionId) } returns details.request.connection
        every {
            executionRequestAdapter.createExecutionRequest(
                connectionId = connectionId,
                title = "title",
                type = RequestType.SingleExecution,
                description = "desc",
                statement = "SELECT 1",
                executionStatus = any(),
                reviewStatus = any(),
                authorId = "author-1",
                temporaryAccessDuration = null,
            )
        } returns details

        service.create(
            connectionId,
            CreateDatasourceExecutionRequestRequest(
                connectionId = connectionId,
                title = "title",
                type = RequestType.SingleExecution,
                description = "desc",
                statement = "SELECT 1",
            ),
            userId = "author-1",
        )

        verify {
            applicationEventPublisher.publishEvent(match { it is RequestCreatedEvent })
            applicationEventPublisher.publishEvent(RequestRevisionChangedEvent(requestId))
        }
    }

    @Test
    fun `update publishes RequestRevisionChangedEvent for datasource SingleExecution`() {
        val existing = details()
        val updated = details(statement = "SELECT 2")
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns existing
        every {
            executionRequestAdapter.updateExecutionRequest(
                id = requestId,
                title = "title",
                description = "desc",
                statement = "SELECT 2",
                executionStatus = any(),
                reviewStatus = any(),
                namespace = null,
                podName = null,
                containerName = null,
                command = null,
                temporaryAccessDuration = null,
            )
        } returns updated
        every { roleAdapter.findByIds(any()) } returns emptyList()
        every { permissionResolver.resolveForCurrentUser(any()) } returns emptySet()

        service.update(
            requestId,
            UpdateExecutionRequestRequest(
                title = "title",
                description = "desc",
                statement = "SELECT 2",
            ),
            userId = "author-1",
        )

        verify {
            applicationEventPublisher.publishEvent(RequestRevisionChangedEvent(requestId))
        }
    }

    @Test
    fun `execute throws AI gate message when Mandatory review blocks`() {
        every { executionRequestAdapter.getExecutionRequestDetails(requestId) } returns details()
        every { aiQueryReviewService.currentSnapshot(any()) } returns AiReviewSnapshot(
            mode = AiReviewMode.MANDATORY,
            currentFingerprint = "fp",
            latestAttempt = null,
            override = null,
            blocksExecution = true,
            gate = AiGateDecision.BLOCKED_REJECTED,
        )

        val ex = assertThrows(InvalidReviewException::class.java) {
            service.execute(requestId, query = null, userId = "author-1", dryRun = false)
        }
        assertEquals("AI query review rejected this revision", ex.message)
    }

    private fun details(statement: String = "SELECT 1"): ExecutionRequestDetails {
        val connection = DatasourceConnection(
            id = connectionId,
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
            dryRunEnabled = true,
            dryRunRequiresApproval = true,
            aiReviewMode = AiReviewMode.MANDATORY,
        )
        return ExecutionRequestDetails(
            request = DatasourceExecutionRequest(
                id = requestId,
                connection = connection,
                title = "title",
                type = RequestType.SingleExecution,
                description = "desc",
                statement = statement,
                executionStatus = "EXECUTABLE",
                reviewStatus = "APPROVED",
                author = User(id = UserId("author-1"), email = "a@example.com"),
                temporaryAccessDuration = null,
            ),
            events = mutableSetOf(),
        )
    }
}

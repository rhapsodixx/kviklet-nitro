package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.controller.UpdateDatasourceConnectionRequest
import dev.kviklet.kviklet.db.ConnectionAdapter
import dev.kviklet.kviklet.db.RoleAdapter
import dev.kviklet.kviklet.security.PermissionResolver
import dev.kviklet.kviklet.service.ConnectionService
import dev.kviklet.kviklet.service.ExecutionRequestStatusService
import dev.kviklet.kviklet.service.JDBCExecutor
import dev.kviklet.kviklet.service.LicenseService
import dev.kviklet.kviklet.service.MongoDBExecutor
import dev.kviklet.kviklet.service.dto.AuthenticationDetails
import dev.kviklet.kviklet.service.dto.AuthenticationType
import dev.kviklet.kviklet.service.dto.ConnectionId
import dev.kviklet.kviklet.service.dto.DatabaseProtocol
import dev.kviklet.kviklet.service.dto.DatasourceConnection
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ReviewConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AiReviewConnectionValidationTest {

    private val connectionAdapter = mockk<ConnectionAdapter>()
    private val jdbcExecutor = mockk<JDBCExecutor>(relaxed = true)
    private val mongoDBExecutor = mockk<MongoDBExecutor>(relaxed = true)
    private val executionRequestStatusService = mockk<ExecutionRequestStatusService>(relaxed = true)
    private val licenseService = mockk<LicenseService>(relaxed = true)
    private val roleAdapter = mockk<RoleAdapter>(relaxed = true)
    private val permissionResolver = mockk<PermissionResolver>(relaxed = true)
    private val aiReviewProperties = mockk<AiReviewProperties>()

    private lateinit var service: ConnectionService

    @BeforeEach
    fun setUp() {
        service = ConnectionService(
            connectionAdapter,
            jdbcExecutor,
            mongoDBExecutor,
            executionRequestStatusService,
            licenseService,
            roleAdapter,
            permissionResolver,
            aiReviewProperties,
        )
        every { connectionAdapter.connectionExists(any()) } returns false
        every { permissionResolver.resolveForCurrentUser(any()) } returns emptySet()
    }

    @Test
    fun `create with MANDATORY fails when OpenRouter is not configured`() {
        every { aiReviewProperties.isConfigured() } returns false

        val ex = assertThrows(IllegalArgumentException::class.java) {
            createConnection(AiReviewMode.MANDATORY)
        }

        assertEquals("OpenRouter API key is not configured", ex.message)
        verify(exactly = 0) {
            connectionAdapter.createDatasourceConnection(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `create with OPTIONAL fails when OpenRouter is not configured`() {
        every { aiReviewProperties.isConfigured() } returns false

        val ex = assertThrows(IllegalArgumentException::class.java) {
            createConnection(AiReviewMode.OPTIONAL)
        }

        assertEquals("OpenRouter API key is not configured", ex.message)
    }

    @Test
    fun `create with DISABLED succeeds when OpenRouter is not configured`() {
        every { aiReviewProperties.isConfigured() } returns false
        every {
            connectionAdapter.createDatasourceConnection(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(),
            )
        } returns sampleConnection(AiReviewMode.DISABLED)

        val result = createConnection(AiReviewMode.DISABLED)

        assertEquals(AiReviewMode.DISABLED, (result.connection as DatasourceConnection).aiReviewMode)
    }

    @Test
    fun `create with MANDATORY succeeds when OpenRouter is configured`() {
        every { aiReviewProperties.isConfigured() } returns true
        val modeSlot = slot<AiReviewMode>()
        every {
            connectionAdapter.createDatasourceConnection(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), capture(modeSlot),
            )
        } returns sampleConnection(AiReviewMode.MANDATORY)

        val result = createConnection(AiReviewMode.MANDATORY)

        assertEquals(AiReviewMode.MANDATORY, (result.connection as DatasourceConnection).aiReviewMode)
        assertEquals(AiReviewMode.MANDATORY, modeSlot.captured)
    }

    @Test
    fun `update to MANDATORY fails when OpenRouter is not configured`() {
        every { aiReviewProperties.isConfigured() } returns false
        every { connectionAdapter.getConnection(ConnectionId("conn-1")) } returns
            sampleConnection(AiReviewMode.DISABLED)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.updateConnection(
                ConnectionId("conn-1"),
                UpdateDatasourceConnectionRequest(aiReviewMode = AiReviewMode.MANDATORY),
            )
        }

        assertEquals("OpenRouter API key is not configured", ex.message)
    }

    @Test
    fun `update to MANDATORY succeeds when OpenRouter is configured`() {
        every { aiReviewProperties.isConfigured() } returns true
        every { connectionAdapter.getConnection(ConnectionId("conn-1")) } returns
            sampleConnection(AiReviewMode.DISABLED)
        val modeSlot = slot<AiReviewMode>()
        every {
            connectionAdapter.updateDatasourceConnection(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), capture(modeSlot),
            )
        } returns sampleConnection(AiReviewMode.MANDATORY)

        val result = service.updateConnection(
            ConnectionId("conn-1"),
            UpdateDatasourceConnectionRequest(aiReviewMode = AiReviewMode.MANDATORY),
        )

        assertEquals(AiReviewMode.MANDATORY, (result.connection as DatasourceConnection).aiReviewMode)
        assertEquals(AiReviewMode.MANDATORY, modeSlot.captured)
    }

    private fun createConnection(mode: AiReviewMode) = service.createDatasourceConnection(
        connectionId = ConnectionId("conn-1"),
        displayName = "Test",
        databaseName = "db",
        maxExecutions = 1,
        username = "user",
        password = "pass",
        authenticationType = AuthenticationType.USER_PASSWORD,
        description = "",
        reviewConfig = ReviewConfig(numTotalRequired = 1),
        port = 5432,
        hostname = "localhost",
        type = DatasourceType.POSTGRESQL,
        protocol = DatabaseProtocol.POSTGRESQL,
        additionalJDBCOptions = "",
        dumpsEnabled = false,
        temporaryAccessEnabled = false,
        explainEnabled = false,
        storeResults = false,
        roleArn = null,
        maxTemporaryAccessDuration = null,
        category = null,
        dryRunEnabled = false,
        dryRunRequiresApproval = true,
        aiReviewMode = mode,
    )

    private fun sampleConnection(mode: AiReviewMode) = DatasourceConnection(
        id = ConnectionId("conn-1"),
        displayName = "Test",
        description = "",
        reviewConfig = ReviewConfig(numTotalRequired = 1),
        maxExecutions = 1,
        databaseName = "db",
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
        dryRunRequiresApproval = true,
        aiReviewMode = mode,
    )
}

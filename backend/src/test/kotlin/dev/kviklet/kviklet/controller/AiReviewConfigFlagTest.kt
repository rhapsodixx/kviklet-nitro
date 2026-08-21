package dev.kviklet.kviklet.controller

import dev.kviklet.kviklet.ApplicationProperties
import dev.kviklet.kviklet.security.IdentityProviderProperties
import dev.kviklet.kviklet.security.ldap.LdapProperties
import dev.kviklet.kviklet.security.saml.SamlProperties
import dev.kviklet.kviklet.service.ConfigService
import dev.kviklet.kviklet.service.LicenseService
import dev.kviklet.kviklet.service.aireview.AiReviewProperties
import dev.kviklet.kviklet.service.dto.Configuration
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AiReviewConfigFlagTest {

    private val identityProviderProperties = mockk<IdentityProviderProperties>(relaxed = true)
    private val ldapProperties = mockk<LdapProperties>(relaxed = true)
    private val samlProperties = mockk<SamlProperties>(relaxed = true)
    private val configService = mockk<ConfigService>()
    private val licenseService = mockk<LicenseService>(relaxed = true)
    private val applicationProperties = mockk<ApplicationProperties>(relaxed = true)
    private val aiReviewProperties = mockk<AiReviewProperties>()

    private lateinit var controller: ConfigController

    @BeforeEach
    fun setUp() {
        every { applicationProperties.version } returns "0.0.0-test"
        every { applicationProperties.buildDate } returns "2026-01-01"
        every { applicationProperties.gitCommit } returns "abc"
        every { licenseService.getLicenses() } returns emptyList()
        every { configService.getConfiguration() } returns Configuration(teamsUrl = null, slackUrl = null)
        every { identityProviderProperties.type } returns null
        every { ldapProperties.enabled } returns false
        every { samlProperties.isSamlEnabled() } returns false

        controller = ConfigController(
            identityProviderProperties,
            ldapProperties,
            samlProperties,
            configService,
            licenseService,
            applicationProperties,
            aiReviewProperties,
        )
    }

    @Test
    fun `config exposes aiReviewConfigured true when OpenRouter key is set`() {
        every { aiReviewProperties.isConfigured() } returns true

        val response = controller.getConfig() as ConfigResponse

        assertTrue(response.aiReviewConfigured)
    }

    @Test
    fun `config exposes aiReviewConfigured false when OpenRouter key is missing`() {
        every { aiReviewProperties.isConfigured() } returns false

        val response = controller.getConfig() as ConfigResponse

        assertFalse(response.aiReviewConfigured)
    }
}

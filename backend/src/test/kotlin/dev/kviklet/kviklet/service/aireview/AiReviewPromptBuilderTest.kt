package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.DatasourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiReviewPromptBuilderTest {

    @Test
    fun `postgres prompt includes engine name and sql delimiters`() {
        val built = AiReviewPromptBuilder.build(
            DatasourceType.POSTGRESQL, "DELETE FROM t", "cleanup", null,
        )
        assertTrue(built.user.contains("<<<SQL"))
        assertTrue(built.user.contains("DELETE FROM t"))
        assertTrue(built.system.contains("PostgreSQL") || built.system.contains("POSTGRESQL"))
        assertEquals("2026-08-21.1", built.policyVersion)
    }
}

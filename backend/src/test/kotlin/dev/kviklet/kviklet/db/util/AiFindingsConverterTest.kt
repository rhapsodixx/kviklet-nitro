package dev.kviklet.kviklet.db.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.kviklet.kviklet.service.aireview.AiFindingSeverity
import dev.kviklet.kviklet.service.dto.AiFinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiFindingsConverterTest {

    private val converter = AiFindingsConverter().apply {
        objectMapper = jacksonObjectMapper()
    }

    @Test
    fun `round-trips findings JSON shape`() {
        val findings = listOf(
            AiFinding(
                severity = AiFindingSeverity.BLOCKER,
                category = "security",
                explanation = "Avoid SELECT *",
                fix = "List explicit columns",
            ),
            AiFinding(
                severity = AiFindingSeverity.WARNING,
                category = "performance",
                explanation = "Missing index",
                fix = "Add index on user_id",
            ),
        )

        val json = converter.convertToDatabaseColumn(findings)
        assertEquals(
            """[{"severity":"BLOCKER","category":"security","explanation":"Avoid SELECT *","fix":"List explicit columns"},""" +
                """{"severity":"WARNING","category":"performance","explanation":"Missing index","fix":"Add index on user_id"}]""",
            json,
        )

        assertEquals(findings, converter.convertToEntityAttribute(json))
        assertEquals(null, converter.convertToDatabaseColumn(null))
        assertEquals(null, converter.convertToEntityAttribute(null))
    }
}

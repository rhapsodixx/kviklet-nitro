package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.RequestType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RevisionFingerprintTest {
    @Test
    fun `same inputs produce same hash`() {
        val a = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 1", "t", "d", RequestType.SingleExecution,
        )
        val b = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 1", "t", "d", RequestType.SingleExecution,
        )
        assertEquals(a, b)
        assertEquals(64, a.length) // sha-256 hex
    }

    @Test
    fun `sql change changes hash`() {
        val a = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 1", "t", null, RequestType.SingleExecution,
        )
        val b = RevisionFingerprint.compute(
            DatasourceType.POSTGRESQL, "SELECT 2", "t", null, RequestType.SingleExecution,
        )
        assertNotEquals(a, b)
    }
}

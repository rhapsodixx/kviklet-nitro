package dev.kviklet.kviklet.service.aireview

import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.RequestType
import java.security.MessageDigest

object RevisionFingerprint {
    fun compute(
        engine: DatasourceType,
        statement: String,
        title: String,
        description: String?,
        requestType: RequestType,
    ): String {
        val canonical = buildString {
            append("engine=${engine.name}\n")
            append("type=${requestType.name}\n")
            append("title=$title\n")
            append("description=${description ?: ""}\n")
            append("statement=$statement")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

package dev.kviklet.kviklet.db.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import dev.kviklet.kviklet.db.Payload
import dev.kviklet.kviklet.service.dto.AiFinding
import dev.kviklet.kviklet.service.dto.ReviewConfig
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.apache.commons.text.StringEscapeUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
abstract class PayloadConverter<T> : AttributeConverter<T, String> {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    override fun convertToDatabaseColumn(payload: T): String = objectMapper.writeValueAsString(payload)

    override fun convertToEntityAttribute(payloadJson: String): T {
        val unquoteJson = if (payloadJson.startsWith("\"")) {
            StringEscapeUtils.unescapeJson(payloadJson).removeSurrounding("\"")
        } else {
            payloadJson
        }
        return objectMapper.readValue(unquoteJson, clazz())
    }

    abstract fun clazz(): Class<T>
}

@Converter(autoApply = true)
class EventPayloadConverter : PayloadConverter<Payload>() {
    override fun clazz() = Payload::class.java
}

@Converter(autoApply = true)
class ReviewConfigConverter : PayloadConverter<ReviewConfig>() {
    override fun clazz() = ReviewConfig::class.java
}

@Converter(autoApply = true)
class AiFindingsConverter : AttributeConverter<List<AiFinding>?, String?> {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    private val typeRef = object : TypeReference<List<AiFinding>>() {}

    override fun convertToDatabaseColumn(findings: List<AiFinding>?): String? {
        if (findings == null) return null
        return objectMapper.writeValueAsString(findings)
    }

    override fun convertToEntityAttribute(findingsJson: String?): List<AiFinding>? {
        if (findingsJson == null) return null
        val unquoteJson = if (findingsJson.startsWith("\"")) {
            StringEscapeUtils.unescapeJson(findingsJson).removeSurrounding("\"")
        } else {
            findingsJson
        }
        return objectMapper.readValue(unquoteJson, typeRef)
    }
}

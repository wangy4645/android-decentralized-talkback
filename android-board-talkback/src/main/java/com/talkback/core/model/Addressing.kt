package com.talkback.core.model

private val MODULE_ID_REGEX = Regex("^[A-Z0-9]{2,16}$")
private val ENDPOINT_ID_REGEX = Regex("^[A-Z0-9]{1,16}$")

@JvmInline
value class ModuleId(val value: String) {
    init {
        require(MODULE_ID_REGEX.matches(value)) {
            "Invalid moduleId=$value. Use 2-16 chars [A-Z0-9]."
        }
    }
}

@JvmInline
value class EndpointId(val value: String) {
    init {
        require(ENDPOINT_ID_REGEX.matches(value)) {
            "Invalid endpointId=$value. Use 1-16 chars [A-Z0-9]."
        }
    }
}

data class EndpointAddress(
    val moduleId: ModuleId,
    val endpointId: EndpointId
) {
    val key: String = "${moduleId.value}-${endpointId.value}"
}

enum class EndpointPriority(val weight: Int) {
    NORMAL(0),
    DISPATCH(10),
    EMERGENCY(20)
}

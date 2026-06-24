package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointPriority

data class FloorClaim(
    val endpoint: EndpointAddress,
    val priority: EndpointPriority,
    val requestTsMs: Long
)

class FloorArbitrator {
    fun pickWinner(claims: List<FloorClaim>): FloorClaim? {
        if (claims.isEmpty()) return null
        return claims
            .sortedWith(
                compareByDescending<FloorClaim> { it.priority.weight }
                    .thenBy { it.requestTsMs }
                    .thenBy { it.endpoint.moduleId.value }
                    .thenBy { it.endpoint.endpointId.value }
            )
            .first()
    }
}

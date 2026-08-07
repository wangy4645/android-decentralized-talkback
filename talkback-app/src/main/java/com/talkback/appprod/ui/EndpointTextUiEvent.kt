package com.talkback.appprod.ui

/**
 * Inbound Endpoint Text UI signal — no message body in alerts.
 */
data class EndpointTextUiEvent(
    val fromKey: String,
    val fromLabel: String,
    val teamName: String
)

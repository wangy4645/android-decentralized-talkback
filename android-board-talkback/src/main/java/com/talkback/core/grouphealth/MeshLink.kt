package com.talkback.core.grouphealth

/** Directed module-level mesh responsibility (local diagnostic). */
data class MeshLink(val from: String, val to: String) {
    override fun toString(): String = "$from->$to"
}

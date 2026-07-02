package com.talkback.architecture

import java.io.File

/**
 * v1 source scanner for Runtime Ownership contracts (grep-style, RO-1).
 * v2: replace with Detekt rule / Kotlin AST (RO-9+).
 */
object OwnershipContractScanner {

    private val coordinatorRelative =
        "com/talkback/app/TalkbackCoordinator.kt"

    private val participantMediaWhitelistSuffixes = listOf(
        "com/talkback/core/session/ConferenceParticipantManager.kt",
        "com/talkback/core/media/MediaRuntime.kt"
    )

    fun mainJavaRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("android-board-talkback/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("main source root not found; cwd=${File(".").absolutePath}")
    }

    fun coordinatorSource(): String =
        readMainSource(coordinatorRelative)

    fun countCoordinatorGroupMembersDirectAssign(): Int =
        Regex("""session\.groupMembers\s*=""").findAll(coordinatorSource()).count().toInt()

    fun countCoordinatorFloorAuthorityDirectAssign(): Int =
        Regex("""session\.floorAuthorityModuleId\s*=""").findAll(coordinatorSource()).count().toInt()

    fun countResolveFloorAuthorityRouteSignalPeerReads(): Int {
        val source = coordinatorSource()
        if (!Regex("""\bfun\s+resolveFloorAuthorityRoute\s*\(""").containsMatchIn(source)) {
            return 0
        }
        val body = extractFunctionBody(source, "resolveFloorAuthorityRoute")
        return Regex("""signalPeersByModule""").findAll(body).count().toInt()
    }

    fun countParticipantMediaWritesOutsideWhitelist(): Int {
        val root = mainJavaRoot()
        var total = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString()
                    .replace('\\', '/')
                if (participantMediaWhitelistSuffixes.any { relative.endsWith(it) }) return@forEach
                val lines = file.readText().lines()
                lines.forEach { line ->
                    if (isParticipantMediaAssignment(line)) {
                        total++
                    }
                }
            }
        return total
    }

    internal fun isParticipantMediaAssignment(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("//")) return false
        if (trimmed.contains("==")) return false
        if (Regex("""\bval\s+media\s*=""").containsMatchIn(trimmed)) return false
        if (Regex("""\bmedia\s*=\s*ps\?""").containsMatchIn(trimmed)) return false
        if (Regex("""meshParticipant\([^)]*\)\.media\s*=[^=]""").containsMatchIn(trimmed)) return true
        if (Regex("""\bparticipant\.media\s*=[^=]""").containsMatchIn(trimmed)) return true
        if (Regex("""\bps\.media\s*=[^=]""").containsMatchIn(trimmed)) return true
        if (Regex("""\bmedia\s*=\s*MediaState\.""").containsMatchIn(trimmed)) return true
        return false
    }

    internal fun extractFunctionBody(source: String, functionName: String): String {
        val marker = Regex("""\bfun\s+$functionName\s*\(""")
        val match = marker.find(source)
            ?: error("function $functionName not found")
        val start = match.range.first
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "no opening brace for $functionName" }
        var depth = 0
        var i = braceStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(braceStart, i + 1)
                    }
                }
            }
            i++
        }
        error("unclosed brace for $functionName")
    }

    private fun readMainSource(relativePath: String): String {
        val file = File(mainJavaRoot(), relativePath)
        require(file.isFile) { "missing source: ${file.absolutePath}" }
        return file.readText()
    }
}

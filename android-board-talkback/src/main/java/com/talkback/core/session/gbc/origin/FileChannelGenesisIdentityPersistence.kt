package com.talkback.core.session.gbc.origin

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FileChannelGenesisIdentityPersistence(
    private val rootDirectory: Path,
) : ChannelGenesisIdentityPersistence {
    init {
        Files.createDirectories(rootDirectory)
    }

    override fun load(channelId: String): String? {
        val file = pathFor(channelId)
        if (!Files.exists(file)) return null
        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
        return text.takeIf { it.isNotBlank() }
    }

    override fun save(
        channelId: String,
        generationIdentity: String,
    ) {
        val target = pathFor(channelId)
        if (Files.exists(target)) return
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.write(temp, generationIdentity.toByteArray(StandardCharsets.UTF_8))
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun pathFor(channelId: String): Path {
        val safe = channelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return rootDirectory.resolve("$safe.genesis")
    }
}

package com.talkback.core.session.gbc.trust.profile.establishment

import com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * File-backed create-once operational anchor persistence (OE-IA-T2/T3).
 *
 * Integrity metadata detects corruption/partial state — not establishment authority.
 */
class FileOperationalTrustAnchorPersistence(
    private val file: Path,
) : OperationalTrustAnchorPersistence {
    private val lock = ReentrantLock()
    private var cachedState: AnchorPersistenceState? = null

    init {
        file.parent?.let { Files.createDirectories(it) }
    }

    override fun currentState(): AnchorPersistenceState =
        lock.withLock {
            cachedState ?: loadFromDisk().also { cachedState = it }
        }

    override fun establishFirstAnchor(
        anchor: OperationalTrustAnchor,
        establishmentRecordIdentity: String,
    ): EstablishmentCommitResult =
        lock.withLock {
            val existing = currentStateLocked()
            when (existing) {
                is AnchorPersistenceState.Established -> {
                    if (anchorsEquivalent(existing, anchor, establishmentRecordIdentity)) {
                        EstablishmentCommitResult.AlreadyEstablished(existing.anchor)
                    } else {
                        EstablishmentCommitResult.Rejected("operational anchor already established")
                    }
                }
                AnchorPersistenceState.Empty -> {
                    if (Files.exists(file)) {
                        val loaded = decodeFile(Files.readAllBytes(file))
                        if (loaded != null) {
                            cachedState = loaded
                            return@withLock if (
                                loaded is AnchorPersistenceState.Established &&
                                anchorsEquivalent(loaded, anchor, establishmentRecordIdentity)
                            ) {
                                EstablishmentCommitResult.AlreadyEstablished(loaded.anchor)
                            } else {
                                EstablishmentCommitResult.Rejected("operational anchor already established")
                            }
                        }
                        return@withLock EstablishmentCommitResult.Rejected(
                            "corrupt operational anchor persistence",
                        )
                    }
                    val encoded = encodeEstablished(anchor, establishmentRecordIdentity)
                    try {
                        Files.write(
                            file,
                            encoded,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE,
                        )
                    } catch (ex: java.nio.file.FileAlreadyExistsException) {
                        val loaded = decodeFile(Files.readAllBytes(file))
                        if (loaded is AnchorPersistenceState.Established &&
                            anchorsEquivalent(loaded, anchor, establishmentRecordIdentity)
                        ) {
                            cachedState = loaded
                            return@withLock EstablishmentCommitResult.AlreadyEstablished(loaded.anchor)
                        }
                        return@withLock EstablishmentCommitResult.Rejected(
                            "operational anchor already established",
                        )
                    } catch (ex: Exception) {
                        return@withLock EstablishmentCommitResult.Rejected(
                            ex.message ?: "establishment persist failed",
                        )
                    }
                    val established =
                        AnchorPersistenceState.Established(
                            anchor =
                                anchor.copy(
                                    operationalAuthorityPublicKeySpki =
                                        anchor.operationalAuthorityPublicKeySpki.copyOf(),
                                ),
                            establishmentRecordIdentity = establishmentRecordIdentity,
                        )
                    cachedState = established
                    EstablishmentCommitResult.Committed(established.anchor)
                }
            }
        }

    fun invalidateCache() {
        lock.withLock { cachedState = null }
    }

    private fun currentStateLocked(): AnchorPersistenceState = cachedState ?: loadFromDisk().also { cachedState = it }

    private fun loadFromDisk(): AnchorPersistenceState {
        if (!Files.exists(file)) return AnchorPersistenceState.Empty
        return decodeFile(Files.readAllBytes(file)) ?: AnchorPersistenceState.Empty
    }

    private fun anchorsEquivalent(
        established: AnchorPersistenceState.Established,
        anchor: OperationalTrustAnchor,
        recordIdentity: String,
    ): Boolean =
        established.establishmentRecordIdentity == recordIdentity &&
            established.anchor.deploymentTrustDomainId == anchor.deploymentTrustDomainId &&
            established.anchor.operationalAuthorityPublicKeySpki.contentEquals(
                anchor.operationalAuthorityPublicKeySpki,
            )

    companion object {
        private val MAGIC = "TBOP".encodeToByteArray()
        private const val FORMAT_VERSION: Int = 1

        fun encodeEstablished(
            anchor: OperationalTrustAnchor,
            establishmentRecordIdentity: String,
        ): ByteArray {
            val domainBytes = utf8(anchor.deploymentTrustDomainId)
            val recordBytes = utf8(establishmentRecordIdentity)
            val spki = anchor.operationalAuthorityPublicKeySpki
            val bodyCapacity =
                4 + MAGIC.size + 4 + 4 + domainBytes.size + 4 + recordBytes.size + 4 + spki.size
            val body = ByteBuffer.allocate(bodyCapacity).order(ByteOrder.BIG_ENDIAN)
            body.putInt(FORMAT_VERSION)
            body.put(MAGIC)
            body.putInt(domainBytes.size)
            body.put(domainBytes)
            body.putInt(recordBytes.size)
            body.put(recordBytes)
            body.putInt(spki.size)
            body.put(spki)
            val bodyBytes = body.array().copyOf(body.position())
            val digest = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
            return bodyBytes + digest
        }

        fun decodeFile(bytes: ByteArray): AnchorPersistenceState? {
            if (bytes.size < 32) return null
            val bodyBytes = bytes.copyOf(bytes.size - 32)
            val checksum = bytes.copyOfRange(bytes.size - 32, bytes.size)
            val expected = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
            if (!expected.contentEquals(checksum)) return null
            val buffer = ByteBuffer.wrap(bodyBytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.remaining() < 4 + 4) return null
            if (buffer.int != FORMAT_VERSION) return null
            if (buffer.remaining() < MAGIC.size) return null
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) return null
            val domain = readUtf8(buffer) ?: return null
            val recordIdentity = readUtf8(buffer) ?: return null
            if (buffer.remaining() < 4) return null
            val spkiLen = buffer.int
            if (spkiLen < 0 || buffer.remaining() < spkiLen) return null
            val spki = ByteArray(spkiLen)
            buffer.get(spki)
            if (buffer.hasRemaining()) return null
            if (domain.isBlank() || recordIdentity.isBlank() || spki.isEmpty()) return null
            return AnchorPersistenceState.Established(
                anchor = OperationalTrustAnchor(domain, spki),
                establishmentRecordIdentity = recordIdentity,
            )
        }

        private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

        private fun readUtf8(buffer: ByteBuffer): String? {
            if (buffer.remaining() < 4) return null
            val length = buffer.int
            if (length < 0 || buffer.remaining() < length) return null
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return bytes.toString(StandardCharsets.UTF_8)
        }
    }
}

package com.talkback.core.session.gbc.issuance

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class GenerationFactSignerPersistenceState {
    data object Empty : GenerationFactSignerPersistenceState()

    data class Established(
        val pkcs8PrivateKey: ByteArray,
        val publicKeySpki: ByteArray,
        val establishmentRecordIdentity: String,
    ) : GenerationFactSignerPersistenceState()
}

sealed class GenerationFactSignerEstablishResult {
    data class Established(
        val signer: PersistedGenerationFactIssuanceSigner,
    ) : GenerationFactSignerEstablishResult()

    data class AlreadyEstablished(
        val signer: PersistedGenerationFactIssuanceSigner,
    ) : GenerationFactSignerEstablishResult()

    data class Rejected(
        val reason: String,
    ) : GenerationFactSignerEstablishResult()
}

/**
 * Create-once durable Generation Fact signer persistence (FTPH-IA-T1 Path B).
 */
class FileGenerationFactSignerPersistence(
    private val file: Path,
) {
    private val lock = ReentrantLock()
    private var cachedState: GenerationFactSignerPersistenceState? = null

    init {
        file.parent?.let { Files.createDirectories(it) }
    }

    fun currentState(): GenerationFactSignerPersistenceState =
        lock.withLock {
            cachedState ?: loadFromDisk().also { cachedState = it }
        }

    fun loadSigner(): PersistedGenerationFactIssuanceSigner? =
        when (val state = currentState()) {
            GenerationFactSignerPersistenceState.Empty -> null
            is GenerationFactSignerPersistenceState.Established ->
                PersistedGenerationFactIssuanceSigner.fromEstablished(
                    state.pkcs8PrivateKey,
                    state.publicKeySpki,
                )
        }

    fun establishFirstSigner(
        pkcs8PrivateKey: ByteArray,
        publicKeySpki: ByteArray,
        establishmentRecordIdentity: String,
    ): GenerationFactSignerEstablishResult =
        lock.withLock {
            if (
                pkcs8PrivateKey.isEmpty() ||
                publicKeySpki.isEmpty() ||
                establishmentRecordIdentity.isBlank()
            ) {
                return GenerationFactSignerEstablishResult.Rejected("invalid signer material")
            }
            val signer =
                PersistedGenerationFactIssuanceSigner.fromEstablished(pkcs8PrivateKey, publicKeySpki)
                    ?: return GenerationFactSignerEstablishResult.Rejected("invalid PKCS8/SPKI")
            val existing = currentStateLocked()
            when (existing) {
                is GenerationFactSignerPersistenceState.Established -> {
                    if (
                        existing.establishmentRecordIdentity == establishmentRecordIdentity &&
                            existing.pkcs8PrivateKey.contentEquals(pkcs8PrivateKey) &&
                            existing.publicKeySpki.contentEquals(publicKeySpki)
                    ) {
                        GenerationFactSignerEstablishResult.AlreadyEstablished(signer)
                    } else {
                        GenerationFactSignerEstablishResult.Rejected("generation fact signer already established")
                    }
                }
                GenerationFactSignerPersistenceState.Empty -> {
                    if (Files.exists(file)) {
                        val loaded = decodeFile(Files.readAllBytes(file))
                        if (loaded is GenerationFactSignerPersistenceState.Established) {
                            cachedState = loaded
                            return@withLock if (
                                loaded.establishmentRecordIdentity == establishmentRecordIdentity &&
                                    loaded.pkcs8PrivateKey.contentEquals(pkcs8PrivateKey) &&
                                    loaded.publicKeySpki.contentEquals(publicKeySpki)
                            ) {
                                GenerationFactSignerEstablishResult.AlreadyEstablished(signer)
                            } else {
                                GenerationFactSignerEstablishResult.Rejected(
                                    "generation fact signer already established",
                                )
                            }
                        }
                        return@withLock GenerationFactSignerEstablishResult.Rejected(
                            "corrupt generation fact signer persistence",
                        )
                    }
                    val encoded = encodeEstablished(pkcs8PrivateKey, publicKeySpki, establishmentRecordIdentity)
                    try {
                        Files.write(
                            file,
                            encoded,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE,
                        )
                    } catch (ex: java.nio.file.FileAlreadyExistsException) {
                        val loaded = decodeFile(Files.readAllBytes(file))
                        if (
                            loaded is GenerationFactSignerPersistenceState.Established &&
                            loaded.establishmentRecordIdentity == establishmentRecordIdentity &&
                            loaded.pkcs8PrivateKey.contentEquals(pkcs8PrivateKey) &&
                            loaded.publicKeySpki.contentEquals(publicKeySpki)
                        ) {
                            cachedState = loaded
                            return@withLock GenerationFactSignerEstablishResult.AlreadyEstablished(signer)
                        }
                        return@withLock GenerationFactSignerEstablishResult.Rejected(
                            "generation fact signer already established",
                        )
                    }
                    cachedState =
                        GenerationFactSignerPersistenceState.Established(
                            pkcs8PrivateKey = pkcs8PrivateKey.copyOf(),
                            publicKeySpki = publicKeySpki.copyOf(),
                            establishmentRecordIdentity = establishmentRecordIdentity,
                        )
                    GenerationFactSignerEstablishResult.Established(signer)
                }
            }
        }

    fun invalidateCache() {
        lock.withLock { cachedState = null }
    }

    private fun currentStateLocked(): GenerationFactSignerPersistenceState =
        cachedState ?: loadFromDisk().also { cachedState = it }

    private fun loadFromDisk(): GenerationFactSignerPersistenceState {
        if (!Files.exists(file)) return GenerationFactSignerPersistenceState.Empty
        return decodeFile(Files.readAllBytes(file)) ?: GenerationFactSignerPersistenceState.Empty
    }

    companion object {
        private val MAGIC = "TBGF".encodeToByteArray()
        private const val FORMAT_VERSION: Int = 2

        fun encodeEstablished(
            pkcs8PrivateKey: ByteArray,
            publicKeySpki: ByteArray,
            establishmentRecordIdentity: String,
        ): ByteArray {
            val recordBytes = utf8(establishmentRecordIdentity)
            val bodyCapacity =
                4 + MAGIC.size + 4 + recordBytes.size + 4 + pkcs8PrivateKey.size + 4 + publicKeySpki.size
            val body = ByteBuffer.allocate(bodyCapacity).order(ByteOrder.BIG_ENDIAN)
            body.putInt(FORMAT_VERSION)
            body.put(MAGIC)
            body.putInt(recordBytes.size)
            body.put(recordBytes)
            body.putInt(pkcs8PrivateKey.size)
            body.put(pkcs8PrivateKey)
            body.putInt(publicKeySpki.size)
            body.put(publicKeySpki)
            val bodyBytes = body.array().copyOf(body.position())
            val digest = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
            return bodyBytes + digest
        }

        fun decodeFile(bytes: ByteArray): GenerationFactSignerPersistenceState? {
            if (bytes.size < 32) return null
            val bodyBytes = bytes.copyOf(bytes.size - 32)
            val checksum = bytes.copyOfRange(bytes.size - 32, bytes.size)
            val expected = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
            if (!expected.contentEquals(checksum)) return null
            val buffer = ByteBuffer.wrap(bodyBytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.remaining() < 4 + MAGIC.size) return null
            if (buffer.int != FORMAT_VERSION) return null
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) return null
            val recordIdentity = readUtf8(buffer) ?: return null
            if (buffer.remaining() < 4) return null
            val pkcs8Len = buffer.int
            if (pkcs8Len < 0 || buffer.remaining() < pkcs8Len) return null
            val pkcs8 = ByteArray(pkcs8Len)
            buffer.get(pkcs8)
            if (buffer.remaining() < 4) return null
            val spkiLen = buffer.int
            if (spkiLen < 0 || buffer.remaining() < spkiLen) return null
            val spki = ByteArray(spkiLen)
            buffer.get(spki)
            if (buffer.hasRemaining()) return null
            if (recordIdentity.isBlank() || pkcs8.isEmpty() || spki.isEmpty()) return null
            return GenerationFactSignerPersistenceState.Established(
                pkcs8PrivateKey = pkcs8,
                publicKeySpki = spki,
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

class GenerationFactSignerEstablisher(
    private val persistence: FileGenerationFactSignerPersistence,
) {
    fun establish(
        pkcs8PrivateKey: ByteArray,
        publicKeySpki: ByteArray,
        establishmentRecordIdentity: String,
    ): GenerationFactSignerEstablishResult =
        persistence.establishFirstSigner(pkcs8PrivateKey, publicKeySpki, establishmentRecordIdentity)
}

object GbcFieldEstablishedSignerSupport {
    const val SIGNER_FILE_NAME = "field-established-generation-fact-signer.pkcs8"

    fun signerPersistence(trustDir: java.io.File): FileGenerationFactSignerPersistence =
        FileGenerationFactSignerPersistence(
            trustDir.resolve(SIGNER_FILE_NAME).toPath(),
        )

    fun loadSigner(trustDir: java.io.File): PersistedGenerationFactIssuanceSigner? =
        signerPersistence(trustDir).loadSigner()
}

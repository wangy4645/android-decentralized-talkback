package com.talkback.core.session.gbc.issuance

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic snapshot persistence for crash-recovery harness (PV2-D / PV2-IA-T1).
 */
class FileSnapshotIssuancePersistence(
    private val rootDirectory: Path,
) : IssuanceStatePersistence {
    init {
        Files.createDirectories(rootDirectory)
    }

    override fun save(
        key: GenerationFactIssuanceKey,
        state: KeyIssuanceState,
    ) {
        val target = pathFor(key)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.write(temp, encodeSnapshot(key, state))
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(key: GenerationFactIssuanceKey): KeyIssuanceState? {
        val file = pathFor(key)
        if (!Files.exists(file)) return null
        return decodeSnapshot(key, Files.readAllBytes(file))
    }

    private fun pathFor(key: GenerationFactIssuanceKey): Path =
        rootDirectory.resolve("${key.storageId()}.bin")

    private fun encodeSnapshot(
        key: GenerationFactIssuanceKey,
        state: KeyIssuanceState,
    ): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(state.retirementPhase.ordinal)
        buffer.write(if (state.fenceClosed) 1 else 0)
        buffer.write(intToBytes(state.prepared.size))
        state.prepared.values.forEach { prepared ->
            writePrepared(buffer, prepared)
        }
        buffer.write(intToBytes(state.finalizedOrder.size))
        state.finalizedOrder.forEach { commitment ->
            val record = state.finalizedByCommitment[commitment.toHexKey()] ?: return@forEach
            writeFinalized(buffer, record)
        }
        val proposed = state.proposedTerminalHead
        buffer.write(if (proposed == null) 0 else 1)
        if (proposed != null) {
            buffer.write(proposed.issuanceRoot)
            buffer.write(intToBytes(proposed.finalizedCount))
        }
        buffer.write(longToBytes(state.proposedHeadGeneration))
        buffer.write(key.moduleId.toByteArray(Charsets.UTF_8).size)
        buffer.write(key.moduleId.toByteArray(Charsets.UTF_8))
        buffer.write(longToBytes(key.signerKeyVersion))
        return buffer.toByteArray()
    }

    private fun decodeSnapshot(
        key: GenerationFactIssuanceKey,
        bytes: ByteArray,
    ): KeyIssuanceState {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val phase = IssuanceRetirementPhase.entries[buffer.get().toInt() and 0xFF]
        val fenceClosed = buffer.get().toInt() != 0
        val preparedCount = buffer.int
        val prepared = linkedMapOf<String, PreparedIssuanceRecord>()
        repeat(preparedCount) {
            val record = readPrepared(buffer)
            prepared[record.prepareId] = record
        }
        val finalizedCount = buffer.int
        val finalizedByCommitment = linkedMapOf<String, FinalizedIssuanceRecord>()
        val finalizedOrder = mutableListOf<ByteArray>()
        repeat(finalizedCount) {
            val record = readFinalized(buffer)
            finalizedByCommitment[record.factCommitment.toHexKey()] = record
            finalizedOrder += record.factCommitment.copyOf()
        }
        val hasProposed = buffer.get().toInt() != 0
        val proposed =
            if (hasProposed) {
                val root = ByteArray(32)
                buffer.get(root)
                val count = buffer.int
                ProposedTerminalHead(
                    moduleId = key.moduleId,
                    signerKeyVersion = key.signerKeyVersion,
                    issuanceRoot = root,
                    finalizedCount = count,
                )
            } else {
                null
            }
        val generation = buffer.long
        return KeyIssuanceState(
            retirementPhase = phase,
            fenceClosed = fenceClosed,
            prepared = prepared,
            finalizedByCommitment = finalizedByCommitment,
            finalizedOrder = finalizedOrder,
            proposedTerminalHead = proposed,
            proposedHeadGeneration = generation,
        )
    }

    private fun writePrepared(
        out: java.io.ByteArrayOutputStream,
        record: PreparedIssuanceRecord,
    ) {
        writeString(out, record.prepareId)
        writeBytes(out, record.factCommitment)
        writeBytes(out, record.authorityCanonicalBytes)
        writeBytes(out, record.verificationContextBytes)
        out.write(if (record.admittedBeforeFence) 1 else 0)
    }

    private fun readPrepared(buffer: ByteBuffer): PreparedIssuanceRecord {
        val prepareId = readString(buffer)
        val commitment = readBytes(buffer)
        val authority = readBytes(buffer)
        val context = readBytes(buffer)
        val admitted = buffer.get().toInt() != 0
        return PreparedIssuanceRecord(prepareId, commitment, authority, context, admitted)
    }

    private fun writeFinalized(
        out: java.io.ByteArrayOutputStream,
        record: FinalizedIssuanceRecord,
    ) {
        writeBytes(out, record.factCommitment)
        writeBytes(out, record.signedFactBytes)
        out.write(intToBytes(record.merkleIndex))
    }

    private fun readFinalized(buffer: ByteBuffer): FinalizedIssuanceRecord {
        val commitment = readBytes(buffer)
        val signed = readBytes(buffer)
        val index = buffer.int
        return FinalizedIssuanceRecord(commitment, signed, index)
    }

    private fun writeString(
        out: java.io.ByteArrayOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(intToBytes(bytes.size))
        out.write(bytes)
    }

    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.int
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeBytes(
        out: java.io.ByteArrayOutputStream,
        bytes: ByteArray,
    ) {
        out.write(intToBytes(bytes.size))
        out.write(bytes)
    }

    private fun readBytes(buffer: ByteBuffer): ByteArray {
        val length = buffer.int
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun intToBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun longToBytes(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
}

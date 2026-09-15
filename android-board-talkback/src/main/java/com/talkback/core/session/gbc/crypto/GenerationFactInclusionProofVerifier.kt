package com.talkback.core.session.gbc.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Merkle inclusion proof against a retirement-frozen issuance commitment (C3-H / H2-b).
 *
 * Proof bytes:
 *   [leafIndex: u32][siblingCount: u8][siblingHash32 * count]
 */
object GenerationFactInclusionProofVerifier {
    fun merkleRoot(commitments: List<ByteArray>): ByteArray {
        require(commitments.isNotEmpty()) { "commitments must not be empty" }
        var level = commitments.map { hashLeaf(it) }
        while (level.size > 1) {
            val next = ArrayList<ByteArray>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                if (i + 1 < level.size) {
                    next += hashPair(level[i], level[i + 1])
                } else {
                    next += hashPair(level[i], level[i])
                }
                i += 2
            }
            level = next
        }
        return level.single()
    }

    fun buildProof(
        commitments: List<ByteArray>,
        targetCommitment: ByteArray,
    ): ByteArray? {
        val leaves = commitments.map { hashLeaf(it) }
        val leafIndex = commitments.indexOfFirst { it.contentEquals(targetCommitment) }
        if (leafIndex < 0) return null
        val siblings = mutableListOf<ByteArray>()
        var index = leafIndex
        var level = leaves
        while (level.size > 1) {
            val siblingIndex = if (index % 2 == 0) index + 1 else index - 1
            val sibling =
                if (siblingIndex < level.size) {
                    level[siblingIndex]
                } else {
                    level[index]
                }
            siblings += sibling
            val next = ArrayList<ByteArray>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                if (i + 1 < level.size) {
                    next += hashPair(level[i], level[i + 1])
                } else {
                    next += hashPair(level[i], level[i])
                }
                i += 2
            }
            index /= 2
            level = next
        }
        val buffer = ByteBuffer.allocate(4 + 1 + siblings.size * 32).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(leafIndex)
        buffer.put(siblings.size.toByte())
        siblings.forEach { buffer.put(it) }
        return buffer.array()
    }

    fun verify(
        commitment: ByteArray,
        checkpointRoot: ByteArray,
        proofBytes: ByteArray,
    ): Boolean {
        if (checkpointRoot.size != 32) return false
        val buffer = ByteBuffer.wrap(proofBytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 5) return false
        var index = buffer.int
        if (index < 0) return false
        val siblingCount = buffer.get().toInt() and 0xFF
        if (buffer.remaining() != siblingCount * 32) return false
        var hash = hashLeaf(commitment)
        repeat(siblingCount) {
            val sibling = ByteArray(32)
            buffer.get(sibling)
            hash =
                if (index % 2 == 0) {
                    hashPair(hash, sibling)
                } else {
                    hashPair(sibling, hash)
                }
            index /= 2
        }
        return hash.contentEquals(checkpointRoot)
    }

    private fun hashLeaf(commitment: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GenerationFactDomains.ISSUANCE_MERKLE_DOMAIN)
        digest.update(0x00)
        digest.update(commitment)
        return digest.digest()
    }

    private fun hashPair(left: ByteArray, right: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GenerationFactDomains.ISSUANCE_MERKLE_DOMAIN)
        digest.update(0x01)
        digest.update(left)
        digest.update(right)
        return digest.digest()
    }
}

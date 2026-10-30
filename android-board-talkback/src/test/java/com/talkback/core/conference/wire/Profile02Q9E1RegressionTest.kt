package com.talkback.core.conference.wire

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.charset.StandardCharsets

/**
 * E1 Profile 02 Q9 regression harness — drives [ConferenceWireIngress] (real seams).
 * PUBLIC TEST KEYS ONLY.
 */
@RunWith(Parameterized::class)
class Profile02Q9E1RegressionTest(
    private val vectorName: String,
    private val vectorJson: String,
) {
    @Test
    fun vectorMatchesFrozenExpectation() {
        val vector = GSON.fromJson(vectorJson, CorpusVector::class.java)
        val datagram = hexToBytes(vector.datagramBytesHex)
        assertEquals(vector.udpPayloadLength, datagram.size)

        val context = buildIngressContext(vector.context)
        val result = ConferenceWireIngress.admit(datagram, context)

        when (vector.expectedOutcome) {
            "ACCEPT" -> {
                val accepted =
                    result as? WireIngressResult.Accepted
                        ?: fail("expected ACCEPT for $vectorName, got $result").let { return }
                assertEquals(vector.context.packetIndex, accepted.packetIndex)
                assertEquals(vector.context.ssrc, accepted.ssrc)
                assertEquals(vector.context.seq, accepted.sequence)
                assertTrue(
                    vector.expectedOwningSeam == "P02-full-pipeline" ||
                        vector.expectedOwningSeam.isNullOrEmpty(),
                )
                assertNull(vector.expectedFrozenClass)
            }
            "REJECT" -> {
                val rejected =
                    result as? WireIngressResult.Rejected
                        ?: fail("expected REJECT for $vectorName, got $result").let { return }
                val expectedSeam = mapExpectedSeam(vector.expectedOwningSeam)
                if (expectedSeam != null) {
                    assertEquals("owning seam for $vectorName", expectedSeam, rejected.owningSeam)
                }
                val expectedClass = vector.expectedFrozenClass
                if (expectedClass != null) {
                    assertEquals(
                        "frozen class for $vectorName",
                        expectedClass,
                        rejected.frozenClass,
                    )
                }
            }
            else -> fail("unknown expectedOutcome=${vector.expectedOutcome} for $vectorName")
        }
    }

    companion object {
        private val GSON = Gson()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val root = loadCorpus()
            val out = mutableListOf<Array<Any>>()
            for (v in root.positiveVectors) {
                out += arrayOf(v.name, GSON.toJson(v))
            }
            for (v in root.negativeVectors) {
                out += arrayOf(v.name, GSON.toJson(v))
            }
            return out
        }

        private fun loadCorpus(): CorpusRoot {
            val stream =
                Profile02Q9E1RegressionTest::class.java.classLoader
                    .getResourceAsStream("adr0058/0058-profile-02-q9-golden-vectors.json")
            assertNotNull("E1 corpus missing from test resources", stream)
            val text = stream!!.readBytes().toString(StandardCharsets.UTF_8)
            return GSON.fromJson(text, CorpusRoot::class.java)
        }

        private fun mapExpectedSeam(raw: String?): WireOwningSeam? =
            when (raw) {
                "Q8" -> WireOwningSeam.Q8
                "Q7" -> WireOwningSeam.Q7
                "Q2" -> WireOwningSeam.Q2
                "Q5" -> WireOwningSeam.Q5
                "Q6" -> WireOwningSeam.Q6
                "Q3" -> WireOwningSeam.Q3
                "P02-full-pipeline" -> WireOwningSeam.FULL_PIPELINE
                "earliest-authoritative-seam" -> WireOwningSeam.Q2
                else -> null
            }

        private fun buildIngressContext(ctx: CorpusContext): WireIngressContext {
            val trialKey =
                ctx.trialSrtpMasterKeyHex?.let { hexToBytes(it) }

            val installedKeyHex =
                ctx.installedSourceAdmissionKey48Hex ?: ctx.sourceAdmissionKey48Hex
            val installedSsrc = ctx.installedSsrc ?: ctx.ssrc

            val replay =
                if (ctx.highestAuthenticatedPacketIndex != null) {
                    val highest = ctx.highestAuthenticatedPacketIndex
                    val seen = mutableSetOf<Long>()
                    if (ctx.replayBitmapContainsPacketIndex == true) {
                        seen += ctx.packetIndex
                    }
                    WireReplayState(
                        highestAuthenticatedPacketIndex = highest,
                        seenPacketIndices = seen,
                        windowPackets = ctx.replayWindowPackets ?: 64,
                    )
                } else {
                    null
                }

            return WireIngressContext(
                key =
                    WireKeyContext(
                        masterKey = hexToBytes(ctx.srtpMasterKeyHex),
                        masterSalt = hexToBytes(ctx.srtpMasterSaltHex),
                        keyContextHint64 = hexToBytes(ctx.keyContextHint64Hex),
                    ),
                installedBinding =
                    WireSourceBinding(
                        ssrc = installedSsrc,
                        sourceAdmissionKey48 = hexToBytes(installedKeyHex),
                    ),
                replay = replay,
                trialMasterKey = trialKey,
                roc = ctx.roc,
            )
        }

        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd hex length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }

    private data class CorpusRoot(
        val positiveVectors: List<CorpusVector>,
        val negativeVectors: List<CorpusVector>,
    )

    private data class CorpusVector(
        val name: String,
        val context: CorpusContext,
        val datagramBytesHex: String,
        val udpPayloadLength: Int,
        val expectedOutcome: String,
        val expectedOwningSeam: String?,
        val expectedFrozenClass: String?,
    )

    private data class CorpusContext(
        val ssrc: Int,
        val seq: Int,
        val roc: Int,
        val packetIndex: Long,
        val srtpMasterKeyHex: String,
        val srtpMasterSaltHex: String,
        val keyContextHint64Hex: String,
        val sourceAdmissionKey48Hex: String,
        val replayWindowPackets: Int? = null,
        val trialSrtpMasterKeyHex: String? = null,
        val installedSourceAdmissionKey48Hex: String? = null,
        val installedSsrc: Int? = null,
        val highestAuthenticatedPacketIndex: Long? = null,
        val replayBitmapContainsPacketIndex: Boolean? = null,
    )
}

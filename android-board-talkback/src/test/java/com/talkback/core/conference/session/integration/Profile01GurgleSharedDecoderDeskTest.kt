package com.talkback.core.conference.session.integration



import com.talkback.core.conference.runtime.ConcentusOpusDecoderSeam

import com.talkback.core.conference.runtime.OpusPayloadStore

import com.talkback.core.conference.runtime.OpusTestVectors

import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly

import org.junit.Assert.assertTrue

import org.junit.Test

import kotlin.math.abs



/**

 * GURGLE-DESK (2) / P2 — per-source physical Opus decoders on [ConcentusOpusDecoderSeam].

 */

class Profile01GurgleSharedDecoderDeskTest {

    @Test

    fun independentDecoderBaseline_matchesDedicatedM03Stream() {

        val opusM03 = encodeIndependentPayload(880.0)

        val seamA = ConcentusOpusDecoderSeam(OpusPayloadStore())

        val seamB = ConcentusOpusDecoderSeam(OpusPayloadStore())

        val opusM01 = encodeIndependentPayload(440.0)



        val baselineM03 = decodeStream(seamB, "M03", 1L, opusM03)

        val crossSeamM03 =

            decodeStreamWithInterleavedOtherSource(

                decodeSeam = seamA,

                otherSeam = seamB,

                otherSource = "M01",

                otherIncarnation = 1L,

                otherOpus = opusM01,

                targetSource = "M03",

                targetIncarnation = 1L,

                targetOpus = opusM03,

            )



        val drift = meanAbsSampleDiff(baselineM03, crossSeamM03)

        assertTrue(

            "independent decoder instances must not cross-pollute (meanAbsDiff=$drift)",

            drift < INDEPENDENT_MAX_MEAN_ABS_DIFF,

        )

    }



    @Test

    fun productionAssemblyDecoder_alternatingSources_matchesIndependentM03Baseline() {

        val opusM01 = encodeIndependentPayload(440.0)

        val opusM03 = encodeIndependentPayload(880.0)



        val baselineStore = OpusPayloadStore()

        val baselineSeam = ConcentusOpusDecoderSeam(baselineStore)

        val baselineM03 = decodeStream(baselineSeam, "M03", 1L, opusM03)



        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()

        val sharedSeam = assembly.decoderSeam

        val sharedStore = assembly.opusPayloadStore

        val sharedM03 =

            decodeStreamWithInterleavedOtherSource(

                decodeSeam = sharedSeam,

                otherSeam = sharedSeam,

                otherSource = "M01",

                otherIncarnation = 1L,

                otherOpus = opusM01,

                targetSource = "M03",

                targetIncarnation = 1L,

                targetOpus = opusM03,

                store = sharedStore,

            )



        val drift = meanAbsSampleDiff(baselineM03, sharedM03)

        assertTrue(

            "P2: shared assembly must match independent M03 baseline (meanAbsDiff=$drift)",

            drift < INDEPENDENT_MAX_MEAN_ABS_DIFF,

        )

    }



    @Test

    fun fourSources_alternatingSlots_staysNearPerSourceBaselines() {
        val sources = listOf("M01", "M02", "M03", "M04")
        val freqs = listOf(440.0, 554.0, 659.0, 784.0)
        val opusBySource = sources.zip(freqs).associate { (id, hz) -> id to encodeIndependentPayload(hz) }

        val baselines =
            sources.associateWith { id ->
                decodeStream(ConcentusOpusDecoderSeam(OpusPayloadStore()), id, 1L, opusBySource[id]!!)
            }

        val store = OpusPayloadStore()
        val shared = ConcentusOpusDecoderSeam(store)
        sources.forEach { shared.onPoolAllocated(it, 1L) }
        val interleaved = sources.associateWith { ShortArray(FRAME_SAMPLES * FRAMES) }
        for (slot in 0 until FRAMES) {
            for (id in sources) {
                store.put(id, 1L, slot.toLong(), opusBySource[id]!!)
            }
            for (id in sources) {
                val pcm = shared.decode(id, 1L, slot.toLong(), nowMs = 0L)!!
                pcm.samples.copyInto(interleaved[id]!!, slot * FRAME_SAMPLES)
            }
        }

        for (id in sources) {
            val drift = meanAbsSampleDiff(baselines[id]!!, interleaved[id]!!)
            assertTrue(
                "$id alternating drift too high (meanAbsDiff=$drift)",
                drift < INDEPENDENT_MAX_MEAN_ABS_DIFF,
            )
        }
    }



    private fun encodeIndependentPayload(frequencyHz: Double): ByteArray =

        OpusTestVectors.encodePcm(OpusTestVectors.pcmTone(frequencyHz))



    private fun decodeStream(

        seam: ConcentusOpusDecoderSeam,

        source: String,

        incarnationId: Long,

        opusPayload: ByteArray,

        frames: Int = FRAMES,

    ): ShortArray {

        seam.onPoolAllocated(source, incarnationId)

        val store = seamStoreFor(seam) ?: error("harness store required")

        val out = ShortArray(FRAME_SAMPLES * frames)

        for (slot in 0 until frames) {

            store.put(source, incarnationId, slot.toLong(), opusPayload)

            val pcm =

                seam.decode(source, incarnationId, slot.toLong(), nowMs = 0L)

                    ?: error("decode failed slot=$slot source=$source")

            pcm.samples.copyInto(out, slot * FRAME_SAMPLES)

        }

        return out

    }



    private fun decodeStreamWithInterleavedOtherSource(

        decodeSeam: ConcentusOpusDecoderSeam,

        otherSeam: ConcentusOpusDecoderSeam,

        otherSource: String,

        otherIncarnation: Long,

        otherOpus: ByteArray,

        targetSource: String,

        targetIncarnation: Long,

        targetOpus: ByteArray,

        store: OpusPayloadStore? = null,

        frames: Int = FRAMES,

    ): ShortArray {

        decodeSeam.onPoolAllocated(otherSource, otherIncarnation)

        decodeSeam.onPoolAllocated(targetSource, targetIncarnation)

        if (otherSeam !== decodeSeam) {

            otherSeam.onPoolAllocated(otherSource, otherIncarnation)

        }

        val payloadStore = store ?: seamStoreFor(decodeSeam) ?: OpusPayloadStore()

        val out = ShortArray(FRAME_SAMPLES * frames)

        for (slot in 0 until frames) {

            payloadStore.put(otherSource, otherIncarnation, slot.toLong(), otherOpus)

            payloadStore.put(targetSource, targetIncarnation, slot.toLong(), targetOpus)

            otherSeam.decode(otherSource, otherIncarnation, slot.toLong(), nowMs = 0L)

            val pcm =

                decodeSeam.decode(targetSource, targetIncarnation, slot.toLong(), nowMs = 0L)

                    ?: error("decode failed slot=$slot source=$targetSource")

            pcm.samples.copyInto(out, slot * FRAME_SAMPLES)

        }

        return out

    }



    private fun seamStoreFor(seam: ConcentusOpusDecoderSeam): OpusPayloadStore? {

        val field =

            ConcentusOpusDecoderSeam::class.java.getDeclaredField("payloadStore").apply {

                isAccessible = true

            }

        return field.get(seam) as OpusPayloadStore

    }



    private fun meanAbsSampleDiff(

        a: ShortArray,

        b: ShortArray,

    ): Double {

        require(a.size == b.size)

        var sum = 0.0

        for (i in a.indices) {

            sum += abs(a[i].toInt() - b[i].toInt())

        }

        return sum / a.size

    }



    private companion object {

        const val FRAMES = 12

        const val FRAME_SAMPLES = 960

        const val INDEPENDENT_MAX_MEAN_ABS_DIFF = 50.0

    }

}



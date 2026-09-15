package com.talkback.core.conference.capacity

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * G-RES-3-H1 sender capacity harness: absolute 20ms pacing, 9-leg unicast fan-out, preflight-first.
 */
class Gres3CapacityHarnessRunner(
    private val context: Context,
) {
    data class RunOutcome(
        val bundleDir: File,
        val qualificationPass: Boolean,
        val thermalPreflight: ThermalPreflight.Result,
        val counters: FanOutMetricsCounters,
        val h1cAdjudication: Gres3H1cAdjudicatorOutput? = null,
        val c4Adjudication: Gres3AdjudicatorOutput? = null,
        val h1dAdjudication: Gres3H1dAdjudicatorOutput? = null,
        val h1d4Attribution: Gres3H1d4AdjudicatorOutput? = null,
        val h1d5aAdjudication: Gres3H1d5aAdjudicatorOutput? = null,
        val topologyPreflight: Gres3TopologyPreflightResult? = null,
        val pacingThreadProfile: Gres3PacingThreadBootstrap.Profile? = null,
        val egressWarmupValid: Boolean = true,
        val lifecycleState: Gres3HarnessLifecycleState = Gres3HarnessLifecycleState.MEASUREMENT,
    )

    fun run(config: Gres3CapacityHarnessConfig): RunOutcome {
        val thermalPreflight = ThermalPreflight.run(context)
        if (!thermalPreflight.passesPreflight) {
            log("THERMAL_PREFLIGHT_FAIL ${thermalPreflight.blockerReason}")
        } else {
            log("THERMAL_PREFLIGHT_PASS api=${thermalPreflight.apiSources.count { it.readable }} sysfs=${thermalPreflight.sysfsZones.count { it.readable }}")
        }

        val formalTopology = config.formalTopology
        val wlanInterface = formalTopology?.senderDut?.wlanInterface ?: "wlan0"
        val senderLocalIp = Gres3NetworkObservabilityCapture.resolveWlanIpv4(wlanInterface)
        val topologyPreflight =
            formalTopology?.let { topology ->
                Gres3TopologyPreflight.validate(
                    topology = topology,
                    senderLocalIp = senderLocalIp,
                    sinkBindingsVerified = config.sinkBindingsVerified,
                    intendedWlanInterface = wlanInterface,
                )
            }
        if (topologyPreflight != null) {
            if (topologyPreflight.passes) {
                log("TOPOLOGY_PREFLIGHT_PASS distinctIps=${formalTopology?.distinctReceiverIpCount}")
            } else {
                log("TOPOLOGY_PREFLIGHT_FAIL ${topologyPreflight.blockerReasons.joinToString()}")
            }
        }

        val networkObservability =
            formalTopology?.let {
                Gres3NetworkObservabilityCapture.capture(
                    context = context,
                    wlanInterface = wlanInterface,
                    routeHints = topologyPreflight?.routeHints ?: emptyList(),
                )
            }

        val paths = Gres3EvidenceWriter.prepareBundleDir(context, config.runId)
        val resourceSampler = HarnessResourceSampler(context)
        val startedAtMs = System.currentTimeMillis()
        resourceSampler.sample("preflight", startedAtMs)

        val artifactRing = ProtectedArtifactRing.build(config.artifactRingSize)
        val sender = FanOutSenderFactory.open(config)
        val pacingProfileRef = AtomicReference<Gres3PacingThreadBootstrap.Profile?>(null)

        Gres3EvidenceWriter.writeManifest(
            file = paths.manifestFile,
            config = config,
            thermalPreflight = thermalPreflight,
            pacingThreadProfile = null,
            senderLocalPort = sender.localPort,
            senderLocalPorts = sender.localPorts,
            networkObservability = networkObservability,
            topologyPreflight = topologyPreflight,
            socketProfile = sender.socketProfile,
        )
        Gres3EvidenceWriter.writeConfig(paths.configFile, config)

        try {
            log("LIFECYCLE_STATE ${Gres3HarnessLifecycleState.SOCKET_CONFIGURED.name}")
            log(
                "EXECUTION_MODEL ${config.executionModel.name} socketCount=${config.executionModel.socketCount}",
            )
            log("WARMUP_BEGIN sec=${config.warmupSec}")
            log("LIFECYCLE_STATE ${Gres3HarnessLifecycleState.TIME_WARMUP.name}")
            Thread.sleep(config.warmupSec * 1000L)
            resourceSampler.sample("warmup_end", startedAtMs)

            val maxSlots = config.measurementSec * AbsoluteSlotScheduler.LOGICAL_MEDIA_RATE_HZ
            val metrics = FanOutMetricsBuffer.forConfig(context, config, maxSlots)
            val egressWarmupResultRef = AtomicReference<Gres3EgressWarmupResult?>(null)
            val h1d5aAdjudicationRef = AtomicReference<Gres3H1d5aAdjudicatorOutput?>(null)
            val warmupGatePassedRef = AtomicReference(false)
            val pacingError = AtomicReference<Throwable?>(null)

            log(
                "PACING_THREAD_SCHEDULED egressWarmupOnPacingThread=true " +
                    "attempts=${Gres3H1d5aAdjudicator.WARMUP_ATTEMPTS} " +
                    "processPriority=${config.pacingThreadPriority}",
            )
            val pacingThread =
                Thread(
                    {
                        try {
                            val profile =
                                Gres3PacingThreadBootstrap.applyAndVerify(config.pacingThreadPriority)
                            pacingProfileRef.set(profile)

                            log("LIFECYCLE_STATE ${Gres3HarnessLifecycleState.EGRESS_WARMUP.name}")
                            log(
                                "EGRESS_WARMUP_BEGIN legs=${sender.legCount} " +
                                    "attempts=${Gres3H1d5aAdjudicator.WARMUP_ATTEMPTS} " +
                                    "pacingTid=${profile.threadId}",
                            )
                            val egressWarmupResult = sender.warmEgress(artifactRing.artifactAt(0))
                            egressWarmupResultRef.set(egressWarmupResult)
                            val h1d5aAdjudication = Gres3H1d5aAdjudicator.adjudicate(egressWarmupResult)
                            h1d5aAdjudicationRef.set(h1d5aAdjudication)
                            Gres3EvidenceWriter.writeH1d5aAdjudication(
                                file = File(paths.bundleDir, "h1d5a-adjudication.json"),
                                output = h1d5aAdjudication,
                            )
                            log(
                                "EGRESS_WARMUP_END attempted=${egressWarmupResult.attempted} " +
                                    "succeeded=${egressWarmupResult.legsSucceeded} " +
                                    "failed=${egressWarmupResult.legsFailed} " +
                                    "warmEgressSucceeded=${egressWarmupResult.succeeded} " +
                                    "pacingTid=${profile.threadId} " +
                                    "sndbufEffective=${sender.socketProfile.sendBufferSizeEffective}",
                            )
                            if (egressWarmupResult.legFailures.isNotEmpty()) {
                                for (failure in egressWarmupResult.legFailures) {
                                    log(
                                        "EGRESS_WARMUP_LEG_FAIL leg=${failure.legIndex} " +
                                            "endpoint=${failure.endpointKey} " +
                                            "exception=${failure.exceptionClass} " +
                                            "message=${failure.message} " +
                                            "cause=${failure.causeClass}:${failure.causeMessage}",
                                    )
                                }
                            }
                            egressWarmupResult.setupError?.let { log("EGRESS_WARMUP_SETUP_ERROR $it") }

                            if (!egressWarmupResult.succeeded) {
                                return@Thread
                            }
                            warmupGatePassedRef.set(true)

                            log("LIFECYCLE_STATE ${Gres3HarnessLifecycleState.MEASUREMENT.name}")
                            val measurementStartNs = System.nanoTime()
                            val scheduler =
                                AbsoluteSlotScheduler(
                                    measurementStartNs = measurementStartNs,
                                    measurementDurationNs = config.measurementSec * 1_000_000_000L,
                                )
                            log(
                                "MEASUREMENT_BEGIN sec=${config.measurementSec} legs=${config.endpoints.size} " +
                                    "processPriority=${config.pacingThreadPriority} " +
                                    "tailDiag=${config.tailLatencyDiagnosticsEnabled} pacingTid=${profile.threadId}",
                            )
                            runMeasurementLoop(
                                scheduler = scheduler,
                                artifactRing = artifactRing,
                                sender = sender,
                                metrics = metrics,
                                perLegSendTiming = config.perLegSendTimingEnabled,
                            )
                        } catch (t: Throwable) {
                            pacingError.set(t)
                        }
                    },
                    "gres3-capacity-pacing",
                ).apply {
                    isDaemon = true
                    start()
                }

            pacingThread.join((config.measurementSec * 1000L) + 30_000L)
            pacingError.get()?.let { throw it }
            resourceSampler.sample("egress_warmup_end", startedAtMs)

            val egressWarmupResult =
                egressWarmupResultRef.get()
                    ?: error("pacing thread exited without egress warm-up result")
            val h1d5aAdjudication =
                h1d5aAdjudicationRef.get()
                    ?: error("pacing thread exited without h1d5a adjudication")

            if (!egressWarmupResult.succeeded) {
                return abortBeforeMeasurement(
                    paths = paths,
                    config = config,
                    thermalPreflight = thermalPreflight,
                    networkObservability = networkObservability,
                    topologyPreflight = topologyPreflight,
                    sender = sender,
                    h1d5aAdjudication = h1d5aAdjudication,
                    resourceSampler = resourceSampler,
                    startedAtMs = startedAtMs,
                    pacingThreadProfile = pacingProfileRef.get(),
                )
            }
            check(warmupGatePassedRef.get()) { "warm-up gate passed flag unset after successful egress warm-up" }

            resourceSampler.sample("measurement_end", startedAtMs)
            log("MEASUREMENT_END emitted=${metrics.snapshotCounters().emittedSlotCount}")

            val pacingProfile = pacingProfileRef.get()
            Gres3EvidenceWriter.writeManifest(
                file = paths.manifestFile,
                config = config,
                thermalPreflight = thermalPreflight,
                pacingThreadProfile = pacingProfile,
                senderLocalPort = sender.localPort,
                senderLocalPorts = sender.localPorts,
                networkObservability = networkObservability,
                topologyPreflight = topologyPreflight,
                socketProfile = sender.socketProfile,
            )

            log("COOLDOWN_BEGIN sec=${config.cooldownSec}")
            Thread.sleep(config.cooldownSec * 1000L)
            val counters = metrics.snapshotCounters()
            Gres3EvidenceWriter.flushRawSlotMetrics(
                rawDir = paths.rawDir,
                records = metrics.emittedSlotRecords(),
                includeLegColumns = config.perLegSendTimingEnabled,
            )
            if (config.tailLatencyDiagnosticsEnabled) {
                Gres3EvidenceWriter.flushTailEvents(
                    rawDir = paths.rawDir,
                    events = metrics.tailEvents(),
                    legCount = config.endpoints.size,
                )
                Gres3EvidenceWriter.flushSchedTailEvents(
                    rawDir = paths.rawDir,
                    events = metrics.schedTailEvents(),
                    legCount = config.endpoints.size,
                )
                Gres3EvidenceWriter.flushFanoutTailAttribution(
                    rawDir = paths.rawDir,
                    events = metrics.tailEvents(),
                    legCount = config.endpoints.size,
                )
            }
            resourceSampler.sample("cooldown_end", startedAtMs)

            val manifestWritten = paths.manifestFile.exists() && paths.configFile.exists()
            val rawFlushed = File(paths.rawDir, "slot-metrics.csv").exists()
            val qualificationChecks =
                QualificationChecks(
                    topologyDistinct = config.endpoints.map { it.endpointKey() }.toSet().size == config.endpoints.size,
                    thermalPreflightPass = thermalPreflight.passesPreflight,
                    manifestWritten = manifestWritten,
                    rawFlushed = rawFlushed,
                )
            Gres3EvidenceWriter.writeSummary(
                file = paths.summaryFile,
                config = config,
                counters = counters,
                qualificationChecks = qualificationChecks,
                thermalPreflight = thermalPreflight,
                pacingThreadProfile = pacingProfile,
                socketProfile = sender.socketProfile,
            )

            val h1cAdjudication =
                if (config.runClass != Gres3RunClass.QUALIFYING &&
                    (config.harnessPhase == Gres3HarnessPhase.H1c || config.harnessPhase == Gres3HarnessPhase.H1d)
                ) {
                    Gres3H1cAdjudicator.adjudicate(
                        Gres3H1cAdjudicatorInput(
                            topologyPreflight = topologyPreflight ?: Gres3TopologyPreflightResult(
                                passes = false,
                                targetCountValid = false,
                                moduleIdsDistinct = false,
                                endpointTuplesDistinct = false,
                                noLoopbackTargets = false,
                                noMulticastTargets = false,
                                senderNotLoopbackOnly = false,
                                sinkBindingsVerified = false,
                                routeHints = emptyList(),
                                blockerReasons = listOf("missing topology preflight"),
                            ),
                            topologyClass = formalTopology?.topologyClass ?: Gres3TopologyClass.LOOPBACK,
                            runClass = config.runClass,
                            harnessComplete = true,
                            rawFlushed = rawFlushed,
                            sendFailCount = counters.sendFailCount,
                            thermalPreflightPass = thermalPreflight.passesPreflight,
                            fanoutP99Ns = counters.fanoutP99Ns,
                            fanoutMaxNs = counters.fanoutMaxNs,
                        ),
                    ).also { output ->
                        Gres3EvidenceWriter.writeH1cAdjudication(
                            file = File(paths.bundleDir, "h1c-adjudication.json"),
                            output = output,
                        )
                        log(
                            "H1C_ADJUDICATION topology=${output.topologyValidity} runtime=${output.harnessRuntimeValidity} formal=${output.formalEvidenceTopology} c3proj=${output.c3Projection}",
                        )
                    }
                } else {
                    null
                }

            val c4Adjudication =
                if (config.runClass == Gres3RunClass.QUALIFYING) {
                    Gres3C4EvidenceAssembler.adjudicate(
                        config = config,
                        counters = counters,
                        thermalPreflight = thermalPreflight,
                        bundleComplete = manifestWritten && rawFlushed,
                    ).also { output ->
                        Gres3EvidenceWriter.writeC4Adjudication(
                            file = File(paths.bundleDir, "c4-adjudication.json"),
                            output = output,
                        )
                        log(
                            "C4_ADJUDICATION validity=${output.validity} tier1=${output.c3Tier1} " +
                                "tier2=${output.c3Tier2} verdict=${output.runVerdict} " +
                                "eligible=${output.eligibleForQualifyingSet}",
                        )
                    }
                } else {
                    null
                }

            val h1dAdjudication =
                if (config.harnessPhase == Gres3HarnessPhase.H1d) {
                    Gres3H1dAdjudicator.adjudicate(
                        Gres3H1dAdjudicatorInput(
                            counters = counters,
                            tailEvents = metrics.tailEvents(),
                            harnessInstrumentationActive = true,
                        ),
                    ).also { output ->
                        Gres3EvidenceWriter.writeH1dAdjudication(
                            file = File(paths.bundleDir, "h1d-adjudication.json"),
                            output = output,
                        )
                        log(
                            "H1D_ADJUDICATION readiness=${output.readiness} unexplained=${output.unexplainedExtremeTail} " +
                                "structuralC3=${output.structuralC3ProjectionFail} recurrentSched=${output.recurrentSchedulingDominantTail}",
                        )
                    }
                } else {
                    null
                }

            val h1d4Attribution =
                if (config.harnessPhase == Gres3HarnessPhase.H1d) {
                    Gres3FanoutTailAttributor.adjudicate(metrics.tailEvents()).also { output ->
                        Gres3EvidenceWriter.writeH1d4Attribution(
                            file = File(paths.bundleDir, "h1d4-attribution.json"),
                            output = output,
                        )
                        log(
                            "H1D4_ATTRIBUTION primary=${output.primaryAttribution} worstWall=${output.worstFanoutWallNs} " +
                                "worstCpu=${output.worstFanoutThreadCpuNs} worstNonCpu=${output.worstNonCpuNs}",
                        )
                    }
                } else {
                    null
                }

            val qualificationPass =
                when {
                    config.runClass == Gres3RunClass.QUALIFYING ->
                        c4Adjudication?.runVerdict == Gres3RunVerdict.PASS
                    config.harnessPhase == Gres3HarnessPhase.H1c ||
                        config.harnessPhase == Gres3HarnessPhase.H1d ->
                        h1cAdjudication?.formalEvidenceTopology == Gres3FormalEvidenceTopologyVerdict.ELIGIBLE
                    else -> qualificationChecks.qualificationPass
                }

            val outcome =
                RunOutcome(
                    bundleDir = paths.bundleDir,
                    qualificationPass = qualificationPass,
                    thermalPreflight = thermalPreflight,
                    counters = counters,
                    h1cAdjudication = h1cAdjudication,
                    c4Adjudication = c4Adjudication,
                    h1dAdjudication = h1dAdjudication,
                    h1d4Attribution = h1d4Attribution,
                    h1d5aAdjudication = h1d5aAdjudication,
                    topologyPreflight = topologyPreflight,
                    pacingThreadProfile = pacingProfile,
                    egressWarmupValid = true,
                    lifecycleState = Gres3HarnessLifecycleState.MEASUREMENT,
                )
            log("RUN_COMPLETE phase=${config.harnessPhase} runClass=${config.runClass} qualificationPass=${outcome.qualificationPass} bundle=${paths.bundleDir.absolutePath}")
            return outcome
        } finally {
            sender.close()
        }
    }

    private fun abortBeforeMeasurement(
        paths: Gres3EvidenceWriter.BundlePaths,
        config: Gres3CapacityHarnessConfig,
        thermalPreflight: ThermalPreflight.Result,
        networkObservability: Gres3NetworkObservability?,
        topologyPreflight: Gres3TopologyPreflightResult?,
        sender: FanOutSender,
        h1d5aAdjudication: Gres3H1d5aAdjudicatorOutput,
        resourceSampler: HarnessResourceSampler,
        startedAtMs: Long,
        pacingThreadProfile: Gres3PacingThreadBootstrap.Profile? = null,
    ): RunOutcome {
        log("EGRESS_WARMUP_ABORT measurement blocked — warm-up not 9/9")
        log("LIFECYCLE_STATE ${Gres3HarnessLifecycleState.ABORTED_BEFORE_MEASUREMENT.name}")
        resourceSampler.sample("aborted_before_measurement", startedAtMs)

        Gres3EvidenceWriter.writeManifest(
            file = paths.manifestFile,
            config = config,
            thermalPreflight = thermalPreflight,
            pacingThreadProfile = pacingThreadProfile,
            senderLocalPort = sender.localPort,
            senderLocalPorts = sender.localPorts,
            networkObservability = networkObservability,
            topologyPreflight = topologyPreflight,
            socketProfile = sender.socketProfile,
            lifecycleState = Gres3HarnessLifecycleState.ABORTED_BEFORE_MEASUREMENT,
        )

        val emptyCounters =
            FanOutMetricsCounters(
                emittedSlotCount = 0,
                missedSlotCount = 0,
                slotDeadlineMissCount = 0,
                catchUpEmissionCount = 0,
                sendFailCount = 0,
                fanoutP99Ns = 0L,
                fanoutMaxNs = 0L,
            )
        val qualificationChecks =
            QualificationChecks(
                topologyDistinct = config.endpoints.map { it.endpointKey() }.toSet().size == config.endpoints.size,
                thermalPreflightPass = thermalPreflight.passesPreflight,
                manifestWritten = paths.manifestFile.exists() && paths.configFile.exists(),
                rawFlushed = false,
            )
        Gres3EvidenceWriter.writeSummary(
            file = paths.summaryFile,
            config = config,
            counters = emptyCounters,
            qualificationChecks = qualificationChecks,
            thermalPreflight = thermalPreflight,
            pacingThreadProfile = pacingThreadProfile,
            socketProfile = sender.socketProfile,
            lifecycleState = Gres3HarnessLifecycleState.ABORTED_BEFORE_MEASUREMENT,
            measurementAborted = true,
        )

        val outcome =
            RunOutcome(
                bundleDir = paths.bundleDir,
                qualificationPass = false,
                thermalPreflight = thermalPreflight,
                counters = emptyCounters,
                h1cAdjudication = null,
                h1dAdjudication = null,
                h1d4Attribution = null,
                h1d5aAdjudication = h1d5aAdjudication,
                topologyPreflight = topologyPreflight,
                pacingThreadProfile = pacingThreadProfile,
                egressWarmupValid = false,
                lifecycleState = Gres3HarnessLifecycleState.ABORTED_BEFORE_MEASUREMENT,
            )
        log(
            "RUN_ABORTED phase=${config.harnessPhase} reason=EGRESS_WARMUP_INVALID " +
                "verdict=${h1d5aAdjudication.verdict.name} bundle=${paths.bundleDir.absolutePath}",
        )
        return outcome
    }

    private fun runMeasurementLoop(
        scheduler: AbsoluteSlotScheduler,
        artifactRing: ProtectedArtifactRing,
        sender: FanOutSender,
        metrics: FanOutMetricsBuffer,
        perLegSendTiming: Boolean,
    ) {
        var slotIndex = 0L
        while (true) {
            val nowNs = System.nanoTime()
            if (scheduler.isMeasurementComplete(nowNs)) {
                break
            }
            val actionable =
                scheduler.resolveNextEmittableSlot(nowNs, slotIndex) { missed ->
                    metrics.recordMissedSlot(missed)
                } ?: break
            slotIndex = actionable
            scheduler.waitUntilSlotStart(slotIndex)
            val fanoutStartNs = System.nanoTime()
            val schedLatenessNs = fanoutStartNs - scheduler.nominalSlotStartNs(slotIndex)
            val artifact = artifactRing.artifactAt(slotIndex.toInt())
            val sendOutcome =
                sender.sendFanOut(
                    artifact = artifact,
                    recordLegTiming = perLegSendTiming,
                )
            val fanoutEndNs = System.nanoTime()
            val slotDeadlineMiss = fanoutEndNs >= scheduler.nominalSlotDeadlineNs(slotIndex)
            metrics.recordEmittedSlot(
                slotIndex = slotIndex,
                schedLatenessNsValue = schedLatenessNs,
                fanoutDurationNsValue = fanoutEndNs - fanoutStartNs,
                sendOutcome = sendOutcome,
                slotDeadlineMiss = slotDeadlineMiss,
                legDurationsNs = if (perLegSendTiming) sender.lastLegDurationsNs else null,
            )
            slotIndex++
        }
    }

    private fun log(message: String) {
        Log.i(Gres3CapacityHarnessConstants.LOG_TAG, message)
    }
}

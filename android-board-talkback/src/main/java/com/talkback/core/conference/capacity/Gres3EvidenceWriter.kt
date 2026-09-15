package com.talkback.core.conference.capacity

import android.content.Context
import android.util.Log
import java.io.File

/**
 * G-RES-3-H1a evidence bundle writer. Raw slot metrics flushed during cooldown only.
 */
object Gres3EvidenceWriter {
    data class BundlePaths(
        val bundleDir: File,
        val manifestFile: File,
        val configFile: File,
        val summaryFile: File,
        val rawDir: File,
    )

    fun prepareBundleDir(context: Context, runId: String): BundlePaths {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val bundleDir = File(root, "gres3-capacity/$runId").apply { mkdirs() }
        val rawDir = File(bundleDir, "raw").apply { mkdirs() }
        return BundlePaths(
            bundleDir = bundleDir,
            manifestFile = File(bundleDir, "manifest.json"),
            configFile = File(bundleDir, "config.json"),
            summaryFile = File(bundleDir, "summary.json"),
            rawDir = rawDir,
        )
    }

    fun writeManifest(
        file: File,
        config: Gres3CapacityHarnessConfig,
        thermalPreflight: ThermalPreflight.Result,
        pacingThreadProfile: Gres3PacingThreadBootstrap.Profile?,
        senderLocalPort: Int,
        senderLocalPorts: List<Int> = listOf(senderLocalPort),
        networkObservability: Gres3NetworkObservability? = null,
        topologyPreflight: Gres3TopologyPreflightResult? = null,
        socketProfile: Gres3UdpEgressSocketProfile? = null,
        lifecycleState: Gres3HarnessLifecycleState? = null,
    ) {
        if (config.harnessPhase == Gres3HarnessPhase.H1c && config.formalTopology != null ||
            config.harnessPhase == Gres3HarnessPhase.H1d && config.formalTopology != null
        ) {
            writeH1cManifest(
                file = file,
                config = config,
                thermalPreflight = thermalPreflight,
                pacingThreadProfile = pacingThreadProfile,
                senderLocalPort = senderLocalPort,
                senderLocalPorts = senderLocalPorts,
                networkObservability = networkObservability,
                topologyPreflight = topologyPreflight,
                socketProfile = socketProfile,
                lifecycleState = lifecycleState,
            )
            return
        }
        val topology =
            config.endpoints.joinToString(prefix = "[", postfix = "]") { endpoint ->
                """{"receiverModuleId":"${endpoint.receiverModuleId}","moduleFixedIp":"${endpoint.moduleFixedIp}","mediaPort":${endpoint.mediaPort}}"""
            }
        val content =
            """
            {
              "harness": "G-RES-3-H1",
              "phase": "H1a",
              "runId": "${config.runId}",
              "runClass": "${config.runClass.name}",
              "deviceLabel": "${config.deviceLabel}",
              "appBuildSha": "${config.appBuildSha}",
              "benchmarkConfigHash": "${config.benchmarkConfigHash}",
              "requiredLegCount": ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT},
              "topology": $topology,
              "pacing": {
                "slotPeriodNs": ${AbsoluteSlotScheduler.SLOT_PERIOD_NS},
                "threadPriorityConfigured": ${config.pacingThreadPriority},
                "threadPriorityObserved": ${pacingThreadProfile?.observedProcessPriority ?: config.pacingThreadPriority},
                "threadPriorityApplied": ${pacingThreadProfile?.priorityApplied ?: false},
                "threadId": ${pacingThreadProfile?.threadId ?: -1},
                "threadName": ${pacingThreadProfile?.threadName?.let { "\"$it\"" } ?: "null"},
                "javaThreadPriorityObserved": ${pacingThreadProfile?.observedJavaPriority ?: -1},
                "catchUpForbidden": true
              },
              "thermalPreflight": {
                "observable": ${thermalPreflight.observable},
                "passesPreflight": ${thermalPreflight.passesPreflight},
                "blockerReason": ${thermalPreflight.blockerReason?.let { "\"$it\"" } ?: "null"}
              },
              "senderLocalPort": $senderLocalPort,
              "senderExecution": ${senderExecutionJson(config, senderLocalPort, senderLocalPorts)},
              "lifecycleState": ${lifecycleState?.let { "\"${it.name}\"" } ?: "null"},
              "udpEgressSocket": ${socketObservabilityJson(socketProfile)}
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun writeH1cManifest(
        file: File,
        config: Gres3CapacityHarnessConfig,
        thermalPreflight: ThermalPreflight.Result,
        pacingThreadProfile: Gres3PacingThreadBootstrap.Profile?,
        senderLocalPort: Int,
        senderLocalPorts: List<Int> = listOf(senderLocalPort),
        networkObservability: Gres3NetworkObservability?,
        topologyPreflight: Gres3TopologyPreflightResult?,
        socketProfile: Gres3UdpEgressSocketProfile? = null,
        lifecycleState: Gres3HarnessLifecycleState? = null,
    ) {
        val topology = config.formalTopology ?: error("H1c manifest requires formalTopology")
        val senderIp = networkObservability?.senderLocalIp ?: topology.senderDut.localIp ?: "null"
        val targetsJson =
            topology.targets.joinToString(prefix = "[", postfix = "]") { target ->
                """{"receiverModuleId":"${target.receiverModuleId}","receiverIp":"${target.receiverIp}","receiverPort":${target.receiverPort},"sinkIdentity":"${target.sinkIdentity}","sinkHostLabel":"${target.sinkHostLabel}"}"""
            }
        val routeJson =
            topologyPreflight?.routeHints?.joinToString(prefix = "[", postfix = "]") { hint ->
                """{"receiverModuleId":"${hint.receiverModuleId}","receiverIp":"${hint.receiverIp}","receiverPort":${hint.receiverPort},"interface":"${hint.resolvesViaInterface ?: ""}","loopback":${hint.isLoopback},"multicast":${hint.isMulticast}}"""
            } ?: "[]"
        val blockerReasonsJson =
            topologyPreflight?.blockerReasons?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "[]"
        val content =
            """
            {
              "harness": "G-RES-3-H1",
              "phase": "${config.harnessPhase.name}",
              "runId": "${config.runId}",
              "runClass": "${config.runClass.name}",
              "topologyId": "${topology.topologyId}",
              "topologyClass": "${topology.topologyClass.name.lowercase().replace('_', '-')}",
              "deviceLabel": "${config.deviceLabel}",
              "appBuildSha": "${config.appBuildSha}",
              "benchmarkConfigHash": "${config.benchmarkConfigHash}",
              "requiredLegCount": ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT},
              "multicastLegCount": 0,
              "distinctReceiverIpCount": ${topology.distinctReceiverIpCount},
              "senderDut": {
                "deviceLabel": "${topology.senderDut.deviceLabel}",
                "deviceSerial": "${topology.senderDut.deviceSerial}",
                "moduleId": "${topology.senderDut.moduleId}",
                "wlanInterface": "${topology.senderDut.wlanInterface}",
                "localIp": "$senderIp"
              },
              "targets": $targetsJson,
              "networkObservability": {
                "wifiSsid": ${networkObservability?.wifiSsid?.let { "\"$it\"" } ?: "null"},
                "wifiBssid": ${networkObservability?.wifiBssid?.let { "\"$it\"" } ?: "null"},
                "routeHints": $routeJson
              },
              "topologyPreflight": {
                "passes": ${topologyPreflight?.passes ?: false},
                "blockerReasons": $blockerReasonsJson
              },
              "pacing": {
                "slotPeriodNs": ${AbsoluteSlotScheduler.SLOT_PERIOD_NS},
                "threadPriorityConfigured": ${config.pacingThreadPriority},
                "threadPriorityObserved": ${pacingThreadProfile?.observedProcessPriority ?: config.pacingThreadPriority},
                "threadPriorityApplied": ${pacingThreadProfile?.priorityApplied ?: false},
                "threadId": ${pacingThreadProfile?.threadId ?: -1},
                "threadName": ${pacingThreadProfile?.threadName?.let { "\"$it\"" } ?: "null"},
                "javaThreadPriorityObserved": ${pacingThreadProfile?.observedJavaPriority ?: -1},
                "catchUpForbidden": true
              },
              "thermalPreflight": {
                "observable": ${thermalPreflight.observable},
                "passesPreflight": ${thermalPreflight.passesPreflight},
                "blockerReason": ${thermalPreflight.blockerReason?.let { "\"$it\"" } ?: "null"}
              },
              "senderLocalPort": $senderLocalPort,
              "senderExecution": ${senderExecutionJson(config, senderLocalPort, senderLocalPorts)},
              "lifecycleState": ${lifecycleState?.let { "\"${it.name}\"" } ?: "null"},
              "udpEgressSocket": ${socketObservabilityJson(socketProfile)}
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun writeC4Adjudication(
        file: File,
        output: Gres3AdjudicatorOutput,
    ) {
        val reasonsJson =
            output.reasons.joinToString(prefix = "[", postfix = "]") { "\"${it.name}\"" }
        val content =
            """
            {
              "validity": "${output.validity.name}",
              "c3Tier1": ${output.c3Tier1?.let { "\"${it.name}\"" } ?: "null"},
              "c3Tier2": ${output.c3Tier2?.let { "\"${it.name}\"" } ?: "null"},
              "runVerdict": "${output.runVerdict.name}",
              "eligibleForQualifyingSet": ${output.eligibleForQualifyingSet},
              "reasons": $reasonsJson
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun writeH1cAdjudication(
        file: File,
        output: Gres3H1cAdjudicatorOutput,
    ) {
        val reasonsJson =
            output.reasons.joinToString(prefix = "[", postfix = "]") { "\"${it.name}\"" }
        val content =
            """
            {
              "topologyValidity": "${output.topologyValidity.name}",
              "harnessRuntimeValidity": "${output.harnessRuntimeValidity.name}",
              "formalEvidenceTopology": "${output.formalEvidenceTopology.name}",
              "c3Projection": "${output.c3Projection.name}",
              "reasons": $reasonsJson
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun writeConfig(file: File, config: Gres3CapacityHarnessConfig) {
        val endpoints =
            config.endpoints.joinToString(prefix = "[", postfix = "]") { endpoint ->
                """{"receiverModuleId":"${endpoint.receiverModuleId}","moduleFixedIp":"${endpoint.moduleFixedIp}","mediaPort":${endpoint.mediaPort}}"""
            }
        val content =
            """
            {
              "warmupSec": ${config.warmupSec},
              "measurementSec": ${config.measurementSec},
              "cooldownSec": ${config.cooldownSec},
              "runClass": "${config.runClass.name}",
              "artifactRingSize": ${config.artifactRingSize},
              "logicalMediaRateHz": ${AbsoluteSlotScheduler.LOGICAL_MEDIA_RATE_HZ},
              "executionModel": "${config.executionModel.name}",
              "socketCount": ${config.executionModel.socketCount},
              "endpoints": $endpoints
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun writeSummary(
        file: File,
        config: Gres3CapacityHarnessConfig,
        counters: FanOutMetricsCounters,
        qualificationChecks: QualificationChecks,
        thermalPreflight: ThermalPreflight.Result,
        pacingThreadProfile: Gres3PacingThreadBootstrap.Profile? = null,
        socketProfile: Gres3UdpEgressSocketProfile? = null,
        lifecycleState: Gres3HarnessLifecycleState? = null,
        measurementAborted: Boolean = false,
    ) {
        val pacingJson =
            pacingThreadProfile?.let { profile ->
                """
                ,
              "pacingThread": {
                "threadId": ${profile.threadId},
                "threadName": "${profile.threadName}",
                "configuredProcessPriority": ${profile.configuredProcessPriority},
                "observedProcessPriority": ${profile.observedProcessPriority},
                "observedJavaPriority": ${profile.observedJavaPriority},
                "priorityApplied": ${profile.priorityApplied}
              }
                """.trimIndent()
            } ?: ""
        val socketJson =
            socketProfile?.let { profile ->
                """
                ,
              "udpEgressSocket": ${socketObservabilityJson(profile)}
                """.trimIndent()
            } ?: ""
        val sendmmsgJson =
            if (config.executionModel.usesSendmmsgBatch) {
                """
                ,
              "sendmmsgProbe": {
                "partialBatchCount": ${counters.sendmmsgPartialBatchCount},
                "minReturnedMessages": ${counters.sendmmsgMinReturnedMessages},
                "worstSyscallWallNs": ${counters.sendmmsgWorstSyscallWallNs},
                "eagainCount": ${counters.sendmmsgEagainCount}
              }
                """.trimIndent()
            } else {
                ""
            }
        val qualifyingEvidenceJson =
            if (config.runClass == Gres3RunClass.QUALIFYING) {
                val expectedSlots = config.measurementSec * AbsoluteSlotScheduler.LOGICAL_MEDIA_RATE_HZ
                val expectedLegAttempts = expectedSlots * Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT
                val minReturnedJson =
                    if (config.executionModel.usesSendmmsgBatch) {
                        ""","minReturnedMessages": ${counters.sendmmsgMinReturnedMessages}"""
                    } else {
                        ""
                    }
                val batchSyscallJson =
                    if (config.executionModel.usesSendmmsgBatch &&
                        counters.sendmmsgWorstSyscallWallNs > 0L
                    ) {
                        ""","batchSyscallDurationUs": ${counters.sendmmsgWorstSyscallWallNs / 1_000L}"""
                    } else {
                        ""
                    }
                """
                ,
              "qualifyingEvidence": {
                "expectedLogicalSlots": $expectedSlots,
                "expectedLegSendAttempts": $expectedLegAttempts,
                "perLegSendAttemptCount": ${counters.perLegSendAttemptCount},
                "notAttemptedCount": ${counters.notAttemptedCount},
                "partialBatchCount": ${counters.sendmmsgPartialBatchCount},
                "fanoutCompleteSlotCount": ${counters.fanoutCompleteSlotCount},
                "fanoutIncompleteSlotCount": ${counters.fanoutIncompleteSlotCount}$minReturnedJson$batchSyscallJson
              }
                """.trimIndent()
            } else {
                ""
            }
        val content =
            """
            {
              "runClass": "${config.runClass.name}",
              "benchmarkConfigHash": "${config.benchmarkConfigHash}",
              "appBuildSha": "${config.appBuildSha}",
              "harnessPhase": "${config.harnessPhase.name}",
              "executionModel": "${config.executionModel.name}",
              "socketCount": ${config.executionModel.socketCount},
              "lifecycleState": ${lifecycleState?.let { "\"${it.name}\"" } ?: "null"},
              "measurementAborted": $measurementAborted,
              "counters": {
                "emittedSlotCount": ${counters.emittedSlotCount},
                "missedSlotCount": ${counters.missedSlotCount},
                "slotDeadlineMissCount": ${counters.slotDeadlineMissCount},
                "catchUpEmissionCount": ${counters.catchUpEmissionCount},
                "sendFailCount": ${counters.sendFailCount},
                "fanoutP99Ns": ${counters.fanoutP99Ns},
                "fanoutMaxNs": ${counters.fanoutMaxNs},
                "schedLatenessMaxNs": ${counters.schedLatenessMaxNs},
                "extremeTailStallCount": ${counters.extremeTailStallCount},
                "notAttemptedCount": ${counters.notAttemptedCount},
                "perLegSendAttemptCount": ${counters.perLegSendAttemptCount},
                "fanoutCompleteSlotCount": ${counters.fanoutCompleteSlotCount},
                "fanoutIncompleteSlotCount": ${counters.fanoutIncompleteSlotCount},
                "slotExecutionCompletionMaxNs": ${counters.slotExecutionCompletionMaxNs}
              },
              "qualification": {
                "topologyDistinct": ${qualificationChecks.topologyDistinct},
                "thermalPreflightPass": ${qualificationChecks.thermalPreflightPass},
                "manifestWritten": ${qualificationChecks.manifestWritten},
                "rawFlushed": ${qualificationChecks.rawFlushed},
                "qualificationPass": ${qualificationChecks.qualificationPass}
              },
              "thermalPreflightObservable": ${thermalPreflight.observable}$pacingJson$socketJson$sendmmsgJson$qualifyingEvidenceJson
            }
            """.trimIndent()
        file.writeText(content)
    }

    internal fun senderExecutionJson(
        config: Gres3CapacityHarnessConfig,
        senderLocalPort: Int,
        senderLocalPorts: List<Int>,
    ): String {
        val portsJson = senderLocalPorts.joinToString(prefix = "[", postfix = "]")
        return """
            {
              "executionModel": "${config.executionModel.name}",
              "socketCount": ${config.executionModel.socketCount},
              "senderLocalPort": $senderLocalPort,
              "senderLocalPorts": $portsJson
            }
            """.trimIndent().replace("\n", "\n              ")
    }

    internal fun socketObservabilityJson(profile: Gres3UdpEgressSocketProfile?): String {
        if (profile == null) {
            return "null"
        }
        val legFailuresJson =
            profile.egressWarmupLegFailures.joinToString(prefix = "[", postfix = "]") { failure ->
                """
                {
                  "legIndex": ${failure.legIndex},
                  "receiverModuleId": ${jsonString(failure.receiverModuleId)},
                  "moduleFixedIp": ${jsonString(failure.moduleFixedIp)},
                  "mediaPort": ${failure.mediaPort},
                  "endpointKey": ${jsonString(failure.endpointKey)},
                  "exceptionClass": ${jsonString(failure.exceptionClass)},
                  "message": ${jsonString(failure.message)},
                  "causeClass": ${jsonString(failure.causeClass)},
                  "causeMessage": ${jsonString(failure.causeMessage)}
                }
                """.trimIndent()
            }
        return """
            {
              "socketMode": "${profile.socketMode}",
              "connected": ${profile.connected},
              "reuseAddress": ${profile.reuseAddress},
              "broadcast": ${profile.broadcast},
              "sendBufferSizeDefault": ${profile.sendBufferSizeDefault},
              "sendBufferSizeRequested": ${profile.sendBufferSizeRequested},
              "sendBufferSizeEffective": ${profile.sendBufferSizeEffective},
              "receiveBufferSizeEffective": ${profile.receiveBufferSizeEffective},
              "trafficClass": ${profile.trafficClass},
              "egressWarmupAttempted": ${profile.egressWarmupAttempted},
              "egressWarmupSucceeded": ${profile.egressWarmupSucceeded},
              "egressWarmupLegsSucceeded": ${profile.egressWarmupLegsSucceeded},
              "egressWarmupLegsFailed": ${profile.egressWarmupLegsFailed},
              "egressWarmupSetupError": ${jsonString(profile.egressWarmupSetupError)},
              "egressWarmupLegFailures": $legFailuresJson
            }
            """.trimIndent().replace("\n", "\n              ")
    }

    internal fun jsonString(value: String?): String {
        if (value == null) {
            return "null"
        }
        val escaped =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        return "\"$escaped\""
    }

    fun writeH1d5aAdjudication(
        file: File,
        output: Gres3H1d5aAdjudicatorOutput,
    ) {
        val legFailuresJson =
            output.legFailures.joinToString(prefix = "[", postfix = "]") { failure ->
                """
                {
                  "legIndex": ${failure.legIndex},
                  "receiverModuleId": ${jsonString(failure.receiverModuleId)},
                  "moduleFixedIp": ${jsonString(failure.moduleFixedIp)},
                  "mediaPort": ${failure.mediaPort},
                  "endpointKey": ${jsonString(failure.endpointKey)},
                  "exceptionClass": ${jsonString(failure.exceptionClass)},
                  "message": ${jsonString(failure.message)},
                  "causeClass": ${jsonString(failure.causeClass)},
                  "causeMessage": ${jsonString(failure.causeMessage)}
                }
                """.trimIndent()
            }
        val reasonsJson = output.reasons.joinToString(prefix = "[", postfix = "]") { jsonString(it) }
        val content =
            """
            {
              "verdict": "${output.verdict.name}",
              "lifecycleState": "${output.lifecycleState.name}",
              "warmEgressSucceeded": ${output.warmEgressSucceeded},
              "warmupAttempts": ${output.warmupAttempts},
              "legsSucceeded": ${output.legsSucceeded},
              "legsFailed": ${output.legsFailed},
              "setupError": ${jsonString(output.setupError)},
              "legFailures": $legFailuresJson,
              "reasons": $reasonsJson
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun flushRawSlotMetrics(
        rawDir: File,
        records: List<FanOutSlotRecord>,
        includeLegColumns: Boolean = false,
    ) {
        val file = File(rawDir, "slot-metrics.csv")
        file.bufferedWriter().use { writer ->
            if (includeLegColumns) {
                writer.appendLine(
                    "slotIndex,schedLatenessNs,fanoutDurationNs,legsFailed,maxLegIndex,maxLegDurationNs",
                )
                for (record in records) {
                    writer.appendLine(
                        "${record.slotIndex},${record.schedLatenessNs},${record.fanoutDurationNs}," +
                            "${record.legsFailed},${record.maxLegIndex},${record.maxLegDurationNs}",
                    )
                }
            } else {
                writer.appendLine("slotIndex,schedLatenessNs,fanoutDurationNs,legsFailed")
                for (record in records) {
                    writer.appendLine(
                        "${record.slotIndex},${record.schedLatenessNs},${record.fanoutDurationNs},${record.legsFailed}",
                    )
                }
            }
        }
        Log.i(Gres3CapacityHarnessConstants.LOG_TAG, "RAW_FLUSH path=${file.absolutePath} rows=${records.size}")
    }

    fun flushTailEvents(
        rawDir: File,
        events: List<FanOutTailEvent>,
        legCount: Int,
    ) {
        if (events.isEmpty()) {
            return
        }
        val file = File(rawDir, "tail-events.csv")
        writeTailEventCsv(file, events, legCount, includeSchedContext = false)
        Log.i(Gres3CapacityHarnessConstants.LOG_TAG, "TAIL_FLUSH path=${file.absolutePath} rows=${events.size}")
    }

    fun flushSchedTailEvents(
        rawDir: File,
        events: List<FanOutTailEvent>,
        legCount: Int,
    ) {
        if (events.isEmpty()) {
            return
        }
        val file = File(rawDir, "sched-tail-events.csv")
        writeTailEventCsv(file, events, legCount, includeSchedContext = true)
        Log.i(Gres3CapacityHarnessConstants.LOG_TAG, "SCHED_TAIL_FLUSH path=${file.absolutePath} rows=${events.size}")
    }

    private fun writeTailEventCsv(
        file: File,
        events: List<FanOutTailEvent>,
        legCount: Int,
        includeSchedContext: Boolean,
    ) {
        val legHeaders = (0 until legCount).joinToString(",") { "leg${it}Ns" }
        val schedHeaders =
            if (includeSchedContext) {
                ",threadId,threadName,processThreadPriority,javaThreadPriority,gcCount,thermalStatus,processImportance,screenInteractive"
            } else {
                ""
            }
        file.bufferedWriter().use { writer ->
            writer.appendLine(
                "slotIndex,schedLatenessNs,fanoutDurationNs,fanoutThreadCpuNs,legWallSumNs,interLegGapNs,fanoutNonCpuNs," +
                    "maxLegIndex,maxLegDurationNs," +
                    "slotDeadlineMiss,javaHeapUsed,nativeHeap,uptimeMs,$legHeaders$schedHeaders",
            )
            for (event in events) {
                val legs = event.legDurationsNs.joinToString(",")
                val schedFields =
                    if (includeSchedContext) {
                        val ctx = event.stallContext
                        ",${ctx.threadId},${ctx.threadName},${ctx.processThreadPriority},${ctx.javaThreadPriority}," +
                            "${ctx.gcCount},${ctx.thermalStatus ?: ""},${ctx.processImportance ?: ""},${ctx.screenInteractive ?: ""}"
                    } else {
                        ""
                    }
                writer.appendLine(
                    "${event.slotIndex},${event.schedLatenessNs},${event.fanoutDurationNs}," +
                        "${event.fanoutThreadCpuNs},${event.legWallSumNs},${event.interLegGapNs},${event.fanoutNonCpuNs}," +
                        "${event.maxLegIndex},${event.maxLegDurationNs},${event.slotDeadlineMiss}," +
                        "${event.stallContext.javaHeapUsedBytes},${event.stallContext.nativeHeapBytes}," +
                        "${event.stallContext.uptimeMs},$legs$schedFields",
                )
            }
        }
    }

    fun flushFanoutTailAttribution(
        rawDir: File,
        events: List<FanOutTailEvent>,
        @Suppress("UNUSED_PARAMETER") legCount: Int,
    ) {
        if (events.isEmpty()) {
            return
        }
        val file = File(rawDir, "fanout-tail-attribution.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine(
                "slotIndex,attributionClass,fanoutWallNs,fanoutThreadCpuNs,legWallSumNs,interLegGapNs," +
                    "maxLegIndex,maxLegDurationNs",
            )
            for (event in events) {
                val attributed = Gres3FanoutTailAttributor.classifyEvent(event)
                writer.appendLine(
                    "${attributed.slotIndex},${attributed.attributionClass.name}," +
                        "${attributed.fanoutWallNs},${attributed.fanoutThreadCpuNs}," +
                        "${attributed.legWallSumNs},${attributed.interLegGapNs}," +
                        "${attributed.maxLegIndex},${attributed.maxLegDurationNs}",
                )
            }
        }
        Log.i(Gres3CapacityHarnessConstants.LOG_TAG, "FANOUT_TAIL_ATTR_FLUSH path=${file.absolutePath} rows=${events.size}")
    }

    fun writeH1d4Attribution(
        file: File,
        output: Gres3H1d4AdjudicatorOutput,
    ) {
        val eventsJson =
            output.tailEventAttributions.joinToString(prefix = "[", postfix = "]") { event ->
                """
                {
                  "slotIndex": ${event.slotIndex},
                  "attributionClass": "${event.attributionClass.name}",
                  "fanoutWallNs": ${event.fanoutWallNs},
                  "fanoutThreadCpuNs": ${event.fanoutThreadCpuNs},
                  "legWallSumNs": ${event.legWallSumNs},
                  "interLegGapNs": ${event.interLegGapNs},
                  "maxLegIndex": ${event.maxLegIndex},
                  "maxLegDurationNs": ${event.maxLegDurationNs}
                }
                """.trimIndent()
            }
        val reasonsJson = output.reasons.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val content =
            """
            {
              "primaryAttribution": "${output.primaryAttribution.name}",
              "worstFanoutWallNs": ${output.worstFanoutWallNs},
              "worstFanoutThreadCpuNs": ${output.worstFanoutThreadCpuNs},
              "worstNonCpuNs": ${output.worstNonCpuNs},
              "tailEventAttributions": $eventsJson,
              "reasons": $reasonsJson
            }
            """.trimIndent()
        file.writeText(content)
    }

    fun writeH1dAdjudication(
        file: File,
        output: Gres3H1dAdjudicatorOutput,
    ) {
        val summary = output.attributionSummary
        val reasonsJson =
            output.reasons.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val hintsJson =
            output.schedAttributionHints.joinToString(prefix = "[", postfix = "]") { "\"${it.name}\"" }
        val content =
            """
            {
              "readiness": "${output.readiness.name}",
              "unexplainedExtremeTail": ${output.unexplainedExtremeTail},
              "structuralC3ProjectionFail": ${output.structuralC3ProjectionFail},
              "recurrentSchedulingDominantTail": ${output.recurrentSchedulingDominantTail},
              "attribution": {
                "tailEventCount": ${summary.tailEventCount},
                "schedTailEventCount": ${summary.schedTailEventCount},
                "worstFanoutNs": ${summary.worstFanoutNs},
                "worstSchedLatenessNs": ${summary.worstSchedLatenessNs},
                "worstSlotExecutionNs": ${summary.worstSlotExecutionNs},
                "worstMaxLegIndex": ${summary.worstMaxLegIndex},
                "worstDominantLegFraction": ${summary.worstDominantLegFraction},
                "schedDominantTailCount": ${summary.schedDominantTailCount},
                "legSendDominantTailCount": ${summary.legSendDominantTailCount},
                "extremeStallCount": ${summary.extremeStallCount}
              },
              "schedAttributionHints": $hintsJson,
              "reasons": $reasonsJson
            }
            """.trimIndent()
        file.writeText(content)
    }
}

data class QualificationChecks(
    val topologyDistinct: Boolean,
    val thermalPreflightPass: Boolean,
    val manifestWritten: Boolean,
    val rawFlushed: Boolean,
) {
    val qualificationPass: Boolean
        get() = topologyDistinct && thermalPreflightPass && manifestWritten && rawFlushed
}

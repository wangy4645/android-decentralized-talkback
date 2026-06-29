package com.talkback.core.contacts

import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.sync.RemoteModuleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsProjectionTest {

  @Test
  fun project_callableWithMultiEndpointDirectory_listsAllEndpoints() {
    val state = moduleState(
      moduleId = "M02",
      endpoints = listOf(
        RemoteEndpointInfo("E02", "Headset", true, EndpointPriority.NORMAL),
        RemoteEndpointInfo("E01", "Handset", true, EndpointPriority.NORMAL)
      ),
      lastHelloMs = 1_000L
    )
    val rows = ContactsProjection.project(
      localModuleId = "M01",
      callableModuleIds = setOf("M02"),
      moduleStates = mapOf("M02" to state),
      isModuleReachable = { _, _ -> true }
    )
    assertEquals(listOf("M02-E01", "M02-E02"), rows.map { it.endpointKey })
    assertTrue(rows.all { it.moduleOnline })
  }

  @Test
  fun project_unverifiedModule_notListed() {
    val state = moduleState(
      moduleId = "M03",
      endpoints = listOf(RemoteEndpointInfo("E01", "X", true, EndpointPriority.NORMAL)),
      lastHelloMs = 1_000L
    )
    val rows = ContactsProjection.project(
      localModuleId = "M01",
      callableModuleIds = emptySet(),
      moduleStates = mapOf("M03" to state),
      isModuleReachable = { _, _ -> true }
    )
    assertTrue(rows.isEmpty())
  }

  @Test
  fun project_callableWithoutDirectory_notListed() {
    val state = moduleState(
      moduleId = "M04",
      endpoints = emptyList(),
      lastHelloMs = 0L
    )
    val rows = ContactsProjection.project(
      localModuleId = "M01",
      callableModuleIds = setOf("M04"),
      moduleStates = mapOf("M04" to state),
      isModuleReachable = { _, _ -> true }
    )
    assertTrue(rows.isEmpty())
  }

  @Test
  fun project_callableWithDirectoryButNoHelloTimestamp_notListed() {
    val state = moduleState(
      moduleId = "M05",
      endpoints = listOf(RemoteEndpointInfo("E01", "X", true, EndpointPriority.NORMAL)),
      lastHelloMs = 0L
    )
    val rows = ContactsProjection.project(
      localModuleId = "M01",
      callableModuleIds = setOf("M05"),
      moduleStates = mapOf("M05" to state),
      isModuleReachable = { _, _ -> true }
    )
    assertTrue(rows.isEmpty())
  }

  private fun moduleState(
    moduleId: String,
    endpoints: List<RemoteEndpointInfo>,
    lastHelloMs: Long
  ): RemoteModuleState = RemoteModuleState(
    presence = ModulePresence(
      moduleId = ModuleId(moduleId),
      host = "10.0.0.1",
      port = 5000,
      endpointCount = endpoints.count { it.online },
      lastSeenMs = lastHelloMs
    ),
    endpoints = endpoints,
    lastHelloMs = lastHelloMs
  )
}

"""Extract first occurrence of coordinator test/internal seams from TalkbackCoordinator dump."""
from __future__ import annotations

import re
import sys
from pathlib import Path

RUNTIME = Path(
    r"d:\workspace\project\talkback\talkback\android-board-talkback\src\main\java\com\talkback\app\TalkbackRuntime.kt"
)
SOURCE = Path(sys.argv[1])
TARGET = Path(
    r"d:\workspace\project\talkback\talkback\android-board-talkback\src\main\java\com\talkback\app\TalkbackCoordinator.kt"
)

PRODUCTION = {
    "start", "stop", "updateStaticPeers", "call", "sendEndpointText", "sendChannelText",
    "groupCall", "conferenceCall", "submitMeetingStartIntent", "sendConferenceInvites",
    "sendConferenceRejoinInvites", "sendConferenceRejoin", "rejoinableConference",
    "isConferenceRejoinInProgress", "isConferenceReconnecting", "isConferenceReconnectFailed",
    "onPttPressed", "onPttReleased", "hangup", "leaveConference", "clearConferencePttCooldown",
    "acceptCall", "rejectCall", "setCallMuted", "setAutoAcceptConferenceInvites",
    "setMeetingPreferred", "channelMode", "isConferenceHostForChannel", "pendingConferenceInvite",
    "acceptPendingConferenceInvite", "rejectPendingConferenceInvite", "activeUnicastSession",
    "activeSessionIds", "sessionSnapshots", "sessionSnapshotForChannel", "sessionPresenceSnapshot",
    "sessionPresenceSnapshots", "modulePresenceSnapshot", "channels", "configureChannelMembership",
    "channelMemberModuleIds", "conferenceNetworkIndicator", "receivePathLive", "mediaEverLive",
    "conferenceParticipantRecordExists", "conferenceAdmissionPhase", "conferenceParticipantMedia",
    "conferenceAuthorityReachable", "conferenceEdgeRecoveryLineage", "conferenceEdgeRecovering",
    "conferenceMediaUnavailable", "conferenceEpisodeCompletion", "onlineModuleCount", "qosSummary",
    "qosSnapshotForModule", "isCurrentSpeakerReachable", "remotePlaybackEnabledForModule",
    "remoteModuleStates", "peerDisplayRoster", "isRemoteModuleReachable", "isRemoteModuleDialable",
    "conferenceAuthorityModuleId", "shouldLocalInitiateConference", "primaryEndpointIdForModule",
    "isChannelMediaReady", "isChannelConnecting", "channelReadiness", "meetingSpeakerAudioLevel",
    "unicastCallAudioLevels", "isGroupSessionTrulyIdle", "refreshStaleGroupSession",
    "forceDestructiveGroupMeshRecovery", "deferHangupBootstrap", "reconcileGroupMesh",
    "reconcileGroupMeshSync", "refreshStaleConferenceSession", "rebroadcastHello",
    "consumeFloorPreempted", "consumeAcquireTimedOut", "onIceStateChanged",
}

NEEDED = sorted(
    set(re.findall(r"coordinator\.(\w+)", RUNTIME.read_text(encoding="utf-8"))) - PRODUCTION
)

lines = SOURCE.read_text(encoding="utf-8", errors="replace").splitlines()
HEADER = re.compile(r"^    internal fun (\w+)")


def extract_method(start: int) -> tuple[str, int]:
    chunk: list[str] = []
    i = start
    depth = 0
    saw_brace = False
    while i < len(lines):
        line = lines[i]
        if i > start and HEADER.match(line) and depth == 0 and not saw_brace:
            break
        chunk.append(line)
        for ch in line:
            if ch == "{":
                depth += 1
                saw_brace = True
            elif ch == "}":
                depth -= 1
        if saw_brace and depth == 0:
            return "\n".join(chunk) + "\n", i + 1
        # Expression body: `= expr` on same line without braces
        if not saw_brace and "=" in line and "{" not in line and not line.rstrip().endswith("="):
            return "\n".join(chunk) + "\n", i + 1
        i += 1
    raise RuntimeError(f"unterminated method at line {start + 1}")


found: dict[str, str] = {}
for idx, line in enumerate(lines):
    m = HEADER.match(line)
    if not m:
        continue
    name = m.group(1)
    if name not in NEEDED or name in found:
        continue
    body, _ = extract_method(idx)
    found[name] = body

missing = [n for n in NEEDED if n not in found]
if missing:
    print("MISSING:", ", ".join(missing), file=sys.stderr)

block = "\n    // --- Recovered test / desk seams (from git HEAD, first occurrence) ---\n\n"
block += "".join(found[n] for n in sorted(found.keys()))

target_text = TARGET.read_text(encoding="utf-8")
marker = "    private fun log(message: String) {"
if marker not in target_text:
    raise SystemExit("log() marker not found in TalkbackCoordinator.kt")
if "// --- Recovered test / desk seams" in target_text:
    raise SystemExit("seams block already present; restore 0WXn before re-running")

TARGET.write_text(target_text.replace(marker, block + marker, 1), encoding="utf-8")
print(f"Inserted {len(found)} methods; still missing {len(missing)}")

#!/usr/bin/env python3
"""Generate and verify ADR-0058 Profile 02 Q9 golden / negative vectors.

Protocol-fixture tooling only. PUBLIC TEST KEYS — never use in product.
Requires: cryptography
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
OUT_JSON = ROOT / "docs" / "analysis" / "0058-profile-02-q9-golden-vectors.json"
OUT_MANIFEST = ROOT / "docs" / "analysis" / "0058-profile-02-q9-corpus-manifest.md"

WARNING = "PUBLIC TEST KEYS ONLY - NEVER USE IN PRODUCT"

HINT_DOMAIN = b"TALKBACK-RTP-KEY-CONTEXT-HINT-V1\x00"
SENDER_DISC_DOMAIN = b"TALKBACK-RTP-SENDER-DISCRIMINATOR-V1\x00"
SOURCE_DISC_DOMAIN = b"TALKBACK-RTP-SOURCE-DISCRIMINATOR-V1\x00"

REPLAY_WINDOW_PACKETS = 64
SAFE_UDP_PAYLOAD_BYTES = 120
PT = 111


def bhex(data: bytes) -> str:
    return data.hex()


def u16(n: int) -> bytes:
    return int(n).to_bytes(2, "big")


def u32(n: int) -> bytes:
    return int(n).to_bytes(4, "big")


def u64(n: int) -> bytes:
    return int(n).to_bytes(8, "big")


def xor_bytes(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b, strict=True))


def srtp_iv(salt12: bytes, ssrc: int, roc: int, seq: int) -> bytes:
    """RFC 7714 §8.1: IV = (0x0000 || SSRC || ROC || SEQ) XOR salt."""
    counter = b"\x00\x00" + u32(ssrc) + u32(roc) + u16(seq)
    return xor_bytes(counter, salt12)


def key_context_hint64(
    conference_id: bytes,
    conference_epoch: int,
    media_key_epoch: int,
    membership_key_context_digest: bytes,
) -> bytes:
    material = (
        HINT_DOMAIN
        + conference_id
        + u64(conference_epoch)
        + u64(media_key_epoch)
        + membership_key_context_digest
    )
    return hashlib.sha256(material).digest()[:8]


def source_discriminator_input(
    conference_id: bytes,
    conference_epoch: int,
    membership_version: int,
    media_key_epoch: int,
    sender_module_id: str,
    source_instance_id: bytes,
    ssrc: int,
    descriptor_digest: bytes,
) -> bytes:
    mid = sender_module_id.encode("ascii")
    return (
        conference_id
        + u64(conference_epoch)
        + u64(membership_version)
        + u64(media_key_epoch)
        + bytes([len(mid)])
        + mid
        + source_instance_id
        + u32(ssrc)
        + descriptor_digest
    )


def discriminators(inp: bytes) -> tuple[bytes, bytes]:
    sender16 = hashlib.sha256(SENDER_DISC_DOMAIN + inp).digest()[:2]
    source32 = hashlib.sha256(SOURCE_DISC_DOMAIN + inp).digest()[:4]
    return sender16, source32


def build_rtp_header_and_he(
    *,
    marker: int,
    seq: int,
    timestamp: int,
    ssrc: int,
    hint64: bytes,
    source32: bytes,
    sender16: bytes,
    voice_level: int,
) -> bytes:
    b0 = 0x90  # V=2 P=0 X=1 CC=0
    b1 = ((marker & 1) << 7) | (PT & 0x7F)
    fixed = bytes([b0, b1]) + u16(seq) + u32(timestamp) + u32(ssrc)
    ext_hdr = b"\xbe\xde" + u16(4)
    element = bytes([0x1E]) + hint64 + source32 + sender16 + bytes([voice_level & 0xFF])
    assert len(element) == 16
    return fixed + ext_hdr + element


def protect_srtp(
    header_he: bytes,
    opus_payload: bytes,
    *,
    master_key: bytes,
    master_salt: bytes,
    ssrc: int,
    roc: int,
    seq: int,
) -> bytes:
    iv = srtp_iv(master_salt, ssrc, roc, seq)
    aead = AESGCM(master_key)
    ct_tag = aead.encrypt(iv, opus_payload, header_he)
    return header_he + ct_tag


def try_unprotect(
    datagram: bytes,
    *,
    master_key: bytes,
    master_salt: bytes,
    ssrc: int,
    roc: int,
    seq: int,
) -> tuple[bool, bytes | None]:
    if len(datagram) < 32 + 16:
        return False, None
    header_he = datagram[:32]
    ct_tag = datagram[32:]
    iv = srtp_iv(master_salt, ssrc, roc, seq)
    aead = AESGCM(master_key)
    try:
        pt = aead.decrypt(iv, ct_tag, header_he)
        return True, pt
    except Exception:
        return False, None


def fixture_base() -> dict[str, Any]:
    conference_id = bytes.fromhex("00112233445566778899aabbccddeeff")
    source_instance_id = bytes.fromhex("0102030405060708090a0b0c0d0e0f10")
    descriptor_digest = bytes.fromhex("aa" * 32)
    membership_key_context_digest = bytes.fromhex("bb" * 32)
    master_key = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    master_salt = bytes.fromhex("101112131415161718191a1b")
    ssrc = 0x11223344
    conference_epoch = 1
    membership_version = 2
    media_key_epoch = 7
    sender_module_id = "M01"
    seq0 = 0x1000
    ts0 = 0x20000000
    roc = 0

    hint = key_context_hint64(
        conference_id, conference_epoch, media_key_epoch, membership_key_context_digest
    )
    disc_in = source_discriminator_input(
        conference_id,
        conference_epoch,
        membership_version,
        media_key_epoch,
        sender_module_id,
        source_instance_id,
        ssrc,
        descriptor_digest,
    )
    sender16, source32 = discriminators(disc_in)

    return {
        "conferenceIdHex": bhex(conference_id),
        "conferenceEpoch": conference_epoch,
        "membershipVersion": membership_version,
        "mediaKeyEpoch": media_key_epoch,
        "membershipKeyContextDigestHex": bhex(membership_key_context_digest),
        "mediaGroupDescriptorDigestHex": bhex(descriptor_digest),
        "senderModuleId": sender_module_id,
        "sourceInstanceIdHex": bhex(source_instance_id),
        "ssrc": ssrc,
        "ssrcHex": f"{ssrc:08x}",
        "seq": seq0,
        "timestamp": ts0,
        "roc": roc,
        "packetIndex": (roc << 16) | seq0,
        "srtpMasterKeyHex": bhex(master_key),
        "srtpMasterSaltHex": bhex(master_salt),
        "keyContextHint64Hex": bhex(hint),
        "sourceDiscriminator32Hex": bhex(source32),
        "senderDiscriminator16Hex": bhex(sender16),
        "sourceAdmissionKey48Hex": bhex(sender16 + source32),
        "voiceActiveAudioLevel": 0x80 | 20,
        "replayWindowPackets": REPLAY_WINDOW_PACKETS,
        "SafeUdpPayloadBytes": SAFE_UDP_PAYLOAD_BYTES,
        "rtpPayloadType": PT,
        "rtpWireProfileId": 1,
    }


def make_packet(
    base: dict[str, Any],
    opus_len: int,
    *,
    seq: int | None = None,
    roc: int | None = None,
    opus_fill: int = 0x42,
) -> tuple[bytes, dict[str, Any]]:
    ssrc = base["ssrc"]
    seq_v = base["seq"] if seq is None else seq
    roc_v = base["roc"] if roc is None else roc
    ts = base["timestamp"]
    vl = base["voiceActiveAudioLevel"]
    h = bytes.fromhex(base["keyContextHint64Hex"])
    s32 = bytes.fromhex(base["sourceDiscriminator32Hex"])
    s16 = bytes.fromhex(base["senderDiscriminator16Hex"])
    key = bytes.fromhex(base["srtpMasterKeyHex"])
    salt = bytes.fromhex(base["srtpMasterSaltHex"])
    header = build_rtp_header_and_he(
        marker=0,
        seq=seq_v,
        timestamp=ts,
        ssrc=ssrc,
        hint64=h,
        source32=s32,
        sender16=s16,
        voice_level=vl,
    )
    assert len(header) == 32
    opus = bytes([opus_fill & 0xFF]) * opus_len
    dg = protect_srtp(
        header,
        opus,
        master_key=key,
        master_salt=salt,
        ssrc=ssrc,
        roc=roc_v,
        seq=seq_v,
    )
    ctx = dict(base)
    ctx.update(
        {
            "seq": seq_v,
            "roc": roc_v,
            "packetIndex": (roc_v << 16) | seq_v,
            "opusPayloadLength": opus_len,
            "udpPayloadLength": len(dg),
        }
    )
    return dg, ctx


def vector(
    name: str,
    kind: str,
    ctx: dict[str, Any],
    datagram: bytes,
    outcome: str,
    owning_seam: str,
    frozen_class: str | None,
    notes: str = "",
    mutation: str | None = None,
) -> dict[str, Any]:
    out: dict[str, Any] = {
        "name": name,
        "kind": kind,
        "context": ctx,
        "datagramBytesHex": bhex(datagram),
        "udpPayloadLength": len(datagram),
        "expectedOutcome": outcome,
        "expectedOwningSeam": owning_seam,
        "expectedFrozenClass": frozen_class,
        "notes": notes,
    }
    if mutation:
        out["mutationFrom"] = mutation
    return out


def generate() -> dict[str, Any]:
    base = fixture_base()
    positives: list[dict[str, Any]] = []
    negatives: list[dict[str, Any]] = []

    dg1, ctx1 = make_packet(base, 40, opus_fill=0x11)
    positives.append(
        vector(
            "G1-valid-normal",
            "positive",
            ctx1,
            dg1,
            "ACCEPT",
            "P02-full-pipeline",
            None,
            "Valid protected RTP under admitted Source/key context",
        )
    )

    dg2, ctx2 = make_packet(base, 72, seq=base["seq"] + 1, opus_fill=0x22)
    assert len(dg2) == 120
    positives.append(
        vector(
            "G2-valid-exact-120",
            "positive",
            ctx2,
            dg2,
            "ACCEPT",
            "P02-full-pipeline",
            None,
            "UDP payload length == SafeUdpPayloadBytes (120)",
        )
    )

    dg3a, ctx3a = make_packet(base, 20, seq=base["seq"] + 2, opus_fill=0x33)
    dg3b, ctx3b = make_packet(base, 60, seq=base["seq"] + 3, opus_fill=0x44)
    positives.append(
        vector(
            "G3a-valid-opus-len-20",
            "positive",
            ctx3a,
            dg3a,
            "ACCEPT",
            "P02-full-pipeline",
            None,
            "Distinct legal Opus/VBR length (20)",
        )
    )
    positives.append(
        vector(
            "G3b-valid-opus-len-60",
            "positive",
            ctx3b,
            dg3b,
            "ACCEPT",
            "P02-full-pipeline",
            None,
            "Distinct legal Opus/VBR length (60)",
        )
    )

    n1 = dg2 + b"\xaa"
    assert len(n1) == 121
    ctx_n1 = dict(ctx2)
    ctx_n1["udpPayloadLength"] = 121
    negatives.append(
        vector(
            "N1-oversize-121",
            "negative",
            ctx_n1,
            n1,
            "REJECT",
            "Q8",
            "WIRE_ENVELOPE_OVERSIZE",
            "UDP payload == 121",
            mutation="G2-valid-exact-120 + append 0xAA",
        )
    )

    n2 = dg1[:8]
    negatives.append(
        vector(
            "N2-truncated-rtp-header",
            "negative",
            {**ctx1, "udpPayloadLength": len(n2)},
            n2,
            "REJECT",
            "Q2",
            "HE_FORMAT_INVALID",
            "Fewer than fixed RTP header octets",
            mutation="G1 truncate to 8 bytes",
        )
    )

    bad_fixed = bytes([0x10, PT]) + u16(ctx1["seq"]) + u32(ctx1["timestamp"]) + u32(
        ctx1["ssrc"]
    )
    n3 = bad_fixed + dg1[12:32] + dg1[32:]
    negatives.append(
        vector(
            "N3-invalid-rtp-version",
            "negative",
            {**ctx1, "udpPayloadLength": len(n3)},
            n3,
            "REJECT",
            "Q2",
            "HE_FORMAT_INVALID",
            "RTP version != 2",
            mutation="G1 with V=0 in fixed header (rebuilt)",
        )
    )

    n4_hdr = bytearray(dg1[:32])
    n4_hdr[14:16] = u16(5)
    n4 = bytes(n4_hdr) + dg1[32:]
    negatives.append(
        vector(
            "N4-malformed-extension-length",
            "negative",
            {**ctx1, "udpPayloadLength": len(n4)},
            n4,
            "REJECT",
            "Q2",
            "HE_FORMAT_INVALID",
            "declared HE word length inconsistent with V1 profile",
            mutation="G1 HE length field 4→5",
        )
    )

    n5_hdr = bytearray(dg1[:32])
    n5_hdr[0] = (n5_hdr[0] & 0xF0) | 0x01
    n5 = bytes(n5_hdr) + dg1[32:]
    negatives.append(
        vector(
            "N5-structural-length-inconsistency",
            "negative",
            {**ctx1, "udpPayloadLength": len(n5)},
            n5,
            "REJECT",
            "earliest-authoritative-seam",
            None,
            "CC=1 without CSRC octets; earliest seam (Q2) proves fault",
            mutation="G1 set CC=1 without inserting CSRC",
        )
    )

    rtcp = bytes([0x80, 200]) + u16(1) + u32(ctx1["ssrc"]) + (b"\x00" * 20)
    negatives.append(
        vector(
            "N6-rtcp-shaped-shared-ingress",
            "negative",
            {
                **{k: base[k] for k in base},
                "udpPayloadLength": len(rtcp),
                "classification": "RTCP candidate before Q2/Q5/Q6",
            },
            rtcp,
            "REJECT",
            "Q7",
            "RTCP_PACKET_DISCARDED",
            "RTCP-shaped datagram on shared UDP ingress",
            mutation="synthetic RTCP SR-shaped datagram",
        )
    )

    n7 = bytearray(dg1)
    n7[-1] ^= 0xFF
    negatives.append(
        vector(
            "N7-srtp-tag-corruption",
            "negative",
            {**ctx1, "udpPayloadLength": len(n7)},
            bytes(n7),
            "REJECT",
            "Q5",
            None,
            "AEAD tag flipped; auth fail",
            mutation="G1 last tag octet XOR 0xFF",
        )
    )

    wrong_key = bytes(16)
    ctx_n8a = dict(ctx1)
    ctx_n8a["trialSrtpMasterKeyHex"] = bhex(wrong_key)
    negatives.append(
        vector(
            "N8a-wrong-srtp-key",
            "negative",
            ctx_n8a,
            dg1,
            "REJECT",
            "Q5",
            None,
            "Same datagramBytes as G1; context installs wrong key → AEAD fail",
            mutation="G1 context with zero master key",
        )
    )

    dg8b, ctx8b = make_packet(base, 40, seq=base["seq"] + 10, opus_fill=0x55)
    ctx8b = dict(ctx8b)
    ctx8b["installedSourceAdmissionKey48Hex"] = "deadbeefdead"
    ctx8b["installedSsrc"] = ctx8b["ssrc"]
    ctx8b["packetSourceAdmissionKey48Hex"] = ctx8b["sourceAdmissionKey48Hex"]
    negatives.append(
        vector(
            "N8b-wrong-source-ssrc-binding",
            "negative",
            ctx8b,
            dg8b,
            "REJECT",
            "Q3",
            "SOURCE_PACKET_IDENTITY_MISMATCH",
            "AEAD OK under CURRENT_ACTIVE; installed map key mismatch",
            mutation="valid packet with mismatched installed admission key",
        )
    )

    dg9, ctx9 = make_packet(base, 40, seq=0x2000, opus_fill=0x66)
    ctx9 = dict(ctx9)
    ctx9["replayWindowPackets"] = REPLAY_WINDOW_PACKETS
    ctx9["highestAuthenticatedPacketIndex"] = ctx9["packetIndex"]
    ctx9["replayBitmapContainsPacketIndex"] = True
    negatives.append(
        vector(
            "N9-replay-duplicate",
            "negative",
            ctx9,
            dg9,
            "REJECT",
            "Q6",
            "SRTP_REPLAY_DUPLICATE",
            "Same packet index already authenticated in window",
            mutation="valid packet + context marks index already seen",
        )
    )

    dg10, ctx10 = make_packet(base, 40, seq=20, roc=0, opus_fill=0x77)
    ctx10 = dict(ctx10)
    ctx10["replayWindowPackets"] = REPLAY_WINDOW_PACKETS
    ctx10["highestAuthenticatedPacketIndex"] = 100
    ctx10["packetIndex"] = 20
    assert 20 < (100 - REPLAY_WINDOW_PACKETS + 1)
    negatives.append(
        vector(
            "N10-replay-too-old",
            "negative",
            ctx10,
            dg10,
            "REJECT",
            "Q6",
            "SRTP_REPLAY_TOO_OLD",
            "packetIndex below (highest - replayWindowPackets + 1); width=64",
            mutation="valid packet with too-old index vs highest=100",
        )
    )

    n11 = bytearray(dg1)
    n11[20] ^= 0x01
    negatives.append(
        vector(
            "N11a-fuzz-he-bitflip",
            "negative",
            {**ctx1, "udpPayloadLength": len(n11)},
            bytes(n11),
            "REJECT",
            "Q5",
            None,
            "Fuzz mutation of authenticated HE; AEAD fail",
            mutation="G1 HE octet20 XOR 0x01",
        )
    )
    positives.append(
        vector(
            "N11b-fuzz-retained-valid-regression",
            "positive",
            ctx1,
            dg1,
            "ACCEPT",
            "P02-full-pipeline",
            None,
            "N11 policy: accidentally-valid fuzz sample MAY be retained as ACCEPT",
            mutation="identical to G1 (regression retention)",
        )
    )

    return {
        "profile": "ADR-0058 Implementation Profile 02 Q9",
        "schemaVersion": 1,
        "warning": WARNING,
        "constants": {
            "SafeUdpPayloadBytes": SAFE_UDP_PAYLOAD_BYTES,
            "replayWindowPackets": REPLAY_WINDOW_PACKETS,
            "rtpPayloadType": PT,
            "headerPlusHeOctets": 32,
            "aeadTagOctets": 16,
            "maxOpusPayloadOctets": 72,
            "srtpProtectionProfile": "AEAD_AES_128_GCM",
            "ivConstruction": "RFC7714-8.1 (0x0000||SSRC||ROC||SEQ) XOR salt",
        },
        "positiveVectors": positives,
        "negativeVectors": negatives,
    }


def parse_rtp_fields(datagram: bytes) -> dict[str, Any]:
    if len(datagram) < 12:
        return {"error": "too-short"}
    v = datagram[0] >> 6
    return {
        "version": v,
        "x": (datagram[0] >> 4) & 1,
        "cc": datagram[0] & 0x0F,
        "pt": datagram[1] & 0x7F,
        "seq": int.from_bytes(datagram[2:4], "big"),
        "ssrc": int.from_bytes(datagram[8:12], "big"),
    }


def by_name(doc: dict[str, Any], name: str) -> dict[str, Any]:
    for v in doc["positiveVectors"] + doc["negativeVectors"]:
        if v["name"] == name:
            return v
    raise KeyError(name)


def verify_corpus(doc: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    consts = doc["constants"]
    if consts["replayWindowPackets"] != 64:
        errors.append("constants.replayWindowPackets != 64")
    if consts["SafeUdpPayloadBytes"] != 120:
        errors.append("constants.SafeUdpPayloadBytes != 120")

    g2 = by_name(doc, "G2-valid-exact-120")
    if g2["udpPayloadLength"] != 120:
        errors.append(f"G2 length {g2['udpPayloadLength']} != 120")
    if len(bytes.fromhex(g2["datagramBytesHex"])) != 120:
        errors.append("G2 datagramBytesHex length != 120")

    n1 = by_name(doc, "N1-oversize-121")
    if n1["udpPayloadLength"] != 121 or n1["expectedFrozenClass"] != "WIRE_ENVELOPE_OVERSIZE":
        errors.append("N1 oversize contract mismatch")

    for v in doc["positiveVectors"]:
        ctx = v["context"]
        dg = bytes.fromhex(v["datagramBytesHex"])
        if len(dg) > 120:
            errors.append(f"{v['name']} positive exceeds 120")
            continue
        if len(dg) < 32 + 16:
            errors.append(f"{v['name']} too short for SRTP")
            continue
        fields = parse_rtp_fields(dg)
        if fields.get("version") != 2:
            errors.append(f"{v['name']} RTP version != 2")
        ok, pt = try_unprotect(
            dg,
            master_key=bytes.fromhex(ctx["srtpMasterKeyHex"]),
            master_salt=bytes.fromhex(ctx["srtpMasterSaltHex"]),
            ssrc=ctx["ssrc"],
            roc=ctx["roc"],
            seq=ctx["seq"],
        )
        if not ok:
            errors.append(f"{v['name']} AEAD decrypt FAIL")
        elif pt is not None and len(pt) != ctx.get("opusPayloadLength"):
            errors.append(f"{v['name']} opus length mismatch")

    n7 = by_name(doc, "N7-srtp-tag-corruption")
    ctx = n7["context"]
    ok, _ = try_unprotect(
        bytes.fromhex(n7["datagramBytesHex"]),
        master_key=bytes.fromhex(ctx["srtpMasterKeyHex"]),
        master_salt=bytes.fromhex(ctx["srtpMasterSaltHex"]),
        ssrc=ctx["ssrc"],
        roc=ctx["roc"],
        seq=ctx["seq"],
    )
    if ok:
        errors.append("N7 unexpectedly AEAD OK")

    n8a = by_name(doc, "N8a-wrong-srtp-key")
    ctx = n8a["context"]
    ok, _ = try_unprotect(
        bytes.fromhex(n8a["datagramBytesHex"]),
        master_key=bytes.fromhex(ctx["trialSrtpMasterKeyHex"]),
        master_salt=bytes.fromhex(ctx["srtpMasterSaltHex"]),
        ssrc=ctx["ssrc"],
        roc=ctx["roc"],
        seq=ctx["seq"],
    )
    if ok:
        errors.append("N8a wrong key unexpectedly OK")

    n8b = by_name(doc, "N8b-wrong-source-ssrc-binding")
    ctx = n8b["context"]
    ok, _ = try_unprotect(
        bytes.fromhex(n8b["datagramBytesHex"]),
        master_key=bytes.fromhex(ctx["srtpMasterKeyHex"]),
        master_salt=bytes.fromhex(ctx["srtpMasterSaltHex"]),
        ssrc=ctx["ssrc"],
        roc=ctx["roc"],
        seq=ctx["seq"],
    )
    if not ok:
        errors.append("N8b AEAD should PASS before Q3 reject")
    if ctx["installedSourceAdmissionKey48Hex"] == ctx["packetSourceAdmissionKey48Hex"]:
        errors.append("N8b binding must differ")

    n9 = by_name(doc, "N9-replay-duplicate")
    if n9["context"]["replayWindowPackets"] != 64:
        errors.append("N9 replayWindowPackets != 64")
    if not n9["context"].get("replayBitmapContainsPacketIndex"):
        errors.append("N9 must mark index already seen")
    if n9["expectedFrozenClass"] != "SRTP_REPLAY_DUPLICATE":
        errors.append("N9 class")

    n10 = by_name(doc, "N10-replay-too-old")
    ctx = n10["context"]
    if ctx["replayWindowPackets"] != 64:
        errors.append("N10 replayWindowPackets != 64")
    oldest = ctx["highestAuthenticatedPacketIndex"] - ctx["replayWindowPackets"] + 1
    if ctx["packetIndex"] >= oldest:
        errors.append("N10 packetIndex not too-old relative to window")
    if n10["expectedFrozenClass"] != "SRTP_REPLAY_TOO_OLD":
        errors.append("N10 class")

    n6 = by_name(doc, "N6-rtcp-shaped-shared-ingress")
    if n6["expectedFrozenClass"] != "RTCP_PACKET_DISCARDED":
        errors.append("N6 class")
    raw = bytes.fromhex(n6["datagramBytesHex"])
    if not (raw[0] >> 6 == 2 and raw[1] >= 200):
        errors.append("N6 not RTCP-shaped")

    forbidden = {"WIRE_INGRESS_REJECTED", "WIRE_NON_UDP"}
    for v in doc["negativeVectors"]:
        if v.get("expectedFrozenClass") in forbidden:
            errors.append(f"{v['name']} invented class")

    if (
        by_name(doc, "G3a-valid-opus-len-20")["context"]["opusPayloadLength"]
        == by_name(doc, "G3b-valid-opus-len-60")["context"]["opusPayloadLength"]
    ):
        errors.append("G3 lengths not distinct")

    # RFC 7714 example IV smoke check
    salt = bytes.fromhex("517569642070726f2071756f")
    iv = srtp_iv(salt, 0x5501A0B2, 0, 0xF17B)
    expected = bytes.fromhex("51753c6580c2726f20718414")
    if iv != expected:
        errors.append(f"RFC7714 IV smoke mismatch: {iv.hex()} != {expected.hex()}")

    return errors


def write_manifest(doc: dict[str, Any]) -> None:
    lines = [
        "# ADR-0058 Profile 02 Q9 — Hex Corpus Manifest",
        "",
        "## Status",
        "",
        "```text",
        "E1 Profile 02 Q9 Hex Corpus",
        "Implementation: NOT AUTHORIZED",
        "warning: PUBLIC TEST KEYS ONLY",
        "```",
        "",
        "## Constants",
        "",
        "```text",
        f"SafeUdpPayloadBytes   = {doc['constants']['SafeUdpPayloadBytes']}",
        f"replayWindowPackets   = {doc['constants']['replayWindowPackets']}",
        f"SRTP profile          = {doc['constants']['srtpProtectionProfile']}",
        f"IV                    = {doc['constants']['ivConstruction']}",
        "```",
        "",
        "## Vectors",
        "",
        "| Name | Kind | UDP len | Outcome | Seam | Class |",
        "|---|---|---:|---|---|---|",
    ]
    for v in doc["positiveVectors"] + doc["negativeVectors"]:
        lines.append(
            f"| {v['name']} | {v['kind']} | {v['udpPayloadLength']} | "
            f"{v['expectedOutcome']} | {v['expectedOwningSeam']} | "
            f"{v.get('expectedFrozenClass') or '-'} |"
        )
    lines.extend(
        [
            "",
            "## Verification",
            "",
            "```text",
            "python talkback/scripts/adr0058_p02_q9_vectors.py --verify",
            "```",
            "",
            "Corpus file: `docs/analysis/0058-profile-02-q9-golden-vectors.json`",
            "",
            "Opus payloads in this corpus are deterministic fill bytes for wire/",
            "SRTP length and AEAD tests. They are not Opus encoder golden audio.",
            "",
        ]
    )
    OUT_MANIFEST.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--generate", action="store_true")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if not args.generate and not args.verify:
        args.generate = True
        args.verify = True

    if args.generate:
        doc = generate()
        OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
        OUT_JSON.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        write_manifest(doc)
        print(f"wrote {OUT_JSON}")
        print(f"wrote {OUT_MANIFEST}")

    if args.verify:
        doc = json.loads(OUT_JSON.read_text(encoding="utf-8"))
        errors = verify_corpus(doc)
        if errors:
            print("VERIFY FAIL")
            for e in errors:
                print(" -", e)
            return 1
        npos = len(doc["positiveVectors"])
        nneg = len(doc["negativeVectors"])
        print(f"VERIFY PASS ({npos} positive + {nneg} negative)")
        print(
            f"G2 length={by_name(doc, 'G2-valid-exact-120')['udpPayloadLength']} "
            f"N1 length={by_name(doc, 'N1-oversize-121')['udpPayloadLength']}"
        )
        print(f"replayWindowPackets={doc['constants']['replayWindowPackets']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

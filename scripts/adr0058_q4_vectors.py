#!/usr/bin/env python3
"""Generate and verify ADR-0058 Profile 01 Q4 golden vectors.

This is protocol-fixture tooling, not product crypto implementation. The fixed
private scalars below are public test data and MUST NOT be used by Talkback.
Only Python's standard library is required.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import re
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
FACT_DOMAIN = b"TALKBACK-CONFERENCE-FACT-V1\x00"
DESCRIPTOR_DOMAIN = b"TALKBACK-MEDIA-GROUP-DESCRIPTOR-V1\x00"
SIGNATURE_DOMAIN = b"TALKBACK-CONFERENCE-FACT-SIGNATURE-V1\x00"

FACT_TYPES = {
    "CREATION": 1,
    "MEMBERSHIP": 2,
    "TERMINATION": 3,
    "INVITE": 4,
    "INVITE_REVOKE": 5,
    "JOIN_REQUEST": 6,
    "JOIN_REJECTION": 7,
    "LEAVE_REQUEST": 8,
    "SOURCE_DECLARATION": 9,
    "SOURCE_RESOLUTION": 10,
    "MEDIA_KEY_PACKAGE": 11,
}
FACT_NAMES = {value: key for key, value in FACT_TYPES.items()}

# NIST P-256 / secp256r1.
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (GX, GY)

# Public, deterministic fixture keys. Never production keys.
TEST_PRIVATE = {
    "M01": int("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100", 16),
    "M02": int("00f1e2d3c4b5a69788776655443322110f1e2d3c4b5a6978877665544332211", 16),
}
TEST_SHARED_SECRET = "Q4-PUBLIC-TEST-SHARED-SECRET"

MODULE_RE = re.compile(r"^[A-Za-z0-9._-]{1,32}$")


class CborError(ValueError):
    pass


def _head(major: int, value: int) -> bytes:
    if value < 0:
        raise CborError("negative value")
    if value < 24:
        return bytes([(major << 5) | value])
    if value <= 0xFF:
        return bytes([(major << 5) | 24, value])
    if value <= 0xFFFF:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    if value <= 0xFFFFFFFF:
        return bytes([(major << 5) | 26]) + value.to_bytes(4, "big")
    if value <= 0xFFFFFFFFFFFFFFFF:
        return bytes([(major << 5) | 27]) + value.to_bytes(8, "big")
    raise CborError("integer too large")


def cbor(value: Any) -> bytes:
    if value is None:
        return b"\xf6"
    if isinstance(value, bool):
        raise CborError("booleans are not in the Q4 subset")
    if isinstance(value, int):
        return _head(0, value)
    if isinstance(value, bytes):
        return _head(2, len(value)) + value
    if isinstance(value, str):
        encoded = value.encode("utf-8", "strict")
        return _head(3, len(encoded)) + encoded
    if isinstance(value, (list, tuple)):
        return _head(4, len(value)) + b"".join(cbor(item) for item in value)
    if isinstance(value, dict):
        encoded_items = [(cbor(key), cbor(item)) for key, item in value.items()]
        encoded_items.sort(key=lambda pair: (len(pair[0]), pair[0]))
        return _head(5, len(encoded_items)) + b"".join(
            key + item for key, item in encoded_items
        )
    raise CborError(f"unsupported type: {type(value)!r}")


def _read_argument(data: bytes, offset: int, ai: int) -> tuple[int, int]:
    if ai < 24:
        return ai, offset
    sizes = {24: 1, 25: 2, 26: 4, 27: 8}
    if ai not in sizes:
        raise CborError("indefinite/reserved additional information")
    size = sizes[ai]
    if offset + size > len(data):
        raise CborError("truncated argument")
    value = int.from_bytes(data[offset : offset + size], "big")
    minimum = {24: 24, 25: 0x100, 26: 0x10000, 27: 0x100000000}[ai]
    if value < minimum:
        raise CborError("non-shortest argument")
    return value, offset + size


def _decode(data: bytes, offset: int = 0) -> tuple[Any, int, bytes]:
    start = offset
    if offset >= len(data):
        raise CborError("truncated item")
    initial = data[offset]
    offset += 1
    major, ai = initial >> 5, initial & 0x1F
    if major == 7:
        if ai == 22:
            return None, offset, data[start:offset]
        raise CborError("unsupported simple/float value")
    if major in (6, 7):
        raise CborError("tags/simple values forbidden")
    argument, offset = _read_argument(data, offset, ai)
    if major == 0:
        return argument, offset, data[start:offset]
    if major == 1:
        raise CborError("negative integers forbidden")
    if major in (2, 3):
        end = offset + argument
        if end > len(data):
            raise CborError("truncated string")
        raw = data[offset:end]
        value = raw if major == 2 else raw.decode("utf-8", "strict")
        return value, end, data[start:end]
    if major == 4:
        result = []
        for _ in range(argument):
            value, offset, _ = _decode(data, offset)
            result.append(value)
        return result, offset, data[start:offset]
    if major == 5:
        result = {}
        previous_order = None
        for _ in range(argument):
            key, offset, key_bytes = _decode(data, offset)
            order = (len(key_bytes), key_bytes)
            if previous_order is not None and order <= previous_order:
                raise CborError("map keys not deterministic or duplicate")
            previous_order = order
            if key in result:
                raise CborError("duplicate map key")
            value, offset, _ = _decode(data, offset)
            result[key] = value
        return result, offset, data[start:offset]
    raise CborError("unsupported major type")


def decode_strict(data: bytes) -> Any:
    value, offset, _ = _decode(data, 0)
    if offset != len(data):
        raise CborError("trailing bytes")
    if cbor(value) != data:
        raise CborError("non-canonical re-encoding")
    return value


def inverse(value: int, modulus: int) -> int:
    return pow(value, -1, modulus)


def point_add(left: tuple[int, int] | None, right: tuple[int, int] | None):
    if left is None:
        return right
    if right is None:
        return left
    x1, y1 = left
    x2, y2 = right
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if left == right:
        slope = ((3 * x1 * x1 + A) * inverse(2 * y1 % P, P)) % P
    else:
        slope = ((y2 - y1) * inverse((x2 - x1) % P, P)) % P
    x3 = (slope * slope - x1 - x2) % P
    y3 = (slope * (x1 - x3) - y1) % P
    return x3, y3


def scalar_mult(scalar: int, point: tuple[int, int] = G):
    result = None
    addend = point
    while scalar:
        if scalar & 1:
            result = point_add(result, addend)
        addend = point_add(addend, addend)
        scalar >>= 1
    return result


def rfc6979_k(private: int, digest: bytes) -> int:
    x = private.to_bytes(32, "big")
    h1 = (int.from_bytes(digest, "big") % N).to_bytes(32, "big")
    v = b"\x01" * 32
    k = b"\x00" * 32
    k = hmac.new(k, v + b"\x00" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b"\x01" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        candidate = int.from_bytes(v, "big")
        if 1 <= candidate < N:
            return candidate
        k = hmac.new(k, v + b"\x00", hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def sign_raw64(private: int, message: bytes) -> bytes:
    digest = hashlib.sha256(message).digest()
    nonce = rfc6979_k(private, digest)
    point = scalar_mult(nonce)
    if point is None:
        raise AssertionError("invalid nonce point")
    r = point[0] % N
    s = (inverse(nonce, N) * (int.from_bytes(digest, "big") + r * private)) % N
    if not r or not s:
        raise AssertionError("degenerate signature")
    if s > N // 2:
        s = N - s
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def verify_raw64(public: tuple[int, int], message: bytes, signature: bytes) -> str:
    if len(signature) != 64:
        return "WRONG_SIGNATURE_LENGTH"
    r = int.from_bytes(signature[:32], "big")
    s = int.from_bytes(signature[32:], "big")
    if not (1 <= r < N and 1 <= s < N):
        return "SIGNATURE_RANGE_INVALID"
    if s > N // 2:
        return "HIGH_S_SIGNATURE"
    digest = int.from_bytes(hashlib.sha256(message).digest(), "big")
    w = inverse(s, N)
    point = point_add(scalar_mult((digest * w) % N), scalar_mult((r * w) % N, public))
    if point is None or point[0] % N != r:
        return "INVALID_INNER_SIGNATURE"
    return "PASS"


def raw_id(label: str) -> bytes:
    return hashlib.sha256(("ID:" + label).encode()).digest()[:16]


def raw_digest(label: str) -> bytes:
    return hashlib.sha256(("DIGEST:" + label).encode()).digest()


def descriptor() -> dict[int, Any]:
    return {
        0: 4,
        1: bytes([239, 1, 2, 3]),
        2: 41000,
        3: 41001,
        4: 8,
        5: {0: 1, 1: 24000, 2: 20, 3: 50, 4: 120, 5: 46},
    }


def membership() -> list[list[Any]]:
    return [
        ["M01", raw_id("member-M01-v1")],
        ["M02", raw_id("member-M02-v1")],
        ["M03", raw_id("member-M03-v1")],
    ]


def full_fact(fact_type: int, authority: dict[int, Any], metadata: dict[int, Any]):
    return {0: SCHEMA_VERSION, 1: fact_type, 2: authority, 3: metadata}


def fact_digest(fact: dict[int, Any]) -> bytes:
    return hashlib.sha256(FACT_DOMAIN + cbor([fact[0], fact[1], fact[2]])).digest()


def signed_case(name: str, fact: dict[int, Any], signer: str, expected="PASS", reason=None):
    full = cbor(fact)
    signature = sign_raw64(TEST_PRIVATE[signer], SIGNATURE_DOMAIN + full)
    return {
        "name": name,
        "factType": FACT_NAMES.get(fact[1], f"UNKNOWN_{fact[1]}"),
        "signingFixture": signer,
        "fullCanonicalHex": full.hex(),
        "authorityDigestObjectHex": cbor([fact[0], fact[1], fact[2]]).hex(),
        "factDigestHex": fact_digest(fact).hex(),
        "signatureHex": signature.hex(),
        "signedFactHex": cbor({0: full, 1: signature}).hex(),
        "expected": expected,
        **({"expectedReason": reason} if reason else {}),
    }


AUTH_KEYS = {
    1: set(range(10)),
    2: set(range(8)),
    3: set(range(5)),
    4: set(range(7)),
    5: set(range(6)),
    6: set(range(7)),
    7: set(range(7)),
    8: set(range(7)),
    9: set(range(11)),
    10: set(range(11)),
    11: set(range(14)),
}
META_KEYS = {
    1: {0, 1}, 2: {0, 1}, 3: {0, 1}, 4: {0}, 5: {0, 1},
    6: {0}, 7: {0, 1}, 8: {0}, 9: {0}, 10: {0}, 11: {0},
}
SIGNER_KEY = {
    1: 2, 2: 2, 3: 2, 4: 2, 5: 2, 6: 2, 7: 2, 8: 2,
    9: 4, 10: 4, 11: 2,
}


def validate_module(value: Any) -> bool:
    return isinstance(value, str) and MODULE_RE.fullmatch(value) is not None


def validate_semantics(fact: Any) -> str:
    if not isinstance(fact, dict) or set(fact) != {0, 1, 2, 3}:
        return "SCHEMA_INVALID"
    if fact[0] != 1 or not isinstance(fact[1], int):
        return "SCHEMA_INVALID"
    fact_type = fact[1]
    if fact_type not in FACT_NAMES:
        return "UNKNOWN_FACT_TYPE"
    authority, metadata = fact[2], fact[3]
    if not isinstance(authority, dict) or set(authority) != AUTH_KEYS[fact_type]:
        return "SCHEMA_INVALID"
    if not isinstance(metadata, dict) or set(metadata) != META_KEYS[fact_type]:
        return "SCHEMA_INVALID"
    if not isinstance(metadata[0], int) or not (0 <= metadata[0] <= 0xFFFFFFFF):
        return "SCHEMA_INVALID"
    signer = authority[SIGNER_KEY[fact_type]]
    if not validate_module(signer):
        return "SCHEMA_INVALID"

    if fact_type in (1, 2):
        view_key = 4 if fact_type == 1 else 5
        view = authority[view_key]
        if not isinstance(view, list) or not (1 <= len(view) <= 10):
            return "SCHEMA_INVALID"
        module_bytes = []
        for entry in view:
            if not isinstance(entry, list) or len(entry) != 2 or not validate_module(entry[0]):
                return "SCHEMA_INVALID"
            if not isinstance(entry[1], bytes) or len(entry[1]) != 16:
                return "SCHEMA_INVALID"
            module_bytes.append(entry[0].encode("ascii"))
        if module_bytes != sorted(module_bytes) or len(set(module_bytes)) != len(module_bytes):
            return "NON_CANONICAL_COLLECTION"

    if fact_type == 9 and authority[8] is not None:
        if not isinstance(authority[8], bytes) or len(authority[8]) != 32:
            return "SCHEMA_INVALID"
    if fact_type == 10:
        if authority[5] is not None and (
            not isinstance(authority[5], bytes) or len(authority[5]) != 32
        ):
            return "SCHEMA_INVALID"
        conflict_set = authority[6]
        if not isinstance(conflict_set, list) or not (1 <= len(conflict_set) <= 32):
            return "SCHEMA_INVALID"
        if any(not isinstance(item, bytes) or len(item) != 32 for item in conflict_set):
            return "SCHEMA_INVALID"
        if conflict_set != sorted(conflict_set) or len(set(conflict_set)) != len(conflict_set):
            return "NON_CANONICAL_COLLECTION"
    if fact_type == 11:
        if not isinstance(authority[9], bytes) or len(authority[9]) != 384:
            return "SCHEMA_INVALID"
        if not isinstance(authority[10], bytes) or len(authority[10]) != 12:
            return "SCHEMA_INVALID"
        if not isinstance(authority[11], bytes) or not (1 <= len(authority[11]) <= 512):
            return "SCHEMA_INVALID"
        if not isinstance(authority[12], bytes) or len(authority[12]) != 16:
            return "SCHEMA_INVALID"
        if not isinstance(authority[13], int) or not (0 <= authority[13] <= 0xFFFFFFFF):
            return "SCHEMA_INVALID"
    return "PASS"


def verify_case(case: dict[str, Any], profiles: dict[str, dict[int, tuple[int, int]]]) -> str:
    full = bytes.fromhex(case["fullCanonicalHex"])
    signature = bytes.fromhex(case["signatureHex"])
    try:
        fact = decode_strict(full)
    except (CborError, UnicodeError):
        return "NON_CANONICAL_FACT"
    semantic = validate_semantics(fact)
    if semantic != "PASS":
        return semantic
    signer = fact[2][SIGNER_KEY[fact[1]]]
    key_version = fact[3][0]
    public = profiles.get(signer, {}).get(key_version)
    if public is None:
        return "UNKNOWN_SIGNER_KEY"
    return verify_raw64(public, SIGNATURE_DOMAIN + full, signature)


def positive_facts() -> list[tuple[str, dict[int, Any], str]]:
    conf = raw_id("conference-1")
    desc = descriptor()
    desc_digest = hashlib.sha256(DESCRIPTOR_DOMAIN + cbor(desc)).digest()
    creation = full_fact(1, {
        0: conf, 1: 7, 2: "M01", 3: desc, 4: membership(), 5: 0, 6: 1,
        7: raw_digest("media-key-commitment-1"), 8: 1, 9: None,
    }, {0: 1, 1: None})
    creation_digest = fact_digest(creation)
    membership_fact = full_fact(2, {
        0: conf, 1: 7, 2: "M01", 3: 1, 4: creation_digest,
        5: membership(), 6: 2, 7: raw_digest("media-key-commitment-2"),
    }, {0: 1, 1: "ADD_M03"})
    membership_digest = fact_digest(membership_fact)
    invite = full_fact(4, {
        0: conf, 1: 7, 2: "M01", 3: creation_digest,
        4: raw_id("invite-M02"), 5: "M02", 6: membership_digest,
    }, {0: 1})
    invite_digest = fact_digest(invite)
    join = full_fact(6, {
        0: conf, 1: 7, 2: "M02", 3: creation_digest,
        4: raw_id("join-M02"), 5: raw_id("invite-M02"), 6: invite_digest,
    }, {0: 1})
    join_digest = fact_digest(join)
    conflict_set = sorted([raw_digest("source-branch-a"), raw_digest("source-branch-b")])
    return [
        ("creation-v1", creation, "M01"),
        ("membership-v1", membership_fact, "M01"),
        ("termination-v1", full_fact(3, {
            0: conf, 1: 7, 2: "M01", 3: creation_digest, 4: 1,
        }, {0: 1, 1: "OWNER_ENDED"}), "M01"),
        ("invite-v1", invite, "M01"),
        ("invite-revoke-v1", full_fact(5, {
            0: conf, 1: 7, 2: "M01", 3: creation_digest,
            4: raw_id("invite-M02"), 5: invite_digest,
        }, {0: 1, 1: "USER_REVOKED"}), "M01"),
        ("join-request-v1", join, "M02"),
        ("join-rejection-v1", full_fact(7, {
            0: conf, 1: 7, 2: "M01", 3: creation_digest,
            4: raw_id("join-M02"), 5: join_digest, 6: 1,
        }, {0: 1, 1: "POLICY"}), "M01"),
        ("leave-request-v1", full_fact(8, {
            0: conf, 1: 7, 2: "M02", 3: creation_digest,
            4: raw_id("member-M02-v1"), 5: raw_id("leave-M02"),
            6: membership_digest,
        }, {0: 1}), "M02"),
        ("source-declaration-v1", full_fact(9, {
            0: conf, 1: 7, 2: 1, 3: 2, 4: "M02", 5: 1,
            6: raw_id("source-M02-1"), 7: 0x10203040, 8: None,
            9: desc_digest, 10: 1,
        }, {0: 1}), "M02"),
        ("source-resolution-v1", full_fact(10, {
            0: conf, 1: 7, 2: 1, 3: 2, 4: "M02", 5: None,
            6: conflict_set, 7: 3, 8: raw_id("source-M02-resolution"),
            9: 0x50607080, 10: desc_digest,
        }, {0: 1}), "M02"),
        ("media-key-package-v1", full_fact(11, {
            0: conf, 1: 7, 2: "M01", 3: 1, 4: 2,
            5: membership_digest, 6: raw_digest("media-key-commitment-2"),
            7: "M02", 8: raw_id("package-M02-key2"),
            9: bytes((index * 17 + 3) % 256 for index in range(384)),
            10: bytes.fromhex("000102030405060708090a0b"),
            11: bytes.fromhex("112233445566778899aabbccddeeff001122334455667788"),
            12: bytes.fromhex("ffeeddccbbaa99887766554433221100"),
            13: 1,
        }, {0: 1}), "M01"),
    ]


def clone_fact(fact: dict[int, Any]) -> dict[int, Any]:
    return decode_strict(cbor(fact))


def make_negative_vectors(positives: list[dict[str, Any]], facts):
    by_name = {item[0]: item for item in facts}
    result = []

    def add(name, fact, signer, expected, signature=None, full_override=None):
        case = signed_case(name, fact, signer, "FAIL", expected)
        if full_override is not None:
            case["fullCanonicalHex"] = full_override.hex()
            case["signedFactHex"] = cbor({
                0: full_override,
                1: bytes.fromhex(case["signatureHex"]),
            }).hex()
        if signature is not None:
            case["signatureHex"] = signature.hex()
            case["signedFactHex"] = cbor({
                0: bytes.fromhex(case["fullCanonicalHex"]), 1: signature,
            }).hex()
        result.append(case)

    creation = by_name["creation-v1"][1]
    creation_case = next(item for item in positives if item["name"] == "creation-v1")
    valid_sig = bytes.fromhex(creation_case["signatureHex"])

    bad_sig = bytearray(valid_sig)
    bad_sig[0] ^= 1
    add("fail-outer-hmac-valid-inner-signature-invalid", creation, "M01",
        "INVALID_INNER_SIGNATURE", bytes(bad_sig))
    outer_payload = base64.b64encode(bytes.fromhex(result[-1]["signedFactHex"])).decode("ascii")
    outer_fields = {
        "type": "CONFERENCE_SIGNED_FACT",
        "fromModuleId": "M01",
        "fromEndpointId": "E01",
        "toModuleId": "",
        "toEndpointId": "",
        "sessionId": "q4-fixture-session",
        "timestampMs": 1700000000000,
        "payload": outer_payload,
        "nonce": "q4-fixture-nonce-001",
    }
    outer_canonical = "|".join([
        outer_fields["type"], outer_fields["fromModuleId"], outer_fields["fromEndpointId"],
        outer_fields["toModuleId"], outer_fields["toEndpointId"], outer_fields["sessionId"],
        str(outer_fields["timestampMs"]), outer_fields["payload"], outer_fields["nonce"],
    ])
    outer_fields["signature"] = hmac.new(
        TEST_SHARED_SECRET.encode("utf-8"), outer_canonical.encode("utf-8"), hashlib.sha256
    ).hexdigest()
    result[-1]["outerHmacPrecondition"] = "VALID"
    result[-1]["outerSharedSecret"] = TEST_SHARED_SECRET
    result[-1]["outerSignalEnvelope"] = outer_fields

    canonical = bytes.fromhex(creation_case["fullCanonicalHex"])
    if canonical[:3] != bytes.fromhex("a40001"):
        raise AssertionError("unexpected FullFact prefix")
    noncanonical = canonical[:2] + b"\x18\x01" + canonical[3:]
    noncanonical_sig = sign_raw64(TEST_PRIVATE["M01"], SIGNATURE_DOMAIN + noncanonical)
    add("fail-non-canonical-cbor", creation, "M01", "NON_CANONICAL_FACT",
        noncanonical_sig, noncanonical)

    wrong_signer = clone_fact(creation)
    wrong_signer[2][2] = "M02"
    add("fail-wrong-signer-module", wrong_signer, "M01", "INVALID_INNER_SIGNATURE")

    unknown_key = clone_fact(creation)
    unknown_key[3][0] = 99
    add("fail-unknown-signer-key-version", unknown_key, "M01", "UNKNOWN_SIGNER_KEY")

    high_s_value = N - int.from_bytes(valid_sig[32:], "big")
    high_s = valid_sig[:32] + high_s_value.to_bytes(32, "big")
    add("fail-high-s-signature", creation, "M01", "HIGH_S_SIGNATURE", high_s)
    add("fail-wrong-signature-length", creation, "M01", "WRONG_SIGNATURE_LENGTH", valid_sig[:-1])

    audit_changed = clone_fact(creation)
    audit_changed[3][1] = "TAMPERED"
    audit_full = cbor(audit_changed)
    add("fail-modified-audit", audit_changed, "M01", "INVALID_INNER_SIGNATURE",
        valid_sig, audit_full)

    authority_changed = clone_fact(creation)
    authority_changed[2][1] = 8
    authority_full = cbor(authority_changed)
    add("fail-modified-authority", authority_changed, "M01", "INVALID_INNER_SIGNATURE",
        valid_sig, authority_full)

    resolution = clone_fact(by_name["source-resolution-v1"][1])
    resolution[2][6] = list(reversed(resolution[2][6]))
    add("fail-unordered-conflict-set", resolution, "M02", "NON_CANONICAL_COLLECTION")

    resolution_dup = clone_fact(by_name["source-resolution-v1"][1])
    resolution_dup[2][6] = [resolution_dup[2][6][0], resolution_dup[2][6][0]]
    add("fail-duplicate-conflict-set", resolution_dup, "M02", "NON_CANONICAL_COLLECTION")

    package = clone_fact(by_name["media-key-package-v1"][1])
    package_case = next(item for item in positives if item["name"] == "media-key-package-v1")
    package_sig = bytes.fromhex(package_case["signatureHex"])
    package[2][11] = bytes([package[2][11][0] ^ 1]) + package[2][11][1:]
    add("fail-modified-package-ciphertext", package, "M01", "INVALID_INNER_SIGNATURE",
        package_sig, cbor(package))

    package_key_version = clone_fact(by_name["media-key-package-v1"][1])
    package_key_version[2][13] = 2
    add("fail-modified-recipient-key-version", package_key_version, "M01",
        "INVALID_INNER_SIGNATURE", package_sig, cbor(package_key_version))

    unknown_type = clone_fact(creation)
    unknown_type[1] = 99
    add("fail-unknown-fact-type", unknown_type, "M01", "UNKNOWN_FACT_TYPE")

    missing_none = clone_fact(by_name["source-declaration-v1"][1])
    del missing_none[2][8]
    add("fail-missing-explicit-null", missing_none, "M02", "SCHEMA_INVALID")
    return result


def build_document():
    facts = positive_facts()
    positives = [signed_case(name, fact, signer) for name, fact, signer in facts]
    negatives = make_negative_vectors(positives, facts)
    public_keys = {}
    for module, private in TEST_PRIVATE.items():
        public = scalar_mult(private)
        if public is None:
            raise AssertionError("invalid test key")
        public_keys[module] = {
            "keyVersion": 1,
            "curve": "P-256",
            "xHex": public[0].to_bytes(32, "big").hex(),
            "yHex": public[1].to_bytes(32, "big").hex(),
            "uncompressedSec1Hex": (
                b"\x04" + public[0].to_bytes(32, "big") + public[1].to_bytes(32, "big")
            ).hex(),
        }
    return {
        "profile": "ADR-0058 Implementation Profile 01 Q4",
        "schemaVersion": 1,
        "warning": "PUBLIC TEST KEYS ONLY - NEVER USE IN PRODUCT",
        "domains": {
            "factDigestHex": FACT_DOMAIN.hex(),
            "descriptorDigestHex": DESCRIPTOR_DOMAIN.hex(),
            "signatureHex": SIGNATURE_DOMAIN.hex(),
        },
        "testPublicKeys": public_keys,
        "positiveVectors": positives,
        "negativeVectors": negatives,
    }


def self_check(document: dict[str, Any]) -> None:
    expected_domains = document["domains"]
    assert expected_domains["factDigestHex"] == FACT_DOMAIN.hex()
    assert expected_domains["descriptorDigestHex"] == DESCRIPTOR_DOMAIN.hex()
    assert expected_domains["signatureHex"] == SIGNATURE_DOMAIN.hex()
    profiles = {
        module: {entry["keyVersion"]: (int(entry["xHex"], 16), int(entry["yHex"], 16))}
        for module, entry in document["testPublicKeys"].items()
    }
    for module, private in TEST_PRIVATE.items():
        if profiles[module][1] != scalar_mult(private):
            raise AssertionError(f"{module}: public fixture key does not match generator constant")
    names = set()
    for case in document["positiveVectors"]:
        assert case["name"] not in names
        names.add(case["name"])
        result = verify_case(case, profiles)
        if result != "PASS":
            raise AssertionError(f"{case['name']}: expected PASS, got {result}")
        fact = decode_strict(bytes.fromhex(case["fullCanonicalHex"]))
        if fact_digest(fact).hex() != case["factDigestHex"]:
            raise AssertionError(f"{case['name']}: digest mismatch")
        envelope = decode_strict(bytes.fromhex(case["signedFactHex"]))
        if envelope[0].hex() != case["fullCanonicalHex"] or envelope[1].hex() != case["signatureHex"]:
            raise AssertionError(f"{case['name']}: SignedFact envelope mismatch")
    if set(FACT_TYPES) != {case["factType"] for case in document["positiveVectors"]}:
        raise AssertionError("positive vectors do not cover the closed factType registry")
    for case in document["negativeVectors"]:
        assert case["name"] not in names
        names.add(case["name"])
        result = verify_case(case, profiles)
        expected = case["expectedReason"]
        if result != expected:
            raise AssertionError(f"{case['name']}: expected {expected}, got {result}")
        if case.get("outerHmacPrecondition") == "VALID":
            outer = case["outerSignalEnvelope"]
            canonical = "|".join([
                outer["type"], outer["fromModuleId"], outer["fromEndpointId"],
                outer["toModuleId"], outer["toEndpointId"], outer["sessionId"],
                str(outer["timestampMs"]), outer["payload"], outer["nonce"],
            ])
            expected_hmac = hmac.new(
                case["outerSharedSecret"].encode("utf-8"),
                canonical.encode("utf-8"), hashlib.sha256,
            ).hexdigest()
            if not hmac.compare_digest(expected_hmac, outer["signature"]):
                raise AssertionError(f"{case['name']}: outer HMAC fixture is not valid")
            decoded_payload = base64.b64decode(outer["payload"], validate=True)
            if decoded_payload.hex() != case["signedFactHex"]:
                raise AssertionError(f"{case['name']}: outer payload does not carry SignedFact")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", type=Path, help="write freshly generated JSON")
    parser.add_argument("--verify", type=Path, help="verify an existing JSON artifact")
    args = parser.parse_args()
    if bool(args.write) == bool(args.verify):
        parser.error("select exactly one of --write or --verify")
    if args.write:
        document = build_document()
        self_check(document)
        args.write.parent.mkdir(parents=True, exist_ok=True)
        args.write.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
        print(f"PASS: generated and verified {len(document['positiveVectors'])} positive + "
              f"{len(document['negativeVectors'])} negative vectors -> {args.write}")
    else:
        document = json.loads(args.verify.read_text(encoding="utf-8"))
        self_check(document)
        print(f"PASS: verified {len(document['positiveVectors'])} positive + "
              f"{len(document['negativeVectors'])} negative vectors from {args.verify}")


if __name__ == "__main__":
    main()

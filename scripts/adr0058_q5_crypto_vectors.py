#!/usr/bin/env python3
"""Generate/verify ADR-0058 Profile 01 Q5 crypto golden vectors.

Protocol fixture only; the embedded RSA private key and every secret below are
public test data and MUST NOT be used by Talkback product code.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from adr0058_q4_vectors import cbor


CONTEXT_DOMAIN = b"TALKBACK-MEMBERSHIP-KEY-CONTEXT-V1\x00"
COMMITMENT_DOMAIN = b"TALKBACK-MEDIA-KEY-COMMITMENT-V1\x00"
SRTP_INFO_DOMAIN = b"TALKBACK-SRTP-KEY-INFO-V1\x00"
PACKAGE_AAD_DOMAIN = b"TALKBACK-MEDIA-KEY-PACKAGE-AAD-V1\x00"

# Public test fixture generated solely for this protocol vector.
RSA_PRIVATE_PEM = b"""-----BEGIN PRIVATE KEY-----
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
Mz37LTuHBR1cnDKL/ND0zFA=
-----END PRIVATE KEY-----
"""


def mgf1(seed: bytes, length: int, digest) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < length:
        out.extend(digest(seed + counter.to_bytes(4, "big")).digest())
        counter += 1
    return bytes(out[:length])


def xor(left: bytes, right: bytes) -> bytes:
    return bytes(a ^ b for a, b in zip(left, right))


def deterministic_oaep_wrap(public_key, message: bytes, seed: bytes) -> bytes:
    numbers = public_key.public_numbers()
    size = (numbers.n.bit_length() + 7) // 8
    hlen = 32
    if len(seed) != hlen or len(message) > size - 2 * hlen - 2:
        raise ValueError("invalid OAEP fixture input")
    db = (
        hashlib.sha256(b"").digest()
        + b"\x00" * (size - len(message) - 2 * hlen - 2)
        + b"\x01"
        + message
    )
    masked_db = xor(db, mgf1(seed, len(db), hashlib.sha1))
    masked_seed = xor(seed, mgf1(masked_db, hlen, hashlib.sha1))
    encoded = b"\x00" + masked_seed + masked_db
    encrypted = pow(int.from_bytes(encoded, "big"), numbers.e, numbers.n)
    return encrypted.to_bytes(size, "big")


def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    output = bytearray()
    previous = b""
    counter = 1
    while len(output) < length:
        previous = hmac.new(prk, previous + info + bytes([counter]), hashlib.sha256).digest()
        output.extend(previous)
        counter += 1
    return bytes(output[:length])


def fixture_inputs() -> dict:
    descriptor = {
        0: 4,
        1: bytes([239, 192, 10, 8]),
        2: 41000,
        3: 41001,
        4: 8,
        5: {0: 1, 1: 24000, 2: 20, 3: 50, 4: 120, 5: 46},
    }
    descriptor_domain = b"TALKBACK-MEDIA-GROUP-DESCRIPTOR-V1\x00"
    descriptor_digest = hashlib.sha256(descriptor_domain + cbor(descriptor)).digest()
    context = {
        0: bytes.fromhex("00112233445566778899aabbccddeeff"),
        1: 7,
        2: "M01",
        3: descriptor_digest,
        4: 2,
        5: hashlib.sha256(b"q5-previous-membership").digest(),
        6: [
            ["M01", bytes.fromhex("10000000000000000000000000000001")],
            ["M02", bytes.fromhex("20000000000000000000000000000002")],
            ["M03", bytes.fromhex("30000000000000000000000000000003")],
        ],
        7: 3,
        8: 1,
    }
    return {
        "descriptor": descriptor,
        "context": context,
        "secret": bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
        "membershipFactDigest": hashlib.sha256(b"q5-accepted-membership-fact").digest(),
        "recipientModuleId": "M02",
        "recipientKeyVersion": 4,
        "packageIdentity": bytes.fromhex("aabbccddeeff00112233445566778899"),
        "pek": bytes.fromhex("f0e0d0c0b0a09080706050403020100000112233445566778899aabbccddeeff"),
        "oaepSeed": bytes.fromhex("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f"),
        "gcmNonce": bytes.fromhex("0102030405060708090a0b0c"),
    }


def build() -> dict:
    i = fixture_inputs()
    private_key = serialization.load_pem_private_key(RSA_PRIVATE_PEM, password=None)
    public_key = private_key.public_key()
    context_bytes = cbor(i["context"])
    context_digest = hashlib.sha256(CONTEXT_DOMAIN + context_bytes).digest()
    commitment = hmac.new(
        i["secret"], COMMITMENT_DOMAIN + context_digest, hashlib.sha256
    ).digest()
    plaintext = {0: 1, 1: i["secret"], 2: context_digest}
    plaintext_bytes = cbor(plaintext)
    wrapped_pek = deterministic_oaep_wrap(public_key, i["pek"], i["oaepSeed"])
    aad_core = {
        0: i["context"][0],
        1: i["context"][1],
        2: i["context"][2],
        3: i["context"][4],
        4: i["context"][7],
        5: i["membershipFactDigest"],
        6: commitment,
        7: i["recipientModuleId"],
        8: i["packageIdentity"],
        9: wrapped_pek,
        10: i["gcmNonce"],
        11: i["recipientKeyVersion"],
    }
    aad = PACKAGE_AAD_DOMAIN + cbor([1, 11, aad_core])
    encrypted = AESGCM(i["pek"]).encrypt(i["gcmNonce"], plaintext_bytes, aad)
    ciphertext, tag = encrypted[:-16], encrypted[-16:]
    prk = hmac.new(context_digest, i["secret"], hashlib.sha256).digest()
    material = hkdf_expand(prk, SRTP_INFO_DOMAIN + context_digest, 28)
    public_numbers = public_key.public_numbers()
    public_der = public_key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return {
        "profile": "ADR-0058 Profile 01 Q5",
        "warning": "PUBLIC TEST DATA ONLY - NEVER USE THESE KEYS OR SECRETS IN PRODUCT",
        "domains": {
            "membershipKeyContextHex": CONTEXT_DOMAIN.hex(),
            "mediaKeyCommitmentHex": COMMITMENT_DOMAIN.hex(),
            "srtpKeyInfoHex": SRTP_INFO_DOMAIN.hex(),
            "packageAadHex": PACKAGE_AAD_DOMAIN.hex(),
        },
        "inputs": {
            "conferenceMediaSecretHex": i["secret"].hex(),
            "membershipFactDigestHex": i["membershipFactDigest"].hex(),
            "recipientModuleId": i["recipientModuleId"],
            "recipientKeyVersion": i["recipientKeyVersion"],
            "packageIdentityHex": i["packageIdentity"].hex(),
            "pekHex": i["pek"].hex(),
            "oaepSeedHex": i["oaepSeed"].hex(),
            "gcmNonceHex": i["gcmNonce"].hex(),
            "rsaPublicModulusHex": format(public_numbers.n, "x"),
            "rsaPublicExponent": public_numbers.e,
            "rsaPublicSpkiSha256Hex": hashlib.sha256(public_der).hexdigest(),
        },
        "expected": {
            "membershipKeyContextCanonicalHex": context_bytes.hex(),
            "membershipKeyContextDigestHex": context_digest.hex(),
            "mediaKeyCommitmentHex": commitment.hex(),
            "packagePlaintextCanonicalHex": plaintext_bytes.hex(),
            "wrappedPekHex": wrapped_pek.hex(),
            "packageAadBytesHex": aad.hex(),
            "ciphertextHex": ciphertext.hex(),
            "gcmTagHex": tag.hex(),
            "hkdfPrkHex": prk.hex(),
            "srtpMasterMaterialHex": material.hex(),
            "srtpMasterKeyHex": material[:16].hex(),
            "srtpMasterSaltHex": material[16:].hex(),
        },
        "negativeVectors": [
            {"name": "wrong-oaep-mgf1-sha256", "expected": "OAEP_DECRYPT_FAIL"},
            {"name": "wrong-oaep-label", "expected": "OAEP_DECRYPT_FAIL"},
            {"name": "wrong-recipient-module-aad", "expected": "GCM_AUTH_FAIL"},
            {"name": "wrong-recipient-key-version-aad", "expected": "GCM_AUTH_FAIL"},
            {"name": "wrong-membership-fact-digest-aad", "expected": "GCM_AUTH_FAIL"},
            {"name": "tampered-gcm-tag", "expected": "GCM_AUTH_FAIL"},
            {"name": "wrong-context-commitment", "expected": "COMMITMENT_FAIL"},
            {"name": "hkdf-info-missing-context", "expected": "DERIVATION_MISMATCH"},
        ],
    }


def expect_fail(action, label: str) -> None:
    try:
        action()
    except Exception:
        return
    raise AssertionError(f"negative vector unexpectedly passed: {label}")


def verify(artifact: dict) -> None:
    expected = build()
    if artifact != expected:
        raise AssertionError("artifact differs from deterministic Q5 fixture")
    i = fixture_inputs()
    private_key = serialization.load_pem_private_key(RSA_PRIVATE_PEM, password=None)
    e = artifact["expected"]
    wrapped = bytes.fromhex(e["wrappedPekHex"])
    correct_padding = padding.OAEP(
        mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=b""
    )
    assert private_key.decrypt(wrapped, correct_padding) == i["pek"]
    expect_fail(
        lambda: private_key.decrypt(
            wrapped,
            padding.OAEP(
                mgf=padding.MGF1(hashes.SHA256()),
                algorithm=hashes.SHA256(),
                label=b"",
            ),
        ),
        "wrong-oaep-mgf1-sha256",
    )
    expect_fail(
        lambda: private_key.decrypt(
            wrapped,
            padding.OAEP(
                mgf=padding.MGF1(hashes.SHA1()),
                algorithm=hashes.SHA256(),
                label=b"wrong",
            ),
        ),
        "wrong-oaep-label",
    )
    aad = bytes.fromhex(e["packageAadBytesHex"])
    encrypted = bytes.fromhex(e["ciphertextHex"] + e["gcmTagHex"])
    plaintext = AESGCM(i["pek"]).decrypt(i["gcmNonce"], encrypted, aad)
    assert plaintext.hex() == e["packagePlaintextCanonicalHex"]
    aad_core = build_aad_core_for_negative(artifact)
    for key, value, name in (
        (7, "M03", "wrong-recipient-module-aad"),
        (11, i["recipientKeyVersion"] + 1, "wrong-recipient-key-version-aad"),
        (5, hashlib.sha256(b"wrong-membership").digest(), "wrong-membership-fact-digest-aad"),
    ):
        changed = dict(aad_core)
        changed[key] = value
        wrong_aad = PACKAGE_AAD_DOMAIN + cbor([1, 11, changed])
        expect_fail(
            lambda wrong_aad=wrong_aad: AESGCM(i["pek"]).decrypt(
                i["gcmNonce"], encrypted, wrong_aad
            ),
            name,
        )
    tampered = encrypted[:-1] + bytes([encrypted[-1] ^ 1])
    expect_fail(
        lambda: AESGCM(i["pek"]).decrypt(i["gcmNonce"], tampered, aad),
        "tampered-gcm-tag",
    )
    context_digest = bytes.fromhex(e["membershipKeyContextDigestHex"])
    wrong_context = hashlib.sha256(b"wrong-context").digest()
    wrong_commitment = hmac.new(
        i["secret"], COMMITMENT_DOMAIN + wrong_context, hashlib.sha256
    ).digest()
    assert wrong_commitment != bytes.fromhex(e["mediaKeyCommitmentHex"])
    prk = bytes.fromhex(e["hkdfPrkHex"])
    assert hkdf_expand(prk, SRTP_INFO_DOMAIN, 28).hex() != e["srtpMasterMaterialHex"]
    assert context_digest.hex() == e["membershipKeyContextDigestHex"]


def build_aad_core_for_negative(artifact: dict) -> dict:
    i = fixture_inputs()
    e = artifact["expected"]
    return {
        0: i["context"][0], 1: i["context"][1], 2: i["context"][2],
        3: i["context"][4], 4: i["context"][7],
        5: i["membershipFactDigest"],
        6: bytes.fromhex(e["mediaKeyCommitmentHex"]),
        7: i["recipientModuleId"], 8: i["packageIdentity"],
        9: bytes.fromhex(e["wrappedPekHex"]), 10: i["gcmNonce"],
        11: i["recipientKeyVersion"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", type=Path)
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if bool(args.write) == bool(args.verify):
        parser.error("choose exactly one of --write or --verify")
    if args.write:
        artifact = build()
        args.write.write_text(json.dumps(artifact, indent=2) + "\n", encoding="utf-8")
        verify(artifact)
        print(f"PASS: wrote 1 positive + {len(artifact['negativeVectors'])} negative Q5 vectors")
    else:
        artifact = json.loads(args.verify.read_text(encoding="utf-8"))
        verify(artifact)
        print(f"PASS: verified 1 positive + {len(artifact['negativeVectors'])} negative Q5 vectors")


if __name__ == "__main__":
    main()

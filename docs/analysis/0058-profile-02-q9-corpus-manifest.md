# ADR-0058 Profile 02 Q9 — Hex Corpus Manifest

## Status

```text
E1 Profile 02 Q9 Hex Corpus
Implementation: NOT AUTHORIZED
warning: PUBLIC TEST KEYS ONLY
```

## Constants

```text
SafeUdpPayloadBytes   = 120
replayWindowPackets   = 64
SRTP profile          = AEAD_AES_128_GCM
IV                    = RFC7714-8.1 (0x0000||SSRC||ROC||SEQ) XOR salt
```

## Vectors

| Name | Kind | UDP len | Outcome | Seam | Class |
|---|---|---:|---|---|---|
| G1-valid-normal | positive | 88 | ACCEPT | P02-full-pipeline | - |
| G2-valid-exact-120 | positive | 120 | ACCEPT | P02-full-pipeline | - |
| G3a-valid-opus-len-20 | positive | 68 | ACCEPT | P02-full-pipeline | - |
| G3b-valid-opus-len-60 | positive | 108 | ACCEPT | P02-full-pipeline | - |
| N11b-fuzz-retained-valid-regression | positive | 88 | ACCEPT | P02-full-pipeline | - |
| N1-oversize-121 | negative | 121 | REJECT | Q8 | WIRE_ENVELOPE_OVERSIZE |
| N2-truncated-rtp-header | negative | 8 | REJECT | Q2 | HE_FORMAT_INVALID |
| N3-invalid-rtp-version | negative | 88 | REJECT | Q2 | HE_FORMAT_INVALID |
| N4-malformed-extension-length | negative | 88 | REJECT | Q2 | HE_FORMAT_INVALID |
| N5-structural-length-inconsistency | negative | 88 | REJECT | earliest-authoritative-seam | - |
| N6-rtcp-shaped-shared-ingress | negative | 28 | REJECT | Q7 | RTCP_PACKET_DISCARDED |
| N7-srtp-tag-corruption | negative | 88 | REJECT | Q5 | - |
| N8a-wrong-srtp-key | negative | 88 | REJECT | Q5 | - |
| N8b-wrong-source-ssrc-binding | negative | 88 | REJECT | Q3 | SOURCE_PACKET_IDENTITY_MISMATCH |
| N9-replay-duplicate | negative | 88 | REJECT | Q6 | SRTP_REPLAY_DUPLICATE |
| N10-replay-too-old | negative | 88 | REJECT | Q6 | SRTP_REPLAY_TOO_OLD |
| N11a-fuzz-he-bitflip | negative | 88 | REJECT | Q5 | - |

## Verification

```text
python talkback/scripts/adr0058_p02_q9_vectors.py --verify
```

Corpus file: `docs/analysis/0058-profile-02-q9-golden-vectors.json`

Opus payloads in this corpus are deterministic fill bytes for wire/
SRTP length and AEAD tests. They are not Opus encoder golden audio.

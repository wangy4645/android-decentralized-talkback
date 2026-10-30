# Primary Native SRD Fault — Clean 1a+1b Adjudication

**Date:** 2026-08-25  
**Status:** **ADJUDICATED** · Field archaeology **STOPPED**  
**Implementation:** **NOT AUTHORIZED** (next: minimal fix design review)

## Captures

| Run | Log | Session | Accept order |
|-----|-----|---------|--------------|
| Clean 1a | `talkback/logs/pnsrd-1a-20260825-120721/` | `815ca018-8340-4614-be1d-b1c4890d49fb` | M02 → M03 |
| Clean 1b | `talkback/logs/pnsrd-1b-20260825-123029/` | `bfc987ae-26e5-458f-901b-32b018da7433` | M03 → M02 |

## Results

| Run | SRD#1 | E3 after #1 | SRD#2 | Tier | M04 |
|-----|-------|-------------|-------|------|-----|
| 1a | M02 **PASS** 70ms | **YES** | M03 **HANG** | TIER_JNI | no APPLY (timeout) |
| 1b | M03 **PASS** 116ms | **YES** | M02 **HANG** | TIER_JNI | APPLY → **DOMAIN_BLOCKED** holder=M02 |

## Hypothesis verdict

| ID | Verdict |
|----|---------|
| **A** sequencing / shared-runtime ordinal | **PRIMARY** — hang follows 2nd sequential host SRD; failure edge inverts with accept order |
| **B** fixed edge/PC | **REFUTED as primary** — M03 PASS in 1b, HANG in 1a; M02 reverse |
| **C** post-SRD#1 non-idle | **REFUTED as sole cause** — both clean runs: SRD#1 → full E3 → SRD#2 still TIER_JNI hang |
| **D** nondeterminism | **NOT required** for this pair — both clean inversions deterministic |

## Exp 2 note (non-decisive)

Both hangs: `signalingState=HAVE_LOCAL_OFFER`, `MATCH`, `attachedPcCount=3`, distinct `pcHash` per edge. No peer-fixed input signature explains inversion.

## Product symptom

Media dead after 2nd accept; M04 blocked or timed out — consistent with lease retained on hung ordinal-2 edge.

## Next (authorized next step only)

Promote **A** → design review **CONDITIONALLY ACCEPTED** · **§11 Native seam investigation AUTHORIZED** (no impl, no field).  
Implementation IA blocked until seam names **what** would be reset.

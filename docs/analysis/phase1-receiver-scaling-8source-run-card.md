# Phase 1 — 8-Source Receiver Scaling Observation Run Card

## Status

```text
8-source: PASS (2026-09-07; run `receiver-scaling-8source-20260907-104400`)
10-source + promotion fence: CLOSED — see phase1-receiver-scaling-10source-run-card.md
capacityGates: NONE
```

## Formal PASS (structural)

```text
TopK max          = 4
liveDecoders max  = 5
MixMaxSources     = 4
write cadence     = 20ms
budget p99        ≈ 10.5ms
lock hold p99     ≈ 12.1ms
fairness          = 8/8 sources entered Top-K
```

No decoder/mix linear blow-up; cadence + lock-scope remain healthy.

## Watch (not fail)

```text
underrunΔ=15              observe-only
late=33125                elevated
non-TopK jitter backlog   WATCH — primary risk into 10-source
  S5 depth=575  S8 depth=761
```

## Field result (`receiver-scaling-8source-20260907-104400`)

```text
scalingPass=true capsHeld=true
ingress S1–S8 all > 0
TopK max=4 · liveDecoders max=5 · mixMax=4
writeInterval p50=20ms p99=24ms
budget p99≈10.5ms · lock hold p99≈12.1ms
fairness: sourcesEverInTopK=8 transitions=32
```

Evidence: `talkback/logs/receiver-scaling-8source-20260907-104400/`

## References

- 4-source post-fix: `talkback/logs/receiver-scaling-4source-20260907-102750/`
- 10-source: `talkback/docs/analysis/phase1-receiver-scaling-10source-run-card.md`

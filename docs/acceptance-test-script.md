# Acceptance Test Script (RF mesh, no central server)

## Environment

- 2 to 10 Android boards in the **same RF mesh** (same RF task key, flat IP subnet).
- Each board configured with unique `moduleId` (e.g. `M01`, `M02`).
- Each board has one or more local endpoints (`E01..EN`).
- Static peers configured (recommended); mDNS as secondary on multi-hop mesh.

## Steps

1. Confirm all boards share the same RF task key and are on the same mesh.
2. Start all boards and wait for discovery list to converge within 5 seconds.
3. From `M01-E01`, single-call `M02-E01`.
4. Press PTT on caller side and confirm remote hears voice.
5. Release PTT and confirm floor is released.
6. Run group call from `M01-E01` to three remote endpoints.
7. Trigger floor contention: two endpoints request floor at same time.
8. Verify arbitration follows priority, then timestamp, then module/endpoint lexicographic order.
9. Keep one 30-minute active call and monitor crash/reconnect.
10. Simulate packet loss/jitter on mesh and verify audible continuity.
11. Power off one board and verify timeout offline removal in 3-5 seconds.
12. (Optional, classified tasks) Place one board on a different RF key — verify it does not appear in discovery.

## Pass criteria

- First voice packet setup under 1 second.
- End-to-end latency under 300 ms on mesh baseline.
- Discovery stable with 10 online modules.
- No app crash in 30-minute continuous call.
- Different RF keys: mutual invisibility in app discovery.

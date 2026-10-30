#!/usr/bin/env python3
"""Slice-M materialization probe — compile-driven minimal closure. Analysis worktree only."""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
GA = "1ab10ae"
L2 = "faf8884"
# Log always under canonical repo docs (worktree may be sparse)
_CANON = Path(__file__).resolve().parents[4]
LOG = _CANON / "docs/analysis/artifacts/l2-closure/slice-m-probe-log.md"

EXCLUDE_PATH_SUBSTR = (
    "ConferenceSessionMediaCoordinatorDelegate.kt",
    "TalkbackCoordinator.kt",
    "Profile01HostLocalSessionFactProjection.kt",
    "Profile01ShadowMulticastReceiveSeam.kt",
    "MeetingProfile01FactWireIngress.kt",
    "RealMeshE2ERunner.kt",
)

L2_PATHS = [
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/ConferenceSessionMediaWiring.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/Profile01ShadowPlayoutClockSeam.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/Profile01ShadowRuntimeObservability.kt",
]

# Slice-M bootstrap: §2 direct symbol definition files @ 1ab10ae
BOOTSTRAP_GA = [
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/ConferenceSessionMediaBridge.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/ConferenceSessionMediaTypes.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/SourceBindingCatalog.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/MeetingProductMediaShadow.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/transport/AbsoluteMediaPlayoutClock.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/cutover/AudibleOwnershipState.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/cutover/ReplacementCutoverRc1.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/cutover/AudiblePlayoutOwnershipSeam.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/authority/AuthorityTypes.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/runtime/MediaResourceTypes.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/transport/MulticastLockPolicy.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/wire/WireTypes.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/transport/ConferenceMulticastNetworkBinding.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/transport/ConferenceMulticastRtpSrtpTransport.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/transport/ConferenceMulticastRealMediaAssembly.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/runtime/ConferenceMediaExecutionOrchestrator.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/transport/ConferenceMulticastMediaPipeline.kt",
]


def run(cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd or REPO, capture_output=True)


def git_checkout_ga(paths):
    paths = [p for p in paths if not any(x in p for x in EXCLUDE_PATH_SUBSTR)]
    if not paths:
        return
    r = run(["git", "checkout", GA, "--", *paths])
    if r.returncode != 0:
        err = (r.stderr or b"").decode("utf-8", errors="replace")
        raise SystemExit(f"git checkout {GA} failed: {err}")


def materialize_l2():
    for p in L2_PATHS:
        r = run(["git", "show", f"{L2}:{p}"])
        if r.returncode != 0:
            raise SystemExit(f"missing L2 {p}")
        dest = REPO / p
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(r.stdout)


def build_symbol_index():
    r = run(
        [
            "git",
            "ls-tree",
            "-r",
            "--name-only",
            GA,
            "android-board-talkback/src/main/java/com/talkback/core/conference/",
        ]
    )
    paths = [
        ln.decode("utf-8", errors="replace")
        for ln in r.stdout.splitlines()
        if ln.endswith(b".kt")
    ]
    r2 = run(
        [
            "git",
            "ls-tree",
            "-r",
            "--name-only",
            GA,
            "android-board-talkback/src/main/java/com/talkback/core/",
        ]
    )
    for ln in r2.stdout.splitlines():
        if not ln.endswith(b".kt"):
            continue
        p = ln.decode("utf-8", errors="replace")
        if "/conference/" not in p:
            paths.append(p)
    sym_to_paths = {}
    for path in paths:
        if any(x in path for x in EXCLUDE_PATH_SUBSTR):
            continue
        show = run(["git", "show", f"{GA}:{path}"])
        if show.returncode != 0:
            continue
        text = show.stdout.decode("utf-8", errors="replace")
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg.group(1) if pkg else ""
        for m in re.finditer(
            r"^(?:public\s+|private\s+|internal\s+)?(?:data\s+|sealed\s+|value\s+)?(?:class|object|interface|enum class)\s+(\w+)",
            text,
            re.M,
        ):
            sym_to_paths.setdefault(m.group(1), []).append(path)
        for m in re.finditer(r"^typealias\s+(\w+)", text, re.M):
            sym_to_paths.setdefault(m.group(1), []).append(path)
        for m in re.finditer(r"^fun\s+[\w.]+\.(\w+)\s*\(", text, re.M):
            sym_to_paths.setdefault(m.group(1), []).append(path)
    return sym_to_paths


def parse_unresolved(stderr_text):
    syms = set()
    junk = {
        "it",
        "receive",
        "send",
        "incarnationId",
        "mediaTimeMs",
        "networkInterface",
        "sourceIdentity",
        "currentMediaKeyEpoch",
        "rotateMediaKeyEpoch",
        "maybeLogIngressFunnel",
    }
    for m in re.finditer(r"Unresolved reference: (\w+)", stderr_text):
        name = m.group(1)
        if name in junk:
            continue
        if name[0].isupper():
            syms.add(name)
    return syms


def compile_module():
    gradle = REPO / "gradlew.bat"
    r = subprocess.run(
        [
            str(gradle),
            ":android-board-talkback:compileDebugKotlin",
            "--rerun-tasks",
            "--no-daemon",
            "-q",
        ],
        cwd=REPO,
        capture_output=True,
    )
    err = (r.stderr or b"").decode("utf-8", errors="replace") + (r.stdout or b"").decode(
        "utf-8", errors="replace"
    )
    ok = r.returncode == 0 and "BUILD SUCCESSFUL" in err
    return ok, err


def main():
    manifest = []  # (path, reason)

    materialize_l2()
    for p in L2_PATHS:
        manifest.append((p, "L2 seed @ faf8884"))

    git_checkout_ga(BOOTSTRAP_GA)
    for p in BOOTSTRAP_GA:
        manifest.append((p, "Slice-M bootstrap (§2 direct import / symbol owners)"))

    added = {p for p, _ in manifest}
    sym_index = build_symbol_index()

    log_lines = [
        "# Slice-M probe run log",
        f"**Worktree:** `{REPO}`",
        f"**Base:** substrate/l1-ops",
        "",
        "## Manifest",
        "",
        "| # | Path | Reason |",
        "|---|------|--------|",
    ]
    for i, (p, r) in enumerate(manifest, 1):
        log_lines.append(f"| {i} | `{p}` | {r} |")

    max_rounds = 40
    for round_i in range(1, max_rounds + 1):
        ok, err = compile_module()
        if ok:
            log_lines.extend(
                [
                    "",
                    f"## Result: **PASS** (round {round_i})",
                    "",
                    f"Total materialized GA paths (excl L2): {len(added) - len(L2_PATHS)}",
                ]
            )
            LOG.write_text("\n".join(log_lines), encoding="utf-8")
            print("PASS", len(added))
            return 0

        syms = parse_unresolved(err)
        if not syms:
            log_lines.extend(["", f"## Result: **FAIL** (no parsed symbols)", "", "```", err[-8000:], "```"])
            LOG.write_text("\n".join(log_lines), encoding="utf-8")
            print("FAIL opaque")
            return 1

        new_paths = []
        blocked = []
        for sym in sorted(syms):
            candidates = sym_index.get(sym, [])
            candidates = [c for c in candidates if c not in added and not any(x in c for x in EXCLUDE_PATH_SUBSTR)]
            if not candidates:
                if sym in ("TalkbackCoordinator", "ConferenceSessionMediaCoordinatorDelegate"):
                    blocked.append(sym)
                continue
            if len(candidates) > 1:
                # prefer conference package
                conf = [c for c in candidates if "/conference/" in c]
                pick = conf[0] if conf else candidates[0]
            else:
                pick = candidates[0]
            new_paths.append((pick, sym))

        if blocked:
            log_lines.extend(
                [
                    "",
                    "## **STOP — boundary**",
                    "",
                    f"Compile requires excluded symbol(s): `{', '.join(blocked)}`",
                    "",
                    "Unresolved (sample):",
                    "",
                    "```",
                    err[-6000:],
                    "```",
                ]
            )
            LOG.write_text("\n".join(log_lines), encoding="utf-8")
            print("STOP boundary", blocked)
            return 2

        if not new_paths:
            log_lines.extend(
                [
                    "",
                    f"## Result: **FAIL** (round {round_i}) — cannot map symbols",
                    "",
                    f"Unresolved: {sorted(syms)}",
                    "",
                    "```",
                    err[-8000:],
                    "```",
                ]
            )
            LOG.write_text("\n".join(log_lines), encoding="utf-8")
            print("FAIL unmapped", syms)
            return 1

        paths_only = [p for p, _ in new_paths]
        git_checkout_ga(paths_only)
        for p, sym in new_paths:
            if p not in added:
                added.add(p)
                reason = f"compile round {round_i}: unresolved `{sym}`"
                manifest.append((p, reason))
                n = len(manifest)
                log_lines.append(f"| {n} | `{p}` | {reason} |")

    log_lines.append("\n## Result: **FAIL** — max rounds exceeded")
    LOG.write_text("\n".join(log_lines), encoding="utf-8")
    print("FAIL max rounds")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

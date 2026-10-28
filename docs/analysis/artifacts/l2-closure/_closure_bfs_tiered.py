# Tiered static closure: conference-first, then minimal com.talkback spill.
import re
import subprocess
from collections import deque
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
GA = "1ab10ae"
CONF = "android-board-talkback/src/main/java/com/talkback/core/conference/"
MODULE = "android-board-talkback/src/main/java/com/talkback/"

L2_SEEDS = [
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/ConferenceSessionMediaWiring.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/Profile01ShadowPlayoutClockSeam.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/Profile01ShadowRuntimeObservability.kt",
]


def git_lines(args):
    r = subprocess.run(["git", "-C", str(REPO)] + args, capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout.decode("utf-8", errors="replace")


def git_cat(commit, path):
    return git_lines(["show", f"{commit}:{path}"])


def list_kt(commit, prefix):
    out = git_lines(["ls-tree", "-r", "--name-only", commit, prefix])
    return [p for p in (out or "").splitlines() if p.endswith(".kt")]


def parse_kt(text):
    pkg_m = re.search(r"^package\s+([\w.]+)", text, re.M)
    pkg = pkg_m.group(1) if pkg_m else ""
    full_imp = set(re.findall(r"^import\s+(com\.talkback\.[\w.]+)", text, re.M))
    return pkg, full_imp


def build_fqn_index(commit, paths):
    fqn_to_file = {}
    for path in paths:
        text = git_cat(commit, path)
        if not text:
            continue
        pkg, _ = parse_kt(text)
        for m in re.finditer(
            r"^(?:public\s+|private\s+|internal\s+)?(?:data\s+|sealed\s+|value\s+)?(?:class|object|interface|enum class)\s+(\w+)",
            text,
            re.M,
        ):
            fqn_to_file[f"{pkg}.{m.group(1)}"] = path
        for m in re.finditer(r"^typealias\s+(\w+)", text, re.M):
            fqn_to_file[f"{pkg}.{m.group(1)}"] = path
    return fqn_to_file


def expand_file(path, commit, fqn_index, by_short, conference_only_identifiers):
    text = git_cat(commit, path) or git_cat("faf8884", path)
    if not text:
        return set()
    pkg, imps = parse_kt(text)
    out = set(imps)
    for m in re.finditer(r"\b([A-Z][\w]{2,})\b", text):
        name = m.group(1)
        cand = f"{pkg}.{name}"
        if cand in fqn_index:
            out.add(cand)
            continue
        if not conference_only_identifiers:
            hits = by_short.get(name, [])
            if len(hits) == 1:
                out.add(hits[0][0])
    return out


def bfs(seed_paths, commit, all_paths, conference_only_identifiers):
    fqn_index = build_fqn_index(commit, all_paths)
    by_short = {fqn.rsplit(".", 1)[-1]: [(fqn, p)] for fqn, p in fqn_index.items()}
    # collapse ambiguous short names — only use unique shorts when conference_only_identifiers
    if conference_only_identifiers:
        by_short = {}
        for fqn, p in fqn_index.items():
            if not fqn.startswith("com.talkback.core.conference."):
                continue
            short = fqn.rsplit(".", 1)[-1]
            by_short.setdefault(short, []).append((fqn, p))

    needed = set()
    q = deque()
    for sp in seed_paths:
        if git_cat(commit, sp) or git_cat("faf8884", sp):
            needed.add(sp)
            q.append(sp)
    while q:
        path = q.popleft()
        fqns = expand_file(path, commit, fqn_index, by_short, conference_only_identifiers)
        for fqn in fqns:
            if fqn not in fqn_index:
                continue
            p2 = fqn_index[fqn]
            if p2 not in needed:
                needed.add(p2)
                q.append(p2)
    return needed


def main():
    conf_kt = list_kt(GA, CONF)
    mod_kt = list_kt(GA, MODULE)
    # Phase A: conference package only (imports + conference identifiers)
    conf_closure = bfs(L2_SEEDS, GA, conf_kt, True)
    # Phase B: spill — any com.talkback import from phase A files, full module index
    mod_index_paths = mod_kt
    fqn_index = build_fqn_index(GA, mod_index_paths)
    spill = set(conf_closure)
    changed = True
    while changed:
        changed = False
        nxt = set(spill)
        for path in list(spill):
            text = git_cat(GA, path) or git_cat("faf8884", path)
            if not text:
                continue
            _, imps = parse_kt(text)
            for imp in imps:
                if imp not in fqn_index:
                    continue
                p2 = fqn_index[imp]
                if p2 not in nxt:
                    nxt.add(p2)
                    changed = True
        spill = nxt
    # Phase C: one more identifier pass on spill set only for unique shorts in module
    by_short = {}
    for fqn, p in fqn_index.items():
        short = fqn.rsplit(".", 1)[-1]
        by_short.setdefault(short, []).append((fqn, p))
    final = set(spill)
    for path in list(spill):
        fqns = expand_file(path, GA, fqn_index, by_short, False)
        for fqn in fqns:
            if fqn not in fqn_index:
                continue
            p2 = fqn_index[fqn]
            final.add(p2)

    ga_backfill = sorted(final - set(L2_SEEDS))
    print(f"conference_kt_total={len(conf_kt)}")
    print(f"phase_a_conference_closure={len(conf_closure)}")
    print(f"phase_b_import_spill={len(spill)}")
    print(f"phase_c_final={len(final)}")
    print(f"ga_backfill={len(ga_backfill)}")
    coord = [p for p in ga_backfill if "TalkbackCoordinator" in p or "CoordinatorDelegate" in p]
    print(f"coordinator_touch_files={len(coord)}")
    for p in coord:
        print(f"  COORD {p}")
    for p in ga_backfill:
        print(p)


if __name__ == "__main__":
    main()

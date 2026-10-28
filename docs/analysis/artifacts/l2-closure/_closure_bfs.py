# One-off static closure helper (analysis only). Run from talkback/ repo root.
import re
import subprocess
from collections import deque
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
GA = "1ab10ae"
MODULE_PREFIX = "android-board-talkback/src/main/java/"

L2_SEEDS = [
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/ConferenceSessionMediaWiring.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/Profile01ShadowPlayoutClockSeam.kt",
    "android-board-talkback/src/main/java/com/talkback/core/conference/session/integration/Profile01ShadowRuntimeObservability.kt",
]


def git_lines(args):
    r = subprocess.run(
        ["git", "-C", str(REPO)] + args,
        capture_output=True,
    )
    if r.returncode != 0:
        return None
    return r.stdout.decode("utf-8", errors="replace")


def git_cat(commit, path):
    return git_lines(["show", f"{commit}:{path}"])


def list_kt(commit, prefix=MODULE_PREFIX):
    out = git_lines(["ls-tree", "-r", "--name-only", commit, prefix])
    if not out:
        return []
    return [p for p in out.splitlines() if p.endswith(".kt")]


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
            r"^(?:public\s+)?(?:private\s+)?(?:internal\s+)?(?:data\s+)?(?:sealed\s+)?(?:value\s+)?(?:class|object|interface|enum class)\s+(\w+)",
            text,
            re.M,
        ):
            fqn_to_file[f"{pkg}.{m.group(1)}"] = path
        for m in re.finditer(r"^typealias\s+(\w+)", text, re.M):
            fqn_to_file[f"{pkg}.{m.group(1)}"] = path
    return fqn_to_file


def short_name_index(fqn_index):
    # last segment -> list of fqns (may be ambiguous)
    by_short = {}
    for fqn, path in fqn_index.items():
        short = fqn.rsplit(".", 1)[-1]
        by_short.setdefault(short, []).append((fqn, path))
    return by_short


def resolve_identifiers(text, pkg, fqn_index, by_short):
    found = set()
    _, imps = parse_kt(text)
    found.update(imps)
    for m in re.finditer(r"\b([A-Z][\w]{2,})\b", text):
        name = m.group(1)
        cand = f"{pkg}.{name}"
        if cand in fqn_index:
            found.add(cand)
            continue
        hits = by_short.get(name, [])
        if len(hits) == 1:
            found.add(hits[0][0])
    return found


def closure_from_seeds(commit, seed_paths, all_paths):
    fqn_index = build_fqn_index(commit, all_paths)
    by_short = short_name_index(fqn_index)
    needed = set()
    queue = deque()

    for sp in seed_paths:
        if git_cat(commit, sp) or git_cat("faf8884", sp):
            needed.add(sp)
            queue.append(sp)

    while queue:
        path = queue.popleft()
        text = git_cat(commit, path) or git_cat("faf8884", path)
        if not text:
            continue
        pkg, _ = parse_kt(text)
        fqns = resolve_identifiers(text, pkg, fqn_index, by_short)
        for imp in fqns:
            if not imp.startswith("com.talkback."):
                continue
            if imp not in fqn_index:
                continue
            p2 = fqn_index[imp]
            if p2 not in needed:
                needed.add(p2)
                queue.append(p2)
    return needed, fqn_index


def main():
    all_kt = list_kt(GA, "android-board-talkback/src/main/java/com/talkback/")
    needed, idx = closure_from_seeds(GA, L2_SEEDS, all_kt)
    ga_only = sorted(needed - set(L2_SEEDS))
    by_dir = {}
    for p in ga_only:
        parts = p.split("/")
        key = "/".join(parts[6:8]) if len(parts) > 7 else "other"
        by_dir.setdefault(key, []).append(p)

    print(f"module_kt_at_ga={len(all_kt)}")
    print(f"l2_seeds={len(L2_SEEDS)}")
    print(f"closure_kt={len(needed)}")
    print(f"ga_backfill_kt={len(ga_only)}")
    for key in sorted(by_dir):
        print(f"  bucket {key}: {len(by_dir[key])}")
    for p in ga_only:
        print(p)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Regenerate values-zh-rCN/strings.xml from values/strings.xml + zh_strings.tsv."""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/res/values/strings.xml"
TSV = Path(__file__).resolve().parent / "zh_strings.tsv"
DST = ROOT / "src/main/res/values-zh-rCN/strings.xml"
DST_EN = ROOT / "src/main/res/values-en/strings.xml"


def load_tsv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "\t" not in line:
            raise ValueError(f"Bad line (need tab): {line[:80]}")
        key, value = line.split("\t", 1)
        out[key] = value.replace("\\n", "\n").replace("\\'", "'")
    return out


def android_text(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace("\n", "\\n")
    )


def write_strings_xml(keys: list[str], values: dict[str, str], path: Path) -> None:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name in keys:
        body = android_text(values[name])
        lines.append(f'    <string name="{name}">{body}</string>')
    lines.append("</resources>")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    if not TSV.exists():
        print(f"Missing {TSV}", file=sys.stderr)
        sys.exit(1)
    zh = load_tsv(TSV)
    tree = ET.parse(SRC)
    keys = [e.attrib["name"] for e in tree.getroot().findall("string")]
    missing = [k for k in keys if k not in zh]
    extra = [k for k in zh if k not in keys]
    if missing:
        print(f"Missing {len(missing)} zh translations:", ", ".join(missing[:20]), file=sys.stderr)
        sys.exit(1)
    if extra:
        print(f"Warning: {len(extra)} unused zh keys", file=sys.stderr)
    write_strings_xml(keys, zh, DST)
    print(f"Wrote {len(keys)} strings to {DST.relative_to(ROOT)}")

    DST_EN.parent.mkdir(parents=True, exist_ok=True)
    en_text = SRC.read_text(encoding="utf-8")
    DST_EN.write_text(en_text, encoding="utf-8")
    print(f"Synced English to {DST_EN.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

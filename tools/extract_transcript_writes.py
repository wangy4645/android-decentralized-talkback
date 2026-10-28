import json
import sys
from pathlib import Path

def main() -> None:
    transcript = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    names = set(sys.argv[3:])
    found: dict[str, str] = {}
    with transcript.open(encoding="utf-8") as f:
        for line in f:
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            if o.get("role") != "assistant":
                continue
            content = o.get("message", {}).get("content", [])
            if not isinstance(content, list):
                continue
            for part in content:
                if part.get("type") != "tool_use":
                    continue
                if part.get("name") != "Write":
                    continue
                inp = part.get("input", {})
                p = inp.get("path", "")
                body = inp.get("contents")
                if not body:
                    continue
                for name in names:
                    if name in p:
                        found[name] = body
    out_dir.mkdir(parents=True, exist_ok=True)
    for name in names:
        body = found.get(name)
        if not body:
            print(f"MISSING {name}")
            continue
        target = out_dir / name
        target.write_text(body, encoding="utf-8", newline="\n")
        print(f"WROTE {target} ({len(body)} bytes)")

if __name__ == "__main__":
    main()

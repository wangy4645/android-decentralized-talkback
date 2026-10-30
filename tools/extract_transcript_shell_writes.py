import json
import re
import sys
from pathlib import Path


def extract_heredoc(cmd: str, filename: str) -> str | None:
    patterns = [
        rf'Path = "\$base\\{re.escape(filename)}"\s+Content = @\'\n(.*?)\n\'@',
        rf'"{re.escape(filename)}" = @\'\n(.*?)\n\'@',
        rf'\$base\\{re.escape(filename)}"\s*\n\$content = @\'\n(.*?)\n\'@',
    ]
    for pat in patterns:
        m = re.search(pat, cmd, re.S)
        if m:
            return m.group(1)
    return None


def main() -> None:
    transcript = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    names = sys.argv[3:]
    found: dict[str, str] = {}
    with transcript.open(encoding="utf-8") as f:
        for line in f:
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            if o.get("role") != "assistant":
                continue
            for part in o.get("message", {}).get("content", []):
                if part.get("type") != "tool_use" or part.get("name") != "Shell":
                    continue
                cmd = part.get("input", {}).get("command", "")
                for name in names:
                    if name not in cmd:
                        continue
                    body = extract_heredoc(cmd, name)
                    if body:
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

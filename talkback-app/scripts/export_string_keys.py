import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "src/main/res/values/strings.xml"
for e in ET.parse(root).getroot().findall("string"):
    name = e.attrib["name"]
    text = (e.text or "").replace("\n", "\\n")
    print(f"{name}\t{text}")

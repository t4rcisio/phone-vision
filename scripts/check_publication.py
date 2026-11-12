#!/usr/bin/env python3
"""Check tracked publication inputs without reading local credentials or ignored captures."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    tracked = subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=root
    ).decode("utf-8").split("\0")
    failures: list[str] = []
    forbidden_suffixes = {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
    text_suffixes = {".kt", ".kts", ".xml", ".md", ".json", ".toml", ".properties", ".yml", ".yaml", ".py"}
    home_path = re.compile(
        r"[A-Za-z]:[\\/]Users[\\/][A-Za-z0-9_. -]+[\\/]"
        r"|/(?:Users|home)/[A-Za-z0-9_.-]+/"
    )
    local_ip = re.compile(r"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.0\.\d{1,3}\.\d{1,3})\b")
    for name in filter(None, tracked):
        path = Path(name)
        if (
            path.suffix in forbidden_suffixes
            or path.name == "local.properties"
            or any(part in {"artifacts", ".private", ".idea", "build", ".gradle"} for part in path.parts)
            or path.name.startswith(".env") and path.name != ".env.example"
            or "sideways-scene" in path.name
        ):
            failures.append(f"Excluded publication input is tracked: {name}")
        if path.suffix in text_suffixes:
            try:
                text = (root / path).read_text(encoding="utf-8")
            except UnicodeDecodeError:
                failures.append(f"Text file is not UTF-8: {name}")
                continue
            if home_path.search(text) or local_ip.search(text):
                failures.append(f"Machine-local path or network address found: {name}")
    model = root / "app/src/main/assets/yolo26n-seg_w8a32.tflite"
    provenance = json.loads((model.parent / "yolo-model.json").read_text(encoding="utf-8"))
    if hashlib.sha256(model.read_bytes()).hexdigest() != provenance["sha256"]:
        failures.append("Bundled model checksum does not match its provenance record")
    if failures:
        print("Publication hygiene failed:")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print(f"Publication hygiene passed for {len(list(filter(None, tracked)))} tracked files.")
    print("Model provenance checksum verified. Run a separate secret scanner as well.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Cross-check pdfgate output against poppler (pdfinfo / pdftotext).

Usage: poppler_compare.py <pdfgate-binary> <fixtures-dir>

Compares, per fixture: page count, PDF version, encrypted flag.
Also compares extracted text on simple.pdf, and agreement that broken.pdf
is unreadable. Exits non-zero on any disagreement.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

failures = 0


def report(name: str, check: str, ours, theirs) -> None:
    global failures
    if ours == theirs:
        print(f"ok   {name}: {check} = {ours!r}")
    else:
        print(f"FAIL {name}: {check} pdfgate={ours!r} poppler={theirs!r}")
        failures += 1


def pdfinfo(path: Path, password: str | None) -> tuple[int, dict]:
    cmd = ["pdfinfo"]
    if password:
        cmd += ["-upw", password]
    proc = subprocess.run(cmd + [str(path)], capture_output=True, text=True, timeout=30)
    fields = {}
    for line in proc.stdout.splitlines():
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    return proc.returncode, fields


def pdfgate_info(binary: str, path: Path, password: str | None) -> tuple[int, dict]:
    cmd = [binary, "info", str(path)]
    if password:
        cmd += ["--password", password]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    return proc.returncode, json.loads(proc.stdout or "{}")


def compare_file(binary: str, path: Path, password: str | None = None) -> None:
    p_code, p = pdfinfo(path, password)
    g_code, g = pdfgate_info(binary, path, password)
    name = path.name
    if p_code != 0 or g_code != 0:
        # both tools should agree the file is unreadable
        report(name, "unreadable-agreement", g_code != 0, p_code != 0)
        return
    report(name, "pages", g.get("pages"), int(p["Pages"]))
    report(name, "version", g.get("version"), p["PDF version"])
    report(name, "encrypted", g.get("encrypted"), p["Encrypted"].startswith("yes"))


def compare_text(binary: str, path: Path) -> None:
    poppler = subprocess.run(
        ["pdftotext", "-q", str(path), "-"], capture_output=True, text=True, timeout=30
    ).stdout
    ours = json.loads(
        subprocess.run([binary, "text", str(path)], capture_output=True, text=True, timeout=30).stdout
    )["text"]
    normalize = lambda s: re.sub(r"\s+", " ", s).strip()
    report(path.name, "text", normalize(ours), normalize(poppler))


def main() -> None:
    binary, fixtures = sys.argv[1], Path(sys.argv[2])
    for pdf in sorted(fixtures.glob("*.pdf")):
        password = "user-secret" if pdf.name == "encrypted.pdf" else None
        compare_file(binary, pdf, password)
    compare_text(binary, fixtures / "simple.pdf")
    if failures:
        print(f"{failures} mismatch(es)")
        sys.exit(1)
    print("pdfgate and poppler agree on all checks")


if __name__ == "__main__":
    main()

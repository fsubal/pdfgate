#!/usr/bin/env python3
"""Validate real pdfgate output against the published schemas in schemas/.

Usage: schema_check.py <pdfgate-binary> <fixtures-dir>

Runs every subcommand against the fixture corpus, asserts the expected exit
code, and validates stdout against the corresponding schema file. Keeps the
schemas honest: any drift between the CLI output and schemas/ fails CI.
Requires the `jsonschema` package.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError:
    sys.exit("this script needs the jsonschema package: pip install jsonschema")

ROOT = Path(__file__).resolve().parent.parent

# (schema name, expected exit code, argv suffix)
CASES = [
    ("info", 0, ["info", "simple.pdf"]),
    ("info", 0, ["info", "with-japanese.pdf"]),
    ("info", 0, ["info", "encrypted.pdf"]),  # passwordRequired variant: nulls
    ("layers", 0, ["layers", "with-layers.pdf"]),
    ("layers", 0, ["layers", "simple.pdf"]),
    ("forms", 0, ["forms", "with-form.pdf"]),
    ("forms", 0, ["forms", "simple.pdf"]),
    ("crypto", 0, ["crypto", "encrypted.pdf", "--password", "user-secret"]),
    ("crypto", 0, ["crypto", "encrypted.pdf"]),  # passwordRequired variant: nulls
    ("crypto", 0, ["crypto", "simple.pdf"]),
    ("scan", 0, ["scan", "with-js.pdf"]),
    ("scan", 0, ["scan", "with-attachment.pdf"]),
    ("scan", 0, ["scan", "with-uri.pdf"]),
    ("scan", 0, ["scan", "simple.pdf"]),
    ("validate", 0, ["validate", "simple.pdf"]),
    ("validate", 1, ["validate", "with-js.pdf"]),
    ("validate", 1, ["validate", "encrypted.pdf"]),
    ("text", 0, ["text", "simple.pdf"]),
    ("text", 0, ["text", "with-japanese.pdf"]),
    ("error", 2, ["info", "broken.pdf"]),  # parse-failure
    ("error", 2, ["scan", "encrypted.pdf"]),  # password-required
]


def main() -> None:
    binary, fixtures = sys.argv[1], Path(sys.argv[2])
    validators = {
        name: Draft202012Validator(json.loads((ROOT / "schemas" / f"{name}.schema.json").read_text()))
        for name in {c[0] for c in CASES}
    }
    failures = 0
    for schema_name, want_code, args in CASES:
        argv = [binary] + [str(fixtures / a) if a.endswith(".pdf") else a for a in args]
        label = f"{' '.join(args)} vs {schema_name}.schema.json"
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        if proc.returncode != want_code:
            print(f"FAIL {label}: exit {proc.returncode} != {want_code}")
            failures += 1
            continue
        errors = list(validators[schema_name].iter_errors(json.loads(proc.stdout)))
        if errors:
            for e in errors:
                print(f"FAIL {label}: {'/'.join(map(str, e.path)) or '<root>'}: {e.message}")
            failures += 1
        else:
            print(f"ok   {label}")
    if failures:
        print(f"{failures} schema check(s) failed")
        sys.exit(1)
    print("all outputs conform to schemas/")


if __name__ == "__main__":
    main()

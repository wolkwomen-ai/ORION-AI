"""Read-only self-inspection for ARION.

This first stage reports what is present in the deployed backend. It does not
write, execute, deploy, or replace source code. Future code changes must happen
on a separate branch and pass isolated tests before an owner-approved release.
"""
from __future__ import annotations

import ast
from pathlib import Path
from typing import Any


def inspect_backend(root: Path | None = None) -> dict[str, Any]:
    """Return a read-only capability and syntax report for this backend."""
    base = (root or Path(__file__).resolve().parent).resolve()
    expected = {
        "app.py": base / "app.py",
        "product_files.py": base / "product_files.py",
        "test_backend.py": base / "test_backend.py",
    }
    files: dict[str, dict[str, Any]] = {}
    for name, path in expected.items():
        exists = path.is_file()
        item: dict[str, Any] = {"exists": exists, "syntax_ok": None}
        if exists and path.suffix == ".py":
            try:
                ast.parse(path.read_text(encoding="utf-8"), filename=name)
                item["syntax_ok"] = True
            except (SyntaxError, UnicodeError, OSError) as exc:
                item["syntax_ok"] = False
                item["error_type"] = type(exc).__name__
        files[name] = item

    required_ok = all(
        files[name]["exists"] and files[name]["syntax_ok"] is True
        for name in ("app.py", "product_files.py")
    )
    tests_present = files["test_backend.py"]["exists"]
    return {
        "mode": "read_only_diagnostic",
        "status": "healthy" if required_ok else "needs_attention",
        "files": files,
        "capabilities": {
            "source_inspection": True,
            "syntax_validation": True,
            "isolated_test_execution": False,
            "ai_code_proposals": False,
            "automatic_source_writes": False,
            "automatic_deployment": False,
            "automatic_rollback": False,
        },
        "tests_present": tests_present,
        "next_stage": (
            "Add an isolated test runner and reviewable change proposals; "
            "do not grant the agent direct production write access."
        ),
        "safety": (
            "This endpoint is read-only. It never reads environment secrets, "
            "changes files, runs generated code, or deploys changes."
        ),
    }

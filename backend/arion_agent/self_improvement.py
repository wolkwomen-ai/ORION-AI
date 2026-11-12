"""Read-only self-inspection for ARION.

This first stage reports what is present in the deployed backend. It does not
write, execute, deploy, or replace source code. Future code changes must happen
on a separate branch and pass isolated tests before an owner-approved release.
"""
from __future__ import annotations

import ast
import re
import sqlite3
import uuid
from datetime import datetime, timezone
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


_ALLOWED_IMPORTS = {
    "math", "statistics", "decimal", "re", "collections", "typing",
    "dataclasses", "enum", "json", "datetime"
}
_FORBIDDEN_NAMES = {
    "exec", "eval", "compile", "open", "__import__", "input",
    "breakpoint"
}


def validate_proposal_source(source: str) -> dict[str, Any]:
    """Perform conservative static checks. This is not a substitute for sandboxing."""
    if not isinstance(source, str) or not source.strip():
        return {"ok": False, "reason": "Kod bo‘sh"}
    if len(source.encode("utf-8")) > 20000:
        return {"ok": False, "reason": "Kod hajmi 20 KB chegarasidan oshdi"}
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        return {"ok": False, "reason": f"Python sintaksis xatosi: {exc.msg}", "line": exc.lineno}

    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            names = [a.name.split(".")[0] for a in node.names] if isinstance(node, ast.Import) else [(node.module or "").split(".")[0]]
            if any(name not in _ALLOWED_IMPORTS for name in names):
                return {"ok": False, "reason": "Ruxsat etilmagan import"}
        if isinstance(node, ast.Name) and node.id in _FORBIDDEN_NAMES:
            return {"ok": False, "reason": "Xavfli funksiya nomi aniqlandi"}
        if isinstance(node, ast.Attribute) and node.attr.startswith("__"):
            return {"ok": False, "reason": "Dunder atributlardan foydalanish taqiqlangan"}
        if isinstance(node, (ast.With, ast.AsyncWith, ast.Global, ast.Nonlocal)):
            return {"ok": False, "reason": "Tashqi resurs yoki global holatga ruxsat yo‘q"}
    return {"ok": True, "reason": "Sintaksis va boshlang‘ich statik tekshiruvdan o‘tdi"}


def init_proposal_store(conn: sqlite3.Connection) -> None:
    conn.execute("""
        CREATE TABLE IF NOT EXISTS self_improvement_proposals (
            id TEXT PRIMARY KEY,
            module_name TEXT NOT NULL,
            goal TEXT NOT NULL,
            summary TEXT NOT NULL,
            source TEXT NOT NULL,
            validation TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'proposed',
            created_at TEXT NOT NULL
        )
    """)


def store_proposal(conn: sqlite3.Connection, module_name: str, goal: str,
                   summary: str, source: str, validation: dict[str, Any]) -> str:
    if not re.fullmatch(r"[a-z][a-z0-9_]{1,40}", module_name):
        raise ValueError("Modul nomi noto‘g‘ri")
    if not validation.get("ok"):
        raise ValueError("Tekshiruvdan o‘tmagan kod saqlanmaydi")
    proposal_id = str(uuid.uuid4())
    conn.execute(
        """INSERT INTO self_improvement_proposals
           (id,module_name,goal,summary,source,validation,status,created_at)
           VALUES(?,?,?,?,?,?,?,?)""",
        (proposal_id, module_name, goal[:1000], summary[:2000], source,
         json.dumps(validation, ensure_ascii=False), "proposed",
         datetime.now(timezone.utc).isoformat())
    )
    return proposal_id

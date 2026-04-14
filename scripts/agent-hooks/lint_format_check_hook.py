#!/usr/bin/env python3
"""Run ceeker repo validation after mutating agent tool calls."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from typing import Any

EDIT_TOOLS = {"Write", "Edit", "MultiEdit"}
VALIDATION_COMMANDS = {
    "make lint",
    "make format-check",
    "make ci",
    "clojure -M:lint",
    "clojure -M:format-check",
    "clojure -M:test",
}
MUTATING_BASH_PATTERNS = [
    r"\bapply_patch\b",
    r"\bgit\s+apply\b",
    r"\bpatch\b",
    r"\bsed\s+-i\b",
    r"\bperl\s+-pi\b",
    r"\bmv\b",
    r"\bcp\b",
    r"\brm\b",
    r"\bmkdir\b",
    r"\btouch\b",
    r"\btee\b",
    r"\bchmod\b",
    r"\bchown\b",
    r">>",
    r"(^|[^>])>([^>]|$)",
]


def read_payload() -> dict[str, Any]:
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    return json.loads(raw)


def normalize_command(command: str) -> str:
    return " ".join(command.strip().split())


def should_run(payload: dict[str, Any]) -> tuple[bool, str]:
    if payload.get("hook_event_name") != "PostToolUse":
        return False, ""

    tool_name = str(payload.get("tool_name") or "")
    if tool_name in EDIT_TOOLS:
        return True, tool_name

    if tool_name != "Bash":
        return False, ""

    command = str((payload.get("tool_input") or {}).get("command") or "")
    normalized_command = normalize_command(command)
    if normalized_command in VALIDATION_COMMANDS:
        return False, ""

    for pattern in MUTATING_BASH_PATTERNS:
        if re.search(pattern, command):
            return True, "Bash command"

    return False, ""


def summarize_output(output: str) -> str:
    stripped = output.strip()
    if not stripped:
        return ""

    lines = stripped.splitlines()[:20]
    summary = "\n".join(lines)
    if len(summary) > 2000:
        summary = summary[:2000] + "\n..."
    return summary


def run_target(target: str) -> tuple[bool, str]:
    result = subprocess.run(
        ["make", target],
        check=False,
        capture_output=True,
        text=True,
    )
    combined = "\n".join(
        part for part in [result.stdout.strip(), result.stderr.strip()] if part
    )
    return result.returncode == 0, combined


def emit_message(message: str) -> None:
    json.dump({"systemMessage": message}, sys.stdout)
    sys.stdout.write("\n")


def main() -> int:
    payload = read_payload()
    should_validate, trigger = should_run(payload)
    if not should_validate:
        return 0

    format_ok, format_output = run_target("format-check")
    if not format_ok:
        summary = summarize_output(format_output)
        message = f"format-check failed after {trigger}."
        if summary:
            message = f"{message}\n{summary}"
        emit_message(message)
        return 0

    lint_ok, lint_output = run_target("lint")
    if not lint_ok:
        summary = summarize_output(lint_output)
        message = f"lint failed after {trigger}."
        if summary:
            message = f"{message}\n{summary}"
        emit_message(message)
        return 0

    emit_message(f"format-check and lint passed after {trigger}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# Codex hooks v0.114.0 support - Implementation Plan

## Goal
Add Codex v0.114.0 hooks engine SessionStart/Stop event support to ceeker while preserving existing notify fallback.

## Investigation Summary
- Codex v0.114.0 (PR #13276) introduces experimental hooks engine
- Events: `session_start`, `stop` (HookEventName enum)
- Config: `~/.codex/hooks.json` with same structure as Claude Code hooks
- Payload: stdin JSON with `session_id`, `cwd`, `hook_event_name`, `last_assistant_message`
- Execution modes: `sync` | `async` (async is experimental)

## Implementation

### handler.clj changes (minimal)
1. `codex-event-fields`: Add `"SessionStart"` → `[:running nil]` and `"Stop"` → `[:completed ...]` with `last_assistant_message` support
2. `resolve-codex-event`: Add `hook_event_name` payload field as resolution source (same as Claude)
3. `codex-type->event`: Map `"session_start"` (snake_case) → `"SessionStart"`

### Test additions
- 7 new tests: SessionStart/Stop normalization, hook_event_name fallback, lifecycle E2E, snake_case mapping, notify regression

### README updates
- Codex (hooks — recommended) section with `~/.codex/hooks.json` config (async)
- Codex (notify — fallback) section for pre-v0.114.0

## Decision Log
- **Async in README**: Config examples use `"async": true` per task requirements, with note about experimental status
- **PascalCase event names**: Used PascalCase (`SessionStart`, `Stop`) for consistency with config key names
- **No new abstraction layer**: Events handled directly in existing `codex-event-fields` case dispatch — KISS/YAGNI

## PR
- https://github.com/boxp/ceeker/pull/61

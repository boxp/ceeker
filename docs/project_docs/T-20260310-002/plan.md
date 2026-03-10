# T-20260310-002: --exit-on-jump CLI option

## Overview

Add a `--exit-on-jump` CLI option that causes ceeker to exit after a successful tmux pane jump. This enables one-shot popup usage where ceeker opens, the user selects a session, jumps to it, and ceeker automatically closes.

## Design

### CLI Option

- Long flag: `--exit-on-jump`
- No short flag (to avoid conflicts with existing `-h`, `-V`)
- Default: disabled (existing behavior preserved)
- Parsed by `clojure.tools.cli` in `core.clj`

### Implementation Flow

1. `core.clj`: Add option to `cli-options`, pass `{:exit-on-jump true/nil}` to `start-tui!`
2. `app.clj/start-tui!`: Accept opts map, extract `:exit-on-jump`, pass to `tui-loop`
3. `app.clj/tui-loop`: Pass `exit-on-jump?` to `process-key`
4. `app.clj/process-key` → `handle-normal-key` → `nav-key-result`: On Enter key, check if jump succeeded AND `exit-on-jump?` is true → return `{:quit true}`
5. `handle-enter-key`: Changed to return `{:msg ... :jumped true/false}` map

### Files Modified

- `src/ceeker/core.clj` - CLI option definition, pass to start-tui!
- `src/ceeker/tui/app.clj` - Thread exit-on-jump? through TUI loop
- `test/ceeker/tui/app_test.clj` - Updated existing tests, added new tests
- `README.md` / `README.ja.md` - Document option and popup usage
- `docs/project_docs/T-20260310-002/plan.md` - This file

### Behavior

| Condition | Result |
|-----------|--------|
| `--exit-on-jump` + jump success | ceeker exits (code 0) |
| `--exit-on-jump` + jump failure | stays in TUI, shows error |
| `--exit-on-jump` + no sessions | stays in TUI, shows error |
| No flag + jump success | stays in TUI (existing behavior) |
| No flag + jump failure | stays in TUI (existing behavior) |

### Risk

- Minimal: only adds a new code path gated by an opt-in flag
- Default behavior is completely unchanged

# llm-session-list-option Plan

## Summary

`ceeker` に TUI を起動せず現在の session list を JSON で返す
`--list-sessions` オプションを追加する。
LLM が対象 pane を特定できるよう、既存の session 情報に加えて
`pane_id` を必ず出力する。

## Key Changes

- CLI に `--list-sessions` を追加
- `ceeker --list-sessions` は TUI を起動せず JSON を stdout に出力
- 出力前に `tmux` pane の stale close と capture ベース状態更新を 1 回実行
- 更新失敗時は fail-open で保存済み state を返す
- 出力 JSON のキーは snake_case に統一し、`agent_type` と
  `agent_status` は文字列化する

## Tests

- `--list-sessions` の CLI parse
- list mode で TUI が起動しないこと
- JSON に `pane_id` が含まれること
- refresh 失敗時も JSON 出力が継続すること
- session 並び順が TUI と一致すること

## Assumptions

- 「今の session list」は TUI が参照している state 全件を指す
- 出力形式は今回は JSON 固定とする
- tmux 外セッションでも `pane_id` キーは出し、値は空文字にする

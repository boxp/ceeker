# T-20260306-012: ceeker hook 非同期化（agent 非ブロッキング化）

## 背景

ceeker の hook CLI (`ceeker hook claude <event>`) は、Claude Code / Codex から subprocess として起動される。
現在、`handle-hook!` 内の以下の処理が同期的にブロックしている:

1. `store/update-session!` - ファイルロック取得 + EDN ファイル読み書き
2. `pane/close-stale-sessions!` - tmux pane 列挙 + プロセスツリー探索 + ファイルロック

これにより、ファイルロック競合や tmux 遅延時に agent のメインフローが数秒以上ブロックされる。

## 設計方針

### 最小差分アプローチ

既存の `handle-hook!` は **変更しない**（テスト後方互換性維持）。
新しい `handle-hook-async!` を追加し、`core.clj` の CLI エントリポイントのみ切り替える。

### 変更対象ファイル

| ファイル | 変更内容 |
|----------|----------|
| `src/ceeker/hook/handler.clj` | `handle-hook-async!`, `await-hook-task!`, `hook-timeout-ms` 追加 |
| `src/ceeker/core.clj` | `handle-hook-command` を async 版に切り替え |
| `test/ceeker/hook/handler_test.clj` | 非同期挙動の回帰テスト追加 |
| `README.md` / `README.ja.md` | 非同期・失敗非伝播の挙動を追記 |

### `handle-hook-async!` の設計

```
入力 → [同期] parse + normalize → session-data を即座に返却
                                 ↓
                          [非同期 future] store/update-session!
                                         pane/close-stale-sessions!
                                 ↓
                          try/catch で失敗をログ化（伝播しない）
```

- `future` で IO 操作をバックグラウンド実行
- `await-hook-task!` で timeout 付き deref
- タイムアウト: 5秒（`hook-timeout-ms` 定数）
- エラー: stderr にログ出力、agent には伝播しない

### 並列実行上限について

- ceeker は CLI ツール（プロセス単位で分離）
- `with-file-lock` が sessions.edn への排他アクセスを既に保証
- 追加のセマフォは不要

## テスト計画

1. `handle-hook-async!` が session-data を即座に返す
2. store 遅延時もタイムアウト内で制御が戻る
3. store エラー時も session-data は正常に返る
4. `handle-hook!`（同期版）は変更なし（既存テスト全パス）

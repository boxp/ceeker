# T-20260303-012: ceeker Codexセッション整理（tmux pane存在チェック）

## 概要

Codexはagent-turn-complete時しかnotifyされず終了イベントがないため、
セッションが溜まり続ける問題を、tmux pane存在チェックで解消する。

## 設計方針

### チェックロジック

1. `tmux list-panes -a -F "#{pane_current_path}"` を1回実行し、全paneのcwdをsetとして取得
2. `:running` 状態のセッションについて、cwdがpane-cwds setに含まれるか確認
3. 含まれない場合、`:closed` に遷移（`last-message` を `"pane closed"` に更新）
4. tmuxコマンドが失敗した場合（tmux未起動等）は何もしない（安全側に倒す）

### チェックタイミング

| タイミング | 実装箇所 | 頻度 |
|-----------|---------|------|
| TUI起動時 | `tui/app.clj` ループ初回 (tick=0) | 1回 |
| 定期チェック | `tui/app.clj` ループ内 (tick % 10 == 0) | ~10秒ごと |
| hook受信時 | `hook/handler.clj` handle-hook! 内 | hook毎 |

### 効率化

- `tmux list-panes -a` は1回のみ実行し、結果をsetで保持
- セッション更新は `with-file-lock` 内で一括処理（read + update を atomic に）
- 個別のtmuxコマンド連打は行わない

## ファイル変更一覧

### 新規作成

1. **`src/ceeker/tmux/pane.clj`** - tmux pane操作モジュール
   - `list-pane-cwds` - 全paneのcwdをset取得
   - `close-stale-sessions!` - staleセッション一括close

2. **`test/ceeker/tmux/pane_test.clj`** - paneモジュールのテスト

### 変更

3. **`src/ceeker/state/store.clj`** - `close-stale-sessions!` 関数追加
   - pane-cwds setを受け取り、file-lock内でatomicに更新

4. **`src/ceeker/tui/app.clj`** - TUIループにpane liveness check追加
   - tick counter追加、10回ごとにcheck実行
   - 起動時(tick=0)も実行

5. **`src/ceeker/tui/view.clj`** - `:closed` ステータスバッジ追加

6. **`src/ceeker/hook/handler.clj`** - hook処理時にpane liveness check呼出

7. **`test/ceeker/state/store_test.clj`** - `close-stale-sessions!` テスト追加

8. **`README.md`** - セッション自動整理の挙動を追記

## 状態遷移

```
:running → (paneが消滅) → :closed
:running → (Stopイベント) → :completed （既存）
:closed  → (hookで再度running) → :running （復活可能）
```

## テスト戦略

- `store/close-stale-sessions!` の単体テスト（pane-cwds setを直接渡す）
  - running + cwdがpane-cwdsに含まれない → :closed
  - running + cwdがpane-cwdsに含まれる → :running のまま
  - completed → 変化なし
  - 空pane-cwds → 全running が closed
- `tmux/pane` のテスト（`list-pane-cwds` のモック）

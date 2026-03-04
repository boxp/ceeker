# T-20260304-004: Session Dedup by Pane Identity with Supersede and Process Liveness

## 背景
同一tmux paneでClaude Codeをclose/resumeするたびにsessionが増殖する。
原因: 新しいsession_idが毎回生成され、旧sessionがrunningのまま残る。

## 実装方針

### D: Agent ID + Pane Fallback（Pane Identity正規化）

**変更対象**: `handler.clj`, `core.clj`

- hookペイロードに `$TMUX_PANE` 環境変数を付与する方法がないため、
  ceekerのhookハンドラが環境変数 `TMUX_PANE` を直接読み取る
- sessionデータに `:pane-id` フィールドを追加
  - `TMUX_PANE` が利用可能な場合はその値（例: `%123`）
  - 利用不可の場合は空文字列（fallback）
- `session_id`（Claude Codeから提供）は引き続きメインの識別子として使用
- `pane-id` は supersede key の一部として活用

### B: Supersede-per-Key

**変更対象**: `store.clj`

- supersede key: `(pane-id, agent-type, cwd)` のタプル
- `update-session!` 時、同一keyを持つ**別の**session-idで`:running`状態のものがあれば
  自動的に `:closed` に遷移させる
- pane-idが空の場合はsupersede判定をスキップ（UUID fallback時は重複検知不可）
- これにより同一paneでresume時、旧sessionが自動closeされる

### C: Process Tree Liveness

**変更対象**: `tmux/pane.clj`, `store.clj`

- 現在のcwd比較に加え、tmux pane内で対象agentプロセスが実際に動いているかを確認
- `tmux list-panes -a -F "#{pane_current_path} #{pane_pid}"` でpane PIDを取得
- pane PIDの子孫プロセスに `claude` または `codex` が含まれるか `/proc/<pid>/` を探索
- agentプロセスが見つからないpaneのsessionは `:closed` に遷移
- tmux非対応環境ではgraceful degradation（既存動作を維持）

## ファイル変更一覧

1. `src/ceeker/hook/handler.clj`
   - `make-session`: `:pane-id` フィールド追加
   - `extract-claude-identity`, `extract-codex-identity`: pane-id取得

2. `src/ceeker/state/store.clj`
   - `update-session!`: supersede-per-key ロジック追加
   - `supersede-key`: keyの定義関数
   - `supersede-running-sessions!`: 同一keyの旧session close

3. `src/ceeker/tmux/pane.clj`
   - `list-pane-info`: cwdとpidの両方を返すように拡張
   - `find-agent-in-process-tree`: プロセスツリー探索
   - `close-stale-sessions!`: process tree livenessも考慮

4. テストファイル
   - `test/ceeker/hook/handler_test.clj`: pane-id関連テスト追加
   - `test/ceeker/state/store_test.clj`: supersede テスト追加
   - `test/ceeker/tmux/pane_test.clj`: process liveness テスト追加

5. ドキュメント
   - `docs/project_docs/T-20260304-004/plan.md`: 本ファイル

## 既知リスク
- `TMUX_PANE`はtmux環境でのみ利用可能。tmux外では空文字列→supersede無効
- Process tree探索は `/proc` 依存（Linux限定）。macOSでは `pgrep -P` を代替使用
- supersede keyにcwdを含むため、cwd変更後のresumeでは重複が残る可能性がある

## ロールバック方針
- 全変更はfeature branchで実施
- 既存のsessions.ednフォーマットと後方互換（`:pane-id`は追加フィールド）
- revert可能な単一PRとして提出

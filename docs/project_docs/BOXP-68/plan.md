# BOXP-68: hooks縮退とゼロコンフィグセットアップ

## 目的

- tmux 内の通常利用では、インストール後に ceeker を起動するだけで
  Claude Code / Codex / pi の session-file watcher が動作することを
  英日 README の標準手順にする。
- ceeker 向け hooks / notify は非 tmux セッションの登録、または
  pane 自動解決が曖昧な場合の補助に限定する。
- hook 入力の後方互換性と repo-local の品質チェック hooks は維持する。

## 変更方針

- 英日 README のセットアップを意味的に揃え、標準手順からユーザー設定
  ファイルの編集を外す。
- Optional の Claude Code 例は async の `SessionStart` / `Stop` のみにする。
- Codex `notify` は任意、Codex hooks は日常利用では非推奨、pi は追加設定
  不要と明記する。
- watcher が Claude Code の assistant JSONL、Codex の
  `task_complete.last_agent_message`、pi の assistant message を保持する既存
  動作を統合回帰テストで明示する。
- `TMUX_PANE` が空の Claude Code `SessionStart` と `Stop` が同じ
  session-id キーへ統合され、終了状態と最終メッセージを保持することを
  回帰テストで確認する。

## 検証

- 対象テストを先に追加して実行する。
- README の禁止イベント名と英日セクション構成を差分レビューする。
- `make ci` を実行する。
- 連続5日以上の watcher-only dogfooding は時間条件を短縮せず、実装 PR と
  分離してチケット Notes に開始条件・実績・未完了を記録する。

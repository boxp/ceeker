# fix/scroll-overflow-agent-list

## Problem

agent 数が増えると ceeker の一覧表示が terminal height を超え、下部の agent が見えなくなる。
特に table view / card view のどちらでも、選択中の agent が画面外に出ると操作不能になる。

## Goal

- terminal height を超える場合でも一覧を表示継続できること
- `j/k` ナビゲーション時に選択中の agent が常に可視範囲へ入ること
- 既存の table view / card view の描画仕様を大きく変えないこと

## Changes

### src/ceeker/tui/view.clj

1. terminal height に応じて session blocks を切り出す helper を追加
2. 選択中の block を基点に visible window を広げるロジックを追加
3. block 単位の行数計算を導入し、card view の複数行表示にも対応
4. `render` に `terminal-height` 引数を追加

### src/ceeker/tui/app.clj

1. JLine terminal から height を取得する `get-terminal-height` を追加
2. render 時に width と height の両方を view へ渡すよう更新

### test/ceeker/tui/view_test.clj

1. table view で overflow 時に選択行を含む window だけ表示されることを検証
2. card view で overflow 時に選択 card が可視になることを検証

### Makefile

1. `make test` 実行時に `TMUX` / `TMUX_PANE` を外し、隔離した `TMUX_TMPDIR` を使うよう変更
2. local で常駐 tmux server の影響を受けずにテストが走るよう調整

### src/ceeker/tmux/pane.clj

1. `read-proc-cmdline` に `ProcessHandle.Info` フォールバックを追加
2. `ps` が sandbox や macOS 環境で読めない場合でも process tree 判定を継続可能にした

### src/ceeker/tui/watcher.clj / test/ceeker/tui/watcher_test.clj

1. `WatchService` のイベントが来ない環境向けに mtime fallback を追加
2. watcher test は共有 state dir ではなく temp dir を使うよう変更

## Verification

- `clojure -M:test -n ceeker.tui.view-test`
- `clojure -M:test -n ceeker.tui.app-test`
- `make ci`

## Current Status

- 追加した TUI 関連テストは通過
- local だけ失敗していた 3 テストは、tmux 隔離と環境差吸収で解消
- local `make ci` は通過
- PR CI も通過しているため、変更は local / CI の両方で整合した状態

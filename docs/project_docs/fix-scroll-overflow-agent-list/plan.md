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

## Verification

- `clojure -M:test -n ceeker.tui.view-test`
- `clojure -M:test -n ceeker.tui.app-test`
- `make ci`

## Current Status

- 追加した TUI 関連テストは通過
- `make ci` は今回の変更と無関係な既存 3 テスト失敗で停止
- GitHub Actions 上では 2026-03-25 時点の `main` 最新 CI は通過しているように見えるため、PR 上の CI で差分影響を確認する

# startup-profiling-option Plan

## Goal

`ceeker` の起動時に、明示オプション指定時だけ
セットアップ区間の計測ログを stderr へ出せるようにする。

## Scope

- CLI に `--startup-profile` を追加
- `start-tui!` の初期化処理を計測
  - `create-terminal`
  - `create-watcher`
  - `start-pane-checker`
  - 合計時間
- 通常起動時の挙動は変えない

## Tests

- `test/ceeker/core_test.clj`
  - 新オプションが parse できること
- `test/ceeker/tui/app_test.clj`
  - オプション有効時だけ計測ログが出ること

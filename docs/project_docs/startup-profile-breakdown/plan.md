# startup-profile-breakdown Plan

## Goal

`--startup-profile` のログで `create-terminal` の内訳を確認できるようにする。

## Scope

- `create-terminal` を内部的に 2 段へ分解
  - terminal build
  - raw mode enter
- startup profile ログに細分化した時間を出力
- 既存の TUI 起動挙動は変えない

## Tests

- `test/ceeker/tui/input_test.clj`
  - `create-terminal-profile` が build/raw-mode/total を返すこと
- `test/ceeker/tui/app_test.clj`
  - startup profile ログに細分化した項目が出ること

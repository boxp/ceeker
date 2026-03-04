# T-20260303-014: ceeker 縦長ペイン向けレイアウト最適化

## 概要
縦長のtmuxペインでceekerの表レイアウトが崩れて可読性が低下する問題を解消する。
画面幅に応じたレスポンシブ表示を実装し、狭幅時にはコンパクトカード表示に切り替える。

## 変更対象ファイル

### 1. `src/ceeker/tui/view.clj` (主要変更)
- **幅判定ロジック**: `render` 関数に `terminal-width` パラメータを追加
  - 閾値: 80カラム未満でcompact card表示に切り替え
- **compact card表示**: `format-session-card` 関数を新規追加
  - 表示形式:
    ```
    ┌ abc123 [Claude] ● Running
    │ 12:34:56  my-project
    │ Working on feature...
    └─
    ```
  - 選択状態はreverse videoで強調
  - メッセージは画面幅に合わせて折り返し/トリム
- **render関数の拡張**: `display-mode` パラメータ追加 (:auto, :table, :card)
  - `:auto` - 幅に応じて自動切替（デフォルト）
  - `:table` - 常にテーブル表示
  - `:card` - 常にカード表示
- 既存のテーブル表示は変更なし

### 2. `src/ceeker/tui/app.clj` (状態管理・キー処理)
- `start-tui!` のloopに `display-mode` 状態を追加
- `handle-key-input` に `v` キー処理を追加（:auto → :table → :card の3状態切替）
- `render-screen` でterminal widthとdisplay-modeをviewに渡す
- JLineのterminal.getWidth()を使って幅取得

### 3. `src/ceeker/tui/input.clj` (変更なし)
- `v` キーは通常の文字入力として既に処理可能（char型で返される）

### 4. `README.md`
- キーバインド表に `v` キー（表示切替）を追加
- 「縦長ペイン時の表示仕様」セクションを追加

## 幅判定ロジック
```
terminal-width < 80 → compact card
terminal-width >= 80 → table
```
※ `v`キーで手動切替時は手動設定を優先

## compact card表示仕様（1セッション1ブロック）
```
┌ <session-id> <agent-badge> <status-badge>
│ <updated-time>  <worktree-name>
│ <message（幅に合わせてトリム）>
└─
```
- 選択中のカードはreverse videoで全行強調
- セッションIDは最大12文字
- メッセージは `(terminal-width - 4)` 文字でトリム

## テスト計画
- 既存テストは変更なし（view.cljのrender関数のシグネチャは後方互換）
- `clojure -M:format-check`, `clojure -M:lint`, `clojure -M:test` を通過

## キーバインド（既存 + 新規）
| キー | 動作 |
|------|------|
| j/↓ | 下へ移動 |
| k/↑ | 上へ移動 |
| Enter | tmuxペインへジャンプ |
| r | リフレッシュ |
| v | 表示切替 (auto→table→card) |
| q | 終了 |

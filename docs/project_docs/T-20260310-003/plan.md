# T-20260310-003: Stale Session / Pane Reuse 修正

## 不具合の再現条件

### Bug 1: プロセス終了済み + pane 残留 → セッションが永続 open
1. Claude Code/Codex をtmux pane内で起動
2. プロセスが終了（Stop hookが届かないケース含む）
3. paneは残ったまま、ターミナルにClaude出力が残る
4. `close-stale-sessions!` がプロセスツリーチェックでセッションを `:closed` にする
5. 直後の `refresh-session-states!` がターミナル出力を capture して `:running` パターンを検出
6. `capture-state-for-closed-session` がプロセス生存確認なしにセッションを再活性化
7. セッションが `:running` に戻り、永続 open 扱いになる

### Bug 2: 同一 pane で別セッション起動 → 旧セッション残留
1. Pane %42 で Claude Code を起動（cwd: `/foo`）→ session-A
2. Claude 終了後、`cd /bar` して再度 Claude を起動 → session-B
3. supersede-key が `[pane-id, agent-type, cwd]` で比較される
4. CWDが異なるためキーが不一致 → session-A は supersede されない
5. `stale-session?` でも pane %42 に Claude（session-B）が見つかるため session-A は `:alive` 判定
6. session-A が `:running` のまま残り続ける

## 根本原因

1. `capture-state-for-closed-session` がターミナル出力パターンのみで再活性化を判断し、プロセスツリーの生存確認をしていない
2. `supersede-key` に CWD が含まれているため、同一pane・同一agent-typeでもCWDが変わると supersede が発動しない

## 修正方針

### Fix 1: 再活性化にプロセスツリーチェックを追加
- `capture-state-for-closed-session` に `pane-infos` パラメータを追加
- `session-has-live-agent?` で `:alive` が確認できた場合のみ再活性化を許可
- `refresh-session-states!` で `list-pane-info` を一度だけ呼び出し、全セッションに共有

### Fix 2: supersede-key から CWD を除去
- `supersede-key` を `[pane-id agent-type]` のみに変更
- 同一pane・同一agent-typeの新セッションが旧セッションを確実に supersede

## テスト

### 変更したテスト
- `test-supersede-different-cwd-no-close` → `test-supersede-different-cwd-closes-old` に変更
  - CWDが異なっても同一pane+agent-typeなら旧セッションがsupersedされることを検証

### 追加したテスト
- `test-closed-session-dead-agent-not-reactivated`: プロセス死亡時にcaptureで `:running` 検出されても再活性化しない
- `test-closed-session-unknown-agent-not-reactivated`: プロセス生存が `:unknown` の場合も再活性化しない
- `test-closed-session-no-matching-pane-not-reactivated`: pane-idがpane-infosに存在しない場合再活性化しない

### 既存テスト互換性
- 既存の再活性化テストはプロセスが生きている前提で `pane/find-agent-in-tree` を `:found` にモック
- 既存の正常系テストは全て通過

## リスク/懸念
- supersede-keyからCWDを除去したため、同一paneで異なるCWDの正当な並行セッションはsupersedされる（ただしtmux paneは1プロセスしか実行できないため、これは正しい動作）
- プロセスツリーチェック追加により、`refresh-session-states!` で `list-pane-info` が追加で1回呼ばれるオーバーヘッドがある（10秒間隔なので問題なし）

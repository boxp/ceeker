# T-20260305-004: table整列・pane status誤判定・closed残留整理

## 原因分析

### 1. table列ずれ
- `format-session-line`で`format`の`%-Ns`パディングを使用しているが、`agent-badge`/`status-badge`にANSIエスケープシーケンスが含まれる
- `format`はANSIコードを可視文字としてカウントするため、パディングが不正確になる
- `column-headers`のフォーマット幅定義とデータ行の実際の表示幅が不一致

### 2. pane status誤判定
- `session-has-live-agent?`がcwdのみでpaneをマッチングしており、pane-idでの直接照合がない
- pane-idを持つセッションでも、cwd一致のpane群のプロセスツリーだけを検索するため、cwdが異なるpaneで動作中のagentを見逃す可能性がある
- 1回のチェックで即座にclosedに遷移し、短時間の観測ぶれに弱い

### 3. closed後の再アクティブ化
- closedセッションは`capturable-statuses` (`#{:running :idle :waiting}`)に含まれない
- `refresh-session-states!`がclosedセッションをスキップするため、paneで再稼働しても検知されない

### 4. closed残留
- pane消失後のclosedセッションを一覧から除去する仕組みがない
- closedセッションが永続的に表示され続ける

## 修正方針

### 1. table列ずれ修正 (view.clj)
- `strip-ansi`関数を追加し、ANSI除去後の表示幅を正しく計算
- `str-display-width`をANSI対応にする（ANSI除去→文字幅計算）
- `format-session-line`で`format`の`%-Ns`を廃止し、`pad-to-width`で手動パディング
- `column-headers`も`pad-to-width`ベースに統一

### 2. pane status誤判定修正 (pane.clj)
- `session-has-live-agent?`でpane-idによる直接マッチングを追加
- pane-id一致のpaneが見つかればそのプロセスツリーを優先チェック
- pane-id不一致時のフォールバックとしてcwdマッチングを維持

### 3. closed後の再アクティブ化 (pane.clj, store.clj)
- `refresh-session-states!`でclosed（非superseded）セッションも対象にする
- pane-idを持つclosedセッションのcapture-pane結果がrunning/idle/waitingなら状態を復旧
- `store/reactivate-closed-session!`を追加：closedかつ非supersededのセッションのみ状態更新

### 4. closed残留整理 (store.clj, app.clj)
- `purge-old-closed-sessions!`を追加：closed後一定時間(5分)経過かつpane不在のセッションを削除
- app.cljの定期チェック(`maybe-check-panes!`)でpurgeも実行

### 5. 回帰テスト
- view_test.clj: ANSI含む列整列テスト、CJK混在テスト
- store_test.clj: closed→reactivate遷移テスト、purgeテスト
- pane_test.clj: pane-idマッチングテスト

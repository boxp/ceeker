# T-20260304-002: ceeker session重複問題の設計方針

## 1. 背景と現象

### 問題概要
ceekerで同一tmux paneにおいてClaude Codeセッションを `close` → `resume`（または新規起動）するたびに、sessions.ednにセッションエントリが増殖し、TUI一覧の可読性が低下する。

### 根本原因
1. **session_idの一意性**: Claude Codeは起動ごとに新しい `session_id` を発行する。`claude --resume <id>` で再開しても、ceeker hookに渡される `session_id` は新規のものになる
2. **重複排除メカニズムの不在**: `update-session!` は `session-id` をキーにしたmerge動作のみ。同一pane/cwdでの再起動を検知する仕組みがない
3. **closedセッションの自動削除なし**: `stale-running?` は `:running` → `:closed` の遷移のみ。`:closed` / `:completed` セッションは永続的に蓄積される

### 再現手順

```
1. tmux pane A で `claude` を起動
   → ceeker hook が SessionStart を受信
   → sessions.edn に session-id=AAA, status=:running, cwd=/path/a を記録

2. Claude Code を終了（Ctrl+C or /exit）
   → ceeker hook が Stop/SessionEnd を受信
   → session-id=AAA が :completed に遷移

3. 同一 pane A で再度 `claude` を起動
   → 新しい session_id=BBB が発行される
   → sessions.edn に session-id=BBB, status=:running, cwd=/path/a を追加
   → session-id=AAA は :completed のまま残存

4. 上記を繰り返すたびに AAA, BBB, CCC, DDD... と増殖
```

### 期待される挙動の例（現状）

```
現状の sessions 一覧:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │  ← 最新(有効)
│ claude  │ completed│ /home/a    │ session ended    │  ← 旧(不要)
│ claude  │ completed│ /home/a    │ session ended    │  ← 旧(不要)
│ claude  │ closed   │ /home/a    │ pane closed      │  ← 旧(不要)
│ claude  │ running  │ /home/b    │ using: Edit      │  ← 別pane(有効)
└─────────┴──────────┴────────────┴──────────────────┘
```

---

## 2. 設計方針案

### 案A: CWDベース重複排除（SessionStart時に同一CWDの旧セッション自動削除）

**概要**: `SessionStart` イベント受信時に、同一cwdを持つ既存セッション（`:completed` / `:closed`）を自動削除する。

**実装箇所**: `ceeker.hook.handler/handle-hook!` または `ceeker.state.store`

**ロジック**:
```
SessionStart受信時:
  1. sessions.edn から同一cwd のエントリを検索
  2. status が :completed / :closed のものを削除
  3. status が :running のものは :closed に遷移（削除はしない）
  4. 新しいセッションを登録
```

**期待される挙動**:
```
セッション一覧（案A適用後）:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │  ← 最新のみ
│ claude  │ running  │ /home/b    │ using: Edit      │  ← 別pane
└─────────┴──────────┴────────────┴──────────────────┘
```

| 観点 | 評価 |
|------|------|
| 期待効果 | ◎ 同一cwdの重複を即座に排除 |
| 誤判定リスク | △ 同一cwdで意図的に複数セッション運用時に後方が消える |
| 実装コスト | ◎ 小（store.cljに関数追加 + handler.cljで呼び出し） |
| 既存互換性 | ○ sessions.ednフォーマット変更なし |
| 運用インパクト | ○ 即座にクリーンな一覧になる |

**ロールバック**: 該当コードを削除するだけで元の挙動に戻る。

---

### 案B: TTLベース自動クリーンアップ（completed/closedセッションの時間経過削除）

**概要**: `:completed` / `:closed` 状態のセッションに対してTTL（Time To Live）を設定し、一定時間経過後に自動削除する。

**実装箇所**: `ceeker.state.store` + `ceeker.tui.app` (TUIループでのGC)

**ロジック**:
```
定期GC（TUIループまたはhook呼び出し時）:
  1. sessions.edn の全エントリを走査
  2. :completed / :closed かつ last-updated から N分経過 → 削除
  3. TTL設定例: completed=30分, closed=5分
```

**期待される挙動**:
```
セッション一覧（案B適用後、TTL経過前）:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │
│ claude  │ completed│ /home/a    │ session ended    │  ← TTL待ち
│ claude  │ running  │ /home/b    │ using: Edit      │
└─────────┴──────────┴────────────┴──────────────────┘

（TTL経過後）:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │
│ claude  │ running  │ /home/b    │ using: Edit      │
└─────────┴──────────┴────────────┴──────────────────┘
```

| 観点 | 評価 |
|------|------|
| 期待効果 | ○ 時間経過で確実にクリーンアップ |
| 誤判定リスク | ◎ 低（完了/閉鎖済みのみ対象） |
| 実装コスト | ○ 中（GC関数 + TTL設定 + 定期実行フック） |
| 既存互換性 | ◎ sessions.ednフォーマット変更なし |
| 運用インパクト | △ TTL期間中は重複が見える |

**ロールバック**: GC関数の呼び出しを削除するだけ。

---

### 案C: pane_idベースIdentity（tmux pane IDによるセッション紐付け）

**概要**: セッション登録時に `tmux display-message -p '#{pane_id}'` でpane IDを取得し、同一pane_idの旧セッションを置換する。

**実装箇所**: `ceeker.hook.handler` + `ceeker.state.store`（pane_idフィールド追加）

**ロジック**:
```
Hook受信時:
  1. tmux display-message -p '#{pane_id}' で現在のpane_id取得
  2. session-data に :pane-id を追加
  3. SessionStart時: 同一pane_idの既存セッションを検索→削除→新規登録
  4. 他イベント時: pane_idが一致すれば更新、不一致なら新規登録
```

**期待される挙動**:
```
セッション一覧（案C適用後）:
┌─────────┬──────────┬────────────┬──────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Pane     │ Last Message     │
├─────────┼──────────┼────────────┼──────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ %42      │ session started  │  ← pane_idで一意
│ claude  │ running  │ /home/b    │ %43      │ using: Edit      │  ← 別pane
└─────────┴──────────┴────────────┴──────────┴──────────────────┘
```

| 観点 | 評価 |
|------|------|
| 期待効果 | ◎ pane単位で厳密に一意化 |
| 誤判定リスク | ○ pane_id再利用時（pane destroy→create）に微小なリスク |
| 実装コスト | △ 中〜大（tmuxコマンド追加呼び出し、hook側の環境変数取得、store拡張） |
| 既存互換性 | △ sessions.ednにpane-idフィールド追加（後方互換あり、古いデータは無視） |
| 運用インパクト | ◎ pane単位で最新セッションのみ表示 |

**ロールバック**: pane_idフィールドを無視するだけで元の挙動に戻る。

**課題**:
- hookはClaude Code/Codexのhook機構経由で呼ばれるため、`$TMUX_PANE` 環境変数が利用可能かどうかはエージェントのhook実行環境に依存する
- tmuxコマンドが呼べない環境では機能しない

---

### 案D: セッションLineage（親子関係による重複管理）

**概要**: `claude --resume <old-session-id>` を実行する際に、旧セッションIDを新セッションの `parent-session-id` として記録し、親子チェーンで重複を管理する。

**実装箇所**: `ceeker.hook.handler` + `ceeker.state.store`

**ロジック**:
```
SessionStart受信時:
  1. payload に resume_session_id があれば parent-session-id として記録
  2. parent のセッションを :superseded に遷移
  3. TUI表示時: :superseded は非表示（またはグレーアウト）
  4. 親子チェーンで最新セッションのみをアクティブ表示
```

| 観点 | 評価 |
|------|------|
| 期待効果 | ○ resume チェーンを正確に追跡 |
| 誤判定リスク | ◎ 明示的な親子関係のため誤判定なし |
| 実装コスト | × 大（Claude Code側のhook payloadに依存、resume_session_idが現在含まれていない可能性大） |
| 既存互換性 | △ sessions.ednにparent-session-idフィールド追加 |
| 運用インパクト | ○ resume時のみ効果あり、新規起動の重複には対応しない |

**期待される挙動**:
```
セッション一覧（案D適用後、resume時）:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │  ← resume後の最新
│ claude  │ running  │ /home/b    │ using: Edit      │  ← 別pane
└─────────┴──────────┴────────────┴──────────────────┘
※ 旧セッション(AAA)は :superseded に遷移し、TUI一覧では非表示

セッション一覧（案D適用後、新規起動時＝resumeではない場合）:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │  ← 新規
│ claude  │ completed│ /home/a    │ session ended    │  ← 旧(残存する)
│ claude  │ running  │ /home/b    │ using: Edit      │  ← 別pane
└─────────┴──────────┴────────────┴──────────────────┘
※ resume以外の新規起動では親子関係が不明なため、旧セッションは残存
```

**ロールバック**: lineageフィールドを無視し、:superseded を通常表示に戻す。

**課題**:
- Claude Code の hook payload に `resume_session_id` が含まれるかは未確認（公式仕様に依存）
- 新規起動（resume以外）での同一cwd重複には対応できない
- 実装の複雑性が高い

---

### 案E: ハイブリッド（案A + 案B の組み合わせ）

**概要**: SessionStart時のCWDベース重複排除（案A）と、TTLベース自動クリーンアップ（案B）を組み合わせる。

**実装箇所**: `ceeker.hook.handler` + `ceeker.state.store`

**ロジック**:
```
1. SessionStart受信時（案Aの即時排除）:
   - 同一cwdの :completed / :closed セッションを削除
   - 同一cwdの :running セッションは :closed に遷移（削除はしない）

2. 定期GC（案BのTTLクリーンアップ）:
   - :completed が30分経過 → 削除
   - :closed が5分経過 → 削除
   - SessionStart以外のタイミングでも蓄積を防止
```

**期待される挙動**:
```
セッション一覧（案E適用後）:
┌─────────┬──────────┬────────────┬──────────────────┐
│ Agent   │ Status   │ CWD        │ Last Message     │
├─────────┼──────────┼────────────┼──────────────────┤
│ claude  │ running  │ /home/a    │ session started  │  ← 即時クリーン
│ claude  │ running  │ /home/b    │ using: Edit      │
└─────────┴──────────┴────────────┴──────────────────┘
```

| 観点 | 評価 |
|------|------|
| 期待効果 | ◎ 即時排除 + 定期清掃の二重保証 |
| 誤判定リスク | △ 案Aと同様（同一cwdの意図的複数セッション） |
| 実装コスト | ○ 中（案A + 案B、ただし各単体より若干多い） |
| 既存互換性 | ◎ sessions.ednフォーマット変更なし |
| 運用インパクト | ◎ 最もクリーンな一覧を維持 |

**ロールバック**: 各機能を個別にON/OFF可能。

---

## 3. 比較表

| 観点 | 案A: CWDベース排除 | 案B: TTLクリーンアップ | 案C: pane_id紐付け | 案D: Lineage | 案E: ハイブリッド |
|------|:---:|:---:|:---:|:---:|:---:|
| **期待効果** | ◎ | ○ | ◎ | ○ | ◎ |
| **誤判定リスク** | △ | ◎ | ○ | ◎ | △ |
| **実装コスト** | ◎ 小 | ○ 中 | △ 中〜大 | × 大 | ○ 中 |
| **既存互換性** | ◎ | ◎ | △ | △ | ◎ |
| **運用インパクト** | ○ | △ | ◎ | ○ | ◎ |
| **ロールバック容易性** | ◎ | ◎ | ○ | ○ | ◎ |
| **外部依存** | なし | なし | tmux環境変数 | Claude Code payload | なし |

---

## 4. 推奨案: 案E（ハイブリッド）を段階導入

### 推奨理由
- **案A単体の弱点を案Bが補完**: SessionStart以外のタイミング（hookが呼ばれない手動終了等）で残った残骸もTTLで自動削除される
- **実装コストが許容範囲**: 各機能が独立しており、段階的に導入・テスト可能
- **sessions.ednのフォーマット変更が不要**: 既存データとの互換性が完全に保たれる
- **ロールバックが容易**: 各機能を個別にON/OFF可能

### 同一cwdの意図的複数セッション問題への対処
- 実運用上、同一cwdで複数のClaude Codeセッションを同時にrunningにするケースは極めて稀
- cwdが同一 かつ 両方 :running の場合は削除しない（新しいSessionStartで旧:runningを:closedに遷移させるのみ）
- 結果的に「同一cwd最新1つ + TTL未満の終了セッション」のみが表示される（TTL: completed=30分, closed=5分）

---

## 5. 段階導入プラン

### Phase 1: CWDベース重複排除（案A部分）

**スコープ**:
1. `ceeker.state.store` に `evict-stale-for-cwd!` 関数を追加
   - SessionStart時に同一cwdの `:completed` / `:closed` セッションを削除
   - 同一cwdの `:running` セッションは `:closed` に遷移
2. `ceeker.hook.handler/handle-hook!` から `SessionStart` 時のみ呼び出し
3. テスト追加: `store_test.clj` に evict 関連テスト

**実装量見積り**: store.clj に30行程度 + handler.clj に5行程度 + テスト50行程度

**テスト観点**:
- SessionStart時に同一cwdのcompleted/closedが削除されること
- 異なるcwdのセッションは影響を受けないこと
- 同一cwdでrunningのセッションはclosedに遷移すること

### Phase 2: TTLベース自動クリーンアップ（案B部分）

**スコープ**:
1. `ceeker.state.store` に `gc-expired-sessions!` 関数を追加
   - `:completed` → 30分後に削除
   - `:closed` → 5分後に削除
   - TTL値はdefで設定（将来的にCLIオプション化可能）
2. `ceeker.tui.app/maybe-check-panes!` の既存周期チェックにGCも追加
3. `ceeker.hook.handler/handle-hook!` にもGC呼び出しを追加（TUI非起動時の対策）

**実装量見積り**: store.clj に25行程度 + app.clj/handler.clj に5行程度 + テスト40行程度

**テスト観点**:
- TTL経過後にcompleted/closedが削除されること
- TTL未経過のセッションは保持されること
- running/waitingセッションはTTLの影響を受けないこと

---

## 6. 追加の観測ログ項目（提案）

Phase 1/2の導入効果を測定するため、以下のログ出力を追加することを推奨:

| ログ項目 | 出力タイミング | 目的 |
|----------|--------------|------|
| `evicted N sessions for cwd=X` | SessionStart時の重複排除実行時 | Phase 1の効果測定 |
| `gc: removed N expired sessions` | GC実行時（削除があった場合のみ） | Phase 2の効果測定 |
| `sessions: total=N running=M` | TUI起動時 | セッション数の推移把握 |

出力先は既存の `*err*` バインディング（stderr）に統一する。

---

## 7. リスクとロールバック

### リスク1: 同一cwdでの意図的複数セッション
- **影響**: Phase 1でSessionStart時に旧セッションが削除される
- **軽減策**: `:running` 状態のセッションは削除せず `:closed` に遷移のみ
- **ロールバック**: `evict-stale-for-cwd!` の呼び出しをコメントアウト

### リスク2: TTL設定が短すぎて有用な履歴が消える
- **影響**: ユーザーが参照したいcompletedセッションが消える
- **軽減策**: TTLを保守的に設定（completed=30分、closed=5分）
- **ロールバック**: `gc-expired-sessions!` の呼び出しを削除

### リスク3: GCのパフォーマンス影響
- **影響**: sessions.ednが大きい場合にGC処理が重くなる
- **軽減策**: Phase 1の重複排除によりセッション数自体が抑制される
- **ロールバック**: GC頻度を下げる（check-intervalの倍数にする）

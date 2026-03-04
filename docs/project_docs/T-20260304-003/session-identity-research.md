# セッション Identity パターン調査: cmux / agentoast / claude-squad / agent-deck

> 調査日: 2026-03-04

## 1. 調査背景

ceeker では session 重複問題が発生している。根本原因は以下の2点:

1. **セッションIDが取得できない場合にランダムUUIDが生成される** → 同一セッションに複数エントリが作られる
2. **liveness判定がcwdの完全一致** → 同じcwdで複数エージェントが動作する場合に誤判定

本調査では、近いUX/セッション管理を持つ外部実装のパターンを収集し、ceeker への適用方針を提案する。

## 2. 調査対象一覧

| ツール | リポジトリ | Stars (概算) | 言語 | 特徴 |
|--------|-----------|-------------|------|------|
| cmux (craigsc) | [craigsc/cmux](https://github.com/craigsc/cmux) | ~330 | Bash | git worktreeベース、tmux非依存 |
| cmux (theforager) | [theforager/cmux](https://github.com/theforager/cmux) | - | Bash | tmux sessionベース、TUI付き |
| agentoast | [shuntaka9576/agentoast](https://github.com/shuntaka9576/agentoast) | - | Rust/TypeScript | macOSメニューバー通知、プロセスツリー検出 |
| claude-squad | [smtg-ai/claude-squad](https://github.com/smtg-ai/claude-squad) | ~6.2k | Go | git worktree + tmux、Pause/Resume |
| agent-deck | [asheshgoplani/agent-deck](https://github.com/asheshgoplani/agent-deck) | ~1.3k | Go | SQLite永続化、Claude Session ID統合、自動GC |

## 3. 各実装の詳細分析

### 3.1 cmux (craigsc/cmux)

#### セッション識別キー
- **git branch名**がセッションの一意キー
- `_cmux_safe_name()` でスラッシュをハイフンに変換（例: `feature/foo` → `feature-foo`）
- pane ID、PID、CWDはセッション識別に不使用

#### Resume時の扱い
- `cmux new <branch>`: 新規 worktree + 新規 claude セッション
- `cmux start <branch>`: 既存 worktree + `claude -c`（`--continue`）で会話継続
- **cmux自体はセッション状態を持たない**。Claude CLIの `--continue` 機能に完全依存

#### Dead Session清掃
- **自動GCなし**。全て手動（`cmux rm` / `cmux rm --all`）
- Claudeプロセスの生死は追跡しない
- worktreeディレクトリの存在 = セッションの存在

#### 重複表示抑制
- **データ側**: git worktreeの一意性に依存。`cmux new` は冪等（worktree存在時は作成スキップ）
- **UI側**: `cmux ls` は `git worktree list` の単純フィルタ

#### トレードオフ
- (+) ファイルシステムレベルの完全分離でエージェント間衝突ゼロ
- (+) マシン再起動後も worktree + `claude -c` で状態復元可能
- (+) tmux非依存
- (-) ディスク使用量が大きい
- (-) リアルタイムステータス表示なし

### 3.2 cmux (theforager/cmux)

#### セッション識別キー
- **tmux session名**がIDキー: `cmux@<parent-dir>@<child-dir>`
- 同じパスで複数セッション作成時は `-2`, `-3` とサフィックス追加
- `CMUX_SESSION`, `CMUX_DIR`, `CMUX_TITLE` をtmux環境変数として格納

#### Resume時の扱い
- 明示的なresumeコマンドなし
- `cmux attach <name>` で既存tmux sessionにアタッチ（Claude再起動なし）
- 会話継続はtmux内でClaudeプロセスが生存していることに依存

#### Dead Session清掃
- **手動のみ**: `cmux kill <name>` またはTUIの `d` キー
- 自動GCなし

#### 重複表示抑制
- **データ側**: セッション名のカウンタサフィックスで一意性保証
- **UI側**: parentディレクトリでグルーピング表示

#### 注目機能: リアルタイムステータス検出
- pane出力のヒューリスティクス判定（ERROR/RUNNING/WAITING/IDLE）
- `tmux capture-pane -S -5` で直近5行をキャプチャして正規表現マッチ

### 3.3 agentoast (shuntaka9576/agentoast)

#### セッション識別キー
- **永続的なセッションエンティティを持たない**
- 通知の識別: `tmux_pane`（環境変数 `$TMUX_PANE`）
- セッション検出: `pane_id` + `pane_pid` + プロセスツリーDFS

#### Resume時の扱い
- **系譜の継承なし**。毎ポーリングでゼロからフルスキャン再構築
- 同じtmuxペインでエージェントを再起動すれば自動再検出

#### Dead Session清掃
- **プロセス消滅で自動消去**: エージェントプロセスが終了 → `detect_agent()` が見つけられない → リストから即消去
- 通知DB: アプリ起動時に `DROP TABLE` で全消去（揮発的設計）
- Active Pane Suppression: ユーザーが該当ペインを見ている場合は通知を即削除

#### 重複表示抑制
- **データ側（最重要）**: `INSERT` 前に同一 `tmux_pane` の既存通知を `DELETE`（トランザクション内）
  → **1ペイン = 最大1通知** を保証
- **UI側**: セッションと通知を `pane_id` をキーにマージ。1ペインにつき最新通知1つ
- **Watcher側**: `LAST_KNOWN_ID` によるインクリメンタル検出 + 300msデバウンス

#### トレードオフ
- (+) ステートレス設計で状態不整合を回避
- (+) 疎結合（CLI↔App間はSQLiteファイルのみ）
- (+) 自然なdedup（tmux_paneキーのoverwrite-on-insert）
- (-) tmux + macOS限定
- (-) 画面パース（スピナー文字等）のハードコードがUI変更で壊れるリスク
- (-) ポーリングコスト（ペイン数に比例）

### 3.4 claude-squad (smtg-ai/claude-squad)

#### セッション識別キー
- **Title（ユーザー入力）** がセッションの一意キー
- tmux名: `claudesquad_` + sanitized title
- worktreeパス: `~/.claude-squad/worktrees/{branchName}_{unixNano_hex}`
- 永続化: `~/.claude-squad/state.json`

#### Resume時の扱い
- 起動時に `state.json` から復元
- Running状態: 既存tmuxセッションにreattach
- Paused状態: メモリに読み込むだけ
- **Pause/Resume**: Pause時にworktree内変更をcommit→worktree削除。Resume時にworktree再作成→tmux再接続
- **会話継続はtmuxセッション生存に依存**（Claude CLIのセッション継続は不使用）

#### Dead Session清掃
- **自動GCなし**。手動 `D` キーまたは `cs reset` のみ
- `TmuxAlive()` チェックはあるが自動削除はしない

#### 重複表示抑制
- tmux名が同一 → `tmux new-session` がエラーで失敗（衝突防止）
- インスタンス上限: `GlobalInstanceLimit = 10`（ハードコード）

#### トレードオフ
- (+) シンプルなアーキテクチャ（JSON 1ファイル、Go単一バイナリ）
- (+) git worktreeによる完全分離
- (+) Pause/Resumeでディスク節約可能
- (-) Titleベースで rename不可
- (-) tmux死亡時に会話履歴が完全消失
- (-) 自動GCなし

### 3.5 agent-deck (asheshgoplani/agent-deck)

#### セッション識別キー
- **ランダム生成ID**が真のセッションID
- tmux名: `agentdeck_` + sanitized title + `_` + 8文字ランダムhash → **同名セッション共存可能**

#### Resume時の扱い
- 起動時にSQLite (WAL mode) から復元
- **Claude Code の `--session-id` / `--resume` を活用**した真の会話継続
- orphanedなtmuxセッションの自動発見・回収機能あり
- **Fork機能**: 既存会話を引き継いだ新セッションを分岐可能

#### Dead Session清掃
- **15分間隔のバックグラウンドメンテナンスワーカー**
  - ログpruning
  - バックアップ世代管理（最新3つを保持）
  - 肥大化セッションのアーカイブ（30MB超 & 24時間経過）
  - 孤立Dockerコンテナの除去
- tieredポーリング: idle=低頻度、running/waiting=高頻度

#### 重複表示抑制
- **データ側**: tmux名のランダムサフィックスで物理的に重複排除
- **UI側**: GroupPath + Title で表示

#### トレードオフ
- (+) Claude Session IDによる真の会話継続性
- (+) Fork機能で会話分岐可能
- (+) SQLite WAL modeで安全な並行アクセス
- (+) 自動GC・orphan回収
- (-) 複雑性が高い（instance.goだけで4000行超）
- (-) 依存関係が多い

## 4. 比較マトリクス

### 4.1 セッション Identity

| 観点 | craigsc/cmux | theforager/cmux | agentoast | claude-squad | agent-deck | **ceeker (現状)** |
|------|-------------|----------------|-----------|-------------|------------|------------------|
| **IDキー** | git branch名 | tmux session名 | tmux pane ID | Title (入力) | ランダムID | session_id (agent提供) |
| **永続性** | ファイルシステム | tmux server | なし (揮発) | JSON file | SQLite | EDNファイル |
| **一意性保証** | git worktreeの一意性 | カウンタサフィックス | pane IDの一意性 | tmux名衝突でエラー | ランダムサフィックス | **なし (UUID fallback)** |
| **ID欠損時** | N/A (branch名必須) | N/A (CWDから生成) | N/A (env var) | N/A (ユーザー入力) | N/A (自動生成) | **ランダムUUID → 重複** |
| **pane ID保持** | なし | tmux env var | `$TMUX_PANE` | tmux session名に内包 | tmux session名に内包 | **なし（未収集）** |

> **注**: ceeker は現時点で `pane-id` をセッションデータに保持していない。変更2・3の前提として、hookイベントから `$TMUX_PANE` を取得し `make-session` に `:pane-id` フィールドを追加する変更が必要（変更0として後述）。

### 4.2 Resume / 会話継続

| 観点 | craigsc/cmux | theforager/cmux | agentoast | claude-squad | agent-deck | **ceeker (現状)** |
|------|-------------|----------------|-----------|-------------|------------|------------------|
| **方式** | `claude -c` | tmuxプロセス保持 | N/A | tmuxプロセス保持 | `--session-id --resume` | N/A |
| **リブート耐性** | あり | なし | N/A | なし | あり | N/A |

### 4.3 Dead Session 清掃

| 観点 | craigsc/cmux | theforager/cmux | agentoast | claude-squad | agent-deck | **ceeker (現状)** |
|------|-------------|----------------|-----------|-------------|------------|------------------|
| **自動GC** | なし | なし | プロセス消滅で即消去 | なし | 15分間隔ワーカー | **cwdベースliveness** |
| **判定基準** | worktree存在 | tmux session存在 | プロセスツリーDFS | tmux session存在 | tmux + メンテナンス | **cwdがペイン一覧に存在するか** |
| **誤判定リスク** | 低 | 低 | 低 | 低 | 低 | **高 (同cwd複数ペイン)** |

### 4.4 重複表示抑制

| 観点 | craigsc/cmux | theforager/cmux | agentoast | claude-squad | agent-deck | **ceeker (現状)** |
|------|-------------|----------------|-----------|-------------|------------|------------------|
| **データ側** | worktree冪等性 | カウンタ | paneキーoverwrite | tmux名衝突エラー | ランダムサフィックス | **なし** |
| **UI側** | フィルタ表示 | グルーピング | マージ表示 | なし | GroupPath表示 | **なし** |

## 5. 設計パターンの抽出

### パターン A: Composite Key Identity（複合キー方式）

**由来**: agentoast, theforager/cmux

セッション識別子として単一フィールドではなく、複数フィールドの組み合わせ（複合キー）を使用する。

```
identity = (tmux_pane_id, agent_type, cwd)
```

- **利点**: pane IDはtmux内で一意かつ安定。cwdとagent_typeを組み合わせることで同一ペインでの再起動を区別可能
- **欠点**: pane IDはtmux再起動で変わる。永続的な識別が必要な場合はsession_idとの併用が必要
- **ceekerへの適用**: `$TMUX_PANE` をhookイベントの環境変数として取得し、session_idの補完/代替として使用

### パターン B: Supersede-per-Key Dedup（キー単位置換方式）

**由来**: agentoast（DELETE→INSERT方式）を ceeker のEDNストアに適応

同一補助キー（tmux_pane等）を持つ既存セッションを「置換済み」としてマークし、最新セッションのみを `:running` として保持する。agentoast の原型は SQL の DELETE→INSERT だが、ceeker のEDNファイルストアでは「旧セッションを `:closed` に遷移させる」ことで同等の効果を得る。

```
;; 擬似コード（EDNストア向け）
for each existing_session where pane-id == new_session.pane-id:
  if existing_session.session-id != new_session.session-id:
    existing_session.agent-status = :closed
    existing_session.last-message = "superseded"
upsert new_session
```

- **利点**: ステートマシンなしで自然にdedupが実現。旧セッション情報も参照可能
- **欠点**: closedセッションが蓄積するため、定期的なパージが別途必要
- **ceekerへの適用**: `update-session!` で同一 `pane-id` の他セッションを `:closed` 化してから新セッションを `merge`

### パターン C: Process Tree Liveness（プロセスツリー生存確認方式）

**由来**: agentoast, agent-deck

cwdの一致ではなく、tmux pane PIDからのプロセスツリーDFSでエージェントプロセスの実在を確認する。

```
1. tmux list-panes -a → (pane_id, pane_pid, cwd)
2. ps -eo pid,ppid,comm → プロセスツリー構築
3. 各pane_pidからDFSでclaude/codex等のプロセスを探索
4. 見つかった → alive、見つからない → dead
```

- **利点**: cwdベースの誤判定を完全に回避。同cwd複数ペインでも正確に識別
- **欠点**: `ps` + `tmux list-panes` の外部コマンド呼び出しコスト
- **ceekerへの適用**: `close-stale-sessions!` のcwdベース判定をプロセスツリー判定に置換

### パターン D: Agent-Provided Session ID + Pane Fallback（エージェント提供ID＋ペインフォールバック方式）

**由来**: agent-deck, ceeker現状の改良

エージェントが提供するsession_idを優先使用し、取得できない場合はtmux pane IDをフォールバックとして使用する。ランダムUUID生成を廃止。

```
session_key = agent_session_id || tmux_pane_id || (cwd + ":" + agent_type)
```

- **利点**: エージェント側のセッション概念と整合。フォールバックでも一意性を維持
- **欠点**: フォールバック時のセッション継続性が弱い
- **ceekerへの適用**: `make-session` のUUIDフォールバックを `$TMUX_PANE` に変更

### パターン E: Tiered Polling with Event-Driven Refresh（段階的ポーリング＋イベント駆動方式）

**由来**: agent-deck, agentoast

全セッションを同一頻度でポーリングするのではなく、状態に応じてチェック頻度を変える。加えてファイル変更イベントで即時更新。

```
running/waiting → 高頻度ポーリング (1-2秒)
idle            → 低頻度ポーリング (10-30秒)
completed       → ポーリング不要
+ inotify/FSウォッチで sessions.edn 変更時に即時リフレッシュ
```

- **利点**: リソース効率が良い。体感レスポンスも向上
- **ceekerへの適用**: 既存の `maybe-check-panes!` の固定20tickを状態別に調整

## 6. ceeker への推奨適用方針

### 推奨パターン: D + B + C の組み合わせ

**「Agent-Provided Session ID + Pane Fallback」+「Supersede-per-Key Dedup」+「Process Tree Liveness」**

この組み合わせを推奨する理由:

1. **パターンD（ID + Pane Fallback）** がセッション重複問題の根本原因（ランダムUUID）を直接解決
2. **パターンB（Supersede-per-Key）** が万が一の重複を安全に吸収
3. **パターンC（Process Tree Liveness）** がcwdベース判定の誤判定を解消

なお、全ての変更の前提として `:pane-id` フィールドのセッションデータへの追加（変更0）が必要。

### 最小変更案

#### 変更0: pane-id の収集・保存（変更1-3の前提条件）

**対象ファイル**: `src/ceeker/hook/handler.clj`

現在の `make-session` は `:pane-id` を保持していない。変更1-3の前提として、hookイベント処理時に `$TMUX_PANE` 環境変数を取得し、セッションデータに含める必要がある。

```clojure
;; make-session に :pane-id フィールドを追加
(defn- make-session [session-id agent-type status cwd message]
  {:session-id   session-id
   :agent-type   agent-type
   :agent-status status
   :pane-id      (System/getenv "TMUX_PANE")  ;; ← 追加
   :cwd          (or cwd "")
   :last-message (or message "")
   :last-updated (current-timestamp)})
```

この変更により、以降の変更1-3で `:pane-id` を利用したdedup・liveness判定が可能になる。

#### 変更1: セッションIDフォールバックの改善（パターンD）

**対象ファイル**: `src/ceeker/hook/handler.clj`

```clojure
;; Before (現状): session-id が取得できない場合にランダムUUID
(defn- extract-session-id [payload agent-type]
  (or (:session_id payload)
      (when (= agent-type :codex) (:thread-id payload))
      (str (java.util.UUID/randomUUID))))  ;; ← 問題箇所

;; After (提案): tmux pane IDをフォールバック、最終手段は cwd+agent-type
(defn- extract-session-id [payload agent-type]
  (or (:session_id payload)
      (when (= agent-type :codex) (:thread-id payload))
      (System/getenv "TMUX_PANE")  ;; ← pane IDでフォールバック
      (str (:cwd payload) ":" (name agent-type))))  ;; ← 最終手段
```

#### 変更2: セッション更新時のdedup（パターンB）

**対象ファイル**: `src/ceeker/state/store.clj`

```clojure
;; Before (現状): session-id のみでmerge
(defn update-session! [session]
  (with-lock
    (let [sessions (load-sessions)
          updated (update-in sessions [:sessions (:session-id session)]
                    merge session)]
      (save-sessions updated))))

;; After (提案): 同一 pane-id の古いセッションを除去してから更新
(defn update-session! [session]
  (with-lock
    (let [sessions (load-sessions)
          pane-id (:pane-id session)
          ;; 同一pane-idの他セッションをrunning以外に変更（重複排除）
          cleaned (if pane-id
                    (update sessions :sessions
                      (fn [m] (reduce-kv
                        (fn [acc k v]
                          (if (and (not= k (:session-id session))
                                   (= (:pane-id v) pane-id)
                                   (= (:agent-status v) :running))
                            (assoc acc k (assoc v :agent-status :closed
                                                  :last-message "superseded by new session"))
                            (assoc acc k v)))
                        {} m)))
                    sessions)
          updated (update-in cleaned [:sessions (:session-id session)]
                    merge session)]
      (save-sessions updated))))
```

#### 変更3: Liveness判定のプロセスツリーベース化（パターンC）

**対象ファイル**: `src/ceeker/tmux/pane.clj`

```clojure
;; Before (現状): cwdの完全一致で生存判定
(defn list-pane-cwds []
  ;; tmux list-panes -a -F "#{pane_current_path}" → cwdのセットを返す

;; After (提案): pane_id + pane_pid を取得し、プロセスツリーでエージェント検出
(defn list-active-agent-panes []
  ;; 1. tmux list-panes -a -F "#{pane_id} #{pane_pid} #{pane_current_path}"
  ;; 2. ps -eo pid,ppid,comm でプロセスツリー構築
  ;; 3. 各pane_pidからDFSでclaude/codexプロセスを検索
  ;; 4. {pane-id {:cwd "..." :agent-type :claude-code :pid 12345}} を返す
  )
```

```clojure
;; store.clj の stale-running? も対応変更
;; Before: cwdがpane-cwdsに含まれるか
;; After: session の pane-id が active-agent-panes に含まれるか
(defn- stale-running? [session active-panes]
  (and (= (:agent-status session) :running)
       (some? (:pane-id session))
       (not (contains? active-panes (:pane-id session)))))
```

### 変更の優先順位

| 優先度 | 変更 | 効果 | 工数 |
|--------|------|------|------|
| **P0** | 変更0: pane-id収集・保存 | 変更1-3の前提条件。`$TMUX_PANE` をセッションデータに含める | 極小 |
| **P0** | 変更1: IDフォールバック改善 | 重複エントリ発生の根本原因を解消 | 小 |
| **P1** | 変更2: Supersede-per-Key dedup | 万が一の重複を安全に吸収 | 小 |
| **P2** | 変更3: プロセスツリーliveness | cwdベース判定の誤判定を解消 | 中 |

変更0+1のみの適用でも重複問題の大部分は解消される見込み。変更2・3は追加の堅牢性を提供する。

## 7. 参考リンク

- [craigsc/cmux](https://github.com/craigsc/cmux) - git worktreeベースのClaude Code multiplexer
- [theforager/cmux](https://github.com/theforager/cmux) - tmux sessionベースのClaude Code manager
- [shuntaka9576/agentoast](https://github.com/shuntaka9576/agentoast) - macOS通知 + tmuxペイン統合
- [smtg-ai/claude-squad](https://github.com/smtg-ai/claude-squad) - Go製のClaude Code session manager (6.2k stars)
- [asheshgoplani/agent-deck](https://github.com/asheshgoplani/agent-deck) - 高機能なClaude Code session manager (1.3k stars)

# T-20260304-012: agentoast実装分析にもとづく ceeker 状態更新改善

## agentoast 分析結果

### agentoast の状態更新メカニズム
agentoast は以下のマルチレイヤー設計で中間状態を追跡している:

1. **tmux capture-pane によるリアルタイム状態検出**
   - スピナー文字 (`✢✽✶✳✻·`) + "esc to interrupt" → Running
   - プロンプト文字 (`❯`, `$ `, `% `) → Idle
   - 質問/権限ダイアログ ("Enter to select") → Waiting
   - モード検出 ("plan mode on") → agent_modes

2. **Hook イベント → DB INSERT → ファイル監視**
   - 300ms debounce のファイルウォッチャー
   - 5秒間隔のポーリングフォールバック

3. **状態優先順位**: スピナー > ダイアログ > プロンプト+通知あり > プロンプト+通知なし

### ceeker のギャップ
| 機能 | agentoast | ceeker 現状 |
|------|-----------|------------|
| Hook イベント駆動 | ○ | ○ |
| tmux capture-pane 状態検出 | ○ | ✗ |
| :waiting 状態遷移 | ○ (ダイアログ検出) | ✗ (定義のみ) |
| :idle 状態遷移 | ○ (プロンプト検出) | ✗ (定義のみ) |
| 定期的な状態リフレッシュ | ○ (get_sessions) | △ (liveness checkのみ) |
| デバウンス/重複抑止 | ○ (300ms) | ✗ |

## 実装計画

### 1. 新規: `ceeker.tmux.capture` 名前空間
tmux pane のコンテンツをキャプチャし、エージェントの中間状態を検出する。

```clojure
;; 主要関数
(capture-pane-content pane-id)     ;; tmux capture-pane -p -t <pane-id>
(detect-claude-state lines)        ;; Claude Code 状態検出
(detect-codex-state lines)         ;; Codex 状態検出
(detect-agent-state pane-id agent-type) ;; 統合ディスパッチ
```

#### Claude Code 状態検出パターン (agentoast由来)
- **Running**: スピナー文字 + "esc to interrupt" or "…"
- **Waiting**: "Enter to select", plan approval セレクタ
- **Idle**: プロンプト行 (`❯`, `$ `, `% `, `>`)

#### Codex 状態検出パターン
- **Running**: "esc to interrupt" + "("
- **Waiting**: 質問ダイアログ
- **Idle**: `›` (U+203A) プロンプト

### 2. 変更: `ceeker.tmux.pane`
- `list-pane-info` を拡張して `:pane-id` も返すようにする
- 新関数 `refresh-session-states!`: 定期チェック時に capture-pane で中間状態を更新

### 3. 変更: `ceeker.state.store`
- 新関数 `update-session-status!`: 状態のみを更新する軽量関数
- デバウンス: hook による更新が直近2秒以内にあればcapture更新をスキップ

### 4. 変更: `ceeker.tui.app`
- `maybe-check-panes!` に状態リフレッシュを統合

### 5. テスト
- `ceeker.tmux.capture-test`: 各エージェントの状態検出パターンテスト
- `ceeker.tmux.pane-test`: refresh-session-states! テスト
- 既存テストの回帰確認

## 状態更新モデル

### トリガー
1. **Hook イベント** (最高優先度) - Claude/Codex フック経由
2. **Capture-pane 定期検出** (~10秒ごと) - tmux ペイン内容から検出
3. **Liveness チェック** (~10秒ごと) - プロセス生存確認

### 優先順位ルール
- Hook 更新が 2秒以内 → capture更新をスキップ
- Capture 結果が現在の状態と同じ → 更新スキップ (dedup)
- :completed/:closed 状態のセッション → capture更新対象外

### フォールバック
- tmux が使えない環境 → capture をスキップ、hook のみで動作
- pane-id が空 → capture をスキップ
- capture-pane 失敗 → 既存状態を維持

## リスクとロールバック
- **リスク**: capture-pane の頻繁な呼び出しによるオーバーヘッド
  - **緩和**: liveness check と同じ10秒間隔で実行、変更なしなら書き込みスキップ
- **リスク**: スピナー文字の誤検出
  - **緩和**: agentoast の検証済みパターンを使用、"esc to interrupt" との複合条件
- **ロールバック**: capture 機能は独立した名前空間なので、無効化が容易

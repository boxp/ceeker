# T-20260303-005: ceeker MVP実装計画

## 概要
boxp/arch PR #7336 の設計に基づき、ceeker MVPを実装する。
hook-driven なAIコーディングエージェントセッション監視TUI。

## プロジェクト構成

```
boxp/ceeker/
├── src/ceeker/
│   ├── core.clj              ; エントリーポイント、CLIパース
│   ├── hook/
│   │   └── handler.clj       ; hookイベント処理・State Store書き込み
│   ├── state/
│   │   └── store.clj         ; State Store (sessions.edn + FileLock)
│   └── tui/
│       ├── app.clj           ; TUIアプリケーションメインループ
│       ├── view.clj          ; 描画ロジック（ANSIエスケープ）
│       └── input.clj         ; キー入力ハンドラ
├── test/ceeker/
│   ├── hook/
│   │   └── handler_test.clj  ; hookハンドラテスト
│   └── state/
│       └── store_test.clj    ; State Storeテスト
├── deps.edn
├── Makefile
├── .clj-kondo/config.edn
├── .cljfmt.edn
├── .github/workflows/ci.yml
├── CLAUDE.md
├── AGENTS.md -> CLAUDE.md
├── LICENSE                    ; MIT License
└── README.md
```

## 技術選定

### TUI描画: 直接ANSIエスケープシーケンス + JLine3
- GraalVM native image互換性が確実
- 外部TUIライブラリ依存を避ける（lanternaはGraalVM互換性リスクあり）
- MVPスコープでは単一リスト表示のみなのでANSIエスケープで十分

### 依存ライブラリ
| ライブラリ | バージョン | 用途 |
|-----------|-----------|------|
| org.clojure/clojure | 1.12.4 | 言語ランタイム |
| org.clojure/tools.cli | 1.1.230 | CLIパース |
| cheshire/cheshire | 6.1.0 | JSON処理（hookペイロード） |
| org.jline/jline | 3.26.3 | ターミナル制御 |

### 開発ツール
| ツール | バージョン | 用途 |
|--------|-----------|------|
| clj-kondo | 2026.01.19 | Linter |
| cljfmt | 0.16.0 | Formatter |
| cognitect-labs/test-runner | 0.5.1 | テスト |

## 実装ステップ

### Step 1: プロジェクト初期構成
- deps.edn, Makefile, .clj-kondo/config.edn, .cljfmt.edn
- LICENSE (MIT), CLAUDE.md, AGENTS.md
- GitHub Actions CI (.github/workflows/ci.yml)

### Step 2: State Store (ceeker.state.store)
- sessions.ednファイルベースの永続ストア
- java.nio.channels.FileLockで排他制御
- read/write/update操作
- XDG_RUNTIME_DIR/ceeker/ またはフォールバック /tmp/ceeker-<uid>/

### Step 3: Hook CLI (ceeker.hook.handler)
- `ceeker hook <agent> <event>` サブコマンド
- stdinからJSON hookペイロードを受け取り
- Claude Code / Codex hookイベントを正規化
- State Storeに書き込み

### Step 4: TUI (ceeker.tui.*)
- ANSIエスケープ + JLine3でターミナルUI
- セッション一覧表示（agent/status/progress/worktree/更新時刻）
- セッション選択でtmux paneへジャンプ
- キーバインド: j/k=上下, Enter=ジャンプ, r=リフレッシュ, q=終了

### Step 5: テスト・lint・format
- handler_test.clj: hookイベント正規化テスト
- store_test.clj: State Store CRUD + ファイルロックテスト
- clj-kondo lint, cljfmt format-check

### Step 6: README
- セットアップ手順
- hook設定例（Claude Code / Codex）
- 実行例

## Hook仕様

### Claude Code hook
Claude Codeのhookはstdinからイベントデータを受け取る。
ceekerは以下のCLI形式で呼び出される:

```
ceeker hook claude <event-type>
```

event-type: PreToolUse, PostToolUse, Notification, Stop, SubagentStop

hookペイロードはstdinからJSONとして受信し、正規化してState Storeに保存する。

### Codex hook
```
ceeker hook codex <event-type>
```

## 設計との差分
- MVPではinotify/WatchServiceによるファイル監視は省略し、手動リフレッシュ(r)で対応
- progressファイル解析はMVP後の拡張とする
- gwq statusパースはMVP後の拡張とする
- フィルタ機能(/)はMVP後の拡張とする
- native imageビルドはPhase 2（MVP後）

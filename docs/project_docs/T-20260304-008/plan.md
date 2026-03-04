# T-20260304-008: native-image E2E テストをCIに追加

## 背景
JVM上では問題なく動作するが、native-imageビルド後のバイナリでのみ発生する不具合を検出するため、
native-image成果物を対象としたE2Eテストを CI に追加する。

## 実装計画

### 1. E2Eテストスクリプト (`e2e/native-e2e.sh`)

Bash スクリプトで以下のテストケースを実行:

| # | テスト名 | 内容 | TTY要否 |
|---|----------|------|---------|
| 1 | `test_help` | `--help` で usage 表示・exit 0 | 不要 |
| 2 | `test_hook_claude` | hook claude SessionStart で session 記録 | 不要 |
| 3 | `test_hook_codex` | hook codex (JSON argv) で session 記録 | 不要 |
| 4 | `test_tui_start_quit` | TUI 起動 → `q` キー → 正常終了 | **tmux** |
| 5 | `test_tui_search_escape` | TUI → `/` → `Esc` → `q` → 正常終了 | **tmux** |

**TTY 対策**: TUI テストは `tmux` を使って PTY を提供。
- `tmux new-session -d -s ceeker-e2e` でバックグラウンドセッション起動
- `tmux send-keys` でキーストローク送信
- `tmux capture-pane` でTUI起動をポーリング確認（"ceeker" ヘッダ出現まで待機、最大15秒）
- exit marker ファイルで終了検知（最大15秒待ち）

**タイムアウト**: TUI起動待ち最大15秒 + 終了待ち最大15秒。CI全体は20分。

**診断**: 失敗時に以下を収集
- stderr ログ
- sessions.edn 内容
- tmux capture-pane 出力

### 2. GitHub Actions ジョブ (`native-e2e`)

`ci.yml` に追加:

```yaml
native-e2e:
  runs-on: ubuntu-latest
  steps:
    - checkout
    - GraalVM 21 + native-image setup
    - Clojure CLI setup
    - dependency cache
    - Build uberjar
    - Build native image (linux-amd64)
    - Install tmux (TUIテストに必要)
    - Run e2e/native-e2e.sh
    - Upload diagnostic artifacts (on failure)
```

**トリガー**: PR / main push (既存CIと同条件 + e2e/, .github/workflows/ci.yml パス追加)

### 3. ドキュメント更新

README.md の「開発」セクションに native-e2e CI ジョブの説明を追記。

## ファイル変更一覧

| ファイル | 変更内容 |
|----------|----------|
| `e2e/native-e2e.sh` | 新規: E2Eテストスクリプト |
| `.github/workflows/ci.yml` | 変更: native-e2e ジョブ追加 + pathsにe2e追加 |
| `docs/project_docs/T-20260304-008/plan.md` | 新規: 本計画 |
| `README.md` | 変更: CI説明追記 |

# T-20260303-013: Claude hooks 公式仕様準拠修正

## 背景

ceeker の Claude hooks 取り込み実装が公式仕様と食い違っていた。

## 公式仕様との差分

### 1. README settings.json 形式（重大）

公式は3レベルネスト: `hooks > EventName > [{matcher?, hooks: [{type, command}]}]`
旧READMEは2レベルで記載しており、Claude Code が認識しない無効な設定だった。

### 2. extract-claude-identity の非仕様パス

`[:session :session_id]` や `[:session :cwd]` は公式仕様に存在しない。
公式では `session_id` と `cwd` はトップレベルフィールド。

### 3. hook_event_name 未使用

公式仕様では全payloadに `hook_event_name` フィールドが含まれる。
ceeker はこれを無視し、CLI引数のみでイベント種別を判定していた。

### 4. イベント種別の不足

公式仕様のイベント: SessionStart, UserPromptSubmit, PreToolUse,
PermissionRequest, PostToolUse, PostToolUseFailure, Notification,
SubagentStart, SubagentStop, Stop, TeammateIdle, TaskCompleted, SessionEnd

ceeker が対応していたのは: Notification, Stop, SubagentStop, PreToolUse, PostToolUse のみ。

### 5. テストfixture

テストpayloadに公式共通フィールド（transcript_path, permission_mode, hook_event_name）が含まれていなかった。

## 修正内容

- `extract-claude-identity`: 非仕様パス `[:session ...]` を削除
- `claude-event-fields`: SessionStart, SessionEnd, SubagentStart, PostToolUseFailure, TaskCompleted を追加
- `resolve-claude-event`: `hook_event_name` からのイベント種別解決を追加
- `handle-hook!`: Claude/Codex 共通のイベント解決ロジック統一
- テスト: 公式spec準拠のpayload fixtureに更新、新イベントのテスト追加
- README: settings.json を公式3レベルネスト形式に修正、より多くのイベント設定例を追加

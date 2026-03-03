# T-20260303-009: ceeker main直push是正 + CI復旧

## 問題
- コミット `0cd9af3` が PR を経由せず main に直接 push された
- 当該コミットにより CI (lint) が失敗状態
  - `test/ceeker/core_test.clj:7` が 100 文字超で `line-length` warning → exit code 2

## 是正方針
1. `0cd9af3` を `git revert` で取り消す（revert コミット）
2. 同一機能を lint/format が通る形で再コミット
3. PR 経由で main にマージし、直 push 状態を解消

## 修正内容
### Revert (コミット 1)
- `0cd9af3 "Improve hook payload handling"` を完全に revert

### 再適用 (コミット 2)
- `src/ceeker/core.clj`: docstring のトレイリングスペース除去のみ（機能差分なし）
- `test/ceeker/core_test.clj`: テストアサーションを `let` binding で分割し 100 文字以内に
- `README.md`: 変更なし（revert + 再適用で同一内容に戻る）

## 再発防止
- main 直 push を行わず、必ず PR フローを経由する運用を徹底
- CI (lint/format/test) が通ることを PR マージの前提条件とする

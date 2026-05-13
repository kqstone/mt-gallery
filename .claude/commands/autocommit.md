---
model: haiku
---

自动提交当前所有未提交的更改。

步骤：
1. 运行 `git status` 查看未提交的文件
2. 运行 `git diff` 和 `git diff --cached` 查看具体改动
3. 如果有未暂存的更改，运行 `git add` 暂存所有更改
4. 根据改动内容生成简洁的中文 commit message（遵循 conventional commits 格式：feat/fix/refactor/chore/docs 等）
5. 运行 `git commit` 提交
6. 显示提交结果

如果没有检测到任何更改，直接告知用户"没有需要提交的更改"。

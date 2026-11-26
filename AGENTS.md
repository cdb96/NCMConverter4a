# AGENTS.md

本文件用于规范 AI Agent 在本仓库中的开发与协作行为。在开始修改代码前，请先参阅 [ARCHITECTURE.md](ARCHITECTURE.md) 了解项目模块划分与核心数据流。

## 1. 代码与修改原则

- **代码尽量精简**：优先使用最简单、直接的实现完成目标，避免过度抽象、冗余封装和不必要的依赖。
- **严禁未提及的改动**：不要在用户未明确提及的情况下擅自新增功能、重构无关代码、补充额外逻辑或修改无关文件。
- **严禁擅自修改 `README.md`**：除非用户明确要求，否则不要写入或修改 `README.md`。
- **同步更新 `ARCHITECTURE.md`**：进行任何文件修改后，都必须同步检查并更新 [ARCHITECTURE.md](ARCHITECTURE.md)，确保架构文档与当前代码保持一致。

## 2. Git Commit 规范

所有提交必须遵守 **Conventional Commits** 规范，并在提交信息末尾标注本次提交所使用的 **AI 模型** 与 **Harness**。

### 提交格式

```text
<type>(<scope>): <subject>

[可选正文：简明说明改动原因与影响]

AI-Model: <模型名称>
AI-Harness: <Harness / 工具名称>
```

### 常用 `type`

- `feat`: 新增功能
- `fix`: 修复缺陷
- `refactor`: 重构（不改变外部行为）
- `test`: 添加或修改测试
- `perf`: 性能优化
- `build`: 构建系统或依赖变更
- `ci`: CI/CD 配置或脚本变更
- `docs`: 文档变更
- `chore`: 杂项维护

### 示例

```text
fix(desktop): 修复主界面列表滚动状态异常

AI-Model: Gemini 4 Hydrogen (Medium)
AI-Harness: Antigravity
```

---
name: code-reviewer
description: 对指定文件夹内的代码进行全面审查，包含规范性检查、Bug检测、性能优化建议、可读性评估和基于华为Java编程规范的质量评分；当用户需要审查代码质量、发现潜在问题、评估代码规范符合度或生成代码审查报告时使用
---

# 代码审查工具

## 任务目标
- 本 Skill 用于：对指定文件夹内的代码进行全面的质量审查和问题检测，并基于华为Java编程规范进行评分
- 能力包含：代码规范性检查、潜在Bug检测、性能优化建议、代码可读性评估、华为Java编程规范评分、生成结构化审查报告
- 触发条件：用户需要审查代码质量、发现潜在Bug、优化代码性能、评估代码规范符合度或生成代码审查报告时

## 前置准备
- 依赖说明：无额外Python包依赖，使用Python 3标准库
- 非标准文件/文件夹准备：无需额外准备

## 操作步骤

### 标准流程

1. **执行代码审查**
   - 调用 `scripts/code_reviewer.py` 对目标文件夹进行审查
   - 参数：
     - `--input-dir`：要审查的文件夹路径（必填）
   - 输出：在当前目录生成 `review_results.json` 文件
   - 示例：
     ```bash
     python scripts/code_reviewer.py --input-dir ./my-project
     ```
   - 功能说明：
     - 自动检测文件语言类型（Python、JavaScript、Java等）
     - 对Java文件自动进行华为Java编程规范评分
     - 检测代码规范性、潜在Bug、性能问题等
     - 统计注释覆盖率

2. **生成审查报告**
   - 调用 `scripts/report_generator.py` 将审查结果转换为Markdown报告
   - 参数：
     - `--review-json`：审查结果JSON文件路径（默认为 `./review_results.json`）
     - `--output-dir`：报告输出文件夹路径（默认为当前目录）
   - 输出：在指定目录生成 `code_review_report.md` 文件
   - 示例：
     ```bash
     python scripts/report_generator.py --review-json ./review_results.json --output-dir ./reports
     ```
   - 报告包含：
     - 概览统计（文件数量、问题总数、各严重性问题分布）
     - 华为Java编程规范评分（总分、各维度得分、评级、修改建议）
     - 严重问题列表（含代码行展示）
     - 一般问题列表
     - 优化建议列表
     - 文件级别的详细分析
     - 可读性评估

3. **查看审查报告**
   - 打开生成的 `code_review_report.md` 文件
   - 重点关注：
     - 华为规范评分及修改建议
     - 严重问题的代码行内容
     - 各文件的评分详情

### 可选分支

- 当只需快速检查问题：执行步骤1后直接查看 `review_results.json` 文件
- 当需要审查特定类型代码：使用 `--file-ext` 参数指定文件扩展名，如 `--file-ext .py`
- 当只关注Java代码规范：使用 `--file-ext .java` 参数

## 资源索引

- 必要脚本：
  - `scripts/code_reviewer.py`（用途：核心审查逻辑，支持多语言代码静态分析和华为Java编程规范评分）
  - `scripts/report_generator.py`（用途：将审查结果转换为Markdown格式报告，包含评分展示和修改建议）
- 参考文档：
  - `references/review-guidelines.md`（用途：详细的审查规则、严重性分级标准和各语言特定检查项）
- 规范文档：
  - `assets/华为JAVA编程规范.pdf`（用途：华为Java编程规范的完整定义，评分依据）

## 注意事项

- 审查工具使用静态分析方法，可能无法检测到运行时问题
- 华为Java编程规范评分仅针对Java文件，其他语言文件不参与评分
- 建议结合单元测试和集成测试进行全面质量保障
- 不同编程语言的检查规则可能有所差异，详见 `review-guidelines.md`
- 报告中的"严重"问题建议优先处理，"一般"问题在代码重构时处理，"优化"问题可根据项目进度安排
- 华为规范评分总分100分，分为5个维度：排版规范（20分）、注释规范（25分）、命名规范（20分）、代码编写规范（20分）、性能与可靠性（15分）

## 使用示例

### 示例1：审查Java项目并获取规范评分
```bash
# 对Java项目进行审查和评分
python scripts/code_reviewer.py --input-dir ./java-project

# 生成包含评分的报告
python scripts/report_generator.py --review-json ./review_results.json --output-dir ./audit-reports
```

### 示例2：审查Python项目
```bash
# 对Python项目进行审查
python scripts/code_reviewer.py --input-dir ./python-project

# 生成报告
python scripts/report_generator.py --review-json ./review_results.json
```

### 示例3：审查特定类型文件
```bash
# 只审查JavaScript文件
python scripts/code_reviewer.py --input-dir ./web-app --file-ext .js

# 生成报告到指定目录
python scripts/report_generator.py --output-dir ./audit-reports
```

### 示例4：快速查看问题
```bash
# 执行审查后直接查看JSON结果
python scripts/code_reviewer.py --input-dir ./src && cat review_results.json | jq '.issues | group_by(.severity)'
```

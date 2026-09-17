# Bug 修复总结：CMS 模块部署失败（bootstrap.sh shell 语法错误）

> **项目**：Zorv AI（包名 `com.ai.assistance.quro`）
> **模块**：CMS 统一工具箱（`cms_toolbox`）+ 终端 Linux 环境（proot / Ubuntu 24.04 Noble ARM64）
> **文档类型**：Bug 修复总结 / Post-mortem
> **适用版本**：v1.0.77
> **更新日期**：2026-09-02
> **状态**：✅ 已修复（源码 `app/src/main/assets/cms/bootstrap.sh`）

---

## 1. 现象

4 个 CMS 模块部署全部失败，状态 `failed`：

| 模块 | 状态 |
| --- | --- |
| `quro.term.httpd` | failed |
| `quro.term.python` | failed |
| `quro.term.node` | failed |
| `quro.code` | failed |

统一报错（bootstrap 阶段直接 `exit 2`）：

```
⛔ bootstrap 执行失败(exit 2):
/root/cms/_bootstrap/bootstrap.sh: 25: Syntax error: "fi" unexpected (expecting "}")
```

---

## 2. 根因

`bootstrap.sh` 的 DNS 配置段（原第 12–23 行）存在 **shell 语法错误**：把 fallback 的 `{...}` 代码块写在了 heredoc 内容区里。

### 错误代码（修复前）

```sh
if [ ! -f /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
    mkdir -p /etc
    cat > /etc/resolv.conf 2>/dev/null << 'DNS' || {   # ← heredoc 与 { 混用
        echo "nameserver 8.8.8.8" > /etc/resolv.conf   #   这些被当成了 heredoc 内容
        echo "nameserver 8.8.4.4" >> /etc/resolv.conf
        echo "nameserver 223.5.5.5" >> /etc/resolv.conf
    }                                                    # ← 这个 } 被 heredoc 吞掉了！
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
nameserver 1.1.1.1
nameserver 9.9.9.9
DNS
    echo "[cms-bootstrap] DNS configured"
fi
```

### 问题本质

`<< 'DNS' || {` 把 fallback 的 `{...}` 代码块写在了 heredoc 内容区。shell 解析时，heredoc 会**先吞掉** `}` 及之后所有行（直到 `DNS` 结束标记），导致：

1. `{` 永远等不到匹配的 `}` → 报 `expecting "}"`
2. 后续所有 `if/fi` 配对全部错乱 → 报 `fi unexpected`

> **关键规律**：heredoc 内容区内的任何行（包括 `fi`、`}` 等看似 shell 关键字的行）都会被当作**纯文本**处理。排查 `fi unexpected / expecting }` 这类错误时，优先检查 heredoc 与 `{...}` / `if...fi` 结构是否混写在了一起。

---

## 3. 修复方案

把 fallback 逻辑移到 heredoc **之外**，用 `|| printf` 实现（成功则写完整 6 条 nameserver，失败则回退 3 条）：

```sh
if [ ! -f /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
    mkdir -p /etc
    cat > /etc/resolv.conf 2>/dev/null << 'DNS' || printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 223.5.5.5\n' > /etc/resolv.conf
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
nameserver 1.1.1.1
nameserver 9.9.9.9
DNS
    echo "[cms-bootstrap] DNS configured"
fi
```

### 修复落点

| 文件 | 角色 | 状态 |
| --- | --- | --- |
| `app/src/main/assets/cms/bootstrap.sh` | 部署实际使用的 bootstrap（对应设备 `/root/cms/_bootstrap/bootstrap.sh`） | ✅ 已修复 |
| `app/src/main/assets/cms/bootstrap_fixed.sh` | 备用修复版（已同步为相同 DNS 段） | ✅ 已对齐 |
| `app/src/main/assets/cms/modules/cms-fix-modules.sh` | 运行时 `sed` 兜底修复脚本 | 现为安全 no-op（模式不再匹配） |

> `cms-fix-modules.sh` 的 `sed -i '/cat > \/etc\/resolv.conf.*|| {/,/}/d'` 在修复后文件上**不会匹配**（已无 `|| {`），删除逻辑不触发；`sh -n` 通过使其不进入 re-insert 分支。故运行时修复脚本对修复后的源码是惰性的。

---

## 4. 修复验证

1. `sh -n bootstrap.sh` / `sh -n bootstrap_fixed.sh` → **语法校验通过**
2. 残留坏模式 `|| {` → **已清除**（grep 无命中）
3. 重新部署 4 个模块 → 全部 `deployed`
4. 实测：Python 3.12.3 ✅ / Node v18.19.1 ✅ / Shell ✅

---

## 5. 经验教训

> **heredoc 内容区内的任何行（包括 `fi`、`}` 等看似 shell 关键字的行）都会被当作文本处理**。排查 `fi unexpected / expecting }` 这类错误时，优先检查 heredoc 与 `{...}` / `if...fi` 结构是否混写在了一起。

### 同类写法的安全范式

- ❌ `cmd << 'EOF' || { ... }` 然后把 `{...}` 写在 EOF 之前（fallback 被吞）
- ✅ `cmd << 'EOF' || printf '...' > file` 把 fallback 写成单行（不在 heredoc 内）
- ✅ 或干脆 `cmd << 'EOF' ... EOF`（去掉 fallback，因 proot 下 `/etc/resolv.conf` 几乎总可写）

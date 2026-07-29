# 本地Linux环境集成文档

> 由用户提供的集成计划，已落盘到工程 `docs/`。目标：在 QuroAI 应用私有目录内构建完整 Linux 终端环境，不依赖第三方应用（如 Termux）。

## 目标
将完整的Linux终端环境集成到应用内部，不依赖第三方应用（如Termux）。

## 技术架构

### 核心组件
1. **proot** - 用户空间的chroot实现，允许在非root环境下运行Linux
2. **Linux rootfs** - 精简的Linux文件系统（推荐Alpine Linux）
3. **包管理器** - apk（Alpine的包管理器）
4. **工具链** - 常用命令行工具

### 目录结构（运行时，在设备私有目录）
```
/data/user/0/com.ai.assistance.quro/files/linux_env/
├── bin/          # 基础命令 / proot 二进制
├── etc/          # 配置文件
├── home/         # 用户目录
├── root/         # root用户目录
├── tmp/          # 临时文件
├── usr/          # 用户程序
│   ├── bin/      # 用户命令
│   ├── lib/      # 库文件
│   └── share/    # 共享数据
├── var/          # 变量数据
└── proot/        # proot相关文件
```

### 工程内资产布局（Phase 1 落盘）
```
app/src/main/assets/linux_env/
├── proot                # aarch64 proot 可执行（含 libtalloc 依赖时同目录放 .so）
├── libtalloc.so         # proot 依赖（若采用 Termux 提取方案）
└── alpine-minirootfs-aarch64.tar.gz   # Alpine aarch64 rootfs
```
运行时首次启动：把 assets 解压/拷贝到 `files/linux_env/`，proot 二进制 `setExecutable(true)`，
`LD_LIBRARY_PATH` 指向自带 lib，再用 `proot -r <rootfs> -b /system -b /dev ... /bin/sh -c "<command>"` 执行。

## 实施步骤

### 阶段1: 获取基础组件（当前进行中）
1. 下载proot二进制文件（aarch64）
2. 下载Alpine Linux rootfs（aarch64 minirootfs）
3. 校验资产（架构/大小/完整性）

### 阶段2: 配置基础环境
1. 解压rootfs到指定目录
2. 配置网络和DNS
3. 安装基础工具包
4. 配置包管理器

### 阶段3: 集成到应用
1. 创建执行接口（CMS模块）
2. 注册为可调用工具
3. 实现命令执行和输出捕获
4. 添加工作目录管理

### 阶段4: 测试和优化
1. 测试常用命令
2. 优化性能
3. 添加错误处理
4. 编写使用文档

## 技术挑战

### 1. proot获取
- **问题**: proot未预装，需要获取
- **解决方案**:
  a. 从Termux提取（如果已安装）
  b. 下载静态编译版本
  c. 自行编译（需要交叉编译环境）

### 2. 权限管理
- **问题**: 应用沙箱限制
- **解决方案**:
  a. 使用应用私有目录
  b. 配置proot模拟root环境
  c. 必要时申请额外权限

### 3. 性能优化
- **问题**: proot有性能开销
- **解决方案**:
  a. 使用精简rootfs
  b. 优化启动参数
  c. 缓存常用环境

### 4. 包管理
- **问题**: 需要网络下载软件包
- **解决方案**:
  a. 配置国内镜像源
  b. 实现离线包管理
  c. 添加包缓存机制

## 预期功能

### 基础终端能力
- 执行任意shell命令
- 文件系统操作
- 网络工具（curl, wget）
- 文本处理（grep, sed, awk）

### 开发环境
- Python运行时
- Node.js运行时
- Git版本控制
- GCC编译器
- Make构建工具

### 系统工具
- 进程管理（ps, top, kill）
- 磁盘管理（df, du, mount）
- 用户管理（chmod, chown）
- 网络配置（ip, ifconfig）

## 工具接口设计

### 主要工具: linux_exec
```json
{
  "name": "linux_exec",
  "description": "在本地Linux环境中执行命令",
  "parameters": {
    "command": "要执行的命令",
    "workdir": "工作目录（可选）",
    "timeout": "超时时间（可选，默认30秒）"
  }
}
```

### 辅助工具: linux_manage
```json
{
  "name": "linux_manage",
  "description": "管理Linux环境",
  "actions": [
    "status - 查看环境状态",
    "start - 启动环境",
    "stop - 停止环境",
    "install - 安装软件包",
    "update - 更新系统"
  ]
}
```

## 风险评估

### 技术风险
1. **兼容性问题**: 不同Android版本的兼容性
2. **性能问题**: proot在某些设备上可能较慢
3. **存储问题**: 完整环境需要较大空间

### 解决方案
1. 分阶段实施，先实现基础功能
2. 提供精简版和完整版选择
3. 实现环境备份和恢复功能

## 时间估算
- 阶段1: 1-2天
- 阶段2: 2-3天
- 阶段3: 3-5天
- 阶段4: 2-3天
- **总计: 1-2周**

## 下一步行动
1. 确认用户需求优先级
2. 开始阶段1: 获取proot和rootfs
3. 创建开发分支
4. 定期测试和反馈

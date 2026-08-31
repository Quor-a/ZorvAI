# ZorvAI 终端命令大全

## 📖 概述

ZorvAI 终端是集成在 ZorvAI Android 应用中的交互式 Linux 环境，基于 proot + Ubuntu 24.04 rootfs，提供完整的 Linux 命令行体验。终端支持两种模式：

- **Linux 模式**：完整的 Ubuntu 环境，支持 apt/dpkg、python3、node.js、gcc 等
- **设备模式**：Android 原生 shell（Toybox），仅包含基础命令

## 🔧 终端管理命令

### 会话管理
```bash
# 创建新会话
+ 新会话

# 清屏
clear
cls

# 中断当前命令
Ctrl+C
# 或点击中断按钮

# 退出终端
exit
```

### 环境检查与安装
```bash
# 检查 Linux 环境状态（在终端界面点击「检查更新」按钮）
# 或通过 AI 工具：
linux:status

# 安装/更新 Linux 环境（约 200MB rootfs）
linux:install

# 重新安装 Linux 环境
linux:reinstall
```

## 📁 文件系统操作

### 基础文件操作
```bash
# 列出文件
ls -la
ls -lh /path/to/dir

# 切换目录
cd /path/to/dir
cd ~
cd ..
cd -

# 创建目录
mkdir -p /path/to/new/dir

# 删除文件/目录
rm file.txt
rm -rf directory/

# 复制
cp source destination
cp -r source_dir destination_dir

# 移动/重命名
mv old_name new_name
mv file.txt /new/path/

# 查看文件内容
cat file.txt
head -n 10 file.txt
tail -n 10 file.txt
less file.txt

# 创建/编辑文件
touch newfile.txt
nano file.txt
vi file.txt

# 查找文件
find / -name "*.txt" 2>/dev/null
locate filename
```

### 文件权限管理
```bash
# 修改权限
chmod 755 script.sh
chmod +x executable

# 修改所有者
chown user:group file.txt
chown -R user:group directory/
```

### 文件搜索与内容
```bash
# 搜索文件内容
grep "pattern" file.txt
grep -r "pattern" /path/to/dir
grep -i "pattern" file.txt  # 忽略大小写

# 文本处理
wc -l file.txt  # 行数
sort file.txt
uniq file.txt
cut -d: -f1 file.txt
awk '{print $1}' file.txt
sed 's/old/new/g' file.txt
```

## 📦 包管理（apt/dpkg）

### 更新系统
```bash
# 更新包列表
sudo apt update

# 升级所有包
sudo apt upgrade -y

# 完全升级
sudo apt full-upgrade -y
```

### 安装软件
```bash
# 安装软件包
sudo apt install package-name

# 安装多个包
sudo apt install package1 package2 package3

# 安装后清理
sudo apt install package-name --no-install-recommends
```

### 卸载软件
```bash
# 卸载软件包
sudo apt remove package-name

# 卸载并删除配置
sudo apt purge package-name

# 清理无用依赖
sudo apt autoremove -y
```

### 搜索软件
```bash
# 搜索包
apt search keyword

# 显示包信息
apt show package-name

# 列出已安装包
dpkg -l | grep package-name

# 查看包安装的文件
dpkg -L package-name
```

## 🐍 Python 开发环境

### Python 基础
```bash
# Python 版本
python3 --version
python3 -V

# 运行 Python 脚本
python3 script.py
python3 -c "print('Hello')"

# 交互式 Python
python3
```

### pip 包管理
```bash
# 安装 Python 包
pip3 install package-name

# 升级包
pip3 install --upgrade package-name

# 卸载包
pip3 uninstall package-name

# 列出已安装包
pip3 list

# 显示包信息
pip3 show package-name

# 导出依赖
pip3 freeze > requirements.txt

# 从文件安装
pip3 install -r requirements.txt
```

### 常用 Python 工具
```bash
# Jupyter Notebook
pip3 install jupyter
jupyter notebook

# Flask/Django
pip3 install flask
pip3 install django

# 数据分析
pip3 install numpy pandas matplotlib

# 机器学习
pip3 install tensorflow torch scikit-learn
```

## 🟢 Node.js 开发环境

### Node.js 基础
```bash
# Node.js 版本
node --version
node -v

# npm 版本
npm --version

# 运行 JS 文件
node script.js
node -e "console.log('Hello')"
```

### npm 包管理
```bash
# 安装包
npm install package-name

# 全局安装
npm install -g package-name

# 卸载包
npm uninstall package-name

# 更新包
npm update package-name

# 查看包信息
npm info package-name

# 运行脚本
npm run script-name
```

### 常用 Node.js 工具
```bash
# Express 服务器
npm install express
npx express-generator

# React/Vue 项目
npx create-react-app my-app
npx create-vue@latest my-app

# 构建工具
npm install webpack --save-dev
npm install vite --save-dev
```

## 🔧 系统信息与监控

### 系统信息
```bash
# 系统信息
uname -a
uname -r

# 内存信息
free -h
cat /proc/meminfo

# CPU 信息
lscpu
cat /proc/cpuinfo

# 磁盘使用
df -h
du -sh /path/to/dir

# 进程信息
top
htop
ps aux
ps -ef | grep process-name
```

### 网络信息
```bash
# 网络接口
ip addr show
ifconfig

# 路由表
ip route show
route -n

# DNS 信息
nslookup domain.com
dig domain.com
cat /etc/resolv.conf

# 网络连接
ss -tuln
netstat -tuln
```

## 🌐 网络工具

### 下载工具
```bash
# wget 下载
wget https://example.com/file.zip
wget -O output.zip https://example.com/file.zip

# curl 下载
curl -O https://example.com/file.zip
curl -L -o output.zip https://example.com/file.zip

# aria2 多线程下载
sudo apt install aria2
aria2c -x 16 -s 16 https://example.com/large-file.zip
```

### 网络诊断
```bash
# ping 测试
ping google.com
ping -c 4 google.com

# traceroute
traceroute google.com
mtr google.com

# 端口扫描
nmap -sV target.com

# HTTP 测试
curl -I https://example.com
curl -v https://example.com
```

## 🗄️ 数据库工具

### SQLite
```bash
# 安装 SQLite
sudo apt install sqlite3

# 创建/打开数据库
sqlite3 database.db

# 常用命令
.tables
.schema table_name
.quit
```

### MySQL/MariaDB
```bash
# 安装 MySQL
sudo apt install mysql-server

# 启动服务
sudo service mysql start

# 登录
mysql -u root -p

# 常用命令
SHOW DATABASES;
USE database_name;
SHOW TABLES;
```

### PostgreSQL
```bash
# 安装 PostgreSQL
sudo apt install postgresql

# 启动服务
sudo service postgresql start

# 登录
sudo -u postgres psql
```

## 🛠️ 开发工具

### 版本控制
```bash
# Git 基础
git init
git clone https://github.com/user/repo.git
git status
git add .
git commit -m "message"
git push origin main

# 分支管理
git branch
git branch branch-name
git checkout branch-name
git merge branch-name
```

### 编译工具
```bash
# GCC 编译
gcc -o output source.c
g++ -o output source.cpp

# Make
make
make clean

# CMake
mkdir build && cd build
cmake ..
make
```

### 代码编辑器
```bash
# Nano（简单）
nano file.txt

# Vim（强大）
vim file.txt

# VS Code（如果安装）
code file.txt
```

## 🔐 权限与安全

### 用户管理
```bash
# 查看当前用户
whoami
id

# 切换用户
su - username
sudo -i

# 添加用户
sudo adduser username
sudo usermod -aG sudo username

# 修改密码
passwd
passwd username
```

### 文件权限
```bash
# 查看权限
ls -la

# 修改权限
chmod 644 file.txt  # rw-r--r--
chmod 755 script.sh # rwxr-xr-x

# 修改所有者
chown user:group file.txt
```

## 📱 Android 特定操作

### Android 文件系统
```bash
# 访问 Android 存储
ls /sdcard/
ls /storage/emulated/0/

# 复制文件到 Android 存储
cp file.txt /sdcard/Download/

# 访问应用数据
ls /data/data/com.ai.assistance.quro/
```

### Android 进程
```bash
# 查看进程
ps | grep android

# 杀死进程
kill -9 process_id

# 查看应用信息
dumpsys package com.ai.assistance.quro
```

## 🎯 实用技巧

### 命令历史
```bash
# 查看历史命令
history

# 搜索历史
history | grep "command"

# 重复上条命令
!!

# 执行历史第 N 条
!N
```

### 别名
```bash
# 创建别名
alias ll='ls -la'
alias gs='git status'

# 保存别名
echo "alias ll='ls -la'" >> ~/.bashrc
source ~/.bashrc
```

### 管道与重定向
```bash
# 管道
command1 | command2

# 重定向
command > output.txt  # 覆盖
command >> output.txt  # 追加
command 2> error.txt  # 错误重定向

# 组合
command1 > output.txt 2>&1
```

### 任务管理
```bash
# 后台运行
command &

# 暂停进程
Ctrl+Z

# 查看后台任务
jobs

# 恢复前台
fg

# 恢复后台
bg
```

## 🚀 性能优化

### 清理缓存
```bash
# 清理 apt 缓存
sudo apt clean
sudo apt autoclean

# 清理日志
sudo journalctl --vacuum-time=7d

# 清理临时文件
sudo rm -rf /tmp/*
```

### 磁盘管理
```bash
# 查找大文件
find / -type f -size +100M 2>/dev/null

# 查看目录大小
du -sh /*

# 压缩文件
tar -czvf archive.tar.gz directory/
zip -r archive.zip directory/

# 解压文件
tar -xzvf archive.tar.gz
unzip archive.zip
```

## 📚 常见问题解决

### 权限问题
```bash
# 解决权限不足
sudo command

# 修复文件权限
chmod 755 script.sh
chown -R user:group directory/
```

### 磁盘空间不足
```bash
# 检查磁盘使用
df -h
du -sh /*

# 清理无用包
sudo apt autoremove
sudo apt clean

# 清理大文件
sudo find / -type f -size +100M -delete
```

### 网络问题
```bash
# 检查网络连接
ping google.com
curl -I https://example.com

# 检查 DNS
nslookup google.com
cat /etc/resolv.conf

# 重启网络
sudo service network-manager restart
```

## 🔗 相关资源

- **ZorvAI 官方文档**: https://zorvai.com/docs
- **Ubuntu 官方文档**: https://help.ubuntu.com/
- **Linux 命令大全**: https://linuxcommand.org/
- **Python 官方文档**: https://docs.python.org/3/
- **Node.js 官方文档**: https://nodejs.org/en/docs/

## 📞 获取帮助

如果遇到问题：
1. 在终端输入 `help` 或 `?` 查看内置帮助
2. 使用 AI 助手：在对话中描述问题，AI 会提供解决方案
3. 检查日志：`cat /var/log/syslog` 或 `dmesg`
4. 搜索社区：Stack Overflow、GitHub Issues

---

*最后更新：2026年8月30日*
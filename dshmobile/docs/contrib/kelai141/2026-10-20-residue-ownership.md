# 快照残留清理的目录归属

旧入口仅检查名称是否含 `.failed-`，会把 `files/report.failed-123` 等未知用户目录当成残留删除。

现在只接受三个实际写点：`files/usr.failed-<ts>`、`files/profiles.failed-<ts>`、`files/home/.dsh/profiles.failed-<ts>`。后缀须是正十进制毫秒时间戳。未知名称、位置和畸形后缀保留。stage orphan 仍由原有 30 分钟年龄窗口回收，立即清理入口不越权处理。

JVM 与 Android 回归均使用独立临时目录，同时验证已知残留仍会回收、未知文件逐字节不变、回滚源不受影响。设备测试只使用测试应用缓存夹具，不把用户实际数据作为负控。具体运行结果登记于 PR；测试源码存在不等于已经通过。

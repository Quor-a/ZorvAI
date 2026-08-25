package com.ai.assistance.quro.core.terminal;

/**
 * JNI 接口，用于调用 Termux 的 PTY 功能。
 * 
 * 基于 Termux 的 JNI 实现，修改包名以匹配我们的项目结构。
 */
public class QuroTerminalJNI {
    
    static {
        System.loadLibrary("termux-terminal");
    }
    
    /**
     * 创建子进程并分配伪终端（PTY）。
     *
     * @param cmd 要执行的命令
     * @param cwd 工作目录
     * @param args 命令参数
     * @param envVars 环境变量
     * @param processIdArray 用于存储进程ID的数组
     * @param rows 终端行数
     * @param columns 终端列数
     * @param cellWidth 单元格宽度（像素）
     * @param cellHeight 单元格高度（像素）
     * @return PTY master 的文件描述符
     */
    public static native int createSubprocess(
        String cmd,
        String cwd,
        String[] args,
        String[] envVars,
        int[] processIdArray,
        int rows,
        int columns,
        int cellWidth,
        int cellHeight
    );
    
    /**
     * 设置伪终端窗口大小。
     *
     * @param fd PTY master 文件描述符
     * @param rows 终端行数
     * @param cols 终端列数
     * @param cellWidth 单元格宽度（像素）
     * @param cellHeight 单元格高度（像素）
     */
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);
    
    /**
     * 设置伪终端 UTF-8 模式。
     *
     * @param fd PTY master 文件描述符
     */
    public static native void setPtyUTF8Mode(int fd);
    
    /**
     * 等待子进程结束。
     *
     * @param pid 子进程ID
     * @return 进程退出状态
     */
    public static native int waitFor(int pid);
    
    /**
     * 关闭文件描述符。
     *
     * @param fileDescriptor 要关闭的文件描述符
     */
    public static native void close(int fileDescriptor);

    /**
     * 复制文件描述符（dup）。返回一个新的 fd，与原 fd 独立。
     * 用于绕过 Android 16 fdsan 所有权冲突。
     *
     * @param fd 要复制的文件描述符
     * @return 新的文件描述符，失败返回 -1
     */
    public static native int dupFd(int fd);
}
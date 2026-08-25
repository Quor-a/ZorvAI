package com.ai.assistance.quro.core.termux.terminal;

import com.ai.assistance.quro.core.terminal.QuroTerminalJNI;

/**
 * JNI 别名，委托给 QuroTerminalJNI。
 * Termux 原版 TerminalSession 引用 JNI 类，这里做桥接。
 */
public final class JNI {

    public static int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns, int cellWidth, int cellHeight) {
        return QuroTerminalJNI.createSubprocess(cmd, cwd, args, envVars, processId, rows, columns, cellWidth, cellHeight);
    }

    public static void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight) {
        QuroTerminalJNI.setPtyWindowSize(fd, rows, cols, cellWidth, cellHeight);
    }

    public static void setPtyUTF8Mode(int fd) {
        QuroTerminalJNI.setPtyUTF8Mode(fd);
    }

    public static int waitFor(int processId) {
        return QuroTerminalJNI.waitFor(processId);
    }

    public static void close(int fileDescriptor) {
        QuroTerminalJNI.close(fileDescriptor);
    }
}

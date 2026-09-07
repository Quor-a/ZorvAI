/**
 * 项目入口（单文件版）。
 * 端侧沙箱不内置 JDK；撰写与逻辑设计在本地完成，编译运行走 ACI 构建台（aci_call）
 * 或让 AI 用 workspace_write 打包多文件工程。
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello from Java!");
        // 纯逻辑可以直接在这里写；需要 Android 真机能力的部分见 android 模板。
    }
}

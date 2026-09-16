package com.zorv.hello;

import com.ai.assistance.quro.kaleidobox.core.engine.AppLifecycle;
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoAppContext;
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit;
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost;
import com.ai.assistance.quro.kaleidobox.core.model.KValue;
import com.ai.assistance.quro.kaleidobox.core.ui.InvokeContext;
import com.ai.assistance.quro.kaleidobox.core.ui.UiNode;
import com.ai.assistance.quro.kaleidobox.core.ui.Mod;
import com.ai.assistance.quro.kaleidobox.core.ui.Bound;
import com.ai.assistance.quro.kaleidobox.core.ui.Action;
import com.ai.assistance.quro.kaleidobox.core.ui.TypeStyle;
import com.ai.assistance.quro.kaleidobox.core.ui.Arrangement;
import com.ai.assistance.quro.kaleidobox.core.ui.UiCodec;
import com.ai.assistance.quro.kaleidobox.core.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 最小可运行的 KaleidoBox 插件（一个独立 App）。
 *
 * 演示"插件 = App"升级的三件事：
 *   1. onAppLifecycle：插件像 Activity 一样有 CREATE/RESUME/PAUSE/DESTROY；
 *   2. host.appContext：读写自己的私有文件目录（filesDir），进程重启后还在；
 *   3. 声明式 UI（UiNode）+ unit（greet）。
 *
 * 编译打包：
 *   python kbox.py build \
 *       --src HelloApp.java \
 *       --manifest hello.kaleido.json \
 *       --api-jar kaleidobox-api.jar \
 *       --d8 $ANDROID_HOME/build-tools/<ver>/d8.jar \
 *       --out hello.kbox
 *
 * 安装：KaleidoBoxHost.get().runtime.install(ZipSource("hello.kbox"))
 */
public class HelloApp implements KaleidoToolkit {

    private ToolkitHost host;
    private KaleidoAppContext appContext;

    @Override
    public void attach(ToolkitHost host) {
        this.host = host;
    }

    /** 生命周期：CREATE 时把启动次数 +1 并持久化到私有文件（进程重启后还在）。 */
    @Override
    public void onAppLifecycle(AppLifecycle event, KaleidoAppContext appContext) {
        this.appContext = appContext;
        if (event == AppLifecycle.CREATE && appContext != null) {
            java.io.File boot = appContext.file("boot_count.txt");
            int n = 0;
            try {
                if (boot.exists())
                    n = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(boot.toPath())).trim());
            } catch (Exception ignored) { }
            n++;
            try {
                java.nio.file.Files.write(boot.toPath(), String.valueOf(n).getBytes());
            } catch (Exception ignored) { }
            host.log("INFO", "hello", "插件第 " + n + " 次启动");
        }
    }

    /** 宿主统一入口：fn = render / onAction / greet。 */
    @Override
    public KValue invoke(String fn, KValue args, InvokeContext ctx) {
        switch (fn) {
            case "greet": {
                String name = args.asMap().getOrDefault("name", KValue.Str("世界")).asString();
                return KValue.Str("你好，" + name + "！这是 ZorvAI 里的一个 App。");
            }
            case "render": {
                UiNode node = buildUi(args);
                // 与内置示例完全一致：UiNode → encode → JSON 字符串 → KValue.Str
                return KValue.Str(Json.write(UiCodec.encode(node)));
            }
            case "onAction": {
                host.call("ui.toast", KValue.obj("text", "Hello from plugin App!"));
                return KValue.Obj(Collections.emptyMap());
            }
            default:
                return KValue.fail("E_NO_UNIT", "未知 fn: " + fn);
        }
    }

    private UiNode buildUi(KValue args) {
        // 从宿主回灌的 state 里取计数（没有就 0）
        int boot = 0;
        try {
            Map<String, Object> state = (Map<String, Object>) ((KValue.Obj) args).value.get("state");
            if (state != null && state.get("boot") != null)
                boot = ((Number) state.get("boot")).intValue();
        } catch (Exception ignored) { }

        List<UiNode> kids = new ArrayList<>();
        kids.add(new UiNode.Text("title",
                Bound.Lit("你好，App"), TypeStyle.TITLE, new Mod(), null, null));
        kids.add(new UiNode.Text("sub",
                Bound.Lit("这是 ZorvAI 系统里的一个 KaleidoBox 插件 App。已启动 " + boot + " 次。"),
                TypeStyle.BODY, new Mod(), null, null));
        kids.add(new UiNode.Button("ping",
                Bound.Lit("点我"), Action.of("ping"), new Mod(),
                Bound.Lit(true), UiNode.Button.Variant.FILLED));
        return new UiNode.Column("root", kids, new Mod(), Arrangement.TOP);
    }
}

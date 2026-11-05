package com.ai.assistance.quro.core.tools

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * AI 直接控制桌面宠物 —— 说话气泡 / 播动作 / 显示隐藏 / 查状态。
 * 动作只支持 actionWave/actionBounce/actionChat/think 四种（PetSprite 内置动画）。
 * 系统提示词的「桌面宠物」段会注入宠物名与支持的互动动作清单。
 */
class PetControlTool : QuroTool {
    override val name = "pet"

    override val description =
        "pet：控制桌面宠物。\n" +
            "- action=speak：让宠物说话（text 参数 = 气泡内容，如问候/提示/总结一句话）\n" +
            "- action=act：播一个动作（anim 选 actionWave|actionBounce|actionChat|think）\n" +
            "- action=hide / show：隐藏或显示宠物\n" +
            "- action=state：查询宠物当前状态\n" +
            "使用时机：用户提到桌宠/宠物时；回复完成想给用户一个轻量提示时；想让宠物打招呼时。"

    override val parametersJson = """{
        "type": "object",
        "properties": {
            "action": {"type": "string", "enum": ["speak", "act", "hide", "show", "state"], "description": "操作类型"},
            "text": {"type": "string", "description": "action=speak 时的气泡内容"},
            "anim": {"type": "string", "enum": ["actionWave", "actionBounce", "actionChat", "think"], "description": "action=act 时的动画"}
        },
        "required": ["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val o = runCatching { org.json.JSONObject(arguments) }.getOrNull()
            ?: return "❌ 参数不是合法 JSON"
        val action = o.optString("action", "state")
        val bus = com.ai.assistance.quro.core.pet.QuroPetStateBus
        return when (action) {
            "speak" -> {
                val text = o.optString("text", "").ifBlank { return "❌ action=speak 需要 text" }
                bus.statusText = text.take(120)
                Handler(Looper.getMainLooper()).postDelayed({ bus.statusText = null }, 6000)
                "✅ 宠物气泡：$text"
            }
            "act" -> {
                val anim = o.optString("anim", "actionWave")
                if (anim !in listOf("actionWave", "actionBounce", "actionChat", "think")) {
                    return "❌ 不支持的动画 $anim（可选 actionWave/actionBounce/actionChat/think）"
                }
                bus.onReplyComplete(null)
                bus.statusText = anim
                "✅ 宠物动作：$anim"
            }
            "hide", "show" -> {
                val prefs = context.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("pet_visible", action == "show").apply()
                context.sendBroadcast(
                    android.content.Intent("com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS")
                        .setPackage(context.packageName)
                )
                if (action == "show") "✅ 宠物已显示" else "✅ 宠物已隐藏"
            }
            else -> {
                val st = com.ai.assistance.quro.genui.aiapp.ui.PetPrefs.load(context)
                "宠物状态：可见=${st.petVisible} 名称=${st.current.name} 动作间隔=${st.actionIntervalSec}s"
            }
        }
    }
}

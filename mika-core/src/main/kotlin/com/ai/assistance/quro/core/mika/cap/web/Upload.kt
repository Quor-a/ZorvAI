package com.ai.assistance.quro.core.mika.cap.web

import com.ai.assistance.quro.core.mika.risk.GateTarget
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.ArgError
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.fail
import com.ai.assistance.quro.core.mika.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.ai.assistance.quro.core.mika.workspace.WorkspacePathCheck
import com.ai.assistance.quro.core.mika.uistate.action
import com.ai.assistance.quro.core.mika.uistate.session

/** 页面脚本给文件选择控件(input type=file)的角色。 */
private const val FILE_ROLE = "file"

/** 把工作区里的文件交给页面上的文件控件;要点文件控件,和点击一样判不可逆。 */
internal object Upload : WebTool {
    override val verb = Verb.Upload
    override val label = "上传文件"
    override val risk = Risk.External
    override val gateTarget = GateTarget.FileControl
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("web_upload",
        Prompts.tool("web_upload", "max_files" to UPLOAD_MAX_FILES, "workspace" to WORKSPACE_PREFIX),
        mapOf(
            "ref" to str("ref of the file control from the latest web_observe/web_list_elements."),
            "snapshotId" to str("snapshotId of that web_observe/web_list_elements result."),
            "paths" to buildJsonObject {
                put("type", "array"); put("description", "1-$UPLOAD_MAX_FILES workspace paths, e.g. /workspace/inbox/photo.jpg.")
                put("minItems", 1); put("maxItems", UPLOAD_MAX_FILES); put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("ref", "snapshotId", "paths"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref()!!
        val paths = a.strList("paths", 1, UPLOAD_MAX_FILES)!!.map(String::trim)
        val target = ctx.session.recentSnapshots.lastOrNull { it.snapshotId == ref.second }?.elements?.firstOrNull { it.ref == ref.first }
        if (target != null && target.role != FILE_ROLE) {
            throw ArgError("ref", "${ref.first} is a ${target.role}, not a file control; pass the ref of the file input (role $FILE_ROLE)")
        }
        // 没接工作区检查时一律当越界:宁可传不了,不能把工作区外的文件交给页面
        val pathCheck = ctx.workspacePath ?: { WorkspacePathCheck.Outside }
        for (p in paths) {
            val check = if (!workspaceShaped(p)) WorkspacePathCheck.Outside else pathCheck(p)
            when (check) {
                WorkspacePathCheck.Outside -> return fail("path_outside_workspace",
                    Feedback.format("upload.outside", "path" to p, "workspace" to WORKSPACE_PREFIX))
                WorkspacePathCheck.NotFound -> return fail("file_not_found", Feedback.format("upload.not_found", "path" to p))
                WorkspacePathCheck.Ok -> Unit
            }
        }
        return ctx.action(ctx.web!!.upload(ref.first, ref.second, paths))
    }

    /** /workspace/ 下、没有 . 和 .. 段、没有连续斜杠的路径;其余交给 app 侧规范化后再判。 */
    private fun workspaceShaped(p: String): Boolean {
        if (!p.startsWith(WORKSPACE_PREFIX) || "//" in p) return false
        return p.removePrefix(WORKSPACE_PREFIX).split('/').none { it == "." || it == ".." || it.isEmpty() }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        (o["paths"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
}

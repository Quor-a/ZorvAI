package com.ai.assistance.quro.service

import android.app.assist.AssistStructure
import com.ai.assistance.quro.activity.QuroMainActivity
import android.app.assist.AssistStructure.ViewNode
import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews

/**
 * ZorvAI 自动填充服务（对应 PERMISSIONS.md #4）。
 *
 * 用户在系统「自动填充服务」中选择 Zorv AI 后启用。当前实现：
 *  - 识别页面中的可编辑字段（EditText）；
 *  - 提供一个「用 ZorvAI 填写」入口（点击打开应用，由 AI 基于页面语义生成填充建议）。
 * 后续可由 AI 在端侧直接回填具体字段值。
 */
class QuroAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure: AssistStructure = request.fillContexts.last().structure
        val fields = ArrayList<AutofillId>()
        traverse(structure.getWindowNodeAt(0).rootViewNode, fields)
        if (fields.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val intent = Intent(this, QuroMainActivity::class.java).apply {
            action = "com.ai.assistance.quro.AUTOFILL"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "用 ZorvAI 填写")
        }
        val dataset = Dataset.Builder(presentation)
            .setAuthentication(pending.intentSender)
            .build()
        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun traverse(node: ViewNode?, out: ArrayList<AutofillId>) {
        node ?: return
        val id = node.autofillId
        val cls = node.className
        if (id != null && cls != null && cls.contains("EditText")) {
            out.add(id)
        }
        for (i in 0 until node.childCount) {
            traverse(node.getChildAt(i), out)
        }
    }
}

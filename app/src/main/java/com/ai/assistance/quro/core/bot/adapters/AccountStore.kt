package com.ai.assistance.quro.core.bot.adapters

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 账户存储（对齐 weixin_clawbot AccountStore）。
 *
 * 职责：1. 账户持久化 2. 上下文令牌更新 3. 账户生命周期管理
 */
class AccountStore private constructor(context: Context) {
    companion object {
        private const val TAG = "AccountStore"
        private const val PREFS_NAME = "weixin_clawbot_accounts"

        @Volatile
        private var instance: AccountStore? = null

        fun getInstance(context: Context): AccountStore {
            return instance ?: synchronized(this) {
                instance ?: AccountStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 保存账户（对齐 weixin_clawbot save）。
     */
    fun save(account: ClawBotAccount) {
        Log.d(TAG, "保存账户: ${account.id}")
        ClawBotAccount.save(account, prefs)
    }

    /**
     * 加载第一个账户（对齐 weixin_clawbot loadFirst）。
     */
    fun loadFirst(): ClawBotAccount? {
        return ClawBotAccount.fromPrefs(prefs)
    }

    /**
     * 加载所有账户（对齐 weixin_clawbot loadAll）。
     * 当前实现只支持单账户，返回列表格式以保持兼容。
     */
    fun loadAll(): List<ClawBotAccount> {
        val account = loadFirst()
        return if (account != null) listOf(account) else emptyList()
    }

    /**
     * 更新上下文令牌（对齐 weixin_clawbot updateContextToken）。
     */
    fun updateContextToken(accountId: String, userId: String, contextToken: String) {
        Log.d(TAG, "更新上下文令牌: accountId=$accountId, userId=$userId")
        val account = loadFirst() ?: return
        if (account.id != accountId) return

        val updated = account.copy(
            defaultTo = account.defaultTo ?: userId,
            contextToken = contextToken
        )
        save(updated)
    }

    /**
     * 删除指定账户（对齐 weixin_clawbot remove）。
     */
    fun remove(accountId: String) {
        Log.d(TAG, "删除账户: $accountId")
        ClawBotAccount.clear(prefs)
    }

    /**
     * 清除所有账户（对齐 weixin_clawbot clear）。
     */
    fun clear() {
        Log.d(TAG, "清除所有账户")
        ClawBotAccount.clear(prefs)
    }

    /**
     * 释放资源。
     */
    fun dispose() {
        // SharedPreferences 不需要显式释放
    }
}
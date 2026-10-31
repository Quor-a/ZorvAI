/*
 * Portions adapted from scrcpy (https://github.com/Genymobile/scrcpy):
 *   Copyright (C) 2018 Genymobile
 *   Copyright (C) 2018-2024 Romain Vimont
 *
 * Portions adapted from Mika (https://github.com/wangzan101/Mika):
 *   Copyright (C) wangzan101
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ai.assistance.quro.core.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Shizuku 特权进程里的系统 Context。
 *
 * 系统服务会核对「包名属于调用方 uid」：本进程跑在 uid 2000（shell），
 * 因此对外必须报 `com.android.shell`，否则 `DisplayManager` / `InputManager` /
 * `StartActivityAsUser` 这些会因包名与 uid 对不上而拒绝或行为异常。
 *
 * 这个类只在 [QuroShellService] 的进程里使用，App 主进程不碰。
 */
internal class QuroShellContext private constructor(base: Context) : ContextWrapper(base) {

    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000

        /**
         * 取（必要时自建）一个伪装成 shell 的系统 Context。
         *
         * Shizuku 的 UserService 进程通常已有 ActivityThread；没有时按 app_process 的方式自建一个。
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun create(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: newActivityThread(at)
            val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val ctx = QuroShellContext(system)
            if (at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.get(thread) == null) {
                runCatching {
                    val app = Instrumentation.newApplication(Application::class.java, ctx)
                    at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
                }
            }
            return ctx
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
        private fun newActivityThread(at: Class<*>): Any {
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                val cc = Class.forName("android.app.ConfigurationController")
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val controller = cc.getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            }
            runCatching {
                val abd = Class.forName("android.app.ActivityThread\$AppBindData")
                val data = abd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                abd.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(data, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
            }
            return thread
        }
    }
}

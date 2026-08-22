package com.ai.assistance.quro.permissions

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 健康 / 健身数据（Health Connect，Android 13+）。
 *
 * 链路：
 * 1. Manifest 经 <meta-data android:name="health_permissions" android:resource="@array/health_permissions"/>
 *    声明本应用支持读写的数据类型（与 [requiredPermissions] 对应），系统据此在授权页展示可授权项。
 * 2. 运行时请求由调用方（Compose 侧用 [PermissionController.createRequestPermissionResultContract]）
 *    发起，本类只持有 [requiredPermissions] 与核心读写 API，避免在构造函数里注册 launcher。
 * 3. 核心 API：读步数（按数据源 DataOrigin 分组）、写运动记录（手动录入标记来源）。
 *
 * Android 14+ Health Connect 为系统模块；Android 13 经 health-connect-client 库桥接，
 * 二者 API 一致，统一用 [HealthConnectClient.getOrCreate]。
 */
class HealthPermissionHelper(private val activity: AppCompatActivity) {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(activity) }

    /** 本应用声明的数据类型权限集合（与 res/values/health_permissions.xml 一一对应）。 */
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    )

    /** 当前已授予的权限集合（异步）。 */
    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** 是否已获得全部声明权限（异步）。 */
    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(requiredPermissions)

    /**
     * 读取某时间段的步数，并按数据源（DataOrigin）分组，
     * 用于"数据源优先级"：区分本应用录入 vs 其它 App（如手表/Google Fit）写入的数据。
     *
     * @return map：packageName -> 步数总和
     */
    suspend fun readStepsBySource(start: Instant, end: Instant): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            client.readRecords(request).records
                .groupBy { it.metadata.dataOrigin.packageName }
                .mapValues { (_, recs) -> recs.sumOf { it.count } }
        }

    /**
     * 写入一次运动记录（手动录入，来源标记为本应用自身的手动条目）。
     * 配合 DataOrigin 过滤可实现"本应用产生的数据优先/可溯源"。
     */
    suspend fun writeWorkout(start: Instant, end: Instant, title: String): Unit =
        withContext(Dispatchers.IO) {
            val session = ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = end,
                endZoneOffset = null,
                metadata = Metadata(
                    dataOrigin = DataOrigin(activity.packageName),
                    recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY
                ),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = title
            )
            client.insertRecords(listOf(session))
        }

    companion object {
        /**
         * Health Connect 是否可用：getSdkStatus == [HealthConnectClient.SDK_AVAILABLE] 表示可用。
         * 其它值（SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED）表示未安装/不可用，
         * 应引导用户安装或走其它健康 SDK。
         */
        fun isHealthConnectAvailable(context: Context): Boolean = runCatching {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }.getOrDefault(false)

        /**
         * 跳转到 Health Connect 管理页（查看/撤销授权、管理数据来源）。
         * 注意 action 值随 health-connect-client 版本固定为
         * "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"（库常量
         * HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS 的同值字面量）。
         */
        fun openHealthConnectManage(context: Context) {
            val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

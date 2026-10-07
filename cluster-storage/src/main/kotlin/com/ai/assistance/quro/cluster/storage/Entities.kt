package com.ai.assistance.quro.cluster.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.ai.assistance.quro.cluster.engine.ClusterStore
import com.ai.assistance.quro.cluster.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 存储设计要点：
 *  - SDK 用**独立数据库文件**（qurocluster.db），不与宿主 Room 库混用，避免迁移互相打架；
 *  - 领域对象以 JSON 列存储，schema 演进成本低（加字段不用写迁移）；
 *  - 高频查询字段（cluster_id / task_id / state / ts）单独成列并建索引；
 *  - 数据库整体加密，密钥放 AndroidKeystore（由 DatabaseProvider 注入）。
 */

private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ——————————— Entities ———————————

@Entity(tableName = "clusters")
data class ClusterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val dataJson: String,
    val updatedAt: Long
)

@Entity(tableName = "agents", indices = [Index("clusterId")])
data class AgentEntity(
    @PrimaryKey val id: String,
    val clusterId: String,
    val enabled: Boolean,
    val dataJson: String,
    val updatedAt: Long
)

@Entity(tableName = "tasks", indices = [Index("clusterId"), Index("state")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val clusterId: String,
    val state: String,
    val dataJson: String,
    val createdAt: Long
)

@Entity(tableName = "messages", indices = [Index("taskId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val dataJson: String,
    val ts: Long
)

@Entity(tableName = "turns", indices = [Index("taskId")])
data class TurnEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val dataJson: String,
    val ts: Long
)

@Entity(tableName = "artifacts", indices = [Index("taskId")])
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val dataJson: String,
    val ts: Long
)

@Entity(tableName = "blackboard", primaryKeys = ["taskId", "key"])
data class BlackboardEntity(
    val taskId: String,
    val key: String,
    val dataJson: String,
    val ts: Long
)

@Entity(tableName = "budget", indices = [Index("taskId")])
data class BudgetEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val dataJson: String,
    val ts: Long
)

@Entity(tableName = "audit")
data class AuditEntity(
    @PrimaryKey val id: String,
    val dataJson: String,
    val ts: Long
)

@Entity(tableName = "events", indices = [Index("taskId")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val taskId: String,
    val type: String,
    val dataJson: String,
    val ts: Long
)

// ——————————— DAO ———————————

@Dao
interface ClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCluster(e: ClusterEntity)
    @Query("SELECT * FROM clusters WHERE id=:id") suspend fun cluster(id: String): ClusterEntity?
    @Query("SELECT * FROM clusters ORDER BY updatedAt DESC") suspend fun clusters(): List<ClusterEntity>
    @Query("DELETE FROM clusters WHERE id=:id") suspend fun deleteCluster(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAgent(e: AgentEntity)
    @Query("SELECT * FROM agents WHERE id=:id") suspend fun agent(id: String): AgentEntity?
    @Query("SELECT * FROM agents WHERE clusterId=:cid") suspend fun agents(cid: String): List<AgentEntity>
    @Query("DELETE FROM agents WHERE id=:id") suspend fun deleteAgent(id: String)
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(e: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE clusterId=:cid ORDER BY createdAt DESC") suspend fun tasks(cid: String): List<TaskEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMessage(e: MessageEntity)
    @Query("SELECT * FROM messages WHERE taskId=:tid ORDER BY ts DESC LIMIT :limit")
    suspend fun messages(tid: String, limit: Int): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTurn(e: TurnEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putArtifact(e: ArtifactEntity)
    @Query("SELECT * FROM artifacts WHERE taskId=:tid ORDER BY ts ASC") suspend fun artifacts(tid: String): List<ArtifactEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBlackboard(e: BlackboardEntity)
    @Query("SELECT * FROM blackboard WHERE taskId=:tid") suspend fun blackboard(tid: String): List<BlackboardEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBudget(e: BudgetEntity)
    @Query("SELECT * FROM budget WHERE taskId=:tid") suspend fun budget(tid: String): List<BudgetEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAudit(e: AuditEntity)
    @Insert suspend fun putEvent(e: EventEntity): Long
    @Query("SELECT * FROM events WHERE taskId=:tid ORDER BY seq ASC") suspend fun events(tid: String): List<EventEntity>
    /** 只保留最近 N 条事件，防止长期运行后库膨胀 */
    @Query("DELETE FROM events WHERE taskId=:tid AND seq NOT IN (SELECT seq FROM events WHERE taskId=:tid ORDER BY seq DESC LIMIT :keep)")
    suspend fun trimEvents(tid: String, keep: Int)
}

@Database(
    entities = [
        ClusterEntity::class, AgentEntity::class, TaskEntity::class, MessageEntity::class,
        TurnEntity::class, ArtifactEntity::class, BlackboardEntity::class,
        BudgetEntity::class, AuditEntity::class, EventEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ClusterDatabase : RoomDatabase() {
    abstract fun clusterDao(): ClusterDao
    abstract fun taskDao(): TaskDao
}

class Converters {
    @TypeConverter fun fromLongList(v: String?): List<Long>? =
        v?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toLongOrNull() }
    @TypeConverter fun toLongList(v: List<Long>?): String? = v?.joinToString(",")
}

package com.zorv.genui.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zorv.genui.protocol.Artifact
import com.zorv.genui.protocol.ArtifactState
import com.zorv.genui.protocol.Lang
import org.json.JSONObject

/**
 * 生成式 UI 持久层（Room）。
 *
 *  - [ArtifactEntity] 版本树：同一 id 的多个 rev 全存，白送"撤销/回到第 N 版"。
 *  - [ArtifactStateEntity] 组件状态快照：复活重放用。
 *  - 跨会话恢复：重进会话可重建卡片（代码 + 状态，重新转译渲染）。
 *  - 清理：每卡片保留最近 [REV_KEEP] 个 rev，总量上限 [TOTAL_CAP]。
 *
 * 注意：本文件依赖 androidx.room（见 build.gradle.kts 的 ksp/room 配置）。
 */

// ---------------------------------------------------------------- 实体

@Entity(tableName = "artifacts")
data class ArtifactEntity(
    @PrimaryKey val key: String,            // "$id:$rev"
    val id: String,
    val rev: Int,
    val code: String,
    val lang: String,                       // "jsx" | "html"
    val caps: String,                       // 逗号分隔
    val deps: String,                       // 逗号分隔
    val createdAt: Long
)

@Entity(tableName = "artifact_states")
data class ArtifactStateEntity(
    @PrimaryKey val id: String,             // artifactId
    val currentRev: Int,
    val uiState: String?                    // JSON 字符串
)

// ---------------------------------------------------------------- DAO

@Dao
interface GenUiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifact(a: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE id=:id ORDER BY rev DESC")
    suspend fun getRevs(id: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE id=:id AND rev=:rev LIMIT 1")
    suspend fun get(id: String, rev: Int): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE id=:id ORDER BY rev DESC LIMIT 1")
    suspend fun getLatest(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ArtifactEntity>

    @Query("DELETE FROM artifacts WHERE id=:id AND rev < :keepRev")
    suspend fun pruneRevs(id: String, keepRev: Int)

    @Query("SELECT COUNT(*) FROM artifacts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(s: ArtifactStateEntity)

    @Query("SELECT * FROM artifact_states WHERE id=:id LIMIT 1")
    suspend fun getState(id: String): ArtifactStateEntity?

    @Query("DELETE FROM artifact_states WHERE id=:id")
    suspend fun deleteState(id: String)
}

@Database(entities = [ArtifactEntity::class, ArtifactStateEntity::class], version = 1, exportSchema = false)
abstract class GenUiDatabase : RoomDatabase() {
    abstract fun dao(): GenUiDao
}

// ---------------------------------------------------------------- 接口

interface GenUiStore {

    /** 落库一个 artifact（自动追加 parentRev 回溯链在调用方完成） */
    suspend fun save(artifact: Artifact)

    suspend fun get(id: String, rev: Int): Artifact?

    suspend fun getLatest(id: String): Artifact?

    /** 取组件状态快照（用于复活重放） */
    suspend fun getState(id: String): ArtifactState?

    suspend fun saveState(id: String, rev: Int, uiState: JSONObject?)

    /** 按策略清理：每卡片保留最近 REV_KEEP 个 rev，总量上限 TOTAL_CAP */
    suspend fun prune()

    fun close()
}

// ---------------------------------------------------------------- 实现

private const val REV_KEEP = 5
private const val TOTAL_CAP = 200

class RoomBackedStore(context: Context, name: String = "zorv_genui") : GenUiStore {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        GenUiDatabase::class.java,
        name
    ).fallbackToDestructiveMigration().build()

    private val dao = db.dao()

    override suspend fun save(artifact: Artifact) {
        dao.upsertArtifact(
            ArtifactEntity(
                key = "${artifact.id}:${artifact.rev}",
                id = artifact.id,
                rev = artifact.rev,
                code = artifact.code,
                lang = if (artifact.lang == Lang.HTML) "html" else "jsx",
                caps = artifact.caps.joinToString(","),
                deps = artifact.deps.joinToString(","),
                createdAt = artifact.createdAt
            )
        )
    }

    override suspend fun get(id: String, rev: Int): Artifact? =
        dao.get(id, rev)?.toArtifact()

    override suspend fun getLatest(id: String): Artifact? =
        dao.getLatest(id)?.toArtifact()

    override suspend fun getState(id: String): ArtifactState? {
        val e = dao.getState(id) ?: return null
        val json = e.uiState?.let { runCatching { JSONObject(it) }.getOrNull() }
        return ArtifactState(artifactId = e.id, rev = e.currentRev, uiState = json)
    }

    override suspend fun saveState(id: String, rev: Int, uiState: JSONObject?) {
        dao.upsertState(
            ArtifactStateEntity(
                id = id,
                currentRev = rev,
                uiState = uiState?.toString()
            )
        )
    }

    override suspend fun prune() {
        val all = dao.recent(TOTAL_CAP)
        val kept = all.take(TOTAL_CAP).map { it.id }.toSet()
        // 按 id 分组，保留最近 REV_KEEP 个 rev
        val byId = all.groupBy { it.id }
        byId.forEach { (id, list) ->
            if (id !in kept) return@forEach
            val keepRev = (list.maxByOrNull { it.rev }?.rev ?: 0) - REV_KEEP + 1
            if (keepRev > 0) dao.pruneRevs(id, keepRev)
        }
    }

    override fun close() = db.close()

    private fun ArtifactEntity.toArtifact(): Artifact = Artifact(
        id = id,
        rev = rev,
        lang = if (lang == "html") Lang.HTML else Lang.JSX,
        code = code,
        caps = if (caps.isEmpty()) emptySet() else caps.split(",").toSet(),
        deps = if (deps.isEmpty()) emptySet() else deps.split(",").toSet(),
        createdAt = createdAt,
        parentRev = if (rev > 1) rev - 1 else null
    )
}

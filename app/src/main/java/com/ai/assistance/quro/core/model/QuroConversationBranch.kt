package com.ai.assistance.quro.core.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.assistance.quro.core.QuroChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 对话分支数据模型
 * 
 * 参考 Agora 的树状结构对话设计，支持非线性对话分支。
 * 每个分支包含一系列消息，并可以有子分支。
 */
data class QuroConversationBranch(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null, // 父分支ID，null表示根分支
    val name: String = "新分支",
    val messages: List<QuroChatMessage> = emptyList(),
    val childBranchIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
) {
    /**
     * 获取消息数量
     */
    fun messageCount(): Int = messages.size
    
    /**
     * 获取最后一条消息
     */
    fun lastMessage(): QuroChatMessage? = messages.lastOrNull()
    
    /**
     * 获取分支深度（从根分支到当前分支的层数）
     */
    fun depth(): Int {
        // 这里需要通过分支树计算，简化实现
        return 0
    }
    
    /**
     * 获取分支路径描述
     */
    fun pathDescription(): String {
        return buildString {
            append(name)
            if (messages.isNotEmpty()) {
                append(" (${messages.size} 条消息)")
            }
        }
    }
}

/**
 * 对话分支树
 * 
 * 管理整个对话的分支结构，支持分支的创建、切换、合并和删除。
 */
data class QuroConversationTree(
    val id: String = UUID.randomUUID().toString(),
    val rootBranchId: String,
    val branches: Map<String, QuroConversationBranch>,
    val activeBranchId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
) {
    /**
     * 获取根分支
     */
    fun rootBranch(): QuroConversationBranch? = branches[rootBranchId]
    
    /**
     * 获取活跃分支
     */
    fun activeBranch(): QuroConversationBranch? = branches[activeBranchId]
    
    /**
     * 获取所有分支列表（按创建时间排序）
     */
    fun allBranches(): List<QuroConversationBranch> {
        return branches.values.sortedBy { it.createdAt }
    }
    
    /**
     * 获取分支层级结构
     */
    fun branchHierarchy(): List<QuroConversationBranch> {
        val result = mutableListOf<QuroConversationBranch>()
        val root = rootBranch() ?: return emptyList()
        
        fun traverse(branch: QuroConversationBranch) {
            result.add(branch)
            branch.childBranchIds.forEach { childId ->
                branches[childId]?.let { traverse(it) }
            }
        }
        
        traverse(root)
        return result
    }
    
    /**
     * 获取指定分支的所有子分支
     */
    fun childBranches(branchId: String): List<QuroConversationBranch> {
        val branch = branches[branchId] ?: return emptyList()
        return branch.childBranchIds.mapNotNull { branches[it] }
    }
    
    /**
     * 获取分支的祖先分支链
     */
    fun ancestorChain(branchId: String): List<QuroConversationBranch> {
        val chain = mutableListOf<QuroConversationBranch>()
        var currentId: String? = branchId
        
        while (currentId != null) {
            val branch = branches[currentId] ?: break
            chain.add(0, branch)
            currentId = branch.parentId
        }
        
        return chain
    }
    
    /**
     * 获取分支的深度
     */
    fun branchDepth(branchId: String): Int {
        return ancestorChain(branchId).size - 1 // 减去根分支
    }
}

/**
 * 对话分支仓库
 * 
 * 管理对话分支的持久化存储和操作。
 */
class QuroConversationBranchRepository(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("quro_conversation_branches", Context.MODE_PRIVATE)
    
    private val trees = mutableMapOf<String, QuroConversationTree>()
    
    init {
        loadTrees()
    }
    
    /**
     * 创建新的对话树
     */
    fun createTree(name: String = "新对话"): QuroConversationTree {
        val rootBranch = QuroConversationBranch(
            name = name,
            parentId = null
        )
        
        val tree = QuroConversationTree(
            rootBranchId = rootBranch.id,
            branches = mapOf(rootBranch.id to rootBranch),
            activeBranchId = rootBranch.id
        )
        
        trees[tree.id] = tree
        saveTrees()
        
        return tree
    }
    
    /**
     * 获取对话树
     */
    fun getTree(treeId: String): QuroConversationTree? = trees[treeId]
    
    /**
     * 获取所有对话树
     */
    fun getAllTrees(): List<QuroConversationTree> = trees.values.toList()
    
    /**
     * 删除对话树
     */
    fun deleteTree(treeId: String) {
        trees.remove(treeId)
        saveTrees()
    }
    
    /**
     * 在指定分支下创建新分支
     */
    fun createBranch(
        treeId: String,
        parentBranchId: String,
        name: String = "新分支",
        initialMessages: List<QuroChatMessage> = emptyList()
    ): QuroConversationBranch? {
        val tree = trees[treeId] ?: return null
        val parentBranch = tree.branches[parentBranchId] ?: return null
        
        val newBranch = QuroConversationBranch(
            parentId = parentBranchId,
            name = name,
            messages = initialMessages
        )
        
        val updatedParent = parentBranch.copy(
            childBranchIds = parentBranch.childBranchIds + newBranch.id,
            updatedAt = System.currentTimeMillis()
        )
        
        val updatedBranches = tree.branches.toMutableMap()
        updatedBranches[parentBranchId] = updatedParent
        updatedBranches[newBranch.id] = newBranch
        
        val updatedTree = tree.copy(
            branches = updatedBranches,
            updatedAt = System.currentTimeMillis()
        )
        
        trees[treeId] = updatedTree
        saveTrees()
        
        return newBranch
    }
    
    /**
     * 向分支添加消息
     */
    fun addMessage(
        treeId: String,
        branchId: String,
        message: QuroChatMessage
    ): Boolean {
        val tree = trees[treeId] ?: return false
        val branch = tree.branches[branchId] ?: return false
        
        val updatedBranch = branch.copy(
            messages = branch.messages + message,
            updatedAt = System.currentTimeMillis()
        )
        
        val updatedBranches = tree.branches.toMutableMap()
        updatedBranches[branchId] = updatedBranch
        
        val updatedTree = tree.copy(
            branches = updatedBranches,
            updatedAt = System.currentTimeMillis()
        )
        
        trees[treeId] = updatedTree
        saveTrees()
        
        return true
    }
    
    /**
     * 切换活跃分支
     */
    fun switchActiveBranch(treeId: String, branchId: String): Boolean {
        val tree = trees[treeId] ?: return false
        if (!tree.branches.containsKey(branchId)) return false
        
        val updatedTree = tree.copy(
            activeBranchId = branchId,
            updatedAt = System.currentTimeMillis()
        )
        
        trees[treeId] = updatedTree
        saveTrees()
        
        return true
    }
    
    /**
     * 合并分支
     */
    fun mergeBranches(
        treeId: String,
        sourceBranchId: String,
        targetBranchId: String,
        mergeStrategy: MergeStrategy = MergeStrategy.APPEND
    ): Boolean {
        val tree = trees[treeId] ?: return false
        val sourceBranch = tree.branches[sourceBranchId] ?: return false
        val targetBranch = tree.branches[targetBranchId] ?: return false
        
        val mergedMessages = when (mergeStrategy) {
            MergeStrategy.APPEND -> targetBranch.messages + sourceBranch.messages
            MergeStrategy.INTERLEAVE -> interleaveMessages(targetBranch.messages, sourceBranch.messages)
            MergeStrategy.REPLACE -> sourceBranch.messages
        }
        
        val updatedTarget = targetBranch.copy(
            messages = mergedMessages,
            updatedAt = System.currentTimeMillis()
        )
        
        // 更新源分支的父分支，将其子分支移到目标分支下
        val updatedBranches = tree.branches.toMutableMap()
        updatedBranches[targetBranchId] = updatedTarget
        
        // 从源分支的父分支中移除源分支
        sourceBranch.parentId?.let { parentId ->
            val parent = tree.branches[parentId] ?: return@let
            val updatedParent = parent.copy(
                childBranchIds = parent.childBranchIds.filter { it != sourceBranchId },
                updatedAt = System.currentTimeMillis()
            )
            updatedBranches[parentId] = updatedParent
        }
        
        // 将源分支的子分支移到目标分支下
        val updatedSource = sourceBranch.copy(
            childBranchIds = emptyList(),
            isActive = false,
            updatedAt = System.currentTimeMillis()
        )
        updatedBranches[sourceBranchId] = updatedSource
        
        val updatedTargetWithChildren = updatedTarget.copy(
            childBranchIds = updatedTarget.childBranchIds + sourceBranch.childBranchIds
        )
        updatedBranches[targetBranchId] = updatedTargetWithChildren
        
        val updatedTree = tree.copy(
            branches = updatedBranches,
            updatedAt = System.currentTimeMillis()
        )
        
        trees[treeId] = updatedTree
        saveTrees()
        
        return true
    }
    
    /**
     * 删除分支
     */
    fun deleteBranch(treeId: String, branchId: String): Boolean {
        val tree = trees[treeId] ?: return false
        val branch = tree.branches[branchId] ?: return false
        
        // 不能删除根分支
        if (branch.parentId == null) return false
        
        // 递归删除子分支
        fun deleteBranchRecursive(branchId: String) {
            val branch = tree.branches[branchId] ?: return
            branch.childBranchIds.forEach { deleteBranchRecursive(it) }
            trees[treeId]?.let { currentTree ->
                val updatedBranches = currentTree.branches.toMutableMap()
                updatedBranches.remove(branchId)
                trees[treeId] = currentTree.copy(branches = updatedBranches)
            }
        }
        
        deleteBranchRecursive(branchId)
        
        // 从父分支中移除
        branch.parentId?.let { parentId ->
            val parent = tree.branches[parentId] ?: return@let
            val updatedParent = parent.copy(
                childBranchIds = parent.childBranchIds.filter { it != branchId },
                updatedAt = System.currentTimeMillis()
            )
            trees[treeId]?.let { currentTree ->
                val updatedBranches = currentTree.branches.toMutableMap()
                updatedBranches[parentId] = updatedParent
                trees[treeId] = currentTree.copy(branches = updatedBranches)
            }
        }
        
        // 如果删除的是活跃分支，切换到父分支
        if (tree.activeBranchId == branchId) {
            branch.parentId?.let { parentId ->
                switchActiveBranch(treeId, parentId)
            }
        }
        
        saveTrees()
        return true
    }
    
    /**
     * 重命名分支
     */
    fun renameBranch(treeId: String, branchId: String, newName: String): Boolean {
        val tree = trees[treeId] ?: return false
        val branch = tree.branches[branchId] ?: return false
        
        val updatedBranch = branch.copy(
            name = newName,
            updatedAt = System.currentTimeMillis()
        )
        
        val updatedBranches = tree.branches.toMutableMap()
        updatedBranches[branchId] = updatedBranch
        
        val updatedTree = tree.copy(
            branches = updatedBranches,
            updatedAt = System.currentTimeMillis()
        )
        
        trees[treeId] = updatedTree
        saveTrees()
        
        return true
    }
    
    /**
     * 获取分支的消息历史
     */
    fun getBranchMessageHistory(
        treeId: String,
        branchId: String,
        includeAncestors: Boolean = true
    ): List<QuroChatMessage> {
        val tree = trees[treeId] ?: return emptyList()
        
        return if (includeAncestors) {
            val ancestorChain = tree.ancestorChain(branchId)
            ancestorChain.flatMap { it.messages }
        } else {
            tree.branches[branchId]?.messages ?: emptyList()
        }
    }
    
    /**
     * 交错合并消息
     */
    private fun interleaveMessages(
        messages1: List<QuroChatMessage>,
        messages2: List<QuroChatMessage>
    ): List<QuroChatMessage> {
        val result = mutableListOf<QuroChatMessage>()
        val maxSize = maxOf(messages1.size, messages2.size)
        
        for (i in 0 until maxSize) {
            if (i < messages1.size) result.add(messages1[i])
            if (i < messages2.size) result.add(messages2[i])
        }
        
        return result
    }
    
    private fun loadTrees() {
        trees.clear()
        val json = prefs.getString(KEY_TREES, null) ?: return
        
        runCatching {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val treeJson = obj.getJSONObject(key)
                trees[key] = parseTree(treeJson)
            }
        }
    }
    
    private fun saveTrees() {
        prefs.edit {
            val obj = JSONObject()
            trees.forEach { (id, tree) ->
                obj.put(id, serializeTree(tree))
            }
            putString(KEY_TREES, obj.toString())
        }
    }
    
    private fun parseTree(obj: JSONObject): QuroConversationTree {
        val branchesObj = obj.optJSONObject("branches") ?: JSONObject()
        val branches = mutableMapOf<String, QuroConversationBranch>()
        
        for (key in branchesObj.keys()) {
            branches[key] = parseBranch(branchesObj.getJSONObject(key))
        }
        
        return QuroConversationTree(
            id = obj.optString("id", ""),
            rootBranchId = obj.optString("rootBranchId", ""),
            branches = branches,
            activeBranchId = obj.optString("activeBranchId", ""),
            createdAt = obj.optLong("createdAt", 0L),
            updatedAt = obj.optLong("updatedAt", 0L),
            metadata = parseMetadata(obj.optJSONObject("metadata"))
        )
    }
    
    private fun parseBranch(obj: JSONObject): QuroConversationBranch {
        val messagesArr = obj.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<QuroChatMessage>()
        
        for (i in 0 until messagesArr.length()) {
            val messageJson = messagesArr.getJSONObject(i)
            // 这里需要实现消息的解析
            // 简化实现，实际需要完整的消息解析逻辑
        }
        
        val childBranchIdsArr = obj.optJSONArray("childBranchIds") ?: JSONArray()
        val childBranchIds = mutableListOf<String>()
        for (i in 0 until childBranchIdsArr.length()) {
            childBranchIds.add(childBranchIdsArr.optString(i))
        }
        
        return QuroConversationBranch(
            id = obj.optString("id", ""),
            parentId = obj.optString("parentId", null),
            name = obj.optString("name", ""),
            messages = messages,
            childBranchIds = childBranchIds,
            createdAt = obj.optLong("createdAt", 0L),
            updatedAt = obj.optLong("updatedAt", 0L),
            isActive = obj.optBoolean("isActive", true),
            metadata = parseMetadata(obj.optJSONObject("metadata"))
        )
    }
    
    private fun parseMetadata(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            map[key] = obj.optString(key, "")
        }
        return map
    }
    
    private fun serializeTree(tree: QuroConversationTree): JSONObject {
        val branchesObj = JSONObject()
        tree.branches.forEach { (id, branch) ->
            branchesObj.put(id, serializeBranch(branch))
        }
        
        return JSONObject().apply {
            put("id", tree.id)
            put("rootBranchId", tree.rootBranchId)
            put("branches", branchesObj)
            put("activeBranchId", tree.activeBranchId)
            put("createdAt", tree.createdAt)
            put("updatedAt", tree.updatedAt)
            put("metadata", serializeMetadata(tree.metadata))
        }
    }
    
    private fun serializeBranch(branch: QuroConversationBranch): JSONObject {
        val messagesArr = JSONArray()
        branch.messages.forEachIndexed { index, message ->
            // 注意 QuroChatMessage 的字段实况：role 是 String（不是枚举，没有 .name），
            // 且消息本身没有 id 字段。因此这里不臆造 id，改用分支内下标作为稳定序号，
            // 足以支撑导出后的顺序回放；工具消息额外带上关联字段，避免回放时工具调用对不上。
            messagesArr.put(JSONObject().apply {
                put("index", index)
                put("role", message.role)
                put("content", message.content)
                if (!message.toolCallId.isNullOrBlank()) put("toolCallId", message.toolCallId)
                if (!message.toolName.isNullOrBlank()) put("toolName", message.toolName)
                if (!message.reasoning.isNullOrBlank()) put("reasoning", message.reasoning)
            })
        }
        
        val childBranchIdsArr = JSONArray()
        branch.childBranchIds.forEach { childBranchIdsArr.put(it) }
        
        return JSONObject().apply {
            put("id", branch.id)
            put("parentId", branch.parentId ?: "")
            put("name", branch.name)
            put("messages", messagesArr)
            put("childBranchIds", childBranchIdsArr)
            put("createdAt", branch.createdAt)
            put("updatedAt", branch.updatedAt)
            put("isActive", branch.isActive)
            put("metadata", serializeMetadata(branch.metadata))
        }
    }
    
    private fun serializeMetadata(map: Map<String, String>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) -> obj.put(key, value) }
        return obj
    }
    
    companion object {
        private const val KEY_TREES = "conversation_trees"
    }
}

/**
 * 合并策略
 */
enum class MergeStrategy {
    APPEND,      // 追加：源分支消息追加到目标分支末尾
    INTERLEAVE,  // 交错：交替合并两个分支的消息
    REPLACE      // 替换：用源分支消息替换目标分支消息
}

/**
 * 分支可视化节点
 */
data class BranchVisualizationNode(
    val branch: QuroConversationBranch,
    val depth: Int,
    val position: Int, // 在同级中的位置
    val isExpanded: Boolean = true,
    val isSelected: Boolean = false,
    val childNodes: List<BranchVisualizationNode> = emptyList()
)

/**
 * 分支可视化树
 */
class BranchVisualizationTree(private val tree: QuroConversationTree) {
    
    /**
     * 生成可视化节点列表
     */
    fun generateVisualizationNodes(): List<BranchVisualizationNode> {
        val root = tree.rootBranch() ?: return emptyList()
        return generateNodesRecursive(root, 0, 0)
    }
    
    private fun generateNodesRecursive(
        branch: QuroConversationBranch,
        depth: Int,
        position: Int
    ): List<BranchVisualizationNode> {
        val nodes = mutableListOf<BranchVisualizationNode>()
        
        nodes.add(BranchVisualizationNode(
            branch = branch,
            depth = depth,
            position = position,
            isSelected = branch.id == tree.activeBranchId,
            childNodes = branch.childBranchIds.mapNotNull { childId ->
                tree.branches[childId]
            }.mapIndexed { index, childBranch ->
                generateNodesRecursive(childBranch, depth + 1, index).firstOrNull()
            }.filterNotNull()
        ))
        
        // 递归添加子节点
        branch.childBranchIds.forEachIndexed { index, childId ->
            tree.branches[childId]?.let { childBranch ->
                nodes.addAll(generateNodesRecursive(childBranch, depth + 1, index))
            }
        }
        
        return nodes
    }
    
    /**
     * 获取分支统计信息
     */
    fun getStatistics(): BranchStatistics {
        val allBranches = tree.allBranches()
        val maxDepth = allBranches.maxOfOrNull { tree.branchDepth(it.id) } ?: 0
        val totalMessages = allBranches.sumOf { it.messages.size }
        
        return BranchStatistics(
            totalBranches = allBranches.size,
            maxDepth = maxDepth,
            totalMessages = totalMessages,
            activeBranchId = tree.activeBranchId,
            rootBranchId = tree.rootBranchId
        )
    }
}

/**
 * 分支统计信息
 */
data class BranchStatistics(
    val totalBranches: Int,
    val maxDepth: Int,
    val totalMessages: Int,
    val activeBranchId: String,
    val rootBranchId: String
) {
    fun getDescription(): String {
        return buildString {
            append("分支统计: ")
            append("$totalBranches 个分支, ")
            append("最大深度: $maxDepth, ")
            append("总消息数: $totalMessages")
        }
    }
}

package com.ai.assistance.quro.cluster.engine

import com.ai.assistance.quro.cluster.model.AgentConfig
import com.ai.assistance.quro.cluster.model.Cluster
import kotlinx.serialization.Serializable

/** 集群导出/导入的快照结构，可直接分享给别的设备 */
@Serializable
data class ClusterSnapshot(
    val cluster: Cluster,
    val agents: List<AgentConfig> = emptyList()
)

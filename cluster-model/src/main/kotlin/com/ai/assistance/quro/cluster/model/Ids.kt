package com.ai.assistance.quro.cluster.model

import kotlinx.serialization.Serializable

// —— 标识（value class 防止把 AgentId 当 TaskId 传错）——
@Serializable @JvmInline value class ClusterId(val value: String)
@Serializable @JvmInline value class AgentId(val value: String)
@Serializable @JvmInline value class SkillId(val value: String)
@Serializable @JvmInline value class ToolId(val value: String)
@Serializable @JvmInline value class TaskId(val value: String)
@Serializable @JvmInline value class NodeId(val value: String)
@Serializable @JvmInline value class TurnId(val value: String)
@Serializable @JvmInline value class MessageId(val value: String)
@Serializable @JvmInline value class ArtifactId(val value: String)

/** 主持的固定 ID：集群内唯一、不可增、不可删、不可改名。 */
val HOST_AGENT_ID: AgentId = AgentId("__host__")

fun newId(prefix: String): String =
    prefix + "_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

package com.ai.assistance.quro.workflow.data.model

import com.ai.assistance.quro.workflow.data.WorkflowRepository

/**
 * 内置示例工作流模板（首次启动播种；也可在列表页「加载示例模板」重复导入）。
 *
 * 分两类，直接回应「缺少视频/音乐/图片制作工作流」：
 *  - A. AI 生成（图片/音乐/视频）：用 HTTP 节点调用生成式 API，变量 api_key/endpoint
 *       由用户填写；响应体经 HTTP 节点的 out 参数存入变量，再由 FILE 节点落盘。
 *  - B. 本地多媒体：OPEN_MEDIA / PLAY_MEDIA / CAPTURE_PHOTO 三个新节点的示范，
 *       无需网络，纯本地 Intent + FileProvider。
 *
 * 注意：模板 JSON 中的 \${...} 是为工作流引擎准备的变量占位符（字面量），
 * 在 Kotlin 原始字符串里用 \${'$'}{x} 表示，避免被当作 Kotlin 字符串模板求值。
 */
object Templates {

    private val AI_IMAGE = """
{
  "id": "tpl-ai-image",
  "name": "AI 图片生成",
  "trigger": "manual",
  "schedule": "",
  "enabled": true,
  "variables": [
    {"name":"endpoint","default":"https://api.openai.com/v1/images/generations"},
    {"name":"api_key","default":""},
    {"name":"prompt","default":"一只戴墨镜的猫，赛博朋克霓虹风格"},
    {"name":"size","default":"1024x1024"}
  ],
  "start": "n0",
  "nodes": [
    {"id":"n0","type":"note","text":"即将调用图片生成 API。请先在「变量」填写 api_key，可改 endpoint/prompt/size。","next":"n1"},
    {"id":"n1","type":"http","url":"${'$'}{endpoint}","method":"POST","out":"resp","headers":"{\"Authorization\":\"Bearer ${'$'}{api_key}\",\"Content-Type\":\"application/json\"}","body":"{\"prompt\":\"${'$'}{prompt}\",\"n\":1,\"size\":\"${'$'}{size}\"}","next":"n2"},
    {"id":"n2","type":"file","path":"ai_image_result.json","mode":"write","content":"${'$'}{resp}","next":"n3"},
    {"id":"n3","type":"note","text":"图片生成请求已完成，响应（含图片 URL）已写入 ai_image_result.json，可在「运行历史」查看原始响应。","next":null}
  ]
}
"""

    private val AI_MUSIC = """
{
  "id": "tpl-ai-music",
  "name": "AI 音乐生成",
  "trigger": "manual",
  "schedule": "",
  "enabled": true,
  "variables": [
    {"name":"endpoint","default":"https://api.your-music-provider.com/v1/generate"},
    {"name":"api_key","default":""},
    {"name":"prompt","default":"轻松的 lo-fi 电子背景音乐，30 秒"},
    {"name":"duration","default":"30"}
  ],
  "start":"n0",
  "nodes":[
    {"id":"n0","type":"note","text":"即将调用音乐生成 API。请在「变量」填写 api_key 与你的服务商 endpoint。","next":"n1"},
    {"id":"n1","type":"http","url":"${'$'}{endpoint}","method":"POST","out":"resp","headers":"{\"Authorization\":\"Bearer ${'$'}{api_key}\",\"Content-Type\":\"application/json\"}","body":"{\"prompt\":\"${'$'}{prompt}\",\"duration\":\"${'$'}{duration}\"}","next":"n2"},
    {"id":"n2","type":"file","path":"ai_music_result.json","mode":"write","content":"${'$'}{resp}","next":"n3"},
    {"id":"n3","type":"note","text":"音乐生成请求已完成，响应已写入 ai_music_result.json（通常含音频 URL，可复制到浏览器/播放器播放）。","next":null}
  ]
}
"""

    private val AI_VIDEO = """
{
  "id": "tpl-ai-video",
  "name": "AI 视频生成",
  "trigger":"manual",
  "schedule":"",
  "enabled":true,
  "variables":[
    {"name":"endpoint","default":"https://api.your-video-provider.com/v1/generate"},
    {"name":"api_key","default":""},
    {"name":"prompt","default":"城市夜景延时摄影，电影感，10 秒"}
  ],
  "start":"n0",
  "nodes":[
    {"id":"n0","type":"note","text":"即将调用视频生成 API。请填写 api_key 与你的服务商 endpoint。","next":"n1"},
    {"id":"n1","type":"http","url":"${'$'}{endpoint}","method":"POST","out":"resp","headers":"{\"Authorization\":\"Bearer ${'$'}{api_key}\",\"Content-Type\":\"application/json\"}","body":"{\"prompt\":\"${'$'}{prompt}\"}","next":"n2"},
    {"id":"n2","type":"file","path":"ai_video_result.json","mode":"write","content":"${'$'}{resp}","next":"n3"},
    {"id":"n3","type":"note","text":"视频生成请求已完成，响应已写入 ai_video_result.json（含视频 URL）。","next":null}
  ]
}
"""

    private val CAPTURE_DEMO = """
{
  "id":"tpl-capture-demo",
  "name":"拍照并保存到本机",
  "trigger":"manual",
  "schedule":"",
  "enabled":true,
  "variables":[{"name":"photo_path","default":"captured_photo.jpg"}],
  "start":"n0",
  "nodes":[
    {"id":"n0","type":"note","text":"将调起系统相机，拍照后照片保存到应用私有目录（headless 下仅发起，结果写入指定文件）。","next":"n1"},
    {"id":"n1","type":"capture_photo","path":"${'$'}{photo_path}","next":"n2"},
    {"id":"n2","type":"note","text":"拍照已发起。如需立即查看，可改用「打开本地图片」模板用同一路径打开。","next":null}
  ]
}
"""

    private val PLAY_MUSIC = """
{
  "id":"tpl-play-music",
  "name":"播放本地音乐",
  "trigger":"manual",
  "schedule":"",
  "enabled":true,
  "variables":[{"name":"music_path","default":"music.mp3"}],
  "start":"n0",
  "nodes":[
    {"id":"n0","type":"note","text":"用系统播放器播放应用私有目录内的音乐文件（先把文件放进应用私有目录）。","next":"n1"},
    {"id":"n1","type":"play_media","target":"${'$'}{music_path}","next":null}
  ]
}
"""

    private val OPEN_IMAGE = """
{
  "id":"tpl-open-image",
  "name":"打开本地图片",
  "trigger":"manual",
  "schedule":"",
  "enabled":true,
  "variables":[{"name":"image_path","default":"image.png"}],
  "start":"n0",
  "nodes":[
    {"id":"n0","type":"note","text":"用系统查看器打开应用私有目录内的图片文件（先把图片放进应用私有目录）。","next":"n1"},
    {"id":"n1","type":"open_media","target":"${'$'}{image_path}","next":null}
  ]
}
"""

    /** 全部示例模板的 JSON（顺序：图 / 乐 / 视 / 拍照 / 播放 / 打开图片）。 */
    val SAMPLES: List<String> = listOf(
        AI_IMAGE, AI_MUSIC, AI_VIDEO, CAPTURE_DEMO, PLAY_MUSIC, OPEN_IMAGE
    )

    /** 导入全部示例（按固定 id 合并，重复导入幂等、不会重复）。 */
    fun seed() {
        SAMPLES.forEach { WorkflowRepository.importJson(it) }
    }
}

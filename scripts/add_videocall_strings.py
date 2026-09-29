# -*- coding: utf-8 -*-
"""把「视频通话界面 + 视频通话模型配置」新增的界面文案追加进 i18n 词表，
并生成英文（i18n_en_14.json）与日文（i18n_lang_ja_11.json）批词表。

规则（与流水线一致，绝不能违反）：
- i18n_strings.json 是 **list**，索引即资源键 qk_%05d，**只能追加**；
- 多行文案必须写成字面反斜杠+n（JSON 里写 \\n），此处无多行文案；
- 已存在的完全相同文案不重复追加（否则会产生两条同文本条目，前一条变孤儿）。
"""
import json, os, io

HERE = os.path.dirname(os.path.abspath(__file__))
STRINGS = os.path.join(HERE, 'i18n_strings.json')

# (中文原文, 英文, 日文)
ITEMS = [
    ("视频通话中的实时对话与画面理解所使用的模型（需支持视觉）",
     "Model used for the live conversation and scene understanding in a video call (must support vision)",
     "ビデオ通話中のリアルタイム会話と映像理解に使用するモデル（視覚対応が必要）"),
    ("点我开始视频通话", "Tap to start the video call", "タップしてビデオ通話を開始"),
    ("视频通话待命", "Video call on standby", "ビデオ通話スタンバイ"),
    ("摄像头已开启 · 点麦克风开始说话", "Camera on · tap the mic to speak", "カメラ起動中・マイクを押して話す"),
    ("已结束视频通话", "Video call ended", "ビデオ通話を終了しました"),
    ("缺少悬浮窗权限，请在系统设置中开启",
     "Overlay permission is required; please enable it in system settings",
     "オーバーレイ権限が必要です。システム設定で有効にしてください"),
    ("聆听中：$t", "Listening: $t", "聞き取り中：$t"),
    ("没听清，请再说一次", "Didn't catch that, please say it again", "聞き取れませんでした。もう一度お願いします"),
    ("语音识别不可用，请在 STT 设置中切换引擎",
     "Speech recognition unavailable; switch the engine in STT settings",
     "音声認識を利用できません。STT設定でエンジンを切り替えてください"),
    ("识别出错($msg)", "Recognition error ($msg)", "認識エラー($msg)"),
    ("无法启动语音识别", "Cannot start speech recognition", "音声認識を開始できません"),
    ("未配置 API Key", "API Key not configured", "APIキーが未設定です"),
    ("请先在模型配置页填写 API Key", "Please set the API Key on the model config page first",
     "先にモデル設定ページでAPIキーを入力してください"),
    ("AI 回复中", "AI is replying", "AIが返答中"),
    ("出错了：${e.message}", "Error: ${e.message}", "エラー：${e.message}"),
    ("麦克风已静音", "Microphone muted", "マイクをミュートしました"),
    ("麦克风已开启", "Microphone on", "マイクをオンにしました"),
    ("已切换到前置摄像头", "Switched to the front camera", "インカメラに切り替えました"),
    ("已切换到后置摄像头", "Switched to the back camera", "アウトカメラに切り替えました"),
    ("缺少相机权限，仅使用语音", "Camera permission missing; voice only", "カメラ権限がないため音声のみで動作します"),
    ("ZorvAI 视频通话", "ZorvAI Video Call", "ZorvAI ビデオ通話"),
    ("开关通话", "Toggle call", "通話の切り替え"),
    ("聊天框", "Chat", "チャット"),
    ("麦克风", "Microphone", "マイク"),
    ("静音", "Muted", "ミュート"),
    ("切换摄像头", "Switch camera", "カメラ切り替え"),
    ("挂断", "Hang up", "通話終了"),
    ("你说：$text", "You said: $text", "あなた：$text"),
    ("需要相机与麦克风权限才能开始视频通话",
     "Camera and microphone permission is required to start a video call",
     "ビデオ通話を開始するにはカメラとマイクの権限が必要です"),
    ("需要悬浮窗权限才能显示视频通话界面",
     "Overlay permission is required to show the video call screen",
     "ビデオ通話画面を表示するにはオーバーレイ権限が必要です"),
    ("每个功能可「跟随主模型」或指定独立模型；指定独立模型时还可单独填写基础 URL 与 API Key。"
     "已接入引擎并实时生效：常规对话、人格孵化、UI 控制、图像识别、视频识别、视频通话。",
     "Each feature can follow the main model or use a dedicated model; with a dedicated model you can also "
     "set its own base URL and API Key. Already wired to the engine and effective immediately: chat, persona "
     "incubation, UI control, image recognition, video recognition, video call.",
     "各機能は「メインモデルに従う」か専用モデルを指定できます。専用モデルの場合はベースURLとAPIキーも"
     "個別に入力できます。エンジンに接続済みで即時有効：通常対話、ペルソナ孵化、UI制御、画像認識、"
     "映像認識、ビデオ通話。"),
    ("视觉类能力：图像识别、视频识别与视频通话都需要支持视觉能力的模型（如 GPT-4V）。"
     "为它们指定独立模型并填好基础 URL / API Key 后，视觉分析、视频画面理解与视频通话即改用该模型。",
     "Vision features: image recognition, video recognition and video call all require a vision-capable model "
     "(e.g. GPT-4V). Assign a dedicated model with its own base URL / API Key and visual analysis, video scene "
     "understanding and video calls will switch to it.",
     "視覚系機能：画像認識・映像認識・ビデオ通話はいずれも視覚対応モデル（GPT-4V など）が必要です。"
     "専用モデルとベースURL／APIキーを設定すると、視覚分析・映像理解・ビデオ通話がそのモデルに切り替わります。"),
]

def main():
    data = json.load(open(STRINGS, encoding='utf-8'))
    have = {}
    for i, e in enumerate(data):
        have[e['text']] = i
    en = {}
    ja = {}
    added = 0
    for zh, e, j in ITEMS:
        if zh in have:
            print('  [skip] 已存在 qk_%05d: %s' % (have[zh], zh[:24]))
            en[zh] = e
            ja[zh] = j
            continue
        data.append({
            'text': zh,
            'fmt': ('$' in zh),
            'file': 'app/src/main/java/com/ai/assistance/quro/service/QuroVideoCallService.kt',
            'line': 0,
        })
        have[zh] = len(data) - 1
        en[zh] = e
        ja[zh] = j
        added += 1
        print('  [add ] qk_%05d %s' % (len(data) - 1, zh[:28]))
    with io.open(STRINGS, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    with io.open(os.path.join(HERE, 'i18n_en_14.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(en, f, ensure_ascii=False, indent=1)
    with io.open(os.path.join(HERE, 'i18n_lang_ja_11.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(ja, f, ensure_ascii=False, indent=1)
    print('新增 %d 条；词表总数 %d；en 批 %d 条；ja 批 %d 条' % (added, len(data), len(en), len(ja)))

main()

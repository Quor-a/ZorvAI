# -*- coding: utf-8 -*-
"""为「本轮修复的中文硬编码」补齐 i18n 词表 + 11 语言译文。

产出：
  scripts/i18n_strings.json        追加缺失的中文条目（保持既有索引/键不变）
  scripts/i18n_en_11.json          中文 -> 英文（i18n_build 的 EN 词表会自动加载）
  scripts/i18n_lang_<code>_2.json  中文 -> 该语言（自动加载，缺则回落英文）
  scripts/_newkeys.json            中文 -> qk_XXXXX 键映射（供源码结构改造使用）

约定：zh 文本必须与源码字面量逐字一致（脚本会做存在性校验）。
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = 'app/src/main/java'

# (中文原文, {语言: 译文})  —— en 必填；其余缺省则回落英文（绝不放中文）
CATALOG = [
    # ══ 设置页「视频通话」入口 ══
    ("视频通话", {
        "en": "Video call", "ja": "ビデオ通話", "ko": "영상 통화", "de": "Videoanruf",
        "fr": "Appel vidéo", "es": "Videollamada", "pt": "Chamada de vídeo",
        "ru": "Видеозвонок", "ar": "مكالمة فيديو", "hi": "वीडियो कॉल"}),
    ("开启摄像头与麦克风，用实时画面和语音与 AI 通话", {
        "en": "Turn on the camera and microphone to talk with the AI using live video and voice",
        "ja": "カメラとマイクを有効にし、リアルタイム映像と音声で AI と会話します",
        "ko": "카메라와 마이크를 켜고 실시간 영상과 음성으로 AI와 대화합니다",
        "de": "Kamera und Mikrofon aktivieren, um per Livebild und Sprache mit der KI zu sprechen",
        "fr": "Activez la caméra et le micro pour parler à l’IA en vidéo et en voix en direct",
        "es": "Activa la cámara y el micrófono para hablar con la IA con vídeo y voz en directo",
        "pt": "Ative a câmera e o microfone para falar com a IA com vídeo e voz ao vivo",
        "ru": "Включите камеру и микрофон, чтобы говорить с ИИ по видео и голосом",
        "ar": "شغّل الكاميرا والميكروفون للتحدث مع الذكاء الاصطناعي بالصوت والفيديو المباشر",
        "hi": "कैमरा और माइक्रोफ़ोन चालू करें और लाइव वीडियो व आवाज़ से AI से बात करें"}),

    # ══ QuroFunctionType 标签（12）══
    ("常规对话", {"en": "Regular chat", "ja": "通常の会話", "ko": "일반 대화", "de": "Normales Gespräch",
              "fr": "Conversation normale", "es": "Conversación normal", "pt": "Conversa normal",
              "ru": "Обычный диалог", "ar": "محادثة عادية", "hi": "सामान्य चैट"}),
    ("上下文总结", {"en": "Context summary", "ja": "コンテキスト要約", "ko": "컨텍스트 요약",
                "de": "Kontext-Zusammenfassung", "fr": "Résumé du contexte", "es": "Resumen del contexto",
                "pt": "Resumo do contexto", "ru": "Сводка контекста", "ar": "ملخص السياق", "hi": "संदर्भ सारांश"}),
    ("记忆处理", {"en": "Memory processing", "ja": "記憶の処理", "ko": "기억 처리", "de": "Speicherverarbeitung",
              "fr": "Traitement de la mémoire", "es": "Procesamiento de memoria",
              "pt": "Processamento de memória", "ru": "Обработка памяти", "ar": "معالجة الذاكرة",
              "hi": "स्मृति प्रोसेसिंग"}),
    ("UI 控制", {"en": "UI control", "ja": "UI 操作", "ko": "UI 제어", "de": "UI-Steuerung",
              "fr": "Contrôle de l’interface", "es": "Control de la interfaz", "pt": "Controle da interface",
              "ru": "Управление интерфейсом", "ar": "التحكم بالواجهة", "hi": "UI नियंत्रण"}),
    ("翻译", {"en": "Translation", "ja": "翻訳", "ko": "번역", "de": "Übersetzung", "fr": "Traduction",
            "es": "Traducción", "pt": "Tradução", "ru": "Перевод", "ar": "الترجمة", "hi": "अनुवाद"}),
    ("代码检索", {"en": "Code search", "ja": "コード検索", "ko": "코드 검색", "de": "Codesuche",
              "fr": "Recherche de code", "es": "Búsqueda de código", "pt": "Pesquisa de código",
              "ru": "Поиск по коду", "ar": "البحث في الشيفرة", "hi": "कोड खोज"}),
    ("人格孵化", {"en": "Persona incubation", "ja": "ペルソナ生成", "ko": "페르소나 배양",
              "de": "Persona-Ausarbeitung", "fr": "Incubation de persona", "es": "Incubación de persona",
              "pt": "Incubação de persona", "ru": "Инкубация персоны", "ar": "إنشاء الشخصية",
              "hi": "पर्सोना निर्माण"}),
    ("图像识别", {"en": "Image recognition", "ja": "画像認識", "ko": "이미지 인식", "de": "Bilderkennung",
              "fr": "Reconnaissance d’image", "es": "Reconocimiento de imagen", "pt": "Reconhecimento de imagem",
              "ru": "Распознавание изображений", "ar": "التعرف على الصور", "hi": "छवि पहचान"}),
    ("音频识别", {"en": "Audio recognition", "ja": "音声認識", "ko": "오디오 인식", "de": "Audioerkennung",
              "fr": "Reconnaissance audio", "es": "Reconocimiento de audio", "pt": "Reconhecimento de áudio",
              "ru": "Распознавание аудио", "ar": "التعرف على الصوت", "hi": "ऑडियो पहचान"}),
    ("视频识别", {"en": "Video recognition", "ja": "動画認識", "ko": "비디오 인식", "de": "Videoerkennung",
              "fr": "Reconnaissance vidéo", "es": "Reconocimiento de vídeo", "pt": "Reconhecimento de vídeo",
              "ru": "Распознавание видео", "ar": "التعرف على الفيديو", "hi": "वीडियो पहचान"}),
    ("图片生成", {"en": "Image generation", "ja": "画像生成", "ko": "이미지 생성", "de": "Bilderzeugung",
              "fr": "Génération d’image", "es": "Generación de imágenes", "pt": "Geração de imagem",
              "ru": "Генерация изображений", "ar": "توليد الصور", "hi": "छवि निर्माण"}),
    ("视频生成", {"en": "Video generation", "ja": "動画生成", "ko": "비디오 생성", "de": "Videoerzeugung",
              "fr": "Génération de vidéo", "es": "Generación de vídeo", "pt": "Geração de vídeo",
              "ru": "Генерация видео", "ar": "توليد الفيديو", "hi": "वीडियो निर्माण"}),

    # ══ QuroFunctionType 说明（12）══
    ("主对话使用的模型（恒为主模型）", {
        "en": "Model used for the main conversation (always the primary model)",
        "ja": "メイン会話で使用するモデル（常にメインモデル）",
        "ko": "메인 대화에 사용하는 모델(항상 메인 모델)",
        "de": "Modell für das Hauptgespräch (immer das Hauptmodell)",
        "fr": "Modèle utilisé pour la conversation principale (toujours le modèle principal)",
        "es": "Modelo usado en la conversación principal (siempre el modelo principal)",
        "pt": "Modelo usado na conversa principal (sempre o modelo principal)",
        "ru": "Модель для основного диалога (всегда основная модель)",
        "ar": "النموذج المستخدم في المحادثة الرئيسية (دائمًا النموذج الأساسي)",
        "hi": "मुख्य बातचीत के लिए मॉडल (हमेशा मुख्य मॉडल)"}),
    ("对话与历史压缩、总结所使用的模型", {
        "en": "Model used for compressing and summarizing the conversation history",
        "ja": "会話履歴の圧縮と要約に使用するモデル",
        "ko": "대화 기록 압축과 요약에 사용하는 모델",
        "de": "Modell zum Verdichten und Zusammenfassen des Gesprächsverlaufs",
        "fr": "Modèle utilisé pour compresser et résumer l’historique",
        "es": "Modelo usado para comprimir y resumir el historial",
        "pt": "Modelo usado para comprimir e resumir o histórico",
        "ru": "Модель для сжатия и обобщения истории диалога",
        "ar": "النموذج المستخدم لضغط سجل المحادثة وتلخيصه",
        "hi": "बातचीत इतिहास को संपीड़ित और सारांशित करने वाला मॉडल"}),
    ("记忆库自动沉淀与检索所使用的模型", {
        "en": "Model used to store and retrieve long-term memory automatically",
        "ja": "記憶ライブラリの自動蓄積と検索に使用するモデル",
        "ko": "기억 보관소 자동 저장 및 검색에 사용하는 모델",
        "de": "Modell für automatisches Speichern und Abrufen des Gedächtnisses",
        "fr": "Modèle utilisé pour stocker et rechercher la mémoire automatiquement",
        "es": "Modelo usado para almacenar y recuperar la memoria automáticamente",
        "pt": "Modelo usado para armazenar e recuperar memória automaticamente",
        "ru": "Модель для автоматического сохранения и поиска в памяти",
        "ar": "النموذج المستخدم لتخزين الذاكرة واسترجاعها تلقائيًا",
        "hi": "स्मृति को स्वतः संग्रह और खोजने वाला मॉडल"}),
    ("UI 自动化控制与屏幕理解所使用的模型", {
        "en": "Model used for UI automation and screen understanding",
        "ja": "UI 自動操作と画面理解に使用するモデル",
        "ko": "UI 자동화 제어와 화면 이해에 사용하는 모델",
        "de": "Modell für UI-Automatisierung und Bildschirmverständnis",
        "fr": "Modèle utilisé pour l’automatisation de l’interface et la compréhension d’écran",
        "es": "Modelo usado para la automatización de la interfaz y la comprensión de pantalla",
        "pt": "Modelo usado para automação da interface e compreensão de tela",
        "ru": "Модель для автоматизации интерфейса и понимания экрана",
        "ar": "النموذج المستخدم لأتمتة الواجهة وفهم الشاشة",
        "hi": "UI स्वचालन और स्क्रीन समझ के लिए मॉडल"}),
    ("文本翻译所使用的模型", {
        "en": "Model used for text translation",
        "ja": "テキスト翻訳に使用するモデル", "ko": "텍스트 번역에 사용하는 모델",
        "de": "Modell für Textübersetzung", "fr": "Modèle utilisé pour la traduction de texte",
        "es": "Modelo usado para traducir texto", "pt": "Modelo usado para tradução de texto",
        "ru": "Модель для перевода текста", "ar": "النموذج المستخدم لترجمة النصوص",
        "hi": "पाठ अनुवाद के लिए मॉडल"}),
    ("代码检索与检索规划所使用的模型", {
        "en": "Model used for code search and retrieval planning",
        "ja": "コード検索と検索計画に使用するモデル", "ko": "코드 검색과 검색 계획에 사용하는 모델",
        "de": "Modell für Codesuche und Suchplanung", "fr": "Modèle utilisé pour la recherche de code",
        "es": "Modelo usado para la búsqueda de código", "pt": "Modelo usado para pesquisa de código",
        "ru": "Модель для поиска по коду", "ar": "النموذج المستخدم للبحث في الشيفرة",
        "hi": "कोड खोज और पुनर्प्राप्ति योजना के लिए मॉडल"}),
    ("灵魂卡自动孵化蒸馏所使用的模型", {
        "en": "Model used to incubate and distill soul cards automatically",
        "ja": "ソウルカードの自動生成と蒸留に使用するモデル",
        "ko": "소울 카드 자동 배양과 증류에 사용하는 모델",
        "de": "Modell für automatische Erstellung und Destillation von Soul-Karten",
        "fr": "Modèle utilisé pour générer et distiller les cartes d’âme",
        "es": "Modelo usado para incubar y destilar tarjetas de alma",
        "pt": "Modelo usado para incubar e destilar cartões de alma",
        "ru": "Модель для создания и дистилляции карт души",
        "ar": "النموذج المستخدم لإنشاء بطاقات الروح وتقطيرها",
        "hi": "सोल कार्ड बनाने और डिस्टिल करने वाला मॉडल"}),
    ("图片内容理解所使用的模型（需支持视觉）", {
        "en": "Model used to understand images (must support vision)",
        "ja": "画像内容の理解に使用するモデル（視覚対応必須）",
        "ko": "이미지 내용 이해에 사용하는 모델(비전 지원 필요)",
        "de": "Modell zum Verstehen von Bildinhalten (muss Vision unterstützen)",
        "fr": "Modèle utilisé pour comprendre les images (vision requise)",
        "es": "Modelo usado para entender imágenes (requiere visión)",
        "pt": "Modelo usado para entender imagens (requer visão)",
        "ru": "Модель для понимания изображений (нужна поддержка зрения)",
        "ar": "النموذج المستخدم لفهم الصور (يجب أن يدعم الرؤية)",
        "hi": "छवि सामग्री समझने वाला मॉडल (विज़न समर्थन आवश्यक)"}),
    ("音频内容理解所使用的模型", {
        "en": "Model used to understand audio",
        "ja": "音声内容の理解に使用するモデル", "ko": "오디오 내용 이해에 사용하는 모델",
        "de": "Modell zum Verstehen von Audioinhalten", "fr": "Modèle utilisé pour comprendre l’audio",
        "es": "Modelo usado para entender audio", "pt": "Modelo usado para entender áudio",
        "ru": "Модель для понимания аудио", "ar": "النموذج المستخدم لفهم الصوت",
        "hi": "ऑडियो सामग्री समझने वाला मॉडल"}),
    ("视频与视频通话实时画面理解所使用的模型（需支持视觉）", {
        "en": "Model used to understand video and live video-call frames (must support vision)",
        "ja": "動画とビデオ通話のリアルタイム映像の理解に使用するモデル（視覚対応必須）",
        "ko": "비디오와 영상 통화 실시간 화면 이해에 사용하는 모델(비전 지원 필요)",
        "de": "Modell zum Verstehen von Videos und Live-Bildern im Videoanruf (muss Vision unterstützen)",
        "fr": "Modèle utilisé pour comprendre la vidéo et les images d’appel en direct (vision requise)",
        "es": "Modelo usado para entender vídeo y fotogramas de videollamada (requiere visión)",
        "pt": "Modelo usado para entender vídeo e quadros de chamada de vídeo (requer visão)",
        "ru": "Модель для понимания видео и кадров видеозвонка (нужна поддержка зрения)",
        "ar": "النموذج المستخدم لفهم الفيديو ومشاهد مكالمة الفيديو (يجب أن يدعم الرؤية)",
        "hi": "वीडियो और वीडियो कॉल के लाइव फ़्रेम समझने वाला मॉडल (विज़न आवश्यक)"}),
    ("AI 可直接调用的图片生成模型", {
        "en": "Image generation model the AI can call directly",
        "ja": "AI が直接呼び出せる画像生成モデル", "ko": "AI가 직접 호출할 수 있는 이미지 생성 모델",
        "de": "Bilderzeugungsmodell, das die KI direkt aufrufen kann",
        "fr": "Modèle de génération d’image appelable directement par l’IA",
        "es": "Modelo de generación de imágenes que la IA puede llamar",
        "pt": "Modelo de geração de imagem que a IA pode chamar",
        "ru": "Модель генерации изображений, вызываемая ИИ напрямую",
        "ar": "نموذج توليد الصور الذي يمكن للذكاء الاصطناعي استدعاؤه مباشرة",
        "hi": "छवि निर्माण मॉडल जिसे AI सीधे कॉल कर सकता है"}),
    ("AI 可直接调用的视频生成模型", {
        "en": "Video generation model the AI can call directly",
        "ja": "AI が直接呼び出せる動画生成モデル", "ko": "AI가 직접 호출할 수 있는 비디오 생성 모델",
        "de": "Videoerzeugungsmodell, das die KI direkt aufrufen kann",
        "fr": "Modèle de génération de vidéo appelable directement par l’IA",
        "es": "Modelo de generación de vídeo que la IA puede llamar",
        "pt": "Modelo de geração de vídeo que a IA pode chamar",
        "ru": "Модель генерации видео, вызываемая ИИ напрямую",
        "ar": "نموذج توليد الفيديو الذي يمكن للذكاء الاصطناعي استدعاؤه مباشرة",
        "hi": "वीडियो निर्माण मॉडल जिसे AI सीधे कॉल कर सकता है"}),

    # ══ 功能模型配置页 ══
    ("功能模型配置", {"en": "Feature model config", "ja": "機能モデル設定", "ko": "기능 모델 설정",
                "de": "Funktionsmodell-Konfiguration", "fr": "Configuration des modèles par fonction",
                "es": "Configuración de modelos por función", "pt": "Configuração de modelos por função",
                "ru": "Настройка моделей по функциям", "ar": "إعداد نماذج الميزات", "hi": "फ़ीचर मॉडल कॉन्फ़िग"}),
    ("为各类 AI 能力绑定模型", {"en": "Bind a model to each AI capability", "ja": "各 AI 機能にモデルを割り当てる",
                      "ko": "각 AI 기능에 모델을 연결", "de": "Jedem KI-Feature ein Modell zuweisen",
                      "fr": "Associez un modèle à chaque capacité d’IA",
                      "es": "Asigna un modelo a cada capacidad de IA",
                      "pt": "Atribua um modelo a cada capacidade de IA",
                      "ru": "Назначьте модель для каждой возможности ИИ",
                      "ar": "اربط نموذجًا بكل قدرة من قدرات الذكاء الاصطناعي",
                      "hi": "हर AI क्षमता के लिए मॉडल चुनें"}),
    ("每个功能可「跟随主模型」或指定独立模型；指定独立模型时还可单独填写基础 URL 与 API Key。已接入引擎并实时生效：常规对话、人格孵化、UI 控制、图像识别、视频识别。", {
        "en": "Each feature can follow the primary model or use a dedicated one; a dedicated model can have its own base URL and API key. Wired into the engine and effective immediately: regular chat, persona incubation, UI control, image recognition, video recognition.",
        "ja": "各機能は「メインモデルに従う」か専用モデルを指定できます。専用モデルには独自のベース URL と API Key も設定できます。エンジンに接続済みで即時反映：通常の会話、ペルソナ生成、UI 操作、画像認識、動画認識。",
        "ko": "각 기능은 메인 모델을 따르거나 전용 모델을 지정할 수 있습니다. 전용 모델에는 자체 베이스 URL과 API Key를 입력할 수 있습니다. 엔진에 연결되어 즉시 적용: 일반 대화, 페르소나 배양, UI 제어, 이미지 인식, 비디오 인식.",
        "de": "Jede Funktion kann dem Hauptmodell folgen oder ein eigenes Modell nutzen; ein eigenes Modell darf eigene Basis-URL und eigenen API-Key haben. Bereits angebunden und sofort wirksam: normales Gespräch, Persona-Ausarbeitung, UI-Steuerung, Bilderkennung, Videoerkennung.",
        "fr": "Chaque fonction peut suivre le modèle principal ou utiliser un modèle dédié ; un modèle dédié peut avoir sa propre URL de base et sa clé API. Déjà relié au moteur et actif immédiatement : conversation normale, incubation de persona, contrôle de l’interface, reconnaissance d’image et de vidéo.",
        "es": "Cada función puede seguir el modelo principal o usar uno propio; un modelo propio puede tener su propia URL base y clave de API. Ya integrado en el motor y con efecto inmediato: conversación normal, incubación de persona, control de interfaz, reconocimiento de imagen y de vídeo.",
        "pt": "Cada função pode seguir o modelo principal ou usar um modelo dedicado; um modelo dedicado pode ter a própria URL base e chave de API. Já ligado ao motor e com efeito imediato: conversa normal, incubação de persona, controle da interface, reconhecimento de imagem e de vídeo.",
        "ru": "Каждая функция может следовать основной модели или использовать отдельную; у отдельной модели могут быть свои базовый URL и API-ключ. Уже подключено к движку и действует сразу: обычный диалог, инкубация персоны, управление интерфейсом, распознавание изображений и видео.",
        "ar": "يمكن لكل ميزة أن تتبع النموذج الأساسي أو تستخدم نموذجًا مستقلًا، ويمكن للنموذج المستقل أن يحمل رابط الأساس ومفتاح API الخاصين به. متصل بالمحرك وساري فورًا: المحادثة العادية، إنشاء الشخصية، التحكم بالواجهة، التعرف على الصور والفيديو.",
        "hi": "हर फ़ीचर मुख्य मॉडल का पालन कर सकता है या अपना अलग मॉडल चुन सकता है; अलग मॉडल के लिए अपना बेस URL और API Key भी भर सकते हैं। इंजन से जुड़ा और तुरंत लागू: सामान्य चैट, पर्सोना निर्माण, UI नियंत्रण, छवि पहचान, वीडियो पहचान।"}),
    ("视觉识别与视频通话：图像识别与视频识别需要支持视觉能力的模型（如 GPT-4V）。为它们指定独立模型并填好基础 URL / API Key 后，视觉分析与视频通话的实时画面理解即改用该模型。", {
        "en": "Vision and video calls: image and video recognition need models with vision support (e.g. GPT-4V). Give them a dedicated model with its own base URL and API key, and visual analysis plus live video-call understanding will use that model.",
        "ja": "視覚認識とビデオ通話：画像認識と動画認識には視覚対応モデル（例：GPT-4V）が必要です。専用モデルとベース URL / API Key を設定すると、視覚分析とビデオ通話のリアルタイム映像理解がそのモデルを使うようになります。",
        "ko": "비전 인식과 영상 통화: 이미지·비디오 인식에는 비전을 지원하는 모델(예: GPT-4V)이 필요합니다. 전용 모델과 베이스 URL / API Key를 지정하면 시각 분석과 영상 통화의 실시간 화면 이해가 해당 모델을 사용합니다.",
        "de": "Vision und Videoanrufe: Bild- und Videoerkennung benötigen Modelle mit Vision-Unterstützung (z. B. GPT-4V). Weisen Sie ihnen ein eigenes Modell mit Basis-URL und API-Key zu, und Bildanalyse sowie Live-Verständnis im Videoanruf nutzen dieses Modell.",
        "fr": "Vision et appels vidéo : la reconnaissance d’image et de vidéo nécessite des modèles avec vision (ex. GPT-4V). Associez-leur un modèle dédié avec URL de base et clé API : l’analyse visuelle et la compréhension des images d’appel utiliseront ce modèle.",
        "es": "Visión y videollamadas: el reconocimiento de imagen y vídeo necesita modelos con visión (p. ej. GPT-4V). Asígnales un modelo propio con su URL base y clave de API, y el análisis visual y la comprensión en videollamada usarán ese modelo.",
        "pt": "Visão e chamadas de vídeo: o reconhecimento de imagem e vídeo precisa de modelos com visão (ex.: GPT-4V). Atribua a eles um modelo dedicado com URL base e chave de API, e a análise visual e a compreensão na chamada usarão esse modelo.",
        "ru": "Зрение и видеозвонки: распознавание изображений и видео требует моделей с поддержкой зрения (например, GPT-4V). Назначьте им отдельную модель с базовым URL и API-ключом — визуальный анализ и понимание кадров видеозвонка будут использовать её.",
        "ar": "الرؤية ومكالمات الفيديو: يحتاج التعرف على الصور والفيديو إلى نماذج تدعم الرؤية (مثل GPT-4V). عيّن لها نموذجًا مستقلًا مع رابط الأساس ومفتاح API، وسيستخدم التحليل البصري وفهم مشاهد مكالمة الفيديو ذلك النموذج.",
        "hi": "विज़न और वीडियो कॉल: छवि व वीडियो पहचान के लिए विज़न समर्थित मॉडल (जैसे GPT-4V) चाहिए। इनके लिए अलग मॉडल और बेस URL / API Key भरें, फिर विज़ुअल विश्लेषण और वीडियो कॉल का लाइव फ़्रेम विश्लेषण उसी मॉडल का उपयोग करेगा।"}),
    ("提示：默认所有能力跟随主模型（设置 → 模型配置）。图片与视频生成建议独立指定对应模型；独立绑定会复用主接入点的地址与密钥，仅替换模型名。", {
        "en": "Tip: by default every capability follows the primary model (Settings → Model config). For image and video generation, using a dedicated model is recommended; a dedicated binding reuses the primary endpoint’s URL and key and only replaces the model name.",
        "ja": "ヒント：既定ではすべての機能がメインモデルに従います（設定 → モデル設定）。画像・動画生成には専用モデルの指定を推奨します。専用バインドはメイン接続先の URL とキーを再利用し、モデル名のみ置き換えます。",
        "ko": "안내: 기본적으로 모든 기능은 메인 모델을 따릅니다(설정 → 모델 설정). 이미지·비디오 생성에는 전용 모델 지정을 권장합니다. 전용 바인딩은 메인 접속점의 URL과 키를 재사용하고 모델 이름만 교체합니다.",
        "de": "Tipp: Standardmäßig folgen alle Funktionen dem Hauptmodell (Einstellungen → Modellkonfiguration). Für Bild- und Videoerzeugung empfiehlt sich ein eigenes Modell; eine eigene Bindung nutzt URL und Schlüssel des Hauptendpunkts und ersetzt nur den Modellnamen.",
        "fr": "Astuce : par défaut, toutes les fonctions suivent le modèle principal (Paramètres → Configuration du modèle). Pour la génération d’image et de vidéo, un modèle dédié est conseillé ; une liaison dédiée réutilise l’URL et la clé du point d’accès principal et ne remplace que le nom du modèle.",
        "es": "Consejo: por defecto todas las funciones siguen el modelo principal (Ajustes → Configuración de modelo). Para generar imagen y vídeo se recomienda un modelo propio; la vinculación propia reutiliza la URL y la clave del punto de acceso principal y solo cambia el nombre del modelo.",
        "pt": "Dica: por padrão todas as funções seguem o modelo principal (Configurações → Configuração de modelo). Para geração de imagem e vídeo, recomenda-se um modelo dedicado; a vinculação dedicada reutiliza a URL e a chave do ponto de acesso principal e só troca o nome do modelo.",
        "ru": "Подсказка: по умолчанию все функции используют основную модель (Настройки → Настройка моделей). Для генерации изображений и видео лучше назначить отдельную модель; отдельная привязка использует URL и ключ основного подключения и меняет только имя модели.",
        "ar": "تلميح: افتراضيًا تتبع كل القدرات النموذج الأساسي (الإعدادات ← إعداد النموذج). يُنصح بتحديد نموذج مستقل لتوليد الصور والفيديو؛ الربط المستقل يعيد استخدام رابط نقطة الوصول الأساسية ومفتاحها ويستبدل اسم النموذج فقط.",
        "hi": "सुझाव: डिफ़ॉल्ट रूप से सभी क्षमताएँ मुख्य मॉडल का पालन करती हैं (सेटिंग → मॉडल कॉन्फ़िग)। छवि व वीडियो निर्माण के लिए अलग मॉडल चुनने की सलाह है; अलग बाइंडिंग मुख्य एंडपॉइंट का URL व कुंजी पुनः उपयोग करती है और केवल मॉडल नाम बदलती है।"}),
    ("需先在主模型配置填好接入点", {
        "en": "Configure an endpoint in the main model settings first",
        "ja": "先にメインモデル設定で接続先を設定してください",
        "ko": "먼저 메인 모델 설정에서 접속점을 입력하세요",
        "de": "Bitte zuerst in den Hauptmodelleinstellungen einen Endpunkt hinterlegen",
        "fr": "Configurez d’abord un point d’accès dans les réglages du modèle principal",
        "es": "Configura antes un punto de acceso en los ajustes del modelo principal",
        "pt": "Configure antes um ponto de acesso nas configurações do modelo principal",
        "ru": "Сначала укажите подключение в настройках основной модели",
        "ar": "اضبط نقطة وصول في إعدادات النموذج الأساسي أولًا",
        "hi": "पहले मुख्य मॉडल सेटिंग में एंडपॉइंट भरें"}),
    ("正在拉取模型列表…", {"en": "Fetching model list…", "ja": "モデル一覧を取得中…", "ko": "모델 목록을 가져오는 중…",
                  "de": "Modellliste wird geladen…", "fr": "Récupération de la liste des modèles…",
                  "es": "Obteniendo la lista de modelos…", "pt": "Obtendo a lista de modelos…",
                  "ru": "Получение списка моделей…", "ar": "جارٍ جلب قائمة النماذج…",
                  "hi": "मॉडल सूची लाई जा रही है…"}),
    ("拉取失败：$error\\n已为你保留已配置的模型，也可直接手动输入模型名。", {
        "en": "Fetch failed: $error\\nYour configured models were kept; you can also type a model name manually.",
        "ja": "取得に失敗しました：$error\\n設定済みのモデルは保持されています。モデル名を直接入力することもできます。",
        "ko": "가져오기 실패: $error\\n설정된 모델은 유지되었습니다. 모델 이름을 직접 입력할 수도 있습니다.",
        "de": "Abruf fehlgeschlagen: $error\\nIhre konfigurierten Modelle bleiben erhalten; Sie können den Modellnamen auch manuell eingeben.",
        "fr": "Échec de la récupération : $error\\nVos modèles configurés sont conservés ; vous pouvez aussi saisir un nom de modèle.",
        "es": "Error al obtener: $error\\nSe conservaron tus modelos configurados; también puedes escribir el nombre del modelo.",
        "pt": "Falha ao obter: $error\\nSeus modelos configurados foram mantidos; você também pode digitar o nome do modelo.",
        "ru": "Не удалось получить: $error\\nНастроенные модели сохранены; можно также ввести имя модели вручную.",
        "ar": "فشل الجلب: $error\\nتم الاحتفاظ بالنماذج المهيأة، ويمكنك إدخال اسم النموذج يدويًا.",
        "hi": "प्राप्ति विफल: $error\\nआपके कॉन्फ़िगर किए गए मॉडल सुरक्षित रखे गए हैं; आप मॉडल नाम हाथ से भी भर सकते हैं।"}),
    ("拉取失败：", {"en": "Fetch failed: ", "ja": "取得に失敗しました：", "ko": "가져오기 실패: ",
              "de": "Abruf fehlgeschlagen: ", "fr": "Échec de la récupération : ",
              "es": "Error al obtener: ", "pt": "Falha ao obter: ",
              "ru": "Не удалось получить: ", "ar": "فشل الجلب: ", "hi": "प्राप्ति विफल: "}),
    ("已为你保留已配置的模型，也可直接手动输入模型名。", {
        "en": "Your configured models were kept; you can also type a model name manually.",
        "ja": "設定済みのモデルは保持されています。モデル名を直接入力することもできます。",
        "ko": "설정된 모델은 유지되었습니다. 모델 이름을 직접 입력할 수도 있습니다.",
        "de": "Ihre konfigurierten Modelle bleiben erhalten; Sie können den Modellnamen auch manuell eingeben.",
        "fr": "Vos modèles configurés sont conservés ; vous pouvez aussi saisir un nom de modèle.",
        "es": "Se conservaron tus modelos configurados; también puedes escribir el nombre del modelo.",
        "pt": "Seus modelos configurados foram mantidos; você também pode digitar o nome do modelo.",
        "ru": "Настроенные модели сохранены; можно также ввести имя модели вручную.",
        "ar": "تم الاحتفاظ بالنماذج المهيأة، ويمكنك إدخال اسم النموذج يدويًا.",
        "hi": "आपके कॉन्फ़िगर किए गए मॉडल सुरक्षित रखे गए हैं; आप मॉडल नाम हाथ से भी भर सकते हैं।"}),
    ("选择 %1$s 模型", {"en": "Choose a model for %1$s", "ja": "%1$s のモデルを選択", "ko": "%1$s 모델 선택",
                  "de": "Modell für %1$s wählen", "fr": "Choisir un modèle pour %1$s",
                  "es": "Elegir un modelo para %1$s", "pt": "Escolher um modelo para %1$s",
                  "ru": "Выбор модели для %1$s", "ar": "اختر نموذجًا لـ %1$s", "hi": "%1$s के लिए मॉडल चुनें"}),
    ("跟随主模型", {"en": "Follow primary model", "ja": "メインモデルに従う", "ko": "메인 모델 따르기",
              "de": "Hauptmodell folgen", "fr": "Suivre le modèle principal",
              "es": "Seguir el modelo principal", "pt": "Seguir o modelo principal",
              "ru": "Следовать основной модели", "ar": "اتّباع النموذج الأساسي", "hi": "मुख्य मॉडल का पालन"}),
    ("独立模型", {"en": "Dedicated model", "ja": "専用モデル", "ko": "전용 모델", "de": "Eigenes Modell",
              "fr": "Modèle dédié", "es": "Modelo propio", "pt": "Modelo dedicado",
              "ru": "Отдельная модель", "ar": "نموذج مستقل", "hi": "अलग मॉडल"}),
    ("点击选择模型", {"en": "Tap to choose a model", "ja": "タップしてモデルを選択", "ko": "탭하여 모델 선택",
              "de": "Tippen, um ein Modell zu wählen", "fr": "Appuyez pour choisir un modèle",
              "es": "Toca para elegir un modelo", "pt": "Toque para escolher um modelo",
              "ru": "Нажмите, чтобы выбрать модель", "ar": "اضغط لاختيار نموذج", "hi": "मॉडल चुनने के लिए टैप करें"}),
    ("已接入引擎·开关生效", {"en": "Wired into the engine · takes effect", "ja": "エンジン接続済み・設定が反映されます",
                   "ko": "엔진 연결됨 · 즉시 적용", "de": "An Engine angebunden · wirkt sofort",
                   "fr": "Relié au moteur · actif", "es": "Conectado al motor · tiene efecto",
                   "pt": "Ligado ao motor · tem efeito", "ru": "Подключено к движку · действует",
                   "ar": "متصل بالمحرك · يسري فورًا", "hi": "इंजन से जुड़ा · तुरंत लागू"}),
    ("对话内调用·跟随主对话", {"en": "Called inside chat · follows the main chat", "ja": "会話内で呼び出し・メイン会話に従う",
                    "ko": "대화 내 호출 · 메인 대화 따름", "de": "Aufruf im Chat · folgt dem Hauptgespräch",
                    "fr": "Appelé dans la conversation · suit la conversation principale",
                    "es": "Se llama en el chat · sigue al chat principal",
                    "pt": "Chamado no chat · segue o chat principal",
                    "ru": "Вызов внутри диалога · следует основному диалогу",
                    "ar": "يُستدعى داخل المحادثة · يتبع المحادثة الرئيسية",
                    "hi": "चैट के भीतर कॉल · मुख्य चैट का पालन"}),
    ("高级配置：已设自定义端点", {"en": "Advanced: custom endpoint set", "ja": "詳細設定：カスタム接続先を設定済み",
                     "ko": "고급 설정: 사용자 지정 엔드포인트 설정됨",
                     "de": "Erweitert: eigener Endpunkt gesetzt", "fr": "Avancé : point d’accès personnalisé défini",
                     "es": "Avanzado: punto de acceso propio definido",
                     "pt": "Avançado: ponto de acesso próprio definido",
                     "ru": "Дополнительно: задан свой адрес", "ar": "إعدادات متقدمة: تم تعيين نقطة وصول مخصصة",
                     "hi": "उन्नत: कस्टम एंडपॉइंट सेट"}),
    ("高级配置：已设自定义密钥", {"en": "Advanced: custom key set", "ja": "詳細設定：カスタムキーを設定済み",
                     "ko": "고급 설정: 사용자 지정 키 설정됨", "de": "Erweitert: eigener Schlüssel gesetzt",
                     "fr": "Avancé : clé personnalisée définie", "es": "Avanzado: clave propia definida",
                     "pt": "Avançado: chave própria definida", "ru": "Дополнительно: задан свой ключ",
                     "ar": "إعدادات متقدمة: تم تعيين مفتاح مخصص", "hi": "उन्नत: कस्टम कुंजी सेट"}),
    ("高级配置：已设自定义端点与密钥", {"en": "Advanced: custom endpoint and key set",
                        "ja": "詳細設定：カスタム接続先とキーを設定済み",
                        "ko": "고급 설정: 사용자 지정 엔드포인트와 키 설정됨",
                        "de": "Erweitert: eigener Endpunkt und Schlüssel gesetzt",
                        "fr": "Avancé : point d’accès et clé personnalisés définis",
                        "es": "Avanzado: punto de acceso y clave propios definidos",
                        "pt": "Avançado: ponto de acesso e chave próprios definidos",
                        "ru": "Дополнительно: заданы свой адрес и ключ",
                        "ar": "إعدادات متقدمة: تم تعيين نقطة وصول ومفتاح مخصصين",
                        "hi": "उन्नत: कस्टम एंडपॉइंट और कुंजी सेट"}),

    # ══ GenUI Agent — 快捷操作面板 ══
    ("（我摸了摸宠物）", {"en": "(I petted the pet)", "ja": "（ペットをなでた）", "ko": "(펫을 쓰다듬었다)",
                  "de": "(Ich habe das Haustier gestreichelt)", "fr": "(J’ai caressé l’animal)",
                  "es": "(Acaricié a la mascota)", "pt": "(Fiz carinho no pet)",
                  "ru": "(Я погладил питомца)", "ar": "(لمست الحيوان الأليف)", "hi": "(मैंने पेट को सहलाया)"}),
    ("跟随 ZorvAI", {"en": "Follow ZorvAI", "ja": "ZorvAI に従う", "ko": "ZorvAI 따르기",
                  "de": "ZorvAI folgen", "fr": "Suivre ZorvAI", "es": "Seguir a ZorvAI",
                  "pt": "Seguir o ZorvAI", "ru": "Следовать ZorvAI", "ar": "اتّباع ZorvAI",
                  "hi": "ZorvAI का पालन करें"}),
    ("思考面板", {"en": "Thinking panel", "ja": "思考パネル", "ko": "사고 패널", "de": "Denk-Panel",
              "fr": "Panneau de réflexion", "es": "Panel de pensamiento", "pt": "Painel de pensamento",
              "ru": "Панель рассуждений", "ar": "لوحة التفكير", "hi": "सोच पैनल"}),
    ("查看推理过程", {"en": "View reasoning", "ja": "推論プロセスを表示", "ko": "추론 과정 보기",
                "de": "Denkprozess ansehen", "fr": "Voir le raisonnement", "es": "Ver el razonamiento",
                "pt": "Ver o raciocínio", "ru": "Показать рассуждения", "ar": "عرض الاستدلال",
                "hi": "तर्क प्रक्रिया देखें"}),
    ("设置中心", {"en": "Settings hub", "ja": "設定センター", "ko": "설정 센터", "de": "Einstellungen",
              "fr": "Centre de réglages", "es": "Centro de ajustes", "pt": "Central de ajustes",
              "ru": "Центр настроек", "ar": "مركز الإعدادات", "hi": "सेटिंग केंद्र"}),
    ("身份 / 模型 / 历史", {"en": "Identity / model / history", "ja": "アイデンティティ / モデル / 履歴",
                    "ko": "정체성 / 모델 / 기록", "de": "Identität / Modell / Verlauf",
                    "fr": "Identité / modèle / historique", "es": "Identidad / modelo / historial",
                    "pt": "Identidade / modelo / histórico", "ru": "Личность / модель / история",
                    "ar": "الهوية / النموذج / السجل", "hi": "पहचान / मॉडल / इतिहास"}),
    ("切换/导入导出", {"en": "Switch, import & export", "ja": "切り替え／読み込み・書き出し",
                 "ko": "전환/가져오기·내보내기", "de": "Wechseln, Im- & Export",
                 "fr": "Changer, importer et exporter", "es": "Cambiar, importar y exportar",
                 "pt": "Trocar, importar e exportar", "ru": "Сменить, импорт и экспорт",
                 "ar": "التبديل / الاستيراد والتصدير", "hi": "बदलें / आयात-निर्यात"}),
    ("清空画布", {"en": "Clear canvas", "ja": "キャンバスを消去", "ko": "캔버스 비우기", "de": "Leinwand leeren",
              "fr": "Effacer le canevas", "es": "Vaciar el lienzo", "pt": "Limpar a tela",
              "ru": "Очистить холст", "ar": "مسح اللوحة", "hi": "कैनवास साफ़ करें"}),
    ("回到空白状态", {"en": "Back to blank", "ja": "空白状態に戻す", "ko": "빈 상태로 되돌리기",
                "de": "Zurück zum leeren Zustand", "fr": "Revenir à l’état vide",
                "es": "Volver al estado vacío", "pt": "Voltar ao estado vazio",
                "ru": "Вернуть пустое состояние", "ar": "العودة إلى الحالة الفارغة",
                "hi": "खाली स्थिति में लौटें"}),
    ("原始输出", {"en": "Raw output", "ja": "生の出力", "ko": "원시 출력", "de": "Rohausgabe",
              "fr": "Sortie brute", "es": "Salida sin formato", "pt": "Saída bruta",
              "ru": "Исходный вывод", "ar": "المخرجات الخام", "hi": "कच्चा आउटपुट"}),
    ("(无参数)", {"en": "(no arguments)", "ja": "（引数なし）", "ko": "(인수 없음)", "de": "(keine Argumente)",
              "fr": "(aucun argument)", "es": "(sin argumentos)", "pt": "(sem argumentos)",
              "ru": "(нет аргументов)", "ar": "(لا وسائط)", "hi": "(कोई आर्ग्युमेंट नहीं)"}),
    ("$diff 秒前", {"en": "$diff sec ago"}),
    ("${diff / 1000} 秒前", {"en": "${diff / 1000} sec ago", "ja": "${diff / 1000} 秒前",
                        "ko": "${diff / 1000}초 전", "de": "vor ${diff / 1000} Sek.",
                        "fr": "il y a ${diff / 1000} s", "es": "hace ${diff / 1000} s",
                        "pt": "há ${diff / 1000} s", "ru": "${diff / 1000} сек назад",
                        "ar": "قبل ${diff / 1000} ثانية", "hi": "${diff / 1000} सेकंड पहले"}),
    ("${diff / 60000} 分钟前", {"en": "${diff / 60000} min ago", "ja": "${diff / 60000} 分前",
                          "ko": "${diff / 60000}분 전", "de": "vor ${diff / 60000} Min.",
                          "fr": "il y a ${diff / 60000} min", "es": "hace ${diff / 60000} min",
                          "pt": "há ${diff / 60000} min", "ru": "${diff / 60000} мин назад",
                          "ar": "قبل ${diff / 60000} دقيقة", "hi": "${diff / 60000} मिनट पहले"}),
    ("${diff / 3600000} 小时前", {"en": "${diff / 3600000} h ago", "ja": "${diff / 3600000} 時間前",
                            "ko": "${diff / 3600000}시간 전", "de": "vor ${diff / 3600000} Std.",
                            "fr": "il y a ${diff / 3600000} h", "es": "hace ${diff / 3600000} h",
                            "pt": "há ${diff / 3600000} h", "ru": "${diff / 3600000} ч назад",
                            "ar": "قبل ${diff / 3600000} ساعة", "hi": "${diff / 3600000} घंटे पहले"}),
    ("AI 正在生成界面...", {"en": "AI is generating the interface…", "ja": "AI が画面を生成しています…",
                     "ko": "AI가 화면을 생성하는 중…", "de": "KI erzeugt die Oberfläche…",
                     "fr": "L’IA génère l’interface…", "es": "La IA está generando la interfaz…",
                     "pt": "A IA está gerando a interface…", "ru": "ИИ создаёт интерфейс…",
                     "ar": "الذكاء الاصطناعي ينشئ الواجهة…", "hi": "AI इंटरफ़ेस बना रहा है…"}),
    ("今天天气怎么样", {"en": "What’s the weather today", "ja": "今日の天気は？", "ko": "오늘 날씨 어때",
                 "de": "Wie ist das Wetter heute", "fr": "Quel temps fait-il aujourd’hui",
                 "es": "¿Qué tiempo hace hoy", "pt": "Como está o tempo hoje",
                 "ru": "Какая сегодня погода", "ar": "كيف الطقس اليوم", "hi": "आज मौसम कैसा है"}),
    ("讲个笑话听听", {"en": "Tell me a joke", "ja": "ジョークを聞かせて", "ko": "농담 하나 해줘",
                "de": "Erzähl mir einen Witz", "fr": "Raconte-moi une blague",
                "es": "Cuéntame un chiste", "pt": "Conte uma piada",
                "ru": "Расскажи анекдот", "ar": "أخبرني نكتة", "hi": "एक चुटकुला सुनाओ"}),
    ("帮我算个账", {"en": "Help me do the math", "ja": "計算を手伝って", "ko": "계산 좀 도와줘",
               "de": "Hilf mir beim Rechnen", "fr": "Aide-moi à calculer",
               "es": "Ayúdame a calcular", "pt": "Me ajude a calcular",
               "ru": "Помоги посчитать", "ar": "ساعدني في الحساب", "hi": "गणना में मदद करो"}),
    ("推荐一首音乐", {"en": "Recommend some music", "ja": "音楽を一曲おすすめして", "ko": "음악 한 곡 추천해줘",
                "de": "Empfiehl mir Musik", "fr": "Recommande-moi une musique",
                "es": "Recomiéndame música", "pt": "Recomende uma música",
                "ru": "Порекомендуй музыку", "ar": "اقترح لي موسيقى", "hi": "कोई संगीत सुझाओ"}),
    ("今日运势", {"en": "Today’s fortune", "ja": "今日の運勢", "ko": "오늘의 운세", "de": "Tageshoroskop",
              "fr": "Horoscope du jour", "es": "Fortuna de hoy", "pt": "Sorte de hoje",
              "ru": "Гороскоп на сегодня", "ar": "حظ اليوم", "hi": "आज का भाग्य"}),
    ("说晚安", {"en": "Say good night", "ja": "おやすみを言う", "ko": "잘 자라고 말해줘",
              "de": "Gute Nacht sagen", "fr": "Dire bonne nuit", "es": "Decir buenas noches",
              "pt": "Dizer boa noite", "ru": "Пожелать спокойной ночи",
              "ar": "قل تصبح على خير", "hi": "शुभ रात्रि कहो"}),

    # ══ GenUI Agent — 设置 / 宠物 / 预览 ══
    ("人格卡：$personaName · 模型：$modelLabel（由 ZorvAI 主设置统一管理）", {
        "en": "Persona: $personaName · Model: $modelLabel (managed in ZorvAI main settings)",
        "ja": "ペルソナ：$personaName · モデル：$modelLabel（ZorvAI 本体の設定で一元管理）",
        "ko": "페르소나: $personaName · 모델: $modelLabel(ZorvAI 메인 설정에서 관리)",
        "de": "Persona: $personaName · Modell: $modelLabel (zentral in den ZorvAI-Haupteinstellungen)",
        "fr": "Persona : $personaName · Modèle : $modelLabel (géré dans les réglages ZorvAI)",
        "es": "Persona: $personaName · Modelo: $modelLabel (gestionado en los ajustes de ZorvAI)",
        "pt": "Persona: $personaName · Modelo: $modelLabel (gerenciado nas configurações do ZorvAI)",
        "ru": "Персона: $personaName · Модель: $modelLabel (управляется в основных настройках ZorvAI)",
        "ar": "الشخصية: $personaName · النموذج: $modelLabel (تُدار من إعدادات ZorvAI الرئيسية)",
        "hi": "पर्सोना: $personaName · मॉडल: $modelLabel (ZorvAI मुख्य सेटिंग में प्रबंधित)"}),
    ("共 $worksCount 个界面记录 · 点击回放与查看往来", {
        "en": "$worksCount interface records · tap to replay and view the exchange",
        "ja": "画面記録 $worksCount 件 · タップで再生とやり取りを表示",
        "ko": "화면 기록 $worksCount개 · 탭하여 재생 및 대화 보기",
        "de": "$worksCount Oberflächen-Einträge · tippen zum Abspielen und Ansehen",
        "fr": "$worksCount enregistrements d’interface · appuyez pour rejouer et consulter",
        "es": "$worksCount registros de interfaz · toca para reproducir y ver",
        "pt": "$worksCount registros de interface · toque para reproduzir e ver",
        "ru": "$worksCount записей интерфейса · нажмите для просмотра",
        "ar": "$worksCount سجل واجهة · اضغط لإعادة العرض والاطلاع",
        "hi": "$worksCount इंटरफ़ेस रिकॉर्ड · रीप्ले और बातचीत देखने के लिए टैप करें"}),
    ("JSON 解析失败：字段不完整", {"en": "JSON parse failed: incomplete fields",
                          "ja": "JSON の解析に失敗：項目が不完全です",
                          "ko": "JSON 파싱 실패: 필드가 불완전합니다",
                          "de": "JSON-Analyse fehlgeschlagen: unvollständige Felder",
                          "fr": "Échec de l’analyse JSON : champs incomplets",
                          "es": "Error al analizar JSON: campos incompletos",
                          "pt": "Falha ao analisar JSON: campos incompletos",
                          "ru": "Ошибка разбора JSON: неполные поля",
                          "ar": "فشل تحليل JSON: حقول غير مكتملة",
                          "hi": "JSON पार्स विफल: फ़ील्ड अधूरे हैं"}),
    ("无法读取文件", {"en": "Cannot read the file", "ja": "ファイルを読み込めません", "ko": "파일을 읽을 수 없습니다",
                "de": "Datei kann nicht gelesen werden", "fr": "Impossible de lire le fichier",
                "es": "No se puede leer el archivo", "pt": "Não foi possível ler o arquivo",
                "ru": "Не удалось прочитать файл", "ar": "تعذّر قراءة الملف",
                "hi": "फ़ाइल पढ़ी नहीं जा सकती"}),
    ("导出宠物 JSON", {"en": "Export pet JSON", "ja": "ペット JSON を書き出す", "ko": "펫 JSON 내보내기",
                 "de": "Haustier-JSON exportieren", "fr": "Exporter le JSON du compagnon",
                 "es": "Exportar JSON de la mascota", "pt": "Exportar JSON do pet",
                 "ru": "Экспорт JSON питомца", "ar": "تصدير JSON للحيوان الأليف",
                 "hi": "पेट JSON निर्यात करें"}),
    ("显示宠物", {"en": "Show pet", "ja": "ペットを表示", "ko": "펫 표시", "de": "Haustier anzeigen",
              "fr": "Afficher le compagnon", "es": "Mostrar mascota", "pt": "Mostrar pet",
              "ru": "Показывать питомца", "ar": "إظهار الحيوان الأليف", "hi": "पेट दिखाएँ"}),
    ("关闭后画布上隐藏宠物", {"en": "Hide the pet on the canvas when off", "ja": "オフにするとキャンバス上でペットを隠します",
                   "ko": "끄면 캔버스에서 펫을 숨깁니다", "de": "Blendet das Haustier auf der Leinwand aus",
                   "fr": "Masque le compagnon sur le canevas", "es": "Oculta la mascota en el lienzo",
                   "pt": "Oculta o pet na tela", "ru": "Скрывает питомца на холсте",
                   "ar": "إخفاء الحيوان الأليف على اللوحة", "hi": "बंद करने पर कैनवास पर पेट छिपाएँ"}),
    ("点击宠物打开侧边栏", {"en": "Tap the pet to open the side panel", "ja": "ペットをタップでサイドバーを開く",
                   "ko": "펫을 탭하면 사이드 패널 열기", "de": "Haustier antippen öffnet die Seitenleiste",
                   "fr": "Toucher le compagnon ouvre le panneau latéral",
                   "es": "Toca la mascota para abrir el panel lateral",
                   "pt": "Toque no pet para abrir o painel lateral",
                   "ru": "Нажатие на питомца открывает боковую панель",
                   "ar": "اضغط على الحيوان الأليف لفتح اللوحة الجانبية",
                   "hi": "साइड पैनल खोलने के लिए पेट पर टैप करें"}),
    ("轻点宠物弹出输入侧边栏", {"en": "A light tap pops up the input side panel",
                     "ja": "軽くタップすると入力サイドバーが開きます",
                     "ko": "살짝 탭하면 입력 사이드 패널이 열립니다",
                     "de": "Leichtes Antippen öffnet die Eingabe-Seitenleiste",
                     "fr": "Un appui léger ouvre le panneau de saisie",
                     "es": "Un toque abre el panel lateral de entrada",
                     "pt": "Um toque abre o painel lateral de entrada",
                     "ru": "Лёгкое нажатие открывает панель ввода",
                     "ar": "لمسة خفيفة تُظهر لوحة الإدخال الجانبية",
                     "hi": "हल्का टैप इनपुट साइड पैनल खोलता है"}),
    ("长按宠物与 AI 互动", {"en": "Long-press the pet to interact with the AI",
                    "ja": "長押しで AI とやり取り", "ko": "펫을 길게 눌러 AI와 상호작용",
                    "de": "Langes Antippen interagiert mit der KI",
                    "fr": "Appui long pour interagir avec l’IA",
                    "es": "Mantén pulsada la mascota para interactuar con la IA",
                    "pt": "Pressione e segure o pet para interagir com a IA",
                    "ru": "Долгое нажатие — взаимодействие с ИИ",
                    "ar": "اضغط مطوّلًا على الحيوان للتفاعل مع الذكاء الاصطناعي",
                    "hi": "AI से इंटरैक्ट करने के लिए पेट को देर तक दबाएँ"}),
    ("长按宠物，AI 会回应你的抚摸", {"en": "Long-press the pet and the AI will respond to your touch",
                         "ja": "ペットを長押しすると、AI があなたの撫で方に反応します",
                         "ko": "펫을 길게 누르면 AI가 쓰다듬음에 반응합니다",
                         "de": "Bei langem Antippen reagiert die KI auf deine Streicheleinheit",
                         "fr": "En appuyant longuement, l’IA réagit à votre caresse",
                         "es": "Al mantener pulsada, la IA responde a tu caricia",
                         "pt": "Ao segurar, a IA responde ao seu carinho",
                         "ru": "При долгом нажатии ИИ ответит на вашу ласку",
                         "ar": "عند الضغط المطوّل يستجيب الذكاء الاصطناعي للمستك",
                         "hi": "देर तक दबाने पर AI आपकी सहलाहट का जवाब देगा"}),
    ("状态气泡播报", {"en": "Status bubble", "ja": "ステータスバブル表示", "ko": "상태 버블 표시",
                "de": "Statusblase anzeigen", "fr": "Bulle d’état", "es": "Burbuja de estado",
                "pt": "Bolha de status", "ru": "Пузырь статуса", "ar": "فقاعة الحالة",
                "hi": "स्थिति बबल"}),
    ("展示思考/工具/生成状态气泡", {"en": "Show thinking / tool / generation status bubbles",
                        "ja": "思考・ツール・生成のステータスバブルを表示",
                        "ko": "사고/도구/생성 상태 버블 표시",
                        "de": "Statusblasen für Denken, Tools und Generieren zeigen",
                        "fr": "Afficher les bulles de réflexion, d’outil et de génération",
                        "es": "Mostrar burbujas de pensamiento, herramienta y generación",
                        "pt": "Mostrar bolhas de pensamento, ferramenta e geração",
                        "ru": "Показывать пузыри размышления, инструментов и генерации",
                        "ar": "إظهار فقاعات التفكير والأدوات والتوليد",
                        "hi": "सोच / टूल / जनरेशन स्थिति बबल दिखाएँ"}),
    ("解析失败：$error", {"en": "Parse failed: $error", "ja": "解析に失敗：$error", "ko": "파싱 실패: $error",
                   "de": "Analyse fehlgeschlagen: $error", "fr": "Échec de l’analyse : $error",
                   "es": "Error al analizar: $error", "pt": "Falha ao analisar: $error",
                   "ru": "Ошибка разбора: $error", "ar": "فشل التحليل: $error",
                   "hi": "पार्स विफल: $error"}),

    # ══ QuroChatViewModel 用户可见状态文案 ══
    ("这是来自 ${platform.label} 用户 $userId 的机器人对话。", {
        "en": "This is the bot conversation for user $userId from ${platform.label}.",
        "ja": "${platform.label} のユーザー $userId のボット会話です。",
        "ko": "${platform.label} 사용자 $userId의 봇 대화입니다.",
        "de": "Dies ist die Bot-Unterhaltung für Nutzer $userId von ${platform.label}.",
        "fr": "Ceci est la conversation du bot pour l’utilisateur $userId de ${platform.label}.",
        "es": "Esta es la conversación del bot para el usuario $userId de ${platform.label}.",
        "pt": "Esta é a conversa do bot para o usuário $userId de ${platform.label}.",
        "ru": "Это диалог бота для пользователя $userId из ${platform.label}.",
        "ar": "هذه محادثة البوت للمستخدم $userId من ${platform.label}.",
        "hi": "यह ${platform.label} के उपयोगकर्ता $userId की बॉट बातचीत है।"}),
    ("对话已清空。", {"en": "Conversation cleared.", "ja": "会話をクリアしました。", "ko": "대화를 비웠습니다.",
                "de": "Unterhaltung geleert.", "fr": "Conversation effacée.",
                "es": "Conversación borrada.", "pt": "Conversa apagada.",
                "ru": "Диалог очищен.", "ar": "تم مسح المحادثة.", "hi": "बातचीत साफ़ कर दी गई।"}),
    ("⚠️ 尚未配置模型 API Key，请点右上角模型芯片 →「在模型设置中管理」填入 baseUrl / apiKey / model。", {
        "en": "⚠️ No model API key configured yet. Tap the model chip in the top-right → “Manage in model settings” and fill in baseUrl / apiKey / model.",
        "ja": "⚠️ モデルの API Key が未設定です。右上のモデルチップ →「モデル設定で管理」から baseUrl / apiKey / model を入力してください。",
        "ko": "⚠️ 모델 API Key가 아직 없습니다. 오른쪽 위 모델 칩 → “모델 설정에서 관리”에서 baseUrl / apiKey / model을 입력하세요.",
        "de": "⚠️ Noch kein Modell-API-Key konfiguriert. Tippen Sie oben rechts auf den Modell-Chip → „In den Modelleinstellungen verwalten“ und tragen Sie baseUrl / apiKey / model ein.",
        "fr": "⚠️ Aucune clé API de modèle configurée. Touchez la puce du modèle en haut à droite → « Gérer dans les réglages du modèle » et renseignez baseUrl / apiKey / model.",
        "es": "⚠️ Aún no hay clave de API del modelo. Toca el chip del modelo arriba a la derecha → «Gestionar en ajustes del modelo» y completa baseUrl / apiKey / model.",
        "pt": "⚠️ Ainda não há chave de API do modelo. Toque no chip do modelo no canto superior direito → «Gerenciar nas configurações do modelo» e preencha baseUrl / apiKey / model.",
        "ru": "⚠️ API-ключ модели не задан. Нажмите чип модели справа вверху → «Управление в настройках модели» и укажите baseUrl / apiKey / model.",
        "ar": "⚠️ لم يتم إعداد مفتاح API للنموذج بعد. اضغط شريحة النموذج أعلى اليمين ← «الإدارة في إعدادات النموذج» وأدخل baseUrl / apiKey / model.",
        "hi": "⚠️ मॉडल API Key अभी सेट नहीं है। ऊपर दाएँ मॉडल चिप पर टैप करें → “मॉडल सेटिंग में प्रबंधित करें” और baseUrl / apiKey / model भरें।"}),
    ("⏹ 已停止生成。", {"en": "⏹ Generation stopped.", "ja": "⏹ 生成を停止しました。", "ko": "⏹ 생성을 중지했습니다.",
                   "de": "⏹ Generierung gestoppt.", "fr": "⏹ Génération arrêtée.",
                   "es": "⏹ Generación detenida.", "pt": "⏹ Geração interrompida.",
                   "ru": "⏹ Генерация остановлена.", "ar": "⏹ تم إيقاف التوليد.",
                   "hi": "⏹ जनरेशन रोक दिया गया।"}),
    ("⚠️ 回复生成失败：$e", {"en": "⚠️ Reply generation failed: $e"}),
    ("回复生成失败：$e", {"en": "Reply generation failed: $e"}),
    ("⚠️ 发生错误：$e", {"en": "⚠️ An error occurred: $e"}),
    ("⚠️ 语音球出错了：$e", {"en": "⚠️ Voice ball error: $e"}),
    ("人格孵化失败：$e", {"en": "Persona incubation failed: $e"}),
    ("人格自动孵化失败：$e", {"en": "Automatic persona incubation failed: $e"}),
    ("未知错误", {"en": "Unknown error", "ja": "不明なエラー", "ko": "알 수 없는 오류",
              "de": "Unbekannter Fehler", "fr": "Erreur inconnue", "es": "Error desconocido",
              "pt": "Erro desconhecido", "ru": "Неизвестная ошибка", "ar": "خطأ غير معروف",
              "hi": "अज्ञात त्रुटि"}),
    ("你好，我是 Zorv AI。已就绪，可以聊天、调用工具。点左上角菜单查看历史对话，或点 ＋ 新建对话。", {
        "en": "Hi, I’m Zorv AI. Ready to chat and use tools. Tap the top-left menu for past conversations, or ＋ to start a new one.",
        "ja": "こんにちは、Zorv AI です。準備完了、チャットもツールも使えます。左上のメニューで履歴、＋ で新しい会話を開始できます。",
        "ko": "안녕하세요, Zorv AI입니다. 준비되었습니다. 대화와 도구 호출이 가능합니다. 왼쪽 위 메뉴에서 기록을, ＋로 새 대화를 시작하세요.",
        "de": "Hallo, ich bin Zorv AI. Bereit zum Chatten und für Tools. Oben links öffnen Sie den Verlauf, mit ＋ starten Sie ein neues Gespräch.",
        "fr": "Bonjour, je suis Zorv AI. Prêt à discuter et à utiliser des outils. Menu en haut à gauche pour l’historique, ＋ pour une nouvelle conversation.",
        "es": "Hola, soy Zorv AI. Listo para chatear y usar herramientas. Menú arriba a la izquierda para el historial, o ＋ para una conversación nueva.",
        "pt": "Olá, eu sou o Zorv AI. Pronto para conversar e usar ferramentas. Menu no canto superior esquerdo para o histórico, ou ＋ para uma nova conversa.",
        "ru": "Привет, я Zorv AI. Готов к общению и работе с инструментами. Слева вверху — история, ＋ — новый диалог.",
        "ar": "مرحبًا، أنا Zorv AI. جاهز للدردشة واستخدام الأدوات. القائمة أعلى اليسار للسجل، أو ＋ لمحادثة جديدة.",
        "hi": "नमस्ते, मैं Zorv AI हूँ। चैट और टूल के लिए तैयार। इतिहास के लिए ऊपर बाएँ मेन्यू, नई बातचीत के लिए ＋ दबाएँ।"}),
]

LANGS = ['en', 'ar', 'de', 'es', 'fr', 'hi', 'ja', 'ko', 'pt', 'ru']


def main():
    sp = os.path.join(HERE, 'i18n_strings.json')
    data = json.load(open(sp, encoding='utf-8'))
    have = {e['text'] for e in data}

    added, missing_in_src = [], []
    for zh, _tr in CATALOG:
        if zh in have:
            continue
        data.append({'text': zh, 'fmt': '$' in zh})
        have.add(zh)
        added.append(zh)

    json.dump(data, open(sp, 'w', encoding='utf-8'), ensure_ascii=False, indent=None)
    key_of = {'qk_%05d' % i: e['text'] for i, e in enumerate(data)}

    # 键映射（中文 -> 键）
    rev = {}
    for k, t in key_of.items():
        rev.setdefault(t, k)
    json.dump(rev, open(os.path.join(HERE, '_newkeys.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)

    # 英文词表
    en = {zh: tr['en'] for zh, tr in CATALOG}
    json.dump(en, open(os.path.join(HERE, 'i18n_en_11.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)

    # 其余 10 语言
    written = {}
    for lg in LANGS:
        if lg == 'en':
            continue
        d = {}
        for zh, tr in CATALOG:
            if lg in tr:
                d[zh] = tr[lg]
        f = os.path.join(HERE, 'i18n_lang_%s_2.json' % lg)
        json.dump(d, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        written[lg] = len(d)

    print('词表总数        :', len(data))
    print('本次新增条目    :', len(added))
    print('非英文语言覆盖  :', written)

    # 校验：英文必须全覆盖
    lack = [zh for zh, tr in CATALOG if not tr.get('en')]
    if lack:
        print('!! 缺英文:', lack)
        sys.exit(1)

    # 校验：中文原文必须能在源码里找到（模板串按前缀匹配）
    srcs = []
    for dp, _, fs in os.walk(ROOT):
        for fn in fs:
            if fn.endswith('.kt'):
                srcs.append(open(os.path.join(dp, fn), encoding='utf-8', errors='ignore').read())
    blob = '\n'.join(srcs)
    for zh, _ in CATALOG:
        probe = zh.split('${')[0] if '${' in zh else zh
        probe = probe.replace('$userId', '').replace('$personaName', '').replace('$modelLabel', '')
        probe = probe.replace('$worksCount', '').replace('$error', '').replace('$e', '')
        probe = probe.replace('\\n', '\n')
        if probe and probe not in blob:
            missing_in_src.append(zh[:60])
    print('源码中找不到的原文（需人工确认）:', len(missing_in_src))
    for m in missing_in_src:
        print('   -', m)

    print('\n--- 新增键（中文 -> qk）---')
    for zh in added:
        print('%s  %s' % (rev[zh], zh[:64]))


if __name__ == '__main__':
    main()

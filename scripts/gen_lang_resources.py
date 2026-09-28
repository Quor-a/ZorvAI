# -*- coding: utf-8 -*-
import io, os

base = r"D:\Calw OS-project\QuroAI\app\src\main\res"

# ---- 默认（中文，values/） ----
zh_existing = {
    "app_name": "Zorv AI",
    "quro_accessibility_desc": "Zorv AI 无障碍服务：用于界面自动化与屏幕内容读取等高级能力。当前版本仅完成权限声明，不会收集任何隐私数据。",
    "quro_device_admin_label": "Zorv AI 设备管理员",
    "quro_device_admin_desc": "仅用于两项操作：锁定屏幕、禁用或恢复摄像头。不会清除数据，也不会修改锁屏密码。",
    "ime_label": "Zorv AI 键盘",
    "sc_voice_short": "语音球",
    "sc_voice_long": "唤起悬浮语音球",
    "sc_voice_disabled": "语音球已禁用",
    "sc_chat_short": "小窗对话",
    "sc_chat_long": "直接进入对话",
    "sc_chat_disabled": "对话已禁用",
    "sc_browser_short": "浏览器控制台",
    "sc_browser_long": "ZorvAI 浏览器控制台",
    "sc_browser_disabled": "浏览器控制台已禁用",
}
zh_app = {
    "settings_appearance": "外观与对话",
    "appearance_group_appearance": "外观",
    "appearance_group_conversation": "对话",
    "appearance_dark_mode": "深色模式",
    "appearance_dark_mode_desc": "夜间自动降低亮度",
    "appearance_font_size": "字号",
    "appearance_sound": "回复提示音",
    "appearance_enter_send": "回车发送",
    "appearance_enter_send_desc": "关闭后回车换行",
    "appearance_voice_ball": "悬浮语音球",
    "appearance_voice_ball_desc": "STT → LLM → TTS 随时语音对话",
    "appearance_history_rounds": "保留对话轮数",
    "appearance_history_rounds_desc": "限制发给模型的近期轮次",
    "appearance_group_language": "语言",
    "appearance_follow_system": "跟随系统语言",
    "appearance_follow_system_desc": "不内置语言包，使用手机系统语言",
    "appearance_app_language": "应用语言",
    "appearance_group_profile": "用户资料",
    "appearance_profile": "头像与名字",
    "appearance_profile_desc": "设置你的资料",
}

order = list(zh_existing.keys()) + list(zh_app.keys())

# ---- 翻译（10 个大语言） ----
tr = {
"en": dict(zh_existing,
    quro_accessibility_desc="Zorv AI accessibility service: used for UI automation and screen content reading and other advanced capabilities. This version only completes permission declaration and collects no private data.",
    quro_device_admin_label="Zorv AI Device Admin",
    quro_device_admin_desc="Used only for two operations: lock screen, disable or restore camera. Does not clear data and does not modify lock screen password.",
    ime_label="Zorv AI Keyboard",
    sc_voice_short="Voice ball", sc_voice_long="Open floating voice ball", sc_voice_disabled="Voice ball disabled",
    sc_chat_short="Mini chat", sc_chat_long="Enter chat directly", sc_chat_disabled="Chat disabled",
    sc_browser_short="Browser console", sc_browser_long="ZorvAI Browser console", sc_browser_disabled="Browser console disabled",
    settings_appearance="Appearance and Conversation", appearance_group_appearance="Appearance", appearance_group_conversation="Conversation",
    appearance_dark_mode="Dark mode", appearance_dark_mode_desc="Auto-dim at night", appearance_font_size="Font size",
    appearance_sound="Reply sound", appearance_enter_send="Enter to send", appearance_enter_send_desc="New line with Enter when off",
    appearance_voice_ball="Floating voice ball", appearance_voice_ball_desc="STT to LLM to TTS voice chat anytime",
    appearance_history_rounds="Keep conversation rounds", appearance_history_rounds_desc="Limit recent rounds sent to model",
    appearance_group_language="Language", appearance_follow_system="Follow system language", appearance_follow_system_desc="No bundled language pack; uses phone system language",
    appearance_app_language="App language", appearance_group_profile="User profile", appearance_profile="Avatar and name", appearance_profile_desc="Set your profile",
),
"ja": dict(zh_existing,
    quro_accessibility_desc="Zorv AI アクセシビリティサービス：UI自動化や画面内容の読み取りなどの高度な機能に使用します。本バージョンは権限宣言のみで、いかなるプライバシーデータも収集しません。",
    quro_device_admin_label="Zorv AI デバイス管理者", quro_device_admin_desc="画面ロック、カメラの無効化または復元の2つの操作のみに使用します。データを削除せず、ロック画面のパスワードも変更しません。",
    ime_label="Zorv AI キーボード",
    sc_voice_short="音声ボール", sc_voice_long="フローティング音声ボールを開く", sc_voice_disabled="音声ボールは無効",
    sc_chat_short="ミニ対話", sc_chat_long="直接対話を開く", sc_chat_disabled="対話は無効",
    sc_browser_short="ブラウザコンソール", sc_browser_long="ZorvAI ブラウザコンソール", sc_browser_disabled="ブラウザコンソールは無効",
    settings_appearance="外観と会話", appearance_group_appearance="外観", appearance_group_conversation="会話",
    appearance_dark_mode="ダークモード", appearance_dark_mode_desc="夜間に明るさを自動的に下げる", appearance_font_size="文字サイズ",
    appearance_sound="返信通知音", appearance_enter_send="Enterで送信", appearance_enter_send_desc="オフの場合 Enterで改行",
    appearance_voice_ball="フローティング音声ボール", appearance_voice_ball_desc="STT → LLM → TTS いつでも音声会話",
    appearance_history_rounds="保持する対話ラウンド数", appearance_history_rounds_desc="モデルに送る直近ラウンドを制限",
    appearance_group_language="言語", appearance_follow_system="システム言語に従う", appearance_follow_system_desc="言語パックは内蔵せず、スマホのシステム言語を使用",
    appearance_app_language="アプリの言語", appearance_group_profile="ユーザー情報", appearance_profile="アバターと名前", appearance_profile_desc="あなたのプロフィールを設定",
),
"ko": dict(zh_existing,
    quro_accessibility_desc="Zorv AI 접근성 서비스: UI 자동화 및 화면 내용 읽기 등 고급 기능에 사용됩니다. 이 버전은 권한 선언만 완료하며 개인정보를 수집하지 않습니다.",
    quro_device_admin_label="Zorv AI 기기 관리자", quro_device_admin_desc="화면 잠금, 카메라 비활성화 또는 복원 두 가지 작업에만 사용됩니다. 데이터를 삭제하지 않으며 잠금 화면 암호도 수정하지 않습니다.",
    ime_label="Zorv AI 키보드",
    sc_voice_short="음성 볼", sc_voice_long="플로팅 음성 볼 열기", sc_voice_disabled="음성 볼 비활성화됨",
    sc_chat_short="미니 대화", sc_chat_long="대화로 바로 이동", sc_chat_disabled="대화 비활성화됨",
    sc_browser_short="브라우저 콘솔", sc_browser_long="ZorvAI 브라우저 콘솔", sc_browser_disabled="브라우저 콘솔 비활성화됨",
    settings_appearance="외관 및 대화", appearance_group_appearance="외관", appearance_group_conversation="대화",
    appearance_dark_mode="다크 모드", appearance_dark_mode_desc="야간에 밝기 자동 낮춤", appearance_font_size="글자 크기",
    appearance_sound="응답 알림음", appearance_enter_send="Enter로 전송", appearance_enter_send_desc="끄면 Enter로 줄바꿈",
    appearance_voice_ball="플로팅 음성 볼", appearance_voice_ball_desc="STT → LLM → TTS 언제든 음성 대화",
    appearance_history_rounds="유지할 대화 라운드 수", appearance_history_rounds_desc="모델에 보낼 최근 라운드 제한",
    appearance_group_language="언어", appearance_follow_system="시스템 언어 따르기", appearance_follow_system_desc="언어 팩을 내장하지 않고 휴대폰 시스템 언어 사용",
    appearance_app_language="앱 언어", appearance_group_profile="사용자 정보", appearance_profile="아바타와 이름", appearance_profile_desc="프로필 설정",
),
"fr": dict(zh_existing,
    quro_accessibility_desc="Service d accessibilite Zorv AI : utilise pour l automation de l interface et la lecture du contenu d ecran, entre autres capacites avancees. Cette version ne fait que declarer les permissions et ne collecte aucune donnee privee.",
    quro_device_admin_label="Administrateur d appareil Zorv AI", quro_device_admin_desc="Utilise uniquement pour deux operations : verrouillage de l ecran, desactivation ou restauration de l appareil photo. Ne supprime pas les donnees et ne modifie pas le mot de passe de verrouillage.",
    ime_label="Clavier Zorv AI",
    sc_voice_short="Boule vocale", sc_voice_long="Ouvrir la boule vocale flottante", sc_voice_disabled="Boule vocale desactivee",
    sc_chat_short="Mini dialogue", sc_chat_long="Ouvrir la conversation directement", sc_chat_disabled="Chat desactive",
    sc_browser_short="Console de navigateur", sc_browser_long="Console navigateur ZorvAI", sc_browser_disabled="Console de navigateur desactivee",
    settings_appearance="Apparence et conversation", appearance_group_appearance="Apparence", appearance_group_conversation="Conversation",
    appearance_dark_mode="Mode sombre", appearance_dark_mode_desc="Reduit la luminosite la nuit", appearance_font_size="Taille de police",
    appearance_sound="Son de reponse", appearance_enter_send="Entree pour envoyer", appearance_enter_send_desc="Entree fait un saut de ligne si desactive",
    appearance_voice_ball="Boule vocale flottante", appearance_voice_ball_desc="STT vers LLM vers TTS discussion vocale a tout moment",
    appearance_history_rounds="Rounds de conversation conserves", appearance_history_rounds_desc="Limite les rounds recents envoyes au modele",
    appearance_group_language="Langue", appearance_follow_system="Suivre la langue du systeme", appearance_follow_system_desc="Pas de pack linguistique; utilise la langue du systeme",
    appearance_app_language="Langue de l application", appearance_group_profile="Profil utilisateur", appearance_profile="Avatar et nom", appearance_profile_desc="Definir votre profil",
),
"de": dict(zh_existing,
    quro_accessibility_desc="Zorv AI Barrierefreiheitsdienst: wird fur UI-Automatisierung und Bildschirminhaltslesen sowie weitere erweiterte Funktionen genutzt. Diese Version deklariert nur Berechtigungen und erfasst keine privaten Daten.",
    quro_device_admin_label="Zorv AI Geräteadministrator", quro_device_admin_desc="Wird nur für zwei Vorgänge genutzt: Bildschirm sperren, Kamera deaktivieren oder wiederherstellen. Löscht keine Daten und ändert kein Sperrbildschirm-Passwort.",
    ime_label="Zorv AI Tastatur",
    sc_voice_short="Sprachkugel", sc_voice_long="Schwebende Sprachkugel öffnen", sc_voice_disabled="Sprachkugel deaktiviert",
    sc_chat_short="Mini-Chat", sc_chat_long="Direkt in den Chat gehen", sc_chat_disabled="Chat deaktiviert",
    sc_browser_short="Browser-Konsole", sc_browser_long="ZorvAI Browser-Konsole", sc_browser_disabled="Browser-Konsole deaktiviert",
    settings_appearance="Erscheinungsbild und Konversation", appearance_group_appearance="Erscheinungsbild", appearance_group_conversation="Konversation",
    appearance_dark_mode="Dunkler Modus", appearance_dark_mode_desc="Nachts automatisch dimmen", appearance_font_size="Schriftgröße",
    appearance_sound="Antwortton", appearance_enter_send="Eingabe zum Senden", appearance_enter_send_desc="Bei Aus: Eingabe = Zeilenumbruch",
    appearance_voice_ball="Schwebende Sprachkugel", appearance_voice_ball_desc="STT zu LLM zu TTS Sprachchat jederzeit",
    appearance_history_rounds="Beibehaltene Konversationsrunden", appearance_history_rounds_desc="Begrenzt kürzliche Runden an Modell",
    appearance_group_language="Sprache", appearance_follow_system="Systemsprache folgen", appearance_follow_system_desc="Kein Sprachpaket; nutzt Systemsprache",
    appearance_app_language="App-Sprache", appearance_group_profile="Benutzerprofil", appearance_profile="Avatar und Name", appearance_profile_desc="Profil festlegen",
),
"es": dict(zh_existing,
    quro_accessibility_desc="Servicio de accesibilidad de Zorv AI: se usa para automatizacion de UI y lectura del contenido de pantalla, entre otras capacidades avanzadas. Esta version solo declara permisos y no recopila datos privados.",
    quro_device_admin_label="Administrador de dispositivo Zorv AI", quro_device_admin_desc="Usado solo para dos operaciones: bloqueo de pantalla, desactivar o restaurar la camara. No borra datos ni modifica la contrasena de bloqueo.",
    ime_label="Teclado Zorv AI",
    sc_voice_short="Bola de voz", sc_voice_long="Abrir la bola de voz flotante", sc_voice_disabled="Bola de voz desactivada",
    sc_chat_short="Mini chat", sc_chat_long="Entrar al chat directamente", sc_chat_disabled="Chat desactivado",
    sc_browser_short="Consola del navegador", sc_browser_long="Consola del navegador ZorvAI", sc_browser_disabled="Consola del navegador desactivada",
    settings_appearance="Apariencia y conversación", appearance_group_appearance="Apariencia", appearance_group_conversation="Conversación",
    appearance_dark_mode="Modo oscuro", appearance_dark_mode_desc="Reduce el brillo por la noche", appearance_font_size="Tamaño de fuente",
    appearance_sound="Sonido de respuesta", appearance_enter_send="Enter para enviar", appearance_enter_send_desc="Enter hace salto de línea si está off",
    appearance_voice_ball="Bola de voz flotante", appearance_voice_ball_desc="STT a LLM a TTS chat de voz en cualquier momento",
    appearance_history_rounds="Rondas de conversación conservadas", appearance_history_rounds_desc="Limita rondas recientes enviadas al modelo",
    appearance_group_language="Idioma", appearance_follow_system="Seguir idioma del sistema", appearance_follow_system_desc="Sin paquete de idioma; usa el idioma del sistema",
    appearance_app_language="Idioma de la app", appearance_group_profile="Perfil de usuario", appearance_profile="Avatar y nombre", appearance_profile_desc="Configurar tu perfil",
),
"ru": dict(zh_existing,
    quro_accessibility_desc="Служба специальных возможностей Zorv AI: используется для автоматизации интерфейса и чтения содержимого экрана, а также других расширенных возможностей. В этой версии только объявление разрешений, никакие личные данные не собираются.",
    quro_device_admin_label="Администратор устройства Zorv AI", quro_device_admin_desc="Используется только для двух операций: блокировка экрана, отключение или восстановление камеры. Не удаляет данные и не меняет пароль блокировки.",
    ime_label="Клавиатура Zorv AI",
    sc_voice_short="Голосовой шар", sc_voice_long="Открыть плавающий голосовой шар", sc_voice_disabled="Голосовой шар отключён",
    sc_chat_short="Мини-чат", sc_chat_long="Открыть чат напрямую", sc_chat_disabled="Чат отключён",
    sc_browser_short="Консоль браузера", sc_browser_long="Консоль браузера ZorvAI", sc_browser_disabled="Консоль браузера отключена",
    settings_appearance="Внешний вид и беседа", appearance_group_appearance="Внешний вид", appearance_group_conversation="Беседа",
    appearance_dark_mode="Тёмная тема", appearance_dark_mode_desc="Автоматически тускнеет ночью", appearance_font_size="Размер шрифта",
    appearance_sound="Звук ответа", appearance_enter_send="Enter для отправки", appearance_enter_send_desc="Enter = новая строка, если выключено",
    appearance_voice_ball="Плавающий голосовой шар", appearance_voice_ball_desc="STT → LLM → TTS голосовой чат в любое время",
    appearance_history_rounds="Сохранять раунды беседы", appearance_history_rounds_desc="Ограничивает недавние раунды для модели",
    appearance_group_language="Язык", appearance_follow_system="Следовать языку системы", appearance_follow_system_desc="Нет языкового пакета; используется язык системы",
    appearance_app_language="Язык приложения", appearance_group_profile="Профиль пользователя", appearance_profile="Аватар и имя", appearance_profile_desc="Настроить профиль",
),
"pt": dict(zh_existing,
    quro_accessibility_desc="Serviço de acessibilidade Zorv AI: usado para automação de UI e leitura do conteúdo da tela, entre outros recursos avançados. Esta versão apenas declara permissões e não coleta dados privados.",
    quro_device_admin_label="Administrador de dispositivo Zorv AI", quro_device_admin_desc="Usado apenas para duas operações: bloquear a tela, desativar ou restaurar a câmera. Não apaga dados nem modifica a senha de bloqueio.",
    ime_label="Teclado Zorv AI",
    sc_voice_short="Bola de voz", sc_voice_long="Abrir a bola de voz flutuante", sc_voice_disabled="Bola de voz desativada",
    sc_chat_short="Mini chat", sc_chat_long="Entrar no chat diretamente", sc_chat_disabled="Chat desativado",
    sc_browser_short="Console do navegador", sc_browser_long="Console do navegador ZorvAI", sc_browser_disabled="Console do navegador desativada",
    settings_appearance="Aparência e conversa", appearance_group_appearance="Aparência", appearance_group_conversation="Conversa",
    appearance_dark_mode="Modo escuro", appearance_dark_mode_desc="Diminui o brilho à noite", appearance_font_size="Tamanho da fonte",
    appearance_sound="Som de resposta", appearance_enter_send="Enter para enviar", appearance_enter_send_desc="Enter faz nova linha se desligado",
    appearance_voice_ball="Bola de voz flutuante", appearance_voice_ball_desc="STT para LLM para TTS conversa por voz a qualquer hora",
    appearance_history_rounds="Rodadas de conversa mantidas", appearance_history_rounds_desc="Limita rodadas recentes enviadas ao modelo",
    appearance_group_language="Idioma", appearance_follow_system="Seguir idioma do sistema", appearance_follow_system_desc="Sem pacote de idioma; usa o idioma do sistema",
    appearance_app_language="Idioma do app", appearance_group_profile="Perfil do usuário", appearance_profile="Avatar e nome", appearance_profile_desc="Definir seu perfil",
),
"ar": dict(zh_existing,
    quro_accessibility_desc="خدمة الوصول الخاصة بـ Zorv AI: تُستخدم لأتمتة الواجهة وقراءة محتوى الشاشة وغيرها من القدرات المتقدمة. هذا الإصدار يُعلن الأذونات فقط ولا يجمع أي بيانات خصوصية.",
    quro_device_admin_label="مسؤول جهاز Zorv AI", quro_device_admin_desc="يُستخدم فقط لعمليتين: قفل الشاشة، تعطيل الكاميرا أو استعادتها. لا يحذف البيانات ولا يعدل كلمة مرور القفل.",
    ime_label="لوحة مفاتيح Zorv AI",
    sc_voice_short="كرة صوت", sc_voice_long="فتح كرة الصوت العائمة", sc_voice_disabled="كرة الصوت معطلة",
    sc_chat_short="محادثة مصغرة", sc_chat_long="افتح المحادثة مباشرة", sc_chat_disabled="المحادثة معطلة",
    sc_browser_short="وحدة تحكم المتصفح", sc_browser_long="وحدة تحكم متصفح ZorvAI", sc_browser_disabled="وحدة تحكم المتصفح معطلة",
    settings_appearance="المظهر والمحادثة", appearance_group_appearance="المظهر", appearance_group_conversation="المحادثة",
    appearance_dark_mode="الوضع الداكن", appearance_dark_mode_desc="تعتيم تلقائي ليلاً", appearance_font_size="حجم الخط",
    appearance_sound="صوت الرد", appearance_enter_send="الإدخال للإرسال", appearance_enter_send_desc="الإدخال يُنشئ سطراً جديداً عند الإيقاف",
    appearance_voice_ball="كرة صوت عائمة", appearance_voice_ball_desc="STT → LLM → TTS محادثة صوتية في أي وقت",
    appearance_history_rounds="جولات المحادثة المحفوظة", appearance_history_rounds_desc="يحدّ روينات المحادثة الأخيرة المرسلة للنموذج",
    appearance_group_language="اللغة", appearance_follow_system="اتباع لغة النظام", appearance_follow_system_desc="لا حزمة لغة مدمجة؛ يستخدم لغة النظام",
    appearance_app_language="لغة التطبيق", appearance_group_profile="ملف المستخدم", appearance_profile="الصورة والاسم", appearance_profile_desc="اضبط ملفك الشخصي",
),
"hi": dict(zh_existing,
    quro_accessibility_desc="Zorv AI एक्सेसिबिलिटी सेवा: UI ऑटोमेशन और स्क्रीन सामग्री पढ़ने जैसी उन्नत क्षमताओं के लिए उपयोग। यह संस्करण केवल अनुमति घोषणा करता है, कोई निजी डेटा एकत्र नहीं करता।",
    quro_device_admin_label="Zorv AI डिवाइस व्यवस्थापक", quro_device_admin_desc="केवल दो कार्यों के लिए: स्क्रीन लॉक, कैमरा अक्षम या पुनर्स्थापित करें। डेटा हटाता नहीं, लॉक स्क्रीन पासवर्ड नहीं बदलता।",
    ime_label="Zorv AI कीबोर्ड",
    sc_voice_short="वॉयस बॉल", sc_voice_long="फ़्लोटिंग वॉयस बॉल खोलें", sc_voice_disabled="वॉयस बॉल अक्षम",
    sc_chat_short="मिनी चैट", sc_chat_long="सीधे चैट खोलें", sc_chat_disabled="चैट अक्षम",
    sc_browser_short="ब्राउज़र कंसोल", sc_browser_long="ZorvAI ब्राउज़र कंसोल", sc_browser_disabled="ब्राउज़र कंसोल अक्षम",
    settings_appearance="दिखावट और बातचीत", appearance_group_appearance="दिखावट", appearance_group_conversation="बातचीत",
    appearance_dark_mode="डार्क मोड", appearance_dark_mode_desc="रात में चमक स्वतः कम करें", appearance_font_size="फ़ॉन्ट आकार",
    appearance_sound="जवाब ध्वनि", appearance_enter_send="भेजने के लिए Enter", appearance_enter_send_desc="बंद होने पर Enter से नई पंक्ति",
    appearance_voice_ball="फ़्लोटिंग वॉयस बॉल", appearance_voice_ball_desc="STT → LLM → TTS कभी भी वॉयस चैट",
    appearance_history_rounds="रखी गई बातचीत की राउंड", appearance_history_rounds_desc="मॉडल को भेजे गए हालिया राउंड सीमित करें",
    appearance_group_language="भाषा", appearance_follow_system="सिस्टम भाषा का पालन करें", appearance_follow_system_desc="कोई भाषा पैक नहीं; फ़ोन की सिस्टम भाषा उपयोग करें",
    appearance_app_language="ऐप भाषा", appearance_group_profile="उपयोगकर्ता प्रोफ़ाइल", appearance_profile="अवतार और नाम", appearance_profile_desc="अपनी प्रोफ़ाइल सेट करें",
),
}

# fr: fix apostrophes (replace ASCII ' with typographic ’ to avoid XML issues)
for k in list(tr["fr"].keys()):
    tr["fr"][k] = tr["fr"][k].replace("'", "’")

def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))

def write_xml(lang, d, is_default=False):
    if lang == "zh":
        folder = os.path.join(base, "values")
    else:
        folder = os.path.join(base, "values-" + lang)
    os.makedirs(folder, exist_ok=True)
    fp = os.path.join(folder, "strings.xml")
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    if is_default:
        lines.append('    <!-- 设备管理员：必须与 res/xml/quro_device_admin.xml 策略逐条对应 -->')
    for k in order:
        lines.append('    <string name="%s">%s</string>' % (k, esc(d[k])))
    lines.append('</resources>')
    with io.open(fp, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", fp, len(order), "strings")

# default (zh) = values/
zh = dict(zh_existing, **zh_app)
write_xml("zh", zh, is_default=True)
for lang in ["en","ja","ko","fr","de","es","ru","pt","ar","hi"]:
    write_xml(lang, tr[lang])
print("ALL DONE")

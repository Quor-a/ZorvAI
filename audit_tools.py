import json, sys

# 模拟 buildQuroRegistry 的 specs 输出（从代码硬编码提取）
tools_spec = [
    {"name":"get_current_time","description":"获取当前日期与时间，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"get_device_info","description":"获取设备型号与系统版本，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"calculate","description":"计算一个算术表达式，支持 + - * / 和括号，参数为 {\"expr\":\"1+2*3\"}。","parameters":{"type":"object","properties":{"expr":{"type":"string","description":"算术表达式"}},"required":["expr"]}},
    {"name":"get_battery","description":"获取设备电量百分比与充电状态，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"get_wifi_info","description":"获取当前连接的 Wi-Fi 名称(SSID)与连接状态，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"get_network_info","description":"获取网络类型与是否联网，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"get_sensors","description":"列出设备可用传感器名称与类型，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"vibrate","description":"让设备振动指定毫秒，参数为 {\"ms\":300}。","parameters":{"type":"object","properties":{"ms":{"type":"integer","description":"振动时长(毫秒)"}},"required":["ms"]}},
    {"name":"get_clipboard","description":"读取系统剪贴板文本，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"set_clipboard","description":"写入系统剪贴板，参数为 {\"text\":\"要写入的内容\"}。","parameters":{"type":"object","properties":{"text":{"type":"string","description":"要写入的文本"}},"required":["text"]}},
    {"name":"list_installed_apps","description":"列出已安装应用(名称+包名)，参数为空或 {\"query\":\"名称片段\"}。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"按名称过滤(可选)"}}}},
    {"name":"launch_app","description":"启动指定应用。可通过 package（精确包名）或 name（应用显示名，模糊匹配）指定目标。优先使用 name 参数，无需知道精确包名。","parameters":{"type":"object","properties":{"package":{"type":"string","description":"应用包名（精确），如 com.kuaishou.nebula"},"name":{"type":"string","description":"应用显示名称（模糊匹配），如「快手」「微信」"}}},
    {"name":"search_and_launch_app","description":"用户想要打开某个应用时的首选工具。输入应用名称（如「快手」「抖音」「微信」），自动搜索并启动最匹配的应用。比先 list_installed_apps 再 launch_app 更高效。","parameters":{"type":"object","properties":{"app_name":{"type":"string","description":"要打开的应用名称，如「快手」「微信」「淘宝」"}},"required":["app_name"]}},
    {"name":"get_package_name","description":"根据应用显示名查询其精确包名。参数 {\"app_name\":\"应用名\"}。当需要精确包名做高级操作时使用。","parameters":{"type":"object","properties":{"app_name":{"type":"string","description":"要查询的应用显示名称，如「快手」「微信」「网易云音乐」"}},"required":["app_name"]}},
    {"name":"get_active_notifications","description":"读取当前活跃通知(标题+文本)，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"get_bluetooth_status","description":"获取蓝牙开关状态与已配对设备，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"toggle_flashlight","description":"开关手电筒(闪光灯)，参数为 {\"on\":true}。","parameters":{"type":"object","properties":{"on":{"type":"boolean","description":"true 开/false 关"}},"required":["on"]}},
    {"name":"read_sms","description":"读取最近收到的短信(发件人+正文)，参数为 {\"limit\":20}(可选，默认20)。","parameters":{"type":"object","properties":{"limit":{"type":"integer","description":"返回条数，默认20"}}}},
    {"name":"send_sms","description":"发送一条短信，参数为 {\"phone\":\"138...\",\"message\":\"内容\"}。","parameters":{"type":"object","properties":{"phone":{"type":"string","description":"收件号码"},"message":{"type":"string","description":"短信内容"}},"required":["phone","message"]}},
    {"name":"read_contacts","description":"列出联系人(姓名+号码)，可按姓名片段过滤，参数为 {\"query\":\"张\"} (可选)。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"按姓名过滤(可选)"}}}},
    {"name":"read_calendar","description":"读取未来 N 天内的日历事件(标题+时间+地点)，参数为 {\"days\":7} (默认7)。","parameters":{"type":"object","properties":{"days":{"type":"integer","description":"向前看的天数，默认7"}}}},
    {"name":"browse_files","description":"浏览文件目录，列出指定路径下的文件和子目录。参数 {\"path\":\"路径（默认应用私有根目录）\"}。用于工具箱的文件管理功能。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"要浏览的目录路径（留空则列应用私有根目录）"}}}},
    {"name":"file_read","description":"读取文本文件完整内容。参数 {\"path\":\"文件路径\"}。支持读取应用私有目录内的任何文本文件。用于 IDE/代码查看场景。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"要读取的文件完整路径"}},"required":["path"]}},
    {"name":"open_web","description":"在应用内置浏览器中打开指定 URL。参数 {\"url\":\"网址\"}。当用户需要浏览网页、查看网页内容时使用。会自动在应用内打开网页视图。","parameters":{"type":"object","properties":{"url":{"type":"string","description":"要打开的完整 URL，如 https://www.example.com"}},"required":["url"]}},
    {"name":"run_code","description":"执行一段代码并返回输出结果。参数 {\"code\":\"代码内容\",\"lang\":\"python|node|shell\"}。适用于简单计算、数据处理、文本分析等轻量级任务。注意：无网络/文件系统沙箱外访问限制。","parameters":{"type":"object","properties":{"code":{"type":"string","description":"要执行的代码"},"lang":{"type":"string","description":"语言：python（默认）、node、shell"}},"required":["code"]}},
    {"name":"swipe_screen","description":"在屏幕上执行滑动手势。用于滚动页面、滑动解锁、手势导航等场景。参数 {\"x1\":起点X, \"y1\":起点Y, \"x2\":终点X, \"y2\":终点Y}（坐标为屏幕像素，左上角为 0,0）。需已授权「无障碍」权限；若提示权限不可用，请引导用户先到系统设置开启本应用的无障碍服务。","parameters":{"type":"object","properties":{"x1":{"type":"integer","description":"起始 X 坐标"},"y1":{"type":"integer","description":"起始 Y 坐标"},"x2":{"type":"integer","description":"结束 X 坐标"},"y2":{"type":"integer","description":"结束 Y 坐标"},"duration_ms":{"type":"integer","description":"滑动时长(毫秒)，默认 300"}},"required":["x1","y1","x2","y2"]}},
    {"name":"tap_screen","description":"点击屏幕上的指定坐标。参数 {\"x\":500,\"y\":1200}。需已授权「无障碍」权限；若提示权限不可用，请引导用户先到系统设置开启本应用的无障碍服务。","parameters":{"type":"object","properties":{"x":{"type":"integer","description":"点击 X 坐标"},"y":{"type":"integer","description":"点击 Y 坐标"}},"required":["x","y"]}},
    {"name":"list_files","description":"列出应用专属目录下的文件/文件夹，参数为 {\"path\":\"\",\"limit\":50}。path 为相对 getExternalFilesDir 的子路径，默认根。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对子路径(可选)"},"limit":{"type":"integer","description":"返回条数默认50"}}}},
    {"name":"read_text_file","description":"读取应用专属目录下的文本文件内容，参数为 {\"path\":\"sub/a.txt\",\"maxBytes\":4096}。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对 getExternalFilesDir 的路径"},"maxBytes":{"type":"integer","description":"最多读取字节数默认4096"}},"required":["path"]}},
    {"name":"get_location","description":"获取设备当前经纬度与精度（最后已知或单次定位），参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"geocode","description":"地址转坐标或坐标转地址。{\"query\":\"北京市天安门\"} 正向；{\"lat\":39.9,\"lng\":116.4} 反向。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"地址文本(正向)"},"lat":{"type":"number","description":"纬度(反向)"},"lng":{"type":"number","description":"经度(反向)"}}}},
    {"name":"list_media","description":"列出媒体库中的图片或视频(名称+大小+日期)，参数为 {\"kind\":\"image|video\",\"limit\":20}。","parameters":{"type":"object","properties":{"kind":{"type":"string","description":"image 或 video，默认 image"},"limit":{"type":"integer","description":"返回条数默认20"}}}},
    {"name":"set_alarm","description":"设置一条闹钟，参数为 {\"hour\":7,\"minute\":30,\"label\":\"晨会\",\"days\":[2,4,6],\"skipUi\":false}。days 为 1-7(周一至周日)，可省略。","parameters":{"type":"object","properties":{"hour":{"type":"integer","description":"小时0-23"},"minute":{"type":"integer","description":"分钟0-59"},"label":{"type":"string","description":"标签(可选)"},"days":{"type":"array","description":"重复日 1-7(可选)"},"skipUi":{"type":"boolean","description":"是否跳过确认界面，默认false"}},"required":["hour","minute"]}},
    {"name":"http_request","description":"发起 HTTP 请求。参数 {\"url\":\"https://...\",\"method\":\"GET|POST|PUT|DELETE\",\"headers\":\"可选 JSON 对象字符串\",\"body\":\"可选请求体(字符串)\",\"timeout\":10(秒)}。返回状态码 + 响应体（截断到 8KB）。","parameters":{"type":"object","properties":{"url":{"type":"string","description":"目标 URL"},"method":{"type":"string","description":"HTTP 方法，默认 GET"},"headers":{"type":"string","description":"可选，JSON 对象字符串，如 {\"Authorization\":\"Bearer x\"}"},"body":{"type":"string","description":"可选，请求体（字符串）"},"timeout":{"type":"integer","description":"超时秒数，默认 10，最大 30"}},"required":["url"]}},
    {"name":"write_file","description":"写入文本到应用专属目录文件，参数 {\"path\":\"sub/a.txt\",\"content\":\"文本\",\"append\":false}。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对 getExternalFilesDir 的路径"},"content":{"type":"string","description":"要写入的内容"},"append":{"type":"boolean","description":"是否追加，默认 false"}},"required":["path","content"]}},
    {"name":"delete_file","description":"删除应用专属目录下的文件或文件夹，参数 {\"path\":\"sub/a.txt\"}。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}},
    {"name":"make_directory","description":"在应用专属目录创建文件夹，参数 {\"path\":\"sub/newdir\"}。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}},
    {"name":"move_file","description":"在应用专属目录内移动/重命名文件，参数 {\"from\":\"a.txt\",\"to\":\"sub/b.txt\"}。","parameters":{"type":"object","properties":{"from":{"type":"string","description":"源相对路径"},"to":{"type":"string","description":"目标相对路径"}},"required":["from","to"]}},
    {"name":"copy_file","description":"在应用专属目录内复制文件，参数 {\"from\":\"a.txt\",\"to\":\"sub/b.txt\"}。","parameters":{"type":"object","properties":{"from":{"type":"string","description":"源相对路径"},"to":{"type":"string","description":"目标相对路径"}},"required":["from","to"]}},
    {"name":"find_files","description":"在应用专属目录按名称子串搜索文件，参数 {\"query\":\"log\",\"limit\":50}。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"名称包含的子串"},"limit":{"type":"integer","description":"返回条数默认50"}},"required":["query"]}},
    {"name":"file_info","description":"查看应用专属目录文件信息（大小/修改时间/类型），参数 {\"path\":\"sub/a.txt\"}。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对路径"}},"required":["path"]}},
    {"name":"speak","description":"用系统 TTS 朗读文本，参数 {\"text\":\"要朗读的内容\",\"lang\":\"可选语言代码如 zh-CN\"}。","parameters":{"type":"object","properties":{"text":{"type":"string","description":"要朗读的文本"},"lang":{"type":"string","description":"可选语言代码，如 zh-CN / en-US"}},"required":["text"]}},
    {"name":"stop_speak","description":"停止当前 TTS 朗读，参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"execute_intent","description":"构建一个隐式 Intent 并启动（startActivity）。参数 {\"action\":\"android.intent.action.VIEW\",\"data\":\"https://...\",\"type\":\"可选 MIME\",\"extra\":{\"key\":\"value\"}可选}。注意：部分 action 需对应 App 支持。","parameters":{"type":"object","properties":{"action":{"type":"string","description":"Intent action，如 android.intent.action.VIEW"},"data":{"type":"string","description":"可选 Uri 字符串"},"type":{"type":"string","description":"可选 MIME 类型"},"extra":{"type":"string","description":"可选 JSON 对象字符串，键值对作为 Intent extra"}},"required":["action"]}},
    {"name":"send_broadcast","description":"发送一个隐式广播。参数 {\"action\":\"...\",\"extra\":{\"key\":\"value\"}可选}。","parameters":{"type":"object","properties":{"action":{"type":"string","description":"广播 action"},"extra":{"type":"string","description":"可选 JSON 对象字符串"}},"required":["action"]}},
    {"name":"cms_list","description":"列出 CMS v2 已安装的能力模块与可用能力列表。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"cms_call","description":"调用 CMS v2 能力模块的具体能力。参数 {\"module\":\"模块ID\",\"capability\":\"能力名\",\"params\":{...}}。","parameters":{"type":"object","properties":{"module":{"type":"string","description":"模块标识符"},"capability":{"type":"string","description":"能力名称"},"params":{"type":"object","description":"能力参数(按各能力文档)"}},"required":["module","capability"]}},
    {"name":"priv_status","description":"查询特权通道（无障碍/通知/管理员等）的授权状态，AI 调用高风险能力前应自查。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"memory_save","description":"保存一条长期记忆（用户偏好、事实、约定、项目背景等）。当用户透露了值得跨会话记住的信息时，应主动调用本工具「自动保存」。参数：{\"title\":\"可选标题\",\"content\":\"记忆内容(必填)\",\"group\":\"分组如 偏好/工作/项目(可选)\",\"tags\":\"标签数组(可选)\"}。","parameters":{"type":"object","properties":{"title":{"type":"string","description":"可选标题"},"content":{"type":"string","description":"记忆内容，必填"},"group":{"type":"string","description":"分组，如 偏好/工作/项目"},"tags":{"type":"array","items":{"type":"string"},"description":"标签数组"}},"required":["content"]}},
    {"name":"memory_list","description":"列出已保存的全部长期记忆。参数为空 {}。","parameters":json.loads('{"type":"object","properties":{}}')},
    {"name":"memory_search","description":"按关键词检索已保存的记忆（匹配内容/标题/标签/分组）。参数：{\"query\":\"关键词\"}。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"检索关键词"}},"required":["query"]}},
    {"name":"memory_delete","description":"删除一条记忆（按内容关键词匹配第一条）。参数：{\"query\":\"要删除的记忆内容关键词\"}。","parameters":{"type":"object","properties":{"query":{"type":"string","description":"要删除的记忆内容关键词"}},"required":["query"]}},
]

# 构建 OpenAI 兼容请求体的 tools 数组
tools_array = []
for t in tools_spec:
    tools_array.append({
        "type": "function",
        "function": {
            "name": t["name"],
            "description": t["description"],
            "parameters": t["parameters"]
        }
    })

body = {
    "model": "mimo-v2.5-pro",
    "temperature": 0.7,
    "max_tokens": 2048,
    "messages": [{"role": "user", "content": "\u6253\u5f00\u5feb\u624b"}],
    "tools": tools_array,
    "tool_choice": "auto"
}

raw = json.dumps(body, ensure_ascii=False)
print(f"=== \u5de5\u5177\u6570\u91cf: {len(tools_array)} ===")
print(f"=== \u8bf7\u6c42\u4f53\u603b\u5927\u5c0f: {len(raw):,} \u5b57\u726a / ~{len(raw.encode('utf-8')):,} \u5b57\u8282 ===")
print()

est_tokens = len(raw) // 3
print(f"=== \u7c97\u4f30\u603b token \u6570: ~{est_tokens:,}\uff08\u542b messages + {len(tools_array)} \u4e2a tools \u5b9a\u4e49\uff09 ===")
print()

tools_json = json.dumps(tools_array, ensure_ascii=False)
print(f"=== tools \u6570\u7ec4\u5355\u72ec\u5927\u5c0f: {len(tools_json):,} \u5b57\u726a / ~{len(tools_json.encode('utf-8')):,} \u5b57\u8282 ===")
tools_est_tokens = len(tools_json) // 3
print(f"=== tools \u7c97\u4f30 token: ~{tools_est_tokens:,} ===")
print()
avg_tool_bytes = len(tools_json.encode('utf-8')) // len(tools_array)
print(f"=== \u5e73\u5747\u6bcf\u4e2a tool: ~{avg_tool_bytes:,} \u5b57\u8282 / ~{avg_tool_bytes//3:,} tokens ===")
print()

print("=== description \u6700\u957f\u7684 10 \u4e2a\u5de5\u5177 ===")
sorted_tools = sorted(tools_spec, key=lambda t: len(t["description"]), reverse=True)
for t in sorted_tools[:10]:
    print(f"  [{t['name']}] {len(t['description']):>4}\u5b57\u726a | {t['description'][:60]}...")

print()
print("=== \u26a0\fe0f \u5173\u952e\u98ce\u9669\u70b9 ===")
if est_tokens > 8000:
    print(f"  \u274c \u603b token ~{est_tokens:,} \u53ef\u80fd\u8d85\u8fc7\u90e8\u5206\u6a21\u578b/API\u4ee3\u7406\u7684\u4e0a\u4e0b\u6587\u9650\u5236\uff01")
    print(f"     \u8bb8\u591a\u4e2d\u7aef\u6a21\u578b\u7684\u4e0a\u4e0b\u6587\u7a97\u53e3\u53ea\u6709 4K-8K\uff047\u4e2a\u5de5\u5177\u76f4\u63a5\u5403\u6389\u5927\u90e8\u5206\u7a7a\u95f4\u3002")
if len(tools_array) > 20:
    print(f"  \u26a0\fe0f \u5de5\u5177\u6570\u91cf {len(tools_array)} \u504f\u591a\uff1a\u90e8\u5206 API \u4ee3\u7406/\u4e2d\u8f6c\u5bf9 tools \u6570\u91cf\u6709\u9650\u5236\uff08\u5e38\u89c1\u4e0a\u9650 20-30\uff09")
    print(f"     \u8d85\u9650\u540e\u53ef\u80fd\u9759\u9ed8\u4e22\u5f03\u6574\u4e2a tools \u5b57\u6bb5\u6216\u53ea\u4fdd\u7559\u524d N \u4e2a\uff01")
print(f"  \u2139\ufe0f  maxTokens=2048 \u504f\u4f4e\uff1a\u90e8\u5206\u6a21\u578b\u9700\u8981\u66f4\u9ad8\u503c\u624d\u80fd\u6b63\u786e\u8f93\u51fa tool_calls")
print(f"  \u2139\ufe0f  mimo-v2.5-pro \u662f MiniMax \u6a21\u578b\uff1a\u9700\u786e\u8ba4\u4f60\u7528\u7684 API \u4e2d\u8f6c\u662f\u5426\u5b8c\u6574\u9001\u901e tools \u53c2\u6570")

print("\n=== JSON \u5408\u6cd5\u6027\u68c0\u67e5 ===")
invalid = 0
for t in tools_spec:
    try:
        json.dumps(t["parameters"])
    except:
        invalid += 1
        print(f"  \u274c {t['name']} parametersJson \u4e0d\u5408\u6cd5!")
if invalid == 0:
    print(f"  \u2705 \u5168\u90e8 {len(tools_array)} \u4e2a\u5de5\u5177\u7684 parametersJson \u90fd\u662f\u5408\u6cd5 JSON")

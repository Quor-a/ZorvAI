#!/usr/bin/env python3
import io
paths = [
    r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/core/QuroAssistant.kt",
    r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt",
    r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt",
    r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/chat/ChatPermissionModeBar.kt",
]
for p in paths:
    v = io.open(p, "r", encoding="utf-8", newline="").read()
    print(p.split("\\")[-1], "CRLF" if "\r\n" in v else "LF")

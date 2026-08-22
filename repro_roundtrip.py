#!/usr/bin/env python3
# Faithful replica of QuroConversationPersistence.kt logic:
#   serializeMsg -> parseMsg -> migrateAndClean (JSONObject.NULL == None here)
# Goal: prove whether user/assistant TEXT survives a disk round-trip.

import json, re, uuid

def serialize_msg(m):
    calls = []
    for c in (m.get("toolCalls") or []):
        calls.append({
            "id": c["id"],
            "name": c["name"],
            "arguments": c["arguments"],
            "result": c.get("result"),   # None == JSONObject.NULL
        })
    atts = []
    for a in (m.get("attachments") or []):
        atts.append({"id": a["id"], "type": a["type"], "uri": a["uri"],
                     "name": a["name"], "mime": a["mime"], "size": a["size"]})
    return {
        "id": m["id"],
        "role": m["role"],
        "content": m["content"],
        "toolCallId": m.get("toolCallId"),
        "toolLabel": m.get("toolLabel"),
        "reasoning": m.get("reasoning"),
        "createdAt": m.get("createdAt", 0),
        "hidden": m.get("hidden", False),
        "toolCalls": calls,
        "attachments": atts,
    }

def parse_msg(o):
    calls = None
    if "toolCalls" in o and o["toolCalls"] is not None:
        lst = []
        for c in o["toolCalls"]:
            lst.append({
                "id": c.get("id", ""),
                "name": c.get("name", ""),
                "arguments": c.get("arguments", ""),
                "result": c["result"] if (c.get("result") is not None) else None,
            })
        calls = lst
    atts = None
    if "attachments" in o and o["attachments"] is not None:
        lst = []
        for a in o["attachments"]:
            lst.append({"id": a.get("id", ""), "type": a.get("type", "file"),
                         "uri": a.get("uri", ""), "name": a.get("name", "file"),
                         "mime": a.get("mime", "application/octet-stream"), "size": a.get("size", 0)})
        atts = lst
    tool_call_id = o["toolCallId"] if (o.get("toolCallId") is not None) else None
    tool_label = o["toolLabel"] if (o.get("toolLabel") is not None) else None
    reasoning = o["reasoning"] if (o.get("reasoning") is not None) else None
    role = o.get("role", "user")
    has_tool_calls = bool(calls)
    hidden = o["hidden"] if ("hidden" in o) else (role == "tool" or has_tool_calls)
    return {
        "id": o.get("id", str(uuid.uuid4())),
        "role": role,
        "content": o.get("content", ""),
        "toolCallId": tool_call_id,
        "toolCalls": calls,
        "toolLabel": tool_label,
        "reasoning": reasoning,
        "attachments": atts,
        "hidden": hidden,
        "createdAt": o.get("createdAt", 0),
    }

def is_garbage(s):
    if s is None: return False
    if s.strip() == "" or s == "?" or s == "OK": return True
    if s.startswith("33."): return True
    if re.fullmatch(r"\d+\.?\d*", s):
        try:
            if 0.0 <= float(s) <= 100.0: return True
        except: pass
    return False

def migrate_and_clean(raw):
    cleaned = []
    for conv in raw:
        out = []
        for m in conv["messages"]:
            has_real = (m["content"].strip() != "" or
                         (m.get("reasoning") is not None and m["reasoning"].strip() != "") or
                         bool(m.get("toolCalls")) or
                         bool(m.get("attachments")))
            is_real_result = (m["role"] == "tool" and m.get("toolCallId") is not None
                                 and m["content"].strip() != "")
            if (not has_real) and (not is_real_result):
                continue
            if m["role"] == "tool":
                if is_garbage(m["content"]):
                    out.append(dict(m, content=""))
                else:
                    out.append(m)
                continue
            out.append(m)
        has_real_chat = any((it["role"] == "user") or
                                (it["role"] == "assistant" and it["content"].strip() != "")
                                for it in out)
        if (not has_real_chat) and len(out) == 0:
            continue  # drop empty conv
        if not has_real_chat:
            cleaned.append(dict(conv, messages=[{"id": str(uuid.uuid4()),
                                                       "role": "assistant", "content": "（旧数据已清理）"}]))
            continue
        cleaned.append(dict(conv, messages=out))
    return cleaned

def roundtrip(conv):
    # serialize whole conversation, then parse it back, then migrate
    ser = {"conversations": [{"id": conv["id"], "title": conv["title"],
                       "createdAt": 0, "updatedAt": 0,
                       "messages": [serialize_msg(m) for m in conv["messages"]]}]}
    text = json.dumps(ser)
    root = json.loads(text)
    parsed = []
    for co in root["conversations"]:
        msgs = [parse_msg(m) for m in co["messages"]]
        parsed.append({"id": co["id"], "title": co["title"], "messages": msgs})
    healed = migrate_and_clean(parsed)
    return healed

# ---- realistic conversation: user text + assistant tool block + tool result + assistant final text ----
real = {
    "id": "conv1", "title": "测试",
    "messages": [
        {"id": "u1", "role": "user", "content": "打开快手", "hidden": False},
        {"id": "a1", "role": "assistant", "content": "我要打开快手",
         "reasoning": "我要打开快手", "toolCalls": [{"id": "call_1", "name": "search_and_launch_app", "arguments": "{}"}], "hidden": True},
        {"id": "t1", "role": "tool", "content": "launched kuaishou", "toolCallId": "call_1", "hidden": True},
        {"id": "a2", "role": "assistant", "content": "已为你打开快手", "hidden": False},
    ],
}

healed = roundtrip(real)
print("=== round-trip result (after serialize->parse->migrate) ===")
if not healed:
    print("CONVERSATION WAS DROPPED ENTIRELY")
else:
    for m in healed[0]["messages"]:
        print(f"  role={m['role']:<10} hidden={str(m['hidden']):<5} content={m['content']!r}")
    texts = [m for m in healed[0]["messages"] if m["role"] in ("user", "assistant") and m["content"].strip()]
    print(f"\nTEXT messages surviving: {len(texts)}")
    print("  user text   :", real['messages'][0]['content'], "->",
          next((m['content'] for m in healed[0]['messages'] if m['id']=='u1'), 'MISSING'))
    print("  final reply :", real['messages'][3]['content'], "->",
          next((m['content'] for m in healed[0]['messages'] if m['id']=='a2'), 'MISSING'))

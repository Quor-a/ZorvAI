# -*- coding: utf-8 -*-
"""#210 补签：改了 frontend-dev.md 就必须重签 manifest，否则技能页显示「签名校验失败」。"""
import io, os, re, sys, hashlib, hmac

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKILL = os.path.join(ROOT, "app", "src", "main", "assets", "skills", "zorv")
MAN = os.path.join(SKILL, "manifest.json")
SALT = b"zorv-ai-builtin-skill-sign-v1"
TARGET = "frontend-dev"

raw = io.open(MAN, "rb").read()
crlf = raw.count(b"\r\n")
lf = raw.count(b"\n") - crlf
eol = "\r\n" if crlf and crlf >= lf else "\n"
text = raw.decode("utf-8")

m = re.search(r'\{[^{}]*"name":\s*"%s"[^{}]*\}' % re.escape(TARGET), text)
if not m:
    print("!! 没找到条目:", TARGET)
    sys.exit(1)
block = m.group(0)

sid = re.search(r'"id":\s*"([^"]+)"', block).group(1)
fn = re.search(r'"file":\s*"([^"]+)"', block).group(1)
name = re.search(r'"name":\s*"([^"]+)"', block).group(1)

fpath = os.path.join(SKILL, fn)
data = io.open(fpath, "rb").read()
# 与宿主 SkillSigner / BuiltinSkillAssetsTest 一致：CRLF → LF 归一化后再签
content = data.decode("utf-8").replace("\r\n", "\n")
sig = hmac.new(SALT, ("%s|%s|%s" % (sid, name, content)).encode("utf-8"), hashlib.sha256).hexdigest()

new_block = re.sub(r'"signature":\s*"[0-9a-fA-F]+"', '"signature": "%s"' % sig, block)
new_block = re.sub(r'"size":\s*\d+', '"size": %d' % len(data), new_block)

text = text.replace(block, new_block)
tmp = MAN + ".tmp"
with open(tmp, "wb") as f:
    f.write(text.replace("\n", eol).encode("utf-8"))
os.replace(tmp, MAN)

print("resigned:", name)
print("  id  =", sid)
print("  file=", fn, "size=", len(data))
print("  sig =", sig[:24], "...")

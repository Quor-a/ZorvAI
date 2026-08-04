---
name: edgeone
description: "Deploy a single HTML document to Tencent EdgeOne Pages via mcporter with no login or API key, returning a public URL. Use when a user wants generated HTML published quickly as a live webpage."
---

# EdgeOne
Deploy HTML content to EdgeOne Pages, return the public URL. No login required, no API key required.

## Deploy HTML
HTML or text content to deploy. Provide complete HTML or text content you want to publish, and the system will return a public URL where your content can be accessed.
```shell
npx -y mcporter call mcp-on-edge.edgeone.app/mcp-server.deploy-html value="<html>Content</html>"
npx -y mcporter call mcp-on-edge.edgeone.app/mcp-server.deploy-html value="$(cat index.html)"
```

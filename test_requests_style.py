# 测试 ZorvAI Python 网络请求库
# 使用类似 requests 库的 API

print("=== 测试 ZorvAI Python 网络请求库 ===\n")

# 方法1: 使用全局函数
print("1. 使用全局 get() 函数:")
try:
    import network
    response = network.get("https://api.github.com/repos/Quor-a/ZorvAI")
    print(f"   状态码: {response.status_code}")
    print(f"   响应长度: {len(response.text)} 字符")
    
    # 解析 JSON
    data = response.json()
    print(f"   仓库名: {data['name']}")
    print(f"   星标数: {data['stargazers_count']}")
    print(f"   描述: {data['description'][:80]}...")
except Exception as e:
    print(f"   错误: {e}")

print("\n" + "="*60)

# 方法2: 使用会话对象
print("2. 使用会话对象:")
try:
    import network
    
    # 创建会话
    session = network.Session()
    session.headers = {"User-Agent": "ZorvAI-Python/1.0"}
    
    # 发送请求
    response = session.get("https://api.github.com/search/repositories?q=Zorv+AI")
    print(f"   状态码: {response.status_code}")
    
    # 解析搜索结果
    data = response.json()
    print(f"   找到 {data['total_count']} 个仓库")
    
    # 显示前3个结果
    for i, repo in enumerate(data['items'][:3]):
        print(f"   {i+1}. {repo['full_name']} - ⭐{repo['stargazers_count']}")
except Exception as e:
    print(f"   错误: {e}")

print("\n" + "="*60)

# 方法3: POST 请求
print("3. 测试 POST 请求:")
try:
    import network
    import json
    
    # 使用 httpbin.org 测试
    url = "https://httpbin.org/post"
    data = {"message": "Hello from ZorvAI Python!", "timestamp": "2026-08-20"}
    
    response = network.post(url, json=data)
    print(f"   状态码: {response.status_code}")
    
    # 解析响应
    result = response.json()
    print(f"   服务器收到的 JSON: {result.get('json', {})}")
except Exception as e:
    print(f"   错误: {e}")

print("\n" + "="*60)

# 方法4: 错误处理
print("4. 测试错误处理:")
try:
    import network
    
    # 请求一个不存在的 URL
    response = network.get("https://httpbin.org/status/404")
    print(f"   状态码: {response.status_code}")
    print(f"   状态文本: {response.status_text}")
except Exception as e:
    print(f"   错误: {e}")

print("\n" + "="*60)
print("✅ 所有测试完成！")
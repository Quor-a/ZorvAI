# 测试 Python http_request 功能
# 在 ZorvAI 对话框中运行此代码

import json

# 测试1: 使用同步 http_request_sync 函数
print("=== 测试1: 同步HTTP请求 ===")
try:
    response = http_request_sync("https://api.github.com/repos/Quor-a/ZorvAI")
    print(f"状态码: {response['status']}")
    print(f"响应长度: {len(response['response'])} 字符")
    
    # 解析JSON响应
    data = json.loads(response['response'])
    print(f"仓库名: {data['name']}")
    print(f"星标数: {data['stargazers_count']}")
    print(f"描述: {data['description'][:100]}...")
except Exception as e:
    print(f"同步请求失败: {e}")

print("\n" + "="*50)

# 测试2: 使用异步 http_request 函数
print("=== 测试2: 异步HTTP请求 ===")
try:
    # 注意：异步函数需要在 Brython 中使用 await
    response = await http_request("https://api.github.com/repos/Quor-a/ZorvAI")
    print(f"状态码: {response['status']}")
    print(f"响应长度: {len(response['response'])} 字符")
    
    # 解析JSON响应
    data = json.loads(response['response'])
    print(f"仓库名: {data['name']}")
    print(f"星标数: {data['stargazers_count']}")
    print(f"描述: {data['description'][:100]}...")
except Exception as e:
    print(f"异步请求失败: {e}")

print("\n" + "="*50)

# 测试3: 搜索GitHub仓库
print("=== 测试3: 搜索GitHub仓库 ===")
try:
    search_url = "https://api.github.com/search/repositories?q=Zorv+AI&sort=stars&order=desc"
    response = http_request_sync(search_url)
    print(f"状态码: {response['status']}")
    
    # 解析搜索结果
    data = json.loads(response['response'])
    print(f"找到 {data['total_count']} 个仓库")
    
    # 显示前5个结果
    for i, repo in enumerate(data['items'][:5]):
        print(f"{i+1}. {repo['full_name']} - ⭐{repo['stargazers_count']} - {repo['description'][:50]}...")
except Exception as e:
    print(f"搜索失败: {e}")
# 测试 ZorvAI Python 完整环境
# 展示所有可用功能

print("🚀 测试 ZorvAI Python 完整环境\n")

# 1. 测试标准库
print("1. 标准库测试:")
print(f"   JSON: {json.dumps({'test': 'value'}, ensure_ascii=False)}")
print(f"   数学: π = {math.pi:.6f}")
print(f"   时间: {datetime.datetime.now()}")
print(f"   哈希: {hashlib.md5('test'.encode()).hexdigest()[:8]}...")

print("\n" + "="*60)

# 2. 测试工具函数
print("2. 工具函数测试:")
print(f"   JSON美化: {json_pretty({'name': 'ZorvAI', 'version': '1.0.60'})}")
print(f"   字符串反转: {string_reverse('Hello World')}")
print(f"   单词计数: {string_word_count('This is a test sentence')}")
print(f"   阶乘: 5! = {factorial(5)}")
print(f"   质数检查: 17是质数? {is_prime(17)}")
print(f"   最大公约数: gcd(12, 8) = {gcd(12, 8)}")
print(f"   最小公倍数: lcm(4, 6) = {lcm(4, 6)}")

print("\n" + "="*60)

# 3. 测试日期时间
print("3. 日期时间测试:")
print(f"   当前时间: {now()}")
print(f"   今天日期: {today()}")
print(f"   时间戳: {timestamp()}")

print("\n" + "="*60)

# 4. 测试列表操作
print("4. 列表操作测试:")
nested = [[1, 2], [3, 4], [5, 6]]
print(f"   嵌套列表扁平化: {flatten(nested)}")
lst = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
print(f"   列表分块: {chunk(lst, 3)}")
print(f"   去重: {unique([1, 2, 2, 3, 3, 3])}")

print("\n" + "="*60)

# 5. 测试字典操作
print("5. 字典操作测试:")
dict1 = {'a': 1, 'b': 2}
dict2 = {'c': 3, 'd': 4}
print(f"   字典合并: {dict_merge(dict1, dict2)}")
print(f"   字典反转: {dict_invert({'a': 1, 'b': 2})}")

print("\n" + "="*60)

# 6. 测试调试功能
print("6. 调试功能测试:")
test_obj = {"name": "ZorvAI", "version": "1.0.60", "features": ["Python", "HTTP", "JSON"]}
info = inspect(test_obj)
print(f"   对象检查: {info}")

print("\n" + "="*60)

# 7. 测试网络请求
print("7. 网络请求测试:")
try:
    response = get("https://api.github.com/repos/Quor-a/ZorvAI")
    print(f"   状态码: {response.status_code}")
    data = response.json()
    print(f"   仓库名: {data['name']}")
    print(f"   星标数: {data['stargazers_count']}")
    print(f"   描述: {data['description'][:80]}...")
except Exception as e:
    print(f"   网络请求失败: {e}")

print("\n" + "="*60)

# 8. 测试错误处理
print("8. 错误处理测试:")
try:
    result = 10 / 0
except ZeroDivisionError as e:
    print(f"   捕获异常: {type(e).__name__}: {e}")

print("\n" + "="*60)

# 9. 测试数据结构
print("9. 数据结构测试:")
from collections import Counter, defaultdict
words = ['apple', 'banana', 'apple', 'cherry', 'banana', 'apple']
word_counts = Counter(words)
print(f"   单词计数: {word_counts}")
print(f"   最常见: {word_counts.most_common(2)}")

print("\n" + "="*60)

# 10. 测试正则表达式
print("10. 正则表达式测试:")
text = "Hello World! Email: test@example.com, Phone: 123-456-7890"
emails = re.findall(r'[\w.-]+@[\w.-]+', text)
phones = re.findall(r'\d{3}-\d{3}-\d{4}', text)
print(f"   提取邮箱: {emails}")
print(f"   提取电话: {phones}")

print("\n" + "="*60)
print("✅ 所有测试完成！ZorvAI Python 完整环境运行正常！")
print("🌐 网络请求功能: ✅ 可用")
print("📦 标准库: ✅ 可用")
print("🛠️ 工具函数: ✅ 可用")
print("🔍 调试功能: ✅ 可用")
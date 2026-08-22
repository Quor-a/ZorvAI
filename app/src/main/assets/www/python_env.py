"""
ZorvAI Python 完整环境模块
预加载常用标准库和增强功能
"""

import json
import sys
import os
import re
import math
import datetime
import collections
import itertools
import functools
import string
import io
import hashlib
import base64
import urllib.parse
import time
import random
import uuid
import decimal
import fractions
import statistics
import textwrap
import unicodedata
import xml.etree.ElementTree as ET
import csv
import struct
import socket
import ssl
import http.client
import email
import html
import difflib
import tempfile
import shutil
import glob
import fnmatch
import operator
import contextlib
import abc
import copy
import pprint
import warnings
import traceback
import logging

# 增强的 print 函数
_original_print = print

def enhanced_print(*args, **kwargs):
    """增强的 print 函数，支持更多格式化选项"""
    sep = kwargs.get('sep', ' ')
    end = kwargs.get('end', '\n')
    file = kwargs.get('file', sys.stdout)
    
    # 处理特殊对象
    formatted_args = []
    for arg in args:
        if isinstance(arg, dict):
            formatted_args.append(json.dumps(arg, indent=2, ensure_ascii=False))
        elif isinstance(arg, list):
            formatted_args.append(json.dumps(arg, indent=2, ensure_ascii=False))
        elif isinstance(arg, set):
            formatted_args.append(json.dumps(list(arg), ensure_ascii=False))
        elif isinstance(arg, tuple):
            formatted_args.append(json.dumps(list(arg), ensure_ascii=False))
        else:
            formatted_args.append(str(arg))
    
    message = sep.join(formatted_args) + end
    
    # 使用原始 print 输出
    _original_print(message, end='', file=file)

# 替换全局 print
print = enhanced_print

# 增强的 input 函数（在浏览器中不支持，提供模拟）
def enhanced_input(prompt=""):
    """模拟 input 函数（在浏览器环境中）"""
    print(f"[输入提示] {prompt}")
    return ""

# 增强的类型检查
def type_name(obj):
    """获取对象的类型名称"""
    return type(obj).__name__

def is_type(obj, type_name_str):
    """检查对象是否为指定类型"""
    return type(obj).__name__ == type_name_str

# 增强的 JSON 处理
def json_pretty(obj, indent=2):
    """美化 JSON 输出"""
    return json.dumps(obj, indent=indent, ensure_ascii=False, sort_keys=True)

def json_compact(obj):
    """紧凑 JSON 输出"""
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'))

# 增强的字符串处理
def string_utils():
    """字符串工具函数"""
    return {
        'reverse': lambda s: s[::-1],
        'capitalize_words': lambda s: ' '.join(word.capitalize() for word in s.split()),
        'count_chars': lambda s: {char: s.count(char) for char in set(s)},
        'is_palindrome': lambda s: s == s[::-1],
        'word_count': lambda s: len(s.split()),
        'char_frequency': lambda s: dict(sorted(((char, s.count(char)) for char in set(s)), key=lambda x: x[1], reverse=True))
    }

# 增强的数学处理
def math_utils():
    """数学工具函数"""
    return {
        'factorial': lambda n: math.factorial(n),
        'is_prime': lambda n: all(n % i != 0 for i in range(2, int(math.sqrt(n)) + 1)) if n > 1 else False,
        'fibonacci': lambda n: [0, 1] if n <= 2 else [0, 1] + [fibonacci(i-1) + fibonacci(i-2) for i in range(2, n)],
        'gcd': lambda a, b: math.gcd(a, b),
        'lcm': lambda a, b: abs(a * b) // math.gcd(a, b),
        'deg_to_rad': lambda deg: math.radians(deg),
        'rad_to_deg': lambda rad: math.degrees(rad),
        'clamp': lambda x, min_val, max_val: max(min_val, min(x, max_val))
    }

# 增强的日期时间处理
def datetime_utils():
    """日期时间工具函数"""
    return {
        'now': lambda: datetime.datetime.now().isoformat(),
        'today': lambda: datetime.date.today().isoformat(),
        'timestamp': lambda: int(time.time()),
        'format_date': lambda dt, fmt: dt.strftime(fmt) if isinstance(dt, datetime.datetime) else str(dt),
        'parse_date': lambda s, fmt: datetime.datetime.strptime(s, fmt),
        'days_between': lambda d1, d2: (d2 - d1).days if isinstance(d1, datetime.date) and isinstance(d2, datetime.date) else 0
    }

# 增强的列表处理
def list_utils():
    """列表工具函数"""
    return {
        'flatten': lambda lst: [item for sublist in lst for item in (sublist if isinstance(sublist, list) else [sublist])],
        'chunk': lambda lst, size: [lst[i:i+size] for i in range(0, len(lst), size)],
        'unique': lambda lst: list(dict.fromkeys(lst)),
        'interleave': lambda *lists: [item for items in zip(*lists) for item in items],
        'rotate': lambda lst, n: lst[n:] + lst[:n],
        'compact': lambda lst: [item for item in lst if item is not None and item != '']
    }

# 增强的字典处理
def dict_utils():
    """字典工具函数"""
    return {
        'merge': lambda *dicts: {k: v for d in dicts for k, v in d.items()},
        'invert': lambda d: {v: k for k, v in d.items()},
        'filter_by_value': lambda d, value: {k: v for k, v in d.items() if v == value},
        'map_values': lambda d, func: {k: func(v) for k, v in d.items()},
        'group_by': lambda lst, key: functools.reduce(lambda acc, x: acc.setdefault(key(x), []).append(x) or acc, lst, {})
    }

# 增强的调试功能
def debug_utils():
    """调试工具函数"""
    return {
        'inspect': lambda obj: {
            'type': type(obj).__name__,
            'value': str(obj),
            'repr': repr(obj),
            'id': id(obj),
            'dir': dir(obj)[:20]  # 只显示前20个属性
        },
        'type_check': lambda obj: type(obj).__name__,
        'size_of': lambda obj: sys.getsizeof(obj),
        'memory_view': lambda obj: memoryview(bytes(str(obj), 'utf-8'))
    }

# 增强的网络请求（已包含在 network 模块中）
# 这里只是重新导出
try:
    from network import get, post, put, delete, patch, Session
except ImportError:
    # 如果 network 模块未加载，提供空函数
    def get(url, **kwargs): raise Exception("network 模块未加载")
    def post(url, **kwargs): raise Exception("network 模块未加载")
    def put(url, **kwargs): raise Exception("network 模块未加载")
    def delete(url, **kwargs): raise Exception("network 模块未加载")
    def patch(url, **kwargs): raise Exception("network 模块未加载")
    class Session: pass

# 导出所有工具
__all__ = [
    # 标准库
    'json', 'sys', 'os', 're', 'math', 'datetime', 'collections', 
    'itertools', 'functools', 'string', 'io', 'hashlib', 'base64',
    'urllib', 'time', 'random', 'uuid', 'decimal', 'fractions',
    'statistics', 'textwrap', 'unicodedata', 'xml', 'csv', 'struct',
    'socket', 'ssl', 'http', 'email', 'html', 'difflib', 'tempfile',
    'shutil', 'glob', 'fnmatch', 'operator', 'contextlib', 'abc',
    'copy', 'pprint', 'warnings', 'traceback', 'logging',
    
    # 增强函数
    'print', 'input', 'type_name', 'is_type',
    
    # 工具函数
    'json_pretty', 'json_compact',
    'string_utils', 'math_utils', 'datetime_utils',
    'list_utils', 'dict_utils', 'debug_utils',
    
    # 网络请求
    'get', 'post', 'put', 'delete', 'patch', 'Session'
]

# 初始化信息
print("✅ ZorvAI Python 完整环境已加载")
print(f"📦 Python 版本: {sys.version}")
print(f"🔧 可用模块: {len(__all__)} 个")
print("🌐 网络请求: 已就绪")
print("🛠️ 工具函数: 已加载")
print("💡 提示: 使用 print() 输出，json_pretty() 美化JSON")
print("   使用 get/post/put/delete/patch 发送HTTP请求")
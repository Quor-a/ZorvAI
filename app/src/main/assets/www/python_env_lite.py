"""
ZorvAI Python 完整环境模块（精简版）
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
import difflib
import tempfile
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
        if isinstance(arg, (dict, list, set, tuple)):
            formatted_args.append(json.dumps(arg, indent=2, ensure_ascii=False))
        else:
            formatted_args.append(str(arg))
    
    message = sep.join(formatted_args) + end
    _original_print(message, end='', file=file)

# 替换全局 print
print = enhanced_print

# 增强的 JSON 处理
def json_pretty(obj, indent=2):
    """美化 JSON 输出"""
    return json.dumps(obj, indent=indent, ensure_ascii=False, sort_keys=True)

def json_compact(obj):
    """紧凑 JSON 输出"""
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'))

# 增强的字符串处理
def string_reverse(s): return s[::-1]
def string_word_count(s): return len(s.split())
def string_char_frequency(s): return dict(sorted(((char, s.count(char)) for char in set(s)), key=lambda x: x[1], reverse=True))

# 增强的数学处理
def factorial(n): return math.factorial(n)
def is_prime(n): return all(n % i != 0 for i in range(2, int(math.sqrt(n)) + 1)) if n > 1 else False
def gcd(a, b): return math.gcd(a, b)
def lcm(a, b): return abs(a * b) // math.gcd(a, b)

# 增强的日期时间处理
def now(): return datetime.datetime.now().isoformat()
def today(): return datetime.date.today().isoformat()
def timestamp(): return int(time.time())

# 增强的列表处理
def flatten(lst): return [item for sublist in lst for item in (sublist if isinstance(sublist, list) else [sublist])]
def chunk(lst, size): return [lst[i:i+size] for i in range(0, len(lst), size)]
def unique(lst): return list(dict.fromkeys(lst))

# 增强的字典处理
def dict_merge(*dicts): return {k: v for d in dicts for k, v in d.items()}
def dict_invert(d): return {v: k for k, v in d.items()}

# 增强的调试功能
def inspect(obj):
    """检查对象信息"""
    return {
        'type': type(obj).__name__,
        'value': str(obj)[:100],
        'repr': repr(obj)[:100],
        'id': id(obj),
        'dir': dir(obj)[:15]
    }

def type_name(obj):
    """获取对象类型名称"""
    return type(obj).__name__

# 导出所有工具
__all__ = [
    # 标准库
    'json', 'sys', 'os', 're', 'math', 'datetime', 'collections', 
    'itertools', 'functools', 'string', 'io', 'hashlib', 'base64',
    'urllib', 'time', 'random', 'uuid', 'decimal', 'fractions',
    'statistics', 'textwrap', 'unicodedata', 'xml', 'csv', 'struct',
    'difflib', 'tempfile', 'glob', 'fnmatch', 'operator', 'contextlib',
    'abc', 'copy', 'pprint', 'warnings', 'traceback', 'logging',
    
    # 增强函数
    'print', 'json_pretty', 'json_compact',
    
    # 字符串工具
    'string_reverse', 'string_word_count', 'string_char_frequency',
    
    # 数学工具
    'factorial', 'is_prime', 'gcd', 'lcm',
    
    # 日期时间工具
    'now', 'today', 'timestamp',
    
    # 列表工具
    'flatten', 'chunk', 'unique',
    
    # 字典工具
    'dict_merge', 'dict_invert',
    
    # 调试工具
    'inspect', 'type_name'
]

# 初始化信息
print("✅ ZorvAI Python 完整环境已加载")
print(f"📦 Python 版本: {sys.version}")
print(f"🔧 可用模块: {len(__all__)} 个")
print("🛠️ 工具函数: 已加载")
print("💡 提示: 使用 print() 输出，json_pretty() 美化JSON")
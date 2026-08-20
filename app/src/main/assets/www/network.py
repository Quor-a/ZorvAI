"""
ZorvAI Python 网络请求模块
提供类似 requests 库的 API，基于浏览器 XMLHttpRequest
"""

import json
from browser import window

class Response:
    """HTTP 响应对象"""
    def __init__(self, xhr_response):
        self.status_code = xhr_response['status']
        self.status_text = xhr_response['statusText']
        self.text = xhr_response['response']
        self.headers = self._parse_headers(xhr_response['headers'])
        
    def _parse_headers(self, headers_str):
        """解析响应头字符串"""
        headers = {}
        if headers_str:
            for line in headers_str.split('\n'):
                if ':' in line:
                    key, value = line.split(':', 1)
                    headers[key.strip()] = value.strip()
        return headers
    
    def json(self):
        """解析 JSON 响应"""
        return json.loads(self.text)
    
    def __repr__(self):
        return f"<Response [{self.status_code}]>"

class Session:
    """会话对象，支持持久化设置"""
    def __init__(self):
        self.headers = {}
        self.timeout = 10
    
    def get(self, url, **kwargs):
        """发送 GET 请求"""
        return self._request('GET', url, **kwargs)
    
    def post(self, url, **kwargs):
        """发送 POST 请求"""
        return self._request('POST', url, **kwargs)
    
    def put(self, url, **kwargs):
        """发送 PUT 请求"""
        return self._request('PUT', url, **kwargs)
    
    def delete(self, url, **kwargs):
        """发送 DELETE 请求"""
        return self._request('DELETE', url, **kwargs)
    
    def patch(self, url, **kwargs):
        """发送 PATCH 请求"""
        return self._request('PATCH', url, **kwargs)
    
    def _request(self, method, url, **kwargs):
        """发送 HTTP 请求"""
        headers = kwargs.get('headers', {})
        data = kwargs.get('data', None)
        json_data = kwargs.get('json', None)
        timeout = kwargs.get('timeout', self.timeout)
        
        # 合并会话头和请求头
        merged_headers = {**self.headers, **headers}
        
        # 如果有 JSON 数据，设置 Content-Type
        if json_data is not None:
            merged_headers['Content-Type'] = 'application/json'
            data = json.dumps(json_data)
        
        # 使用浏览器 XMLHttpRequest
        try:
            xhr = window.XMLHttpRequest.new()
            xhr.open(method, url, False)  # False = 同步
            
            # 设置请求头
            for key, value in merged_headers.items():
                xhr.setRequestHeader(key, value)
            
            # 发送请求
            xhr.send(data if data else None)
            
            # 创建响应对象
            return Response({
                'status': xhr.status,
                'statusText': xhr.statusText,
                'response': xhr.responseText,
                'headers': xhr.getAllResponseHeaders()
            })
        except Exception as e:
            raise Exception(f"请求失败: {e}")

# 创建默认会话
session = Session()

# 便捷函数
def get(url, **kwargs):
    """发送 GET 请求"""
    return session.get(url, **kwargs)

def post(url, **kwargs):
    """发送 POST 请求"""
    return session.post(url, **kwargs)

def put(url, **kwargs):
    """发送 PUT 请求"""
    return session.put(url, **kwargs)

def delete(url, **kwargs):
    """发送 DELETE 请求"""
    return session.delete(url, **kwargs)

def patch(url, **kwargs):
    """发送 PATCH 请求"""
    return session.patch(url, **kwargs)

def head(url, **kwargs):
    """发送 HEAD 请求"""
    return session._request('HEAD', url, **kwargs)

def options(url, **kwargs):
    """发送 OPTIONS 请求"""
    return session._request('OPTIONS', url, **kwargs)
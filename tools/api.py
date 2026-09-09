#!/usr/bin/env python3
"""商户 API 的签名客户端（只用标准库）。

用法：
    tools/api.py GET  /api/v1/deposits/balance?token=0x7798...
    tools/api.py POST /api/v1/deposit-addresses '{"token":"0x7798..."}'

凭证从环境变量来，不从参数来（参数会进 shell 历史与 ps 输出）：
    CHAINPAY_API_KEY      商户 API key
    CHAINPAY_API_SECRET   发凭证时唯一一次出现的 secret
    CHAINPAY_API_BASE     默认 http://127.0.0.1:8095

签名协议 CP2（与 ApiCredentialService.prehash / 测试助手 SignedRequests 一字不差，改协议要三处同改）：
    canonical = "CP2\\n" + 毫秒时间戳 + "\\n" + nonce(32 个十六进制字符) + "\\n" + 方法 + "\\n" + 路径(含查询串，原样) + "\\n" + sha256hex(body)
    signature = Base64(HMAC-SHA256(canonical, secret))
换行能当分隔符，因为没有任何一段可能含有它；请求体换成哈希后最后一段也定长，边界因此唯一。
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request


def sign(secret: str, timestamp_ms: int, nonce: str, method: str, path: str, body: str) -> str:
    body_hash = hashlib.sha256(body.encode("utf-8")).hexdigest()
    canonical = f"CP2\n{timestamp_ms}\n{nonce}\n{method}\n{path}\n{body_hash}".encode("utf-8")
    digest = hmac.new(secret.encode("utf-8"), canonical, hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    method, path = argv[1].upper(), argv[2]
    body = argv[3] if len(argv) > 3 else ""
    api_key = os.environ.get("CHAINPAY_API_KEY", "")
    secret = os.environ.get("CHAINPAY_API_SECRET", "")
    base = os.environ.get("CHAINPAY_API_BASE", "http://127.0.0.1:8095")
    if not api_key or not secret:
        print("缺 CHAINPAY_API_KEY / CHAINPAY_API_SECRET 环境变量", file=sys.stderr)
        return 2

    timestamp_ms = int(time.time() * 1000)
    nonce = secrets.token_hex(16)
    headers = {
        "X-CP-API-KEY": api_key,
        "X-CP-API-TIMESTAMP": str(timestamp_ms),
        "X-CP-API-NONCE": nonce,
        "X-CP-API-SIGN": sign(secret, timestamp_ms, nonce, method, path, body),
        "Content-Type": "application/json",
    }
    request = urllib.request.Request(base + path, data=body.encode("utf-8") if body else None,
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, text = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status, text = error.code, error.read().decode("utf-8", errors="replace")
    print(f"HTTP {status}")
    try:
        print(json.dumps(json.loads(text), ensure_ascii=False, indent=2))
    except ValueError:
        print(text)
    return 0 if status < 400 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

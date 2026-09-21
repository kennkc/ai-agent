"""面向外部模型供应商的 URL opener。

系统代理可能失效，也可能只适用于部分供应商。模型配置可以通过 extra 显式指定：
- trust_env: 是否读取系统/环境代理；
- proxy_url: 显式代理；
- no_proxy: 当前模型地址的绕过列表。
"""
from __future__ import annotations

import os
from urllib.request import ProxyHandler, build_opener, getproxies, Request


def proxy_bypass_host(host: str, patterns: str | list[str] | None) -> bool:
    """按 NO_PROXY 语义判断主机是否绕过代理。"""
    if isinstance(patterns, str):
        values = [item.strip() for item in patterns.split(",") if item.strip()]
    else:
        values = [str(item).strip() for item in (patterns or []) if str(item).strip()]
    normalized = (host or "").split(":", 1)[0].lower().strip(".")
    if not normalized:
        return False
    for pattern in values:
        value = pattern.lower().strip()
        if value == "*":
            return True
        value = value.split(":", 1)[0].lstrip(".")
        if normalized == value or normalized.endswith("." + value):
            return True
    return False


class _NoProxyHandler(ProxyHandler):
    def __init__(self, proxies: dict | None, no_proxy: str | list[str] | None):
        super().__init__(proxies or {})
        self._no_proxy = no_proxy

    def proxy_open(self, req, proxy, type):
        if proxy_bypass_host(req.host, self._no_proxy):
            return None
        return super().proxy_open(req, proxy, type)


def build_url_opener(*, proxy_url: str = "", no_proxy: str | list[str] | None = None,
                     trust_env: bool = True):
    """按模型配置构建 opener。显式 no_proxy 优先，否则读取环境 NO_PROXY。"""
    if not trust_env:
        proxies = {}
    elif proxy_url:
        proxies = {"http": proxy_url, "https": proxy_url}
    else:
        proxies = getproxies()
    effective_no_proxy = no_proxy
    if not effective_no_proxy:
        effective_no_proxy = os.getenv("NO_PROXY") or os.getenv("no_proxy") or ""
    return build_opener(_NoProxyHandler(proxies, effective_no_proxy))


def open_url(request: Request, *, timeout: float, proxy_url: str = "",
             no_proxy: str | list[str] | None = None, trust_env: bool = True):
    opener = build_url_opener(proxy_url=proxy_url, no_proxy=no_proxy, trust_env=trust_env)
    return opener.open(request, timeout=timeout)

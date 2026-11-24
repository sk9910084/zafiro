# Seed tool: multi-engine web search (all / baidu / sogou / bing / ddg).
# ponytail: baidu/sogou 返回跳转链接不展开；解析选择器随站点改版会失效，Agent 可自更新本脚本。
# 失败不再静默：有结果时返回结果（引擎错误写 stderr）；零结果且有引擎报错时抛 RuntimeError 并带每个引擎的原因。
import base64
import json
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

import requests
from bs4 import BeautifulSoup

_UA_ANDROID = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
_UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
_ENGINES = ("baidu", "sogou", "bing", "ddg")
_RETRY_STATUS = {429, 500, 502, 503, 504}
_MAX_ATTEMPTS = 3
_BACKOFF_SECONDS = 0.7


class SearchError(Exception):
    pass


def main(query: str, engine: str = "all", max_results: int = 8):
    '''Search the web. engine: "all" (default, merges Baidu + Sogou + Bing + DuckDuckGo), "baidu", "sogou", "bing" or "ddg". Returns a list of {title, url, snippet}. Raises an error listing each engine's failure reason if nothing could be fetched; an empty list means the engines answered with no results.'''
    if engine != "all" and engine not in _ENGINES:
        raise ValueError("engine must be one of: all, " + ", ".join(_ENGINES))
    engines = _ENGINES if engine == "all" else (engine,)
    pool = ThreadPoolExecutor(max_workers=len(engines))
    futures = [pool.submit(_search, e, query, max_results) for e in engines]
    fetched, errors = [], {}
    for e, fut in zip(engines, futures):
        try:
            fetched.append(fut.result(timeout=40))
        except Exception as exc:  # noqa: BLE001 - recorded per engine, never swallowed
            fetched.append([])
            errors[e] = "%s: %s" % (type(exc).__name__, exc)
    pool.shutdown(wait=False)  # don't block on a hung engine thread

    merged = _merge(fetched, max_results)
    if not merged and errors:
        raise RuntimeError("web search failed on every engine: " + json.dumps(errors, ensure_ascii=False))
    if errors:
        print("web search partial failures: " + json.dumps(errors, ensure_ascii=False), file=sys.stderr)
    print(json.dumps(merged, ensure_ascii=False))


def _request(method: str, url: str, **kwargs):
    last = None
    for attempt in range(_MAX_ATTEMPTS):
        try:
            resp = requests.request(method, url, **kwargs)
            if resp.status_code in _RETRY_STATUS:
                last = SearchError("HTTP %d" % resp.status_code)
            else:
                resp.raise_for_status()  # non-retryable 4xx (e.g. 403) surfaces immediately
                return resp
        except (requests.Timeout, requests.ConnectionError) as exc:
            last = exc
        if attempt < _MAX_ATTEMPTS - 1:
            time.sleep(_BACKOFF_SECONDS * (2 ** attempt))
    raise last


def _check_blocked(engine: str, resp):
    head = resp.text[:20000]
    final_url = resp.url or ""
    if engine == "baidu" and ("wappass.baidu.com" in final_url or "captcha" in final_url or "百度安全验证" in head):
        raise SearchError("blocked by Baidu captcha")
    if engine == "sogou" and ("antispider" in final_url or "验证码" in head):
        raise SearchError("blocked by Sogou anti-spider")
    if engine == "ddg" and "anomaly" in head:
        raise SearchError("blocked by DuckDuckGo bot check")


def _search(engine: str, query: str, max_results: int):
    if engine == "baidu":
        # baidu 桌面 UA 会触发验证码，必须移动 UA
        resp = _request(
            "GET", "https://www.baidu.com/s",
            params={"wd": query}, headers={"User-Agent": _UA_ANDROID}, timeout=10,
        )
        parser = _parse_baidu
    elif engine == "sogou":
        resp = _request(
            "GET", "https://www.sogou.com/web",
            params={"query": query}, headers={"User-Agent": _UA_DESKTOP}, timeout=10,
        )
        parser = _parse_sogou
    elif engine == "bing":
        resp = _request(
            "GET", "https://www.bing.com/search",
            params={"q": query},
            headers={"User-Agent": _UA_DESKTOP, "Accept-Language": "en-US,en;q=0.9"},
            timeout=10,
        )
        parser = _parse_bing
    else:
        resp = _request(
            "POST", "https://html.duckduckgo.com/html/",
            data={"q": query}, headers={"User-Agent": _UA_ANDROID}, timeout=8,
        )
        parser = _parse_ddg
    _check_blocked(engine, resp)
    soup = BeautifulSoup(resp.text, "html.parser")
    results = parser(soup, max_results)
    if not results:
        raise SearchError("0 results parsed (blocked page or site layout changed)")
    return results


def _merge(lists, max_results):
    seen = set()
    merged = []
    for group in lists:
        for item in group:
            url = item.get("url", "")
            if url in seen:
                continue
            seen.add(url)
            merged.append(item)
            if len(merged) >= max_results:
                return merged
    return merged


def _parse_ddg(soup, max_results):
    results = []
    for item in soup.select("div.result.results_links")[: max_results]:
        link = item.select_one("a.result__a")
        if link is None:
            continue
        snippet_el = item.select_one("td.result__snippet") or item.select_one(".result__snippet")
        _append(results, link.get_text(strip=True), _clean_ddg_url(link.get("href", "")), snippet_el)
        if len(results) >= max_results:
            break
    return results


def _parse_bing(soup, max_results):
    results = []
    for item in soup.select("li.b_algo"):
        if len(results) >= max_results:
            break
        link = item.select_one("h2 a")
        if link is None:
            continue
        snippet_el = item.select_one(".b_caption p") or item.select_one("p")
        _append(results, link.get_text(strip=True), _clean_bing_url(link.get("href", "")), snippet_el)
    return results


def _parse_baidu(soup, max_results):
    results = []
    for item in soup.select("div.c-result"):
        if len(results) >= max_results:
            break
        title_el = item.select_one("h3")
        if title_el is None:
            continue
        try:
            url = json.loads(item.get("data-log", "{}")).get("mu", "")
        except ValueError:
            url = ""
        snippet_el = None
        for sel in ("[class*=content-right]", "[class*=abstract]", "[class*=clamp]", "[class*=desc]"):
            candidate = item.select_one(sel)
            if candidate is not None:
                snippet_el = candidate
                break
        _append(results, title_el.get_text(strip=True), url, snippet_el)
    return results


def _parse_sogou(soup, max_results):
    results = []
    for item in soup.select("div.vrwrap, div.rb"):
        if len(results) >= max_results:
            break
        link = item.select_one("h3 a")
        if link is None:
            continue
        snippet_el = item.select_one(".str-text-info") or item.select_one(".str_info") or item.select_one(".space-txt")
        _append(results, link.get_text(strip=True), _clean_sogou_url(link.get("href", "")), snippet_el)
    return results


def _append(results, title, url, snippet_el):
    snippet = snippet_el.get_text(strip=True) if snippet_el else ""
    if title and url:
        results.append({"title": title, "url": url, "snippet": snippet})


def _clean_ddg_url(href: str) -> str:
    if href.startswith("//duckduckgo.com/l/") or href.startswith("https://duckduckgo.com/l/"):
        parsed = urllib.parse.urlparse(href if href.startswith("http") else "https:" + href)
        target = urllib.parse.parse_qs(parsed.query).get("uddg", [""])[0]
        return urllib.parse.unquote(target)
    return href


def _clean_bing_url(href: str) -> str:
    # bing wraps many results as bing.com/ck/a?...&u=a1<base64url(target)>
    if "bing.com/ck/a" in href:
        u = urllib.parse.parse_qs(urllib.parse.urlparse(href).query).get("u", [""])[0]
        if u.startswith("a1"):
            payload = u[2:]
            payload += "=" * (-len(payload) % 4)
            try:
                return base64.urlsafe_b64decode(payload).decode("utf-8")
            except Exception:  # noqa: BLE001
                return href
    return href


def _clean_sogou_url(href: str) -> str:
    if href.startswith("/link"):
        return "https://www.sogou.com" + href
    return href

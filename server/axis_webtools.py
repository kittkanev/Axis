import hashlib
import http.client
import ipaddress
import json
import re
import socket
import threading
import time
from html.parser import HTMLParser
from urllib.parse import parse_qs, quote, urlencode, urljoin, urlsplit

from fastapi import Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field


USER_AGENT = "Axis/0.6 (https://github.com/kittkanev/Axis)"
MAX_PAGE = 1024 * 1024
TRANSITION_ADDRESSES = tuple(ipaddress.ip_network(network) for network in (
    "64:ff9b::/96", "64:ff9b:1::/48", "2001::/32", "2002::/16",
))


class InvalidAddress(ValueError):
    pass


def public_addresses(host, port):
    addresses = {item[4][0] for item in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)}
    for text in addresses:
        ip = ipaddress.ip_address(text)
        if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        if not ip.is_global or ip.is_multicast or ip.is_reserved or any(ip in network for network in TRANSITION_ADDRESSES):
            raise InvalidAddress("URL must resolve only to public addresses")
    if not addresses:
        raise InvalidAddress("URL has no public address")
    return sorted(addresses, key=lambda address: (":" in address, address))


def fetch(url, *, headers=None, limit=MAX_PAGE):
    deadline = time.monotonic() + 10
    for _ in range(4):
        if any(char in url for char in ('\r', '\n', '\t', '\\')) or len(url) > 2048:
            raise InvalidAddress("invalid URL")
        parsed = urlsplit(url)
        secure = parsed.scheme == "https"
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username is not None:
            raise InvalidAddress("use an HTTP or HTTPS URL without credentials")
        default_port = 443 if secure else 80
        port = parsed.port or default_port
        if port != default_port or "%" in parsed.hostname:
            raise InvalidAddress("only standard HTTP and HTTPS ports are permitted")
        host = parsed.hostname.encode("idna").decode("ascii")
        addresses = public_addresses(host, port)
        if time.monotonic() >= deadline:
            raise TimeoutError("upstream timed out")
        # Use the validated IP for TCP while keeping the original hostname for TLS and Host.
        transport = http.client.HTTPSConnection if secure else http.client.HTTPConnection
        connection = transport(host, port=port, timeout=min(3, deadline - time.monotonic()))
        connection._create_connection = lambda _target, timeout, source: socket.create_connection(
            (addresses[0], port), timeout=timeout, source_address=source)
        path = quote(parsed.path or "/", safe="/%:@!$&'()*+,;=-._~")
        if parsed.query:
            path += "?" + quote(parsed.query, safe="%=&/:+?@!$'()*+,;~-._")
        try:
            connection.request("GET", path, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "identity",
                                                    **(headers or {})})
            response = connection.getresponse()
            if response.status in {301, 302, 303, 307, 308}:
                location = response.getheader("Location")
                if not location or headers:
                    raise InvalidAddress("provider redirect rejected")
                destination = urljoin(url, location)
                if secure and urlsplit(destination).scheme != "https":
                    raise InvalidAddress("HTTPS downgrade rejected")
                url = destination
                continue
            if response.status != 200 or response.getheader("Content-Encoding", "identity").lower() != "identity":
                raise OSError("upstream refused or compressed the request")
            if response.length is not None and response.length > limit:
                raise InvalidAddress("response exceeds size limit")
            body = bytearray()
            while True:
                if time.monotonic() >= deadline:
                    raise TimeoutError("upstream timed out")
                chunk = response.read1(min(16384, limit + 1 - len(body)))
                if not chunk:
                    return bytes(body), response.headers
                body.extend(chunk)
                if len(body) > limit:
                    raise InvalidAddress("response exceeds size limit")
        finally:
            connection.close()
    raise InvalidAddress("too many redirects")


def read_json(url, headers=None):
    return json.loads(fetch(url, headers=headers, limit=256 * 1024)[0])


class SearchResults(HTMLParser):
    def __init__(self):
        super().__init__()
        self.results = []
        self.link = None
        self.title = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "a" and "result-link" in (attrs.get("class") or "").split():
            link = attrs.get("href") or ""
            redirected = parse_qs(urlsplit(link).query).get("uddg", [])
            self.link = redirected[0] if redirected else link
            self.title = []

    def handle_data(self, text):
        if self.link:
            self.title.append(text)

    def handle_endtag(self, tag):
        if tag == "a" and self.link:
            if urlsplit(self.link).scheme in {"http", "https"} and len(self.results) < 5:
                self.results.append(f"{''.join(self.title).strip()} — {self.link}")
            self.link = None


class ToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Query(ToolInput):
    query: str = Field(min_length=1, max_length=250)


class Target(ToolInput):
    url: str = Field(min_length=1, max_length=2048)


class City(ToolInput):
    city: str = Field(min_length=2, max_length=100)


class Currency(ToolInput):
    base: str = Field(pattern=r"^[A-Za-z]{3}$")
    target: str = Field(pattern=r"^[A-Za-z]{3}$")


class Wiki(ToolInput):
    title: str = Field(min_length=1, max_length=150)
    language: str = Field(default="ru", pattern=r"^[a-z]{2,3}$")


class WebTools:
    def __init__(self, db, limiter, brave_key=None):
        self.db = db
        self.limiter = limiter
        self.brave_key = brave_key
        self.in_flight = threading.BoundedSemaphore(2)
        with db() as conn:
            conn.execute("CREATE TABLE IF NOT EXISTS tool_cache (key TEXT PRIMARY KEY, expires INTEGER, text TEXT)")

    def cached(self, name, request, account, ttl, fetcher):
        self.limiter.check(account)
        key = hashlib.sha256((name + request.model_dump_json()).encode()).hexdigest()
        now = int(time.time())
        with self.db() as conn:
            cached = conn.execute("SELECT text FROM tool_cache WHERE key=? AND expires>?", (key, now)).fetchone()
        if cached:
            return {"text": cached[0]}
        if not self.in_flight.acquire(blocking=False):
            raise HTTPException(429, "tools are busy", headers={"Retry-After": "2"})
        try:
            try:
                text = fetcher()[:4500]
            except InvalidAddress as exc:
                raise HTTPException(422, str(exc)) from None
            except (OSError, ValueError, LookupError, TypeError, http.client.HTTPException):
                raise HTTPException(502, "upstream service temporarily unavailable") from None
        finally:
            self.in_flight.release()
        with self.db() as conn:
            conn.execute("DELETE FROM tool_cache WHERE expires <= ?", (now,))
            conn.execute("INSERT OR REPLACE INTO tool_cache VALUES (?,?,?)", (key, now + ttl, text))
            conn.execute("DELETE FROM tool_cache WHERE key IN (SELECT key FROM tool_cache ORDER BY expires DESC LIMIT -1 OFFSET 1024)")
        return {"text": text}

    def search(self, query):
        if self.brave_key:
            params = urlencode({"q": query, "count": 5})
            results = read_json("https://api.search.brave.com/res/v1/web/search?" + params,
                                {"X-Subscription-Token": self.brave_key, "Accept": "application/json"})
            matches = [f"{item['title']} — {item['url']}" for item in results.get("web", {}).get("results", [])[:5]]
        else:
            page, _ = fetch("https://lite.duckduckgo.com/lite/?" + urlencode({"q": query}))
            parser = SearchResults()
            parser.feed(page.decode("utf-8", "replace"))
            matches = parser.results
        return "\n".join(matches) or "No search results returned."

    def register(self, app, account_for):
        @app.post("/tools/search")
        def search(payload: Query, account: str = Depends(account_for)):
            return self.cached("search", payload, account, 900, lambda: self.search(payload.query))

        @app.post("/tools/read_url")
        def read_url(payload: Target, account: str = Depends(account_for)):
            def extract():
                from trafilatura import extract as extract_html

                content, headers = fetch(payload.url)
                content_type = headers.get_content_type()
                if content_type == "text/plain":
                    return content.decode(headers.get_content_charset() or "utf-8", "replace").strip()
                if content_type not in {"text/html", "application/xhtml+xml"}:
                    raise InvalidAddress("page must contain HTML or plain text")
                return extract_html(content, include_comments=False, include_tables=False, favor_precision=True) or "No readable text found."

            return self.cached("read_url", payload, account, 1800, extract)

        @app.post("/tools/weather")
        def weather(payload: City, account: str = Depends(account_for)):
            def conditions():
                geocoding = read_json("https://geocoding-api.open-meteo.com/v1/search?" + urlencode({"name": payload.city, "count": 1}))
                if not geocoding.get("results"):
                    return "City not found."
                city = geocoding["results"][0]
                query = urlencode({"latitude": city["latitude"], "longitude": city["longitude"], "timezone": "auto",
                                   "current": "temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"})
                current = read_json("https://api.open-meteo.com/v1/forecast?" + query)["current"]
                return (f"{city['name']}, {city.get('country', '')} at {current['time']} local time. "
                        f"{current['temperature_2m']} °C, feels like {current['apparent_temperature']} °C. "
                        f"Wind {current['wind_speed_10m']} km/h, precipitation {current['precipitation']} mm, "
                        f"WMO weather code {current['weather_code']}. Source: Open-Meteo.")

            return self.cached("weather", payload, account, 600, conditions)

        @app.post("/tools/currency")
        def currency(payload: Currency, account: str = Depends(account_for)):
            def rates():
                base, target = payload.base.upper(), payload.target.upper()
                if base == target:
                    return f"1 {base} = 1 {target}"
                data = read_json("https://api.frankfurter.dev/v1/latest?" + urlencode({"base": base, "symbols": target}))
                return f"1 {base} = {data['rates'][target]} {target} on {data['date']}. Source: Frankfurter."

            return self.cached("currency", payload, account, 1800, rates)

        @app.post("/tools/wiki")
        def wiki(payload: Wiki, account: str = Depends(account_for)):
            def summary():
                params = urlencode({"action": "query", "titles": payload.title, "prop": "extracts|info", "inprop": "url",
                                    "exintro": 1, "explaintext": 1, "exchars": 2400, "redirects": 1, "format": "json", "formatversion": 2})
                result = read_json(f"https://{payload.language}.wikipedia.org/w/api.php?" + params)["query"]["pages"][0]
                if result.get("missing"):
                    return "Wikipedia article not found."
                return f"{result['title']}\n{result.get('extract', '')}\n{result.get('fullurl', '')}"

            return self.cached("wiki", payload, account, 21600, summary)

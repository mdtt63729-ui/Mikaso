"""Instant-link API (merged from the "Social Media Downloader API" project).

The main Mikaso API (:mod:`mikaso.routes`) hands a link to yt-dlp and *downloads*
the media through a job queue. This module is the opposite trade-off: it asks a
few public extractor services for the **direct media URL** and returns it right
away, so a client can hand the link to its own player/downloader.

Endpoints (all accept ``?video=<url>`` or ``?url=<url>``, GET or POST):

    /facebook   /instagram  (/Instagram)   /snap   /pinterest  (/Pinterest)   /youtube

Compared with the original snippet this merge:
  * adds the platforms Mikaso was missing (Snapchat, Pinterest),
  * rebrands every response to Mikaso,
  * drops the abandoned ``user-agent`` dependency for a tiny local helper,
  * adds permissive CORS headers so a browser page can call it directly,
  * validates the incoming URL before it reaches any third-party service.
"""
from __future__ import annotations

import logging
import random
import re
from urllib.parse import urlsplit

import requests
import yt_dlp
from bs4 import BeautifulSoup
from flask import Blueprint, jsonify, request

from .config import APP_NAME, APP_VERSION

log = logging.getLogger("mikaso.social")

social = Blueprint("social", __name__)

_TIMEOUT = 20

# Hosts each endpoint will accept. Anything else is rejected before we touch a
# third-party service, which keeps this from becoming an open proxy.
SOCIAL_PLATFORMS = {
    "facebook": ("facebook.com", "fb.watch", "fb.com", "m.facebook.com"),
    "instagram": ("instagram.com", "instagr.am"),
    "snapchat": ("snapchat.com",),
    "pinterest": ("pinterest.com", "pin.it", "pinterest.co.uk", "pinterest.de", "pinterest.fr"),
    "youtube": ("youtube.com", "youtu.be", "youtube-nocookie.com", "m.youtube.com"),
}

_UAS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36",
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
]


def _ua() -> str:
    return random.choice(_UAS)


def _meta() -> dict:
    return {"app": APP_NAME, "version": APP_VERSION}


def _ok(**fields) -> dict:
    return {"status": "success", **_meta(), **fields}


def _err(message: str) -> dict:
    return {"status": "error", "message": message, **_meta()}


def _clean_url(raw: str) -> str:
    return (raw or "").strip()


def _host_ok(url: str, platform: str) -> bool:
    try:
        host = (urlsplit(url if "://" in url else "https://" + url).hostname or "").lower().rstrip(".")
    except ValueError:
        return False
    return any(host == d or host.endswith("." + d) for d in SOCIAL_PLATFORMS[platform])


def _require(platform: str):
    """Pull the URL out of the request and validate its host. Returns (url, error_response)."""
    raw = request.values.get("video") or request.values.get("url") or ""
    url = _clean_url(raw)
    if not url:
        return None, jsonify(_err("Pass the link as ?video=URL (or ?url=URL)."))
    if not _host_ok(url, platform):
        label = platform.capitalize()
        return None, jsonify(_err(f"That is not a {label} link."))
    return url, None


# --------------------------------------------------------------------------- #
# Facebook
# --------------------------------------------------------------------------- #
def _facebook(url: str) -> dict:
    headers = {"User-Agent": _ua(), "Origin": "https://fbdown.blog", "Referer": "https://fbdown.blog/"}
    try:
        r = requests.post("https://fbdown.blog/get.php", headers=headers, data={"url": url}, timeout=_TIMEOUT)
        r.raise_for_status()
        data = r.json()["data"]
        duration_sec = data.get("duration", 0) or 0
        mins, secs = divmod(int(duration_sec), 60)
        hd = sd = "Not available"
        for m in data.get("medias", []):
            if m.get("quality") == "HD":
                hd = m["url"]
            elif m.get("quality") == "SD" and sd == "Not available":
                sd = m["url"]
        return _ok(video={
            "title": data.get("title", "Unknown Title"),
            "author": data.get("author", "Unknown Author"),
            "duration": f"{mins:02d}:{secs:02d}",
            "thumbnail": data.get("thumbnail", ""),
            "hd": hd,
            "sd": sd,
        })
    except Exception as exc:                       # noqa: BLE001 - third-party service, anything can go wrong
        log.warning("facebook extract failed: %s", exc)
        return _err("Facebook video not found.")


# --------------------------------------------------------------------------- #
# Instagram
# --------------------------------------------------------------------------- #
def _instagram(url: str) -> dict:
    try:
        session = requests.Session()
        ua = _ua()
        page = "https://snapdownloader.com/tools/instagram-reels-downloader/download"
        r_get = session.get(page, headers={
            "User-Agent": ua,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        }, timeout=_TIMEOUT)
        r_get.raise_for_status()

        soup = BeautifulSoup(r_get.text, "html.parser")
        csrf_meta = soup.find("meta", attrs={"name": "csrf-token"})
        if not csrf_meta:
            return _err("Instagram extractor is unavailable right now.")
        api_meta = soup.find("meta", attrs={"name": "api-fetch-url"})
        api_url = api_meta.get("content") if api_meta else "https://grabgram.io/api/fetch/instagram"

        m = re.search(r"instagram\.com/([^/?#]+)", url)
        reserved = {"p", "reel", "reels", "stories", "tv", "developer", "about", "explore"}
        tool = "reels" if (not m or m.group(1) in reserved) else "profile"

        r_post = session.post(api_url, headers={
            "User-Agent": ua,
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-CSRF-TOKEN": csrf_meta.get("content"),
            "X-Requested-With": "XMLHttpRequest",
            "Origin": "https://grabgram.io",
            "Referer": "https://grabgram.io/en/instagram-reels-downloader",
        }, json={"url": url.strip(), "tool": tool}, timeout=_TIMEOUT)

        if r_post.status_code != 200:
            try:
                return _err(r_post.json().get("error", "Instagram request failed."))
            except ValueError:
                return _err(f"Instagram request failed (HTTP {r_post.status_code}).")

        res = r_post.json()
        if not res.get("ok"):
            return _err(res.get("error", "Could not fetch media from Instagram."))

        data = res.get("data", {})
        items = data.get("items", [])
        if not items:
            return _err("No media found for that Instagram link.")

        caption = data.get("caption")
        user = data.get("user", {})
        username = user.get("username", "Unknown")
        full_name = user.get("full_name", "Unknown")

        results = []
        for idx, item in enumerate(items):
            downloads = item.get("downloads", [])
            if not downloads:
                continue
            best = downloads[0]
            results.append({
                **_meta(),
                "Download Link": best.get("url"),
                "Quality": best.get("label") or best.get("ext") or "Original",
                "Title": caption or f"Instagram {item.get('kind', 'media')} #{idx + 1} by {username} ({full_name})",
            })
        if not results:
            return _err("No download links found for that Instagram link.")
        return results[0] if len(results) == 1 else results
    except Exception as exc:                       # noqa: BLE001
        log.warning("instagram extract failed: %s", exc)
        return _err("Instagram request failed.")


# --------------------------------------------------------------------------- #
# Snapchat
# --------------------------------------------------------------------------- #
def _snapchat(url: str) -> dict:
    headers = {
        "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "content-type": "application/x-www-form-urlencoded",
        "origin": "https://www.expertstool.com",
        "referer": "https://www.expertstool.com/snapchat-video-downloader/",
        "user-agent": _ua(),
    }
    try:
        response = requests.post("https://www.expertstool.com/converter.php",
                                 headers=headers, data={"url": url}, timeout=_TIMEOUT)
        html = response.text

        def clean(link):
            if not link:
                return None
            link = link.replace("\\u0026", "&").replace("&quot;", "").strip()
            return link.split(",mediaPreviewUrl:")[0].rstrip("&")

        videos = [clean(v) for v in re.findall(r'<source[^>]+src="(https?://[^"]+)"', html)]
        videos = [v for v in videos if v]
        posters = re.findall(r'(?:poster|src)="(https?://[^"]+)"', html)
        images = list({clean(i) for i in posters if clean(i)})

        if videos or images:
            return _ok(video=videos[0] if videos else None,
                       image=images[0] if images else None)
        return _err("Invalid or private Snapchat link.")
    except Exception as exc:                       # noqa: BLE001
        log.warning("snapchat extract failed: %s", exc)
        return _err("Failed to fetch Snapchat data.")


# --------------------------------------------------------------------------- #
# Pinterest
# --------------------------------------------------------------------------- #
def _pinterest(url: str) -> dict:
    headers = {
        "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "origin": "https://www.expertstool.com",
        "referer": "https://www.expertstool.com/pinterest-video-download/",
        "user-agent": _ua(),
    }
    try:
        response = requests.post("https://www.expertstool.com/pinterest-video-download/",
                                 headers=headers, data={"url": url}, timeout=_TIMEOUT)
        html = response.text
        if "API Not Work" in html or "Invalid" in html:
            return _err("Invalid Pinterest link.")

        soup = BeautifulSoup(html, "html.parser")
        for btn in soup.find_all("a", class_=re.compile(r"btn.*primary")):
            href = btn.get("href", "")
            if href.startswith("https://v") and ".mp4" in href:
                return _ok(video=href)
        for img in soup.find_all("a", href=re.compile(r"pinimg\.com.*originals")):
            return _ok(photo=img["href"])
        return _err("No media found for that Pinterest link.")
    except Exception as exc:                       # noqa: BLE001
        log.warning("pinterest extract failed: %s", exc)
        return _err("Pinterest request failed.")


# --------------------------------------------------------------------------- #
# YouTube (direct-link list; the job-based /api/analyze stays the richer option)
# --------------------------------------------------------------------------- #
def _youtube(url: str) -> dict:
    try:
        opts = {"quiet": True, "no_warnings": True, "skip_download": True,
                "noplaylist": True, "format": "best[ext=mp4]/best"}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)

        formats, seen = [], set()
        for f in info.get("formats", []):
            direct = f.get("url")
            if not direct or direct in seen or f.get("vcodec") == "none":
                continue
            seen.add(direct)
            formats.append({
                "quality": f.get("format_note") or f.get("resolution") or "Original",
                "ext": f.get("ext") or "mp4",
                "url": direct,
            })
        return _ok(title=info.get("title", "Unknown Title"),
                   author=info.get("uploader", "Unknown"),
                   duration=info.get("duration"),
                   thumbnail=info.get("thumbnail"),
                   video=formats)
    except Exception as exc:                       # noqa: BLE001
        log.warning("youtube extract failed: %s", exc)
        return _err("YouTube request failed.")


# --------------------------------------------------------------------------- #
# Routes
# --------------------------------------------------------------------------- #
@social.after_request
def _cors(resp):
    resp.headers["Access-Control-Allow-Origin"] = "*"
    resp.headers["Access-Control-Allow-Headers"] = "*"
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    return resp


@social.route("/facebook", methods=["GET", "POST"])
def facebook():
    url, err = _require("facebook")
    return err or jsonify(_facebook(url))


@social.route("/instagram", methods=["GET", "POST"])
@social.route("/Instagram", methods=["GET", "POST"])
def instagram():
    url, err = _require("instagram")
    return err or jsonify(_instagram(url))


@social.route("/snap", methods=["GET", "POST"])
@social.route("/snapchat", methods=["GET", "POST"])
def snapchat():
    url, err = _require("snapchat")
    return err or jsonify(_snapchat(url))


@social.route("/pinterest", methods=["GET", "POST"])
@social.route("/Pinterest", methods=["GET", "POST"])
def pinterest():
    url, err = _require("pinterest")
    return err or jsonify(_pinterest(url))


@social.route("/youtube", methods=["GET", "POST"])
def youtube():
    url, err = _require("youtube")
    return err or jsonify(_youtube(url))

"""Small, bounded client for Afterglow's read-only LAN API."""
from __future__ import annotations

import json
import re
from base64 import b64encode
from contextlib import asynccontextmanager
from uuid import UUID

import aiohttp
from yarl import URL

JSON_LIMIT = 2 * 1024 * 1024
SNAPSHOT_LIMIT = 8 * 1024 * 1024
CLIP_ID = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")
DAY = re.compile(r"\d{4}-\d{2}-\d{2}\Z")


class ApiError(Exception):
    """The phone is unavailable or returned an invalid response."""


class AuthError(ApiError):
    """The phone rejected its configured credentials."""


class IdentityError(ApiError):
    """An address now belongs to a different device."""


def device_id(value: str) -> str:
    """Require a canonical persistent UUID, never an address or display name."""
    if not isinstance(value, str):
        raise ValueError("Invalid device identity")
    parsed = str(UUID(value))
    if parsed != value.lower():
        raise ValueError("Invalid device identity")
    return parsed


def clip_id(value: str) -> str:
    """Allow only opaque identifiers, not paths or query strings."""
    if not isinstance(value, str) or not CLIP_ID.fullmatch(value):
        raise ValueError("Invalid recording identifier")
    return value


async def read_bounded(response: aiohttp.ClientResponse, limit: int) -> bytes:
    """Reject unexpectedly large API/image bodies before buffering them."""
    data = bytearray()
    async for chunk in response.content.iter_chunked(65536):
        data.extend(chunk)
        if len(data) > limit:
            raise ApiError("Response exceeds the size limit")
    return bytes(data)


class AfterglowApi:
    """HTTP transport; session lifetime belongs to Home Assistant."""

    def __init__(self, session, host: str, port: int = 8080,
                 username: str = "", password: str = "") -> None:
        host = host.strip()
        if not host or any(c in host for c in "/@?#\\ \t\r\n"):
            raise ValueError("Enter a hostname or IP address, without a URL")
        if host.startswith("[") and host.endswith("]"):
            host = host[1:-1]
        if not 1 <= int(port) <= 65535:
            raise ValueError("Invalid port")
        self.session = session
        self.host = host
        self.port = int(port)
        self.base_url = URL.build(scheme="http", host=host, port=self.port)
        self._expected_id: str | None = None
        if ":" in username:
            raise ValueError("Authentication username cannot contain a colon")
        self.auth_headers = ({"Authorization": "Basic " + b64encode(
            f"{username}:{password}".encode("utf-8")).decode("ascii")} if username else {})

    def url(self, path: str) -> URL:
        return self.base_url.with_path(path)

    @asynccontextmanager
    async def protected_request(self, method: str, path: str, *, params=None,
                                headers=None, timeout=None):
        """Verify the bound identity immediately before every new credentialed request."""
        await self.info()
        incoming = headers or {}
        outgoing = {key: incoming[key] for key in ("Range", "If-Range") if key in incoming}
        outgoing["Accept-Encoding"] = "identity"
        outgoing.update(self.auth_headers)
        async with self.session.request(method, self.url(path), params=params,
                headers=outgoing, allow_redirects=False, auto_decompress=False,
                timeout=timeout or aiohttp.ClientTimeout(total=10)) as response:
            yield response

    async def _json(self, path: str, *, params=None, public=False) -> dict:
        try:
            request = (self.session.get(self.url(path), params=params,
                    headers={}, allow_redirects=False, timeout=aiohttp.ClientTimeout(total=10))
                if public else self.protected_request("GET", path, params=params))
            async with request as response:
                if response.status in (401, 403):
                    raise AuthError("Authentication rejected")
                if response.status != 200:
                    raise ApiError(f"Phone returned HTTP {response.status}")
                body = await read_bounded(response, JSON_LIMIT)
                result = json.loads(body)
                if not isinstance(result, dict):
                    raise ApiError("Expected a JSON object")
                return result
        except (aiohttp.ClientError, TimeoutError, ValueError) as err:
            raise ApiError("Cannot read the phone API") from err

    async def info(self, expected_id: str | None = None) -> dict:
        """Probe public identity before sending any saved credentials."""
        if expected_id is not None:
            expected_id = device_id(expected_id)
            if self._expected_id is not None and expected_id != self._expected_id:
                raise IdentityError("Cannot replace an already verified device identity")
        info = await self._json("/api/info", public=True)
        try:
            identity = device_id(info["device_id"])
            if info["api_version"] != 1:
                raise ValueError("Unsupported API version")
            if not isinstance(info["name"], str) or not info["name"]:
                raise ValueError("Missing name")
            for field in ("rtsp_enabled", "mjpeg_enabled", "recordings_enabled"):
                if not isinstance(info[field], bool):
                    raise ValueError("Invalid capabilities")
            for field in ("http_port", "rtsp_port"):
                if type(info[field]) is not int or not 1 <= info[field] <= 65535:
                    raise ValueError("Invalid port")
        except (KeyError, ValueError, TypeError) as err:
            raise ApiError("Not a supported Afterglow device") from err
        if ((expected_id is not None and identity != expected_id)
                or (self._expected_id is not None and identity != self._expected_id)):
            raise IdentityError("Device identity differs from the saved identity")
        self._expected_id = identity
        info["device_id"] = identity
        return info

    async def recording_days(self) -> dict:
        payload = await self._json("/api/recordings/days")
        try:
            if not isinstance(payload.get("days"), list):
                raise ValueError("Invalid day list")
            for item in payload["days"]:
                if (not isinstance(item, dict) or not isinstance(item.get("day"), str)
                        or DAY.fullmatch(item["day"]) is None
                        or type(item.get("count")) is not int or item["count"] < 0):
                    raise ValueError("Invalid day")
        except (KeyError, TypeError, ValueError) as err:
            raise ApiError("Invalid recordings response") from err
        return payload

    async def recordings(self, offset: int = 0, limit: int = 100, day: str | None = None) -> dict:
        if type(offset) is not int or offset < 0 or type(limit) is not int or not 1 <= limit <= 200:
            raise ValueError("Invalid page")
        params = {"offset": offset, "limit": limit}
        if day is not None:
            if not isinstance(day, str) or DAY.fullmatch(day) is None:
                raise ValueError("Invalid day")
            params["day"] = day
        page = await self._json("/api/recordings", params=params)
        try:
            if (type(page["total"]) is not int or page["total"] < 0
                    or page["offset"] != offset or page["limit"] != limit
                    or not isinstance(page["recordings"], list)
                    or len(page["recordings"]) > limit):
                raise ValueError("Invalid page")
            for clip in page["recordings"]:
                clip_id(clip["id"])
                for field in ("start_time_ms", "duration_ms", "bytes"):
                    if type(clip[field]) is not int or clip[field] < 0:
                        raise ValueError("Invalid clip metadata")
        except (KeyError, TypeError, ValueError) as err:
            raise ApiError("Invalid recordings response") from err
        return page

    async def status(self) -> dict:
        """Validate access without depending on recording storage availability."""
        return await self._json("/status")

    async def snapshot(self) -> bytes:
        try:
            async with self.protected_request("GET", "/snapshot.jpg") as response:
                if response.status != 200:
                    raise ApiError("Snapshot unavailable")
                return await read_bounded(response, SNAPSHOT_LIMIT)
        except (aiohttp.ClientError, TimeoutError) as err:
            raise ApiError("Snapshot unavailable") from err

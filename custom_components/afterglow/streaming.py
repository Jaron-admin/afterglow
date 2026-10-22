"""Bounded HTTP streaming, shared by recordings and MJPEG."""
from __future__ import annotations

import aiohttp
from aiohttp import web

from .api import AfterglowApi, ApiError

RESPONSE_HEADERS = ("Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")


async def proxy_stream(request: web.Request, api: AfterglowApi,
                       path: str, content_type: str,
                       cache_control: str = "private, no-store") -> web.StreamResponse:
    """Relay bytes without buffering an entire clip or forwarding HA credentials."""
    headers = {key: request.headers[key] for key in ("Range", "If-Range") if key in request.headers}
    response = None
    try:
        async with api.protected_request(request.method, path, headers=headers,
                timeout=aiohttp.ClientTimeout(total=None, connect=10, sock_read=30)) as upstream:
            if upstream.status not in (200, 206, 404, 416):
                raise web.HTTPBadGateway(text="Phone playback is unavailable")
            if upstream.headers.get("Content-Encoding", "identity") != "identity":
                raise web.HTTPBadGateway(text="Unexpected compressed media")
            outgoing = {key: upstream.headers[key] for key in RESPONSE_HEADERS if key in upstream.headers}
            outgoing["Cache-Control"] = cache_control
            if upstream.status in (200, 206):
                actual = upstream.headers.get("Content-Type", "").lower()
                if not actual.startswith(content_type):
                    raise web.HTTPBadGateway(text="Unexpected media type")
                outgoing["Content-Type"] = upstream.headers["Content-Type"]
            response = web.StreamResponse(status=upstream.status, headers=outgoing)
            await response.prepare(request)
            if request.method != "HEAD":
                async for chunk in upstream.content.iter_chunked(65536):
                    await response.write(chunk)
            await response.write_eof()
            return response
    except (ApiError, aiohttp.ClientError, TimeoutError, ConnectionError) as err:
        if response is not None and response.prepared:
            # Abort an incomplete transfer; never report truncated bytes as a full file.
            if request.transport is not None:
                request.transport.close()
            return response
        if isinstance(err, TimeoutError):
            raise web.HTTPGatewayTimeout(text="Phone playback timed out") from err
        raise web.HTTPBadGateway(text="Phone playback is unavailable") from err

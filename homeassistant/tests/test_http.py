"""Real loopback HTTP tests; no Home Assistant package is required."""
import importlib
import pathlib
import sys
import types
import unittest

ROOT = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / ".deps"))
from aiohttp import ClientSession, web
from aiohttp.test_utils import TestServer

PACKAGE = "afterglow_test"
package = types.ModuleType(PACKAGE)
package.__path__ = [str(ROOT.parents[1] / "custom_components" / "afterglow")]
sys.modules[PACKAGE] = package

DEVICE = "4ac472bb-66fb-4fa4-9262-19c77b49d19c"
CLIP = "c2a8351a-0d1d-49b5-a1b6-3cecf3f59a7b"
INFO = {"api_version": 1, "device_id": DEVICE, "name": "Afterglow-4ac472bb",
        "http_port": 8080, "rtsp_port": 8554, "rtsp_enabled": True,
        "mjpeg_enabled": True, "recordings_enabled": True}


def module(name):
    path = pathlib.Path(package.__path__[0]) / (name + ".py")
    if not path.exists():
        raise AssertionError(f"Missing implementation: {name}.py")
    return importlib.import_module(f"{PACKAGE}.{name}")


class HttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.api_module = module("api")
        self.requests = []
        self.info = dict(INFO)
        self.payload = b"0123456789abcdef"
        self.redirect = False
        self.large = False
        self.recordings_unavailable = False
        self.days_unavailable = False

        async def handler(request):
            self.requests.append((request.path, dict(request.headers), dict(request.query)))
            if self.redirect:
                raise web.HTTPFound("/leak")
            if request.path == "/api/info":
                return web.json_response(self.info)
            if request.headers.get("Authorization") != "Basic dmlld2VyOnNlY3JldA==":
                raise web.HTTPUnauthorized()
            if request.path == "/status":
                return web.json_response({"has_frame": True, "mjpeg_enabled": True, "rtsp_enabled": True})
            if request.path == "/snapshot.jpg":
                return web.Response(body=b"\xff\xd8\xff\xd9", content_type="image/jpeg")
            if request.path == "/api/recordings/days":
                if self.days_unavailable:
                    raise web.HTTPNotFound()
                return web.json_response({"days": [{"day": "1970-01-01", "count": 201}]})
            if request.path == "/api/recordings":
                if self.recordings_unavailable:
                    raise web.HTTPServiceUnavailable()
                if self.large:
                    return web.Response(body=b" " * (3 * 1024 * 1024), content_type="application/json")
                return web.json_response({"recordings": [{"id": CLIP, "start_time_ms": 1000,
                    "duration_ms": 5000, "bytes": 16, "locked": False, "with_audio": True,
                    "reasons": "motion", "triggers": ["motion"], "thumbnail": True,
                    "day": "1970-01-01", "local_time": "13:00:01"}],
                    "offset": int(request.query["offset"]),
                    "limit": int(request.query["limit"]), "total": 201})
            if request.path == f"/api/recordings/{CLIP}/thumbnail":
                return web.Response(body=b"\xff\xd8\xff\xd9", content_type="image/jpeg")
            if request.path == f"/api/recordings/{CLIP}":
                headers = {"Accept-Ranges": "bytes", "ETag": '"clip"'}
                if request.headers.get("Range") == "bytes=4-7":
                    headers["Content-Range"] = "bytes 4-7/16"
                    return web.Response(body=b"4567", status=206, headers=headers, content_type="video/mp4")
                if request.headers.get("Range") == "bytes=99-":
                    return web.Response(status=416, headers={"Content-Range": "bytes */16"})
                return web.Response(body=self.payload, headers=headers, content_type="video/mp4")
            raise web.HTTPNotFound()

        app = web.Application()
        app.router.add_route("*", "/{path:.*}", handler)
        self.server = TestServer(app)
        await self.server.start_server()
        self.session = ClientSession()
        self.api = self.api_module.AfterglowApi(self.session, "127.0.0.1", self.server.port, "viewer", "secret")
        self.addAsyncCleanup(self.session.close)
        self.addAsyncCleanup(self.server.close)

    async def test_identity_probe_never_sends_credentials(self):
        info = await self.api.info(expected_id=DEVICE)
        self.assertEqual(info["device_id"], DEVICE)
        self.assertNotIn("Authorization", self.requests[0][1])

    async def test_mismatched_identity_stops_before_protected_request(self):
        with self.assertRaises(self.api_module.IdentityError):
            await self.api.info(expected_id="cc94de68-8e3d-462d-b033-9d716595c4d7")
        self.assertEqual(len(self.requests), 1)
        self.assertNotIn("Authorization", self.requests[0][1])

    async def test_page_authentication_and_limit(self):
        page = await self.api.recordings(100, 100)
        self.assertEqual(page["total"], 201)
        self.assertEqual([request[0] for request in self.requests], ["/api/info", "/api/recordings"])
        self.assertNotIn("Authorization", self.requests[0][1])
        self.assertEqual(self.requests[1][2], {"offset": "100", "limit": "100"})
        self.assertEqual(self.requests[1][1]["Authorization"], "Basic dmlld2VyOnNlY3JldA==")
        with self.assertRaises(ValueError):
            await self.api.recordings(-1, 100)
        with self.assertRaises(ValueError):
            await self.api.recordings(0, 201)

    async def test_redirect_and_oversize_are_rejected(self):
        self.redirect = True
        with self.assertRaises(self.api_module.ApiError):
            await self.api.info()
        self.assertEqual(len(self.requests), 1)
        self.redirect = False
        self.large = True
        with self.assertRaises(self.api_module.ApiError):
            await self.api.recordings()

    async def test_invalid_api_identity_and_host_are_rejected(self):
        self.info["device_id"] = "../../secret"
        with self.assertRaises(self.api_module.ApiError):
            await self.api.info()
        for host in ["http://127.0.0.1", "user@host", "host/path", "host?x", ""]:
            with self.assertRaises(ValueError, msg=host):
                self.api_module.AfterglowApi(self.session, host, 8080)

    async def make_proxy(self, path=None, content_type="video/mp4", cache_control=None):
        streaming = module("streaming")
        async def proxy(request):
            kwargs = {} if cache_control is None else {"cache_control": cache_control}
            return await streaming.proxy_stream(request, self.api, path or f"/api/recordings/{CLIP}", content_type, **kwargs)
        app = web.Application()
        app.router.add_route("*", "/clip", proxy)
        proxy_server = TestServer(app)
        await proxy_server.start_server()
        self.addAsyncCleanup(proxy_server.close)
        return proxy_server

    async def test_proxy_seek_preserves_bytes_but_never_forwards_ha_credentials(self):
        proxy = await self.make_proxy()
        async with self.session.get(proxy.make_url("/clip"), headers={"Range": "bytes=4-7",
                "Authorization": "Bearer ha-secret", "Cookie": "ha-cookie=secret", "If-Range": '"clip"'}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(await response.read(), b"4567")
            self.assertEqual(response.headers["Content-Range"], "bytes 4-7/16")
            self.assertEqual(response.headers["Content-Length"], "4")
        sent = self.requests[-1][1]
        self.assertEqual(sent["Authorization"], "Basic dmlld2VyOnNlY3JldA==")
        self.assertNotIn("Cookie", sent)
        self.assertEqual(sent["If-Range"], '"clip"')

    async def test_proxy_head_and_unsatisfiable_range(self):
        proxy = await self.make_proxy()
        async with self.session.head(proxy.make_url("/clip")) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(await response.read(), b"")
            self.assertEqual(response.headers["Content-Length"], "16")
        async with self.session.get(proxy.make_url("/clip"), headers={"Range": "bytes=99-"}) as response:
            self.assertEqual(response.status, 416)
            self.assertEqual(response.headers["Content-Range"], "bytes */16")

    async def test_proxy_redirect_does_not_follow_or_expose_location(self):
        proxy = await self.make_proxy()
        self.redirect = True
        async with self.session.get(proxy.make_url("/clip")) as response:
            self.assertEqual(response.status, 502)
            self.assertNotIn("Location", response.headers)
        self.assertEqual(len(self.requests), 1)

    async def test_reassigned_host_blocks_all_new_protected_requests_before_poll(self):
        await self.api.info(expected_id=DEVICE)
        self.info["device_id"] = "cc94de68-8e3d-462d-b033-9d716595c4d7"
        self.requests.clear()
        for operation in (self.api.status, self.api.recordings, self.api.snapshot):
            with self.subTest(operation=operation.__name__):
                with self.assertRaises(self.api_module.IdentityError):
                    await operation()
        for path, content_type in ((f"/api/recordings/{CLIP}", "video/mp4"),
                                   ("/video", "multipart/x-mixed-replace")):
            proxy = await self.make_proxy(path, content_type)
            async with self.session.get(proxy.make_url("/clip")) as response:
                self.assertEqual(response.status, 502)
        self.assertEqual([request[0] for request in self.requests], ["/api/info"] * 5)
        self.assertTrue(all("Authorization" not in request[1] for request in self.requests))

    async def test_verified_identity_cannot_be_rebound_by_an_api_caller(self):
        await self.api.info(expected_id=DEVICE)
        other = "cc94de68-8e3d-462d-b033-9d716595c4d7"
        self.info["device_id"] = other
        with self.assertRaises(self.api_module.IdentityError):
            await self.api.info(expected_id=other)
        with self.assertRaises(self.api_module.IdentityError):
            await self.api.info()
        self.info["device_id"] = DEVICE
        self.assertEqual((await self.api.info())["device_id"], DEVICE)

    async def test_failed_identity_probe_never_falls_back_to_saved_auth(self):
        await self.api.info(expected_id=DEVICE)
        self.requests.clear()
        self.redirect = True
        with self.assertRaises(self.api_module.ApiError):
            await self.api.status()
        self.assertEqual([request[0] for request in self.requests], ["/api/info"])
        self.assertNotIn("Authorization", self.requests[0][1])


if __name__ == "__main__":
    unittest.main()

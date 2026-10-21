"""Exercise integration logic with only HA imports replaced by boundary doubles."""
import types
import unittest
import test_http
from ha_stubs import Entries, install

AbortFlow = install()


class BoundaryTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = test_http.HttpTests.asyncSetUp

    def setup_hass(self):
        coordinator = types.SimpleNamespace(api=self.api, data=dict(test_http.INFO), last_update_success=True)
        self.entry = types.SimpleNamespace(entry_id="entry1", unique_id=test_http.DEVICE,
            domain="afterglow", title="Door camera", runtime_data=coordinator,
            data={"host": "192.168.1.5", "port": 8080, "username": "viewer", "password": "secret"})
        self.hass = types.SimpleNamespace(session=self.session, data={},
            config_entries=Entries([self.entry]))

    async def test_discovery_verifies_identity_before_updating_host(self):
        self.setup_hass()
        flow = test_http.module("config_flow").AfterglowConfigFlow()
        flow.hass = self.hass
        discovery = types.SimpleNamespace(host="127.0.0.1", port=self.server.port,
            properties={}, name=f"Afterglow-{test_http.DEVICE} (2)._afterglow._tcp.local.")
        with self.assertRaises(AbortFlow):
            await flow.async_step_zeroconf(discovery)
        self.assertEqual(self.entry.data["host"], "127.0.0.1")
        self.assertEqual(self.hass.config_entries.reloaded, ["entry1"])
        self.assertNotIn("Authorization", self.requests[0][1])

    async def test_discovery_mismatch_cannot_redirect_saved_credentials(self):
        self.setup_hass()
        self.info["device_id"] = "cc94de68-8e3d-462d-b033-9d716595c4d7"
        flow = test_http.module("config_flow").AfterglowConfigFlow()
        flow.hass = self.hass
        result = await flow.async_step_zeroconf(types.SimpleNamespace(host="127.0.0.1",
            port=self.server.port, properties={"id": test_http.DEVICE}, name="unused"))
        self.assertEqual(result["type"], "abort")
        self.assertEqual(self.entry.data["host"], "192.168.1.5")
        self.assertEqual(len(self.requests), 1)
        self.assertNotIn("Authorization", self.requests[0][1])

    async def test_user_setup_checks_protected_api_and_rejects_bad_password(self):
        self.setup_hass()
        self.hass.config_entries.entries = []
        flow = test_http.module("config_flow").AfterglowConfigFlow()
        flow.hass = self.hass
        data = {"host": "127.0.0.1", "port": self.server.port, "username": "viewer", "password": "wrong"}
        result = await flow.async_step_user(data)
        self.assertEqual(result["errors"]["base"], "invalid_auth")
        data["password"] = "secret"
        result = await flow.async_step_user(data)
        self.assertEqual(result["type"], "create_entry")
        self.assertEqual(flow.unique_id, test_http.DEVICE)

    async def test_media_browse_pages_and_proxy_url_without_credentials(self):
        self.setup_hass()
        source = await test_http.module("media_source").async_get_media_source(self.hass)
        root = await source.async_browse_media(types.SimpleNamespace(identifier=""))
        self.assertEqual(root.children[0].identifier, "entry1/days")
        days = await source.async_browse_media(types.SimpleNamespace(identifier="entry1/days"))
        self.assertEqual(days.children[0].identifier, "entry1/day/1970-01-01")
        self.assertIn("201", days.children[0].title)
        page = await source.async_browse_media(types.SimpleNamespace(identifier="entry1/day/1970-01-01"))
        self.assertEqual(page.children[0].identifier, f"entry1/clip/{test_http.CLIP}")
        self.assertIn("13:00:01", page.children[0].title)
        self.assertIn("5秒", page.children[0].title)
        self.assertIn("画面变动", page.children[0].title)
        self.assertEqual(page.children[0].thumbnail, f"/api/afterglow/recordings/entry1/{test_http.CLIP}/thumbnail")
        self.assertEqual(page.children[-1].identifier, "entry1/day/1970-01-01/1")
        media = await source.async_resolve_media(types.SimpleNamespace(identifier=f"entry1/clip/{test_http.CLIP}"))
        self.assertEqual(media.url, f"/api/afterglow/recordings/entry1/{test_http.CLIP}")
        self.assertEqual(media.mime_type, "video/mp4")
        with self.assertRaises(Exception):
            await source.async_resolve_media(types.SimpleNamespace(identifier="entry1/clip/../../secret"))

    async def test_camera_capabilities_follow_info_without_exposing_credentials(self):
        self.setup_hass()
        camera = test_http.module("camera").AfterglowCamera(self.entry.runtime_data, self.entry)
        self.assertEqual(await camera.stream_source(), f"rtsp://127.0.0.1:8554/live")
        self.assertFalse(camera.use_stream_for_stills)
        self.entry.runtime_data.data["mjpeg_enabled"] = False
        self.assertTrue(camera.use_stream_for_stills)
        self.entry.runtime_data.data["rtsp_enabled"] = False
        self.assertIsNone(await camera.stream_source())
        self.assertFalse(camera.available)

    async def test_old_phone_without_day_index_still_lists_clips(self):
        self.setup_hass()
        self.days_unavailable = True
        source = await test_http.module("media_source").async_get_media_source(self.hass)
        page = await source.async_browse_media(types.SimpleNamespace(identifier="entry1/days"))
        self.assertEqual(page.children[0].identifier, f"entry1/clip/{test_http.CLIP}")
        self.assertEqual(page.children[-1].identifier, "entry1/page/1")

    async def test_thumbnail_proxy_is_authenticated_and_cached(self):
        self.setup_hass()
        proxy = await test_http.HttpTests.make_proxy(self, f"/api/recordings/{test_http.CLIP}/thumbnail",
            "image/jpeg", "private, max-age=86400")
        async with self.session.get(proxy.make_url("/clip"),
                headers={"Authorization": "Bearer ha-secret"}) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(await response.read(), b"\xff\xd8\xff\xd9")
            self.assertEqual(response.headers["Cache-Control"], "private, max-age=86400")
        self.assertEqual(self.requests[-1][1]["Authorization"], "Basic dmlld2VyOnNlY3JldA==")
        self.assertNotIn("Bearer ha-secret", self.requests[-1][1].values())

    async def test_unloaded_entry_or_invalid_clip_cannot_be_resolved(self):
        self.setup_hass()
        source = await test_http.module("media_source").async_get_media_source(self.hass)
        self.entry.runtime_data = None
        root = await source.async_browse_media(types.SimpleNamespace(identifier=""))
        self.assertEqual(root.children, [])
        with self.assertRaises(Exception):
            await source.async_resolve_media(types.SimpleNamespace(identifier=f"entry1/clip/{test_http.CLIP}"))

    async def test_setup_and_unload_manage_live_entry_and_proxy_once(self):
        self.setup_hass()
        integration = test_http.module("__init__")
        self.entry.data.update(host="127.0.0.1", port=self.server.port)
        self.hass.http = types.SimpleNamespace(views=[], register_view=lambda view: self.hass.http.views.append(view))
        self.assertTrue(await integration.async_setup_entry(self.hass, self.entry))
        self.assertEqual(self.entry.runtime_data.data["device_id"], test_http.DEVICE)
        self.assertEqual(len(self.hass.http.views), 2)
        self.assertEqual(self.hass.config_entries.forwarded, ("entry1", ["camera"]))
        self.assertTrue(await integration.async_unload_entry(self.hass, self.entry))
        self.assertIsNone(self.entry.runtime_data)
        self.assertTrue(await integration.async_setup_entry(self.hass, self.entry))
        self.assertEqual(len(self.hass.http.views), 2)

    async def test_setup_identity_mismatch_never_sends_saved_password(self):
        self.setup_hass()
        integration = test_http.module("__init__")
        self.entry.data.update(host="127.0.0.1", port=self.server.port)
        self.info["device_id"] = "cc94de68-8e3d-462d-b033-9d716595c4d7"
        with self.assertRaises(Exception):
            await integration.async_setup_entry(self.hass, self.entry)
        self.assertEqual(len(self.requests), 1)
        self.assertNotIn("Authorization", self.requests[0][1])

    async def test_view_rejects_unloaded_entry_and_path_identifiers(self):
        from aiohttp import web
        self.setup_hass()
        view = test_http.module("views").RecordingProxyView(self.hass)
        with self.assertRaises(web.HTTPNotFound):
            await view.get(None, "missing", test_http.CLIP)
        with self.assertRaises(web.HTTPNotFound):
            await view.get(None, "entry1", "../secret")

    async def test_user_can_paste_phone_http_base_url(self):
        self.setup_hass()
        self.hass.config_entries.entries = []
        flow = test_http.module("config_flow").AfterglowConfigFlow()
        flow.hass = self.hass
        result = await flow.async_step_user({"host": f"http://127.0.0.1:{self.server.port}/",
            "port": 8080, "username": "viewer", "password": "secret"})
        self.assertEqual(result["type"], "create_entry")
        self.assertEqual(result["data"]["host"], "127.0.0.1")
        self.assertEqual(result["data"]["port"], self.server.port)

    async def test_recording_storage_failure_does_not_disable_live_camera_setup(self):
        self.setup_hass()
        self.recordings_unavailable = True
        self.hass.config_entries.entries = []
        flow = test_http.module("config_flow").AfterglowConfigFlow()
        flow.hass = self.hass
        result = await flow.async_step_user({"host": "127.0.0.1", "port": self.server.port,
            "username": "viewer", "password": "secret"})
        self.assertEqual(result["type"], "create_entry")
        self.assertEqual([r[0] for r in self.requests], ["/api/info", "/api/info", "/status"])

    async def test_rtsp_source_is_not_returned_for_reassigned_host(self):
        self.setup_hass()
        await self.api.info(expected_id=test_http.DEVICE)
        camera = test_http.module("camera").AfterglowCamera(self.entry.runtime_data, self.entry)
        self.info["device_id"] = "cc94de68-8e3d-462d-b033-9d716595c4d7"
        self.requests.clear()
        self.assertIsNone(await camera.stream_source())
        self.assertEqual([request[0] for request in self.requests], ["/api/info"])
        self.assertNotIn("Authorization", self.requests[0][1])


if __name__ == "__main__":
    unittest.main()

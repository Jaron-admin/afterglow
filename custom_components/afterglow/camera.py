"""Live camera, with HTTP credentials confined to server-side requests."""
from __future__ import annotations

from yarl import URL
from homeassistant.components.camera import Camera, CameraEntityFeature
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .api import ApiError
from .const import DOMAIN
from .streaming import proxy_stream


async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities([AfterglowCamera(entry.runtime_data, entry)])


class AfterglowCamera(CoordinatorEntity, Camera):
    """Prefer RTSP when available; proxy MJPEG and snapshots over HTTP."""

    _attr_has_entity_name = True
    _attr_name = None

    def __init__(self, coordinator, entry):
        Camera.__init__(self)
        CoordinatorEntity.__init__(self, coordinator)
        self._attr_unique_id = f"{entry.unique_id}_camera"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, entry.unique_id)},
            name=entry.title, manufacturer="Afterglow", model="Android camera")
        self.stream_options["rtsp_transport"] = "tcp"

    @property
    def available(self):
        info = self.coordinator.data or {}
        return super().available and bool(info.get("rtsp_enabled") or info.get("mjpeg_enabled"))

    @property
    def supported_features(self):
        return CameraEntityFeature.STREAM if self.coordinator.data.get("rtsp_enabled") else CameraEntityFeature(0)

    @property
    def use_stream_for_stills(self):
        info = self.coordinator.data
        return info.get("rtsp_enabled", False) and not info.get("mjpeg_enabled", False)

    async def stream_source(self):
        info = self.coordinator.data
        if not info.get("rtsp_enabled"):
            return None
        try:
            info = await self.coordinator.api.info()
        except ApiError:
            return None
        if not info.get("rtsp_enabled"):
            return None
        # MJPEG and the still image have no audio. Sound is on this RTSP URL:
        # AAC for players that remux it, and PCMA for HA's WebRTC player.
        return str(URL.build(scheme="rtsp", host=self.coordinator.api.host,
            port=info["rtsp_port"], path="/live"))

    async def async_camera_image(self, width=None, height=None):
        if not self.coordinator.data.get("mjpeg_enabled"):
            return None
        try:
            return await self.coordinator.api.snapshot()
        except ApiError:
            return None

    async def handle_async_mjpeg_stream(self, request):
        if self.coordinator.data.get("mjpeg_enabled"):
            return await proxy_stream(request, self.coordinator.api, "/video", "multipart/x-mixed-replace")
        return await super().handle_async_mjpeg_stream(request)

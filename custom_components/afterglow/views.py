"""Authenticated HA route serving phone recordings without exposing credentials."""
from aiohttp import web
from homeassistant.components.http import HomeAssistantView

from .api import clip_id
from .const import PROXY_URL, THUMBNAIL_URL
from .media_source import loaded_entry
from .streaming import proxy_stream


class RecordingProxyView(HomeAssistantView):
    url = PROXY_URL
    name = "api:afterglow:recording"
    requires_auth = True

    def __init__(self, hass):
        self.hass = hass

    async def get(self, request, entry_id, recording_id):
        try:
            entry = loaded_entry(self.hass, entry_id)
            clip_id(recording_id)
        except ValueError as err:
            raise web.HTTPNotFound() from err
        return await proxy_stream(request, entry.runtime_data.api,
            f"/api/recordings/{recording_id}", "video/mp4")

    async def head(self, request, entry_id, recording_id):
        return await self.get(request, entry_id, recording_id)


class RecordingThumbnailView(HomeAssistantView):
    url = THUMBNAIL_URL
    name = "api:afterglow:recording_thumbnail"
    requires_auth = True

    def __init__(self, hass):
        self.hass = hass

    async def get(self, request, entry_id, recording_id):
        try:
            entry = loaded_entry(self.hass, entry_id)
            clip_id(recording_id)
        except ValueError as err:
            raise web.HTTPNotFound() from err
        return await proxy_stream(request, entry.runtime_data.api,
            f"/api/recordings/{recording_id}/thumbnail", "image/jpeg",
            cache_control="private, max-age=86400")

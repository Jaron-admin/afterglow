"""Browse completed recordings in Home Assistant's native Media panel."""
from __future__ import annotations

from datetime import datetime, timezone

from homeassistant.components.media_player import BrowseError, MediaClass
from homeassistant.components.media_source import BrowseMediaSource, MediaSource, PlayMedia, Unresolvable
from homeassistant.util import dt as dt_util

from .api import ApiError, clip_id
from .const import DOMAIN, PAGE_SIZE, PROXY_URL


async def async_get_media_source(hass):
    return AfterglowMediaSource(hass)


def loaded_entry(hass, entry_id):
    entry = hass.config_entries.async_get_entry(entry_id)
    if entry is None or entry.domain != DOMAIN or not getattr(entry, "runtime_data", None):
        raise ValueError("Device is not loaded")
    return entry


class AfterglowMediaSource(MediaSource):
    name = "Afterglow"

    def __init__(self, hass):
        super().__init__(DOMAIN)
        self.hass = hass

    def _folder(self, identifier, title, children=None):
        return BrowseMediaSource(domain=DOMAIN, identifier=identifier,
            title=title, media_class=MediaClass.DIRECTORY, media_content_type="",
            can_play=False, can_expand=True, children=children)

    async def async_browse_media(self, item):
        if not item.identifier:
            children = [self._folder(f"{entry.entry_id}/page/0", entry.title)
                for entry in self.hass.config_entries.async_entries(DOMAIN)
                if getattr(entry, "runtime_data", None)]
            return self._folder(None, self.name, children)
        try:
            entry_id, kind, value = item.identifier.split("/")
            if kind != "page" or not value.isdecimal():
                raise ValueError("Invalid folder")
            offset = int(value)
            if offset > 2_147_483_647:
                raise ValueError("Invalid page")
            entry = loaded_entry(self.hass, entry_id)
            page = await entry.runtime_data.api.recordings(offset, PAGE_SIZE)
            children = []
            for clip in page["recordings"]:
                when = dt_util.as_local(datetime.fromtimestamp(clip["start_time_ms"] / 1000, timezone.utc))
                title = f"{when:%Y-%m-%d %H:%M:%S} · {clip['duration_ms'] / 1000:.0f}s"
                if clip.get("locked"):
                    title += " · 🔒"
                children.append(BrowseMediaSource(domain=DOMAIN,
                    identifier=f"{entry_id}/clip/{clip['id']}", title=title,
                    media_class=MediaClass.VIDEO, media_content_type="video/mp4",
                    can_play=True, can_expand=False))
            next_offset = offset + len(page["recordings"])
            if page["recordings"] and next_offset < page["total"]:
                children.append(self._folder(f"{entry_id}/page/{next_offset}", "More recordings →"))
            return self._folder(item.identifier, entry.title, children)
        except (ApiError, ValueError, OverflowError, OSError) as err:
            raise BrowseError("Cannot browse Afterglow recordings") from err

    async def async_resolve_media(self, item):
        try:
            entry_id, kind, value = item.identifier.split("/")
            if kind != "clip":
                raise ValueError("Not a recording")
            loaded_entry(self.hass, entry_id)
            clip_id(value)
        except ValueError as err:
            raise Unresolvable("Recording is unavailable") from err
        # HA's media resolver signs relative paths. Query parameters prevent signing.
        return PlayMedia(PROXY_URL.format(entry_id=entry_id, recording_id=value), "video/mp4")

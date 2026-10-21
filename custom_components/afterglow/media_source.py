"""Browse completed recordings in Home Assistant's native Media panel."""
from __future__ import annotations

import re
from datetime import datetime, timezone

from homeassistant.components.media_player import BrowseError, MediaClass
from homeassistant.components.media_source import BrowseMediaSource, MediaSource, PlayMedia, Unresolvable
from homeassistant.util import dt as dt_util

from .api import ApiError, clip_id
from .const import DOMAIN, PAGE_SIZE, PROXY_URL, THUMBNAIL_URL

DAY = re.compile(r"\d{4}-\d{2}-\d{2}\Z")
CLOCK = re.compile(r"\d{2}:\d{2}:\d{2}\Z")
REASONS = (("motion", "画面变动"), ("sound", "声音触发"), ("schedule", "定时"))


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

    def _clip(self, entry_id, clip):
        when = clip.get("local_time")
        if not isinstance(when, str) or CLOCK.fullmatch(when) is None:
            local = dt_util.as_local(datetime.fromtimestamp(clip["start_time_ms"] / 1000, timezone.utc))
            when = f"{local:%H:%M:%S}"
        title = f"{when} · {clip['duration_ms'] / 1000:.0f}秒"
        reason = clip_reason(clip)
        if reason:
            title += f" · {reason}"
        if clip.get("locked"):
            title += " · 🔒"
        thumbnail = None
        if clip.get("thumbnail") is True:
            thumbnail = THUMBNAIL_URL.format(entry_id=entry_id, recording_id=clip["id"])
        return BrowseMediaSource(domain=DOMAIN, identifier=f"{entry_id}/clip/{clip['id']}",
            title=title, media_class=MediaClass.VIDEO, media_content_type="video/mp4",
            can_play=True, can_expand=False, thumbnail=thumbnail)

    async def _clips(self, entry, identifier, offset, day=None):
        page = await entry.runtime_data.api.recordings(offset, PAGE_SIZE, day)
        children = [self._clip(entry.entry_id, clip) for clip in page["recordings"]]
        next_offset = offset + len(page["recordings"])
        if page["recordings"] and next_offset < page["total"]:
            if day is None:
                more = f"{entry.entry_id}/page/{next_offset}"
            else:
                more = f"{entry.entry_id}/day/{day}/{next_offset}"
            children.append(self._folder(more, "更多"))
        return self._folder(identifier, day or entry.title, children)

    async def async_browse_media(self, item):
        if not item.identifier:
            children = [self._folder(f"{entry.entry_id}/days", entry.title)
                for entry in self.hass.config_entries.async_entries(DOMAIN)
                if getattr(entry, "runtime_data", None)]
            return self._folder(None, self.name, children)
        try:
            parts = item.identifier.split("/")
            if len(parts) not in (2, 3, 4):
                raise ValueError("Invalid folder")
            entry_id, kind = parts[0], parts[1]
            entry = loaded_entry(self.hass, entry_id)
            if kind == "days" and len(parts) == 2:
                try:
                    payload = await entry.runtime_data.api.recording_days()
                except ApiError:
                    return await self._clips(entry, f"{entry_id}/page/0", 0)
                children = [self._folder(f"{entry_id}/day/{day['day']}",
                    f"{day['day']} · {day['count']} 段") for day in payload["days"]]
                return self._folder(item.identifier, entry.title, children)
            if kind == "page" and len(parts) == 3 and parts[2].isdecimal():
                offset = int(parts[2])
                if offset > 2_147_483_647:
                    raise ValueError("Invalid page")
                return await self._clips(entry, item.identifier, offset)
            if kind == "day" and len(parts) in (3, 4) and DAY.fullmatch(parts[2]):
                offset = 0
                if len(parts) == 4:
                    if not parts[3].isdecimal():
                        raise ValueError("Invalid page")
                    offset = int(parts[3])
                    if offset > 2_147_483_647:
                        raise ValueError("Invalid page")
                return await self._clips(entry, item.identifier, offset, parts[2])
            raise ValueError("Invalid folder")
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


def clip_reason(clip):
    """画面变动, 声音触发, 定时. Manual recording is not a mode in this app."""
    labels = dict(REASONS)
    triggers = clip.get("triggers")
    codes = []
    if isinstance(triggers, list) and triggers:
        codes = [item for item in triggers if item in labels]
    else:
        text = str(clip.get("reasons") or "")
        tokens = set(re.split(r"[\s·,，]+", text))
        if "画面变动" in text or "motion" in tokens:
            codes.append("motion")
        if "声响" in text or "声音触发" in text or "sound" in tokens:
            codes.append("sound")
        if "定时" in text or "schedule" in tokens:
            codes.append("schedule")
    seen = []
    for code in codes:
        label = labels[code]
        if label not in seen:
            seen.append(label)
    return "、".join(seen)

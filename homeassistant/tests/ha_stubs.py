"""Minimal HA boundary doubles. These do not certify HA runtime compatibility."""
import sys
import types
from enum import IntFlag
from datetime import timedelta, timezone


def install():
    def mod(name, **values):
        item = types.ModuleType(name)
        item.__dict__.update(values)
        sys.modules[name] = item
        return item

    class BaseError(Exception):
        pass

    class AbortFlow(BaseError):
        def __init__(self, reason):
            self.reason = reason

    class ConfigFlow:
        def __init_subclass__(cls, **kwargs):
            pass
        def __init__(self):
            self.context = {}
            self.unique_id = None
        async def async_set_unique_id(self, identity):
            self.unique_id = identity
        def _abort_if_unique_id_configured(self, updates=None):
            for entry in self.hass.config_entries.async_entries("afterglow"):
                if entry.unique_id == self.unique_id:
                    if updates:
                        entry.data.update(updates)
                        self.hass.config_entries.reloaded.append(entry.entry_id)
                    raise AbortFlow("already_configured")
        def async_abort(self, *, reason):
            return {"type": "abort", "reason": reason}
        def async_show_form(self, **kwargs):
            return {"type": "form", **kwargs}
        def async_create_entry(self, **kwargs):
            return {"type": "create_entry", **kwargs}
        def _get_reconfigure_entry(self):
            return self.hass.config_entries.async_get_entry(self.context["entry_id"])
        def _get_reauth_entry(self):
            return self._get_reconfigure_entry()
        def async_update_reload_and_abort(self, entry, *, data_updates, reason="reauth_successful"):
            entry.data.update(data_updates)
            self.hass.config_entries.reloaded.append(entry.entry_id)
            return self.async_abort(reason=reason)

    class BrowseMediaSource:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)
    class MediaSource:
        def __init__(self, domain):
            self.domain = domain
    class PlayMedia:
        def __init__(self, url, mime_type):
            self.url, self.mime_type = url, mime_type
    class Camera:
        def __init__(self):
            self.stream_options = {}
        async def handle_async_mjpeg_stream(self, request):
            return None
    class CoordinatorEntity:
        def __init__(self, coordinator):
            self.coordinator = coordinator
        @property
        def available(self):
            return self.coordinator.last_update_success
    class DataUpdateCoordinator:
        def __init__(self, hass, logger, **kwargs):
            self.hass = hass
            self.data = None
            self.last_update_success = True
        async def async_config_entry_first_refresh(self):
            self.data = await self._async_update_data()
    class Feature(IntFlag):
        STREAM = 2
    class HomeAssistantView:
        requires_auth = True

    mod("homeassistant")
    mod("homeassistant.components")
    mod("homeassistant.helpers")
    mod("homeassistant.util")
    mod("homeassistant.util.dt", as_local=lambda value: value.astimezone(timezone(timedelta(hours=13))))
    mod("homeassistant.core", HomeAssistant=object, callback=lambda fn: fn)
    mod("homeassistant.config_entries", ConfigFlow=ConfigFlow, ConfigFlowResult=dict, ConfigEntry=object)
    mod("homeassistant.const", CONF_HOST="host", CONF_PORT="port", CONF_USERNAME="username",
        CONF_PASSWORD="password", Platform=types.SimpleNamespace(CAMERA="camera"))
    mod("homeassistant.exceptions", HomeAssistantError=BaseError,
        ConfigEntryAuthFailed=type("ConfigEntryAuthFailed", (BaseError,), {}),
        ConfigEntryNotReady=type("ConfigEntryNotReady", (BaseError,), {}))
    mod("homeassistant.data_entry_flow", AbortFlow=AbortFlow)
    mod("homeassistant.helpers.aiohttp_client", async_get_clientsession=lambda hass: hass.session)
    mod("homeassistant.helpers.device_registry", DeviceInfo=dict)
    mod("homeassistant.helpers.entity_platform", AddConfigEntryEntitiesCallback=object)
    mod("homeassistant.helpers.update_coordinator", CoordinatorEntity=CoordinatorEntity,
        DataUpdateCoordinator=DataUpdateCoordinator, UpdateFailed=BaseError)
    mod("homeassistant.components.camera", Camera=Camera, CameraEntityFeature=Feature)
    mod("homeassistant.components.http", HomeAssistantView=HomeAssistantView)
    mod("homeassistant.components.media_player", BrowseError=type("BrowseError", (BaseError,), {}),
        MediaClass=types.SimpleNamespace(APP="app", DIRECTORY="directory", VIDEO="video"))
    mod("homeassistant.components.media_source", MediaSource=MediaSource, BrowseMediaSource=BrowseMediaSource,
        PlayMedia=PlayMedia, MediaSourceItem=object, Unresolvable=type("Unresolvable", (BaseError,), {}))
    mod("homeassistant.helpers.service_info")
    mod("homeassistant.helpers.service_info.zeroconf", ZeroconfServiceInfo=object)
    return AbortFlow


class Entries:
    def __init__(self, entries):
        self.entries = entries
        self.reloaded = []
    def async_entries(self, domain):
        return self.entries
    def async_get_entry(self, entry_id):
        return next((e for e in self.entries if e.entry_id == entry_id), None)
    async def async_forward_entry_setups(self, entry, platforms):
        self.forwarded = (entry.entry_id, platforms)
    async def async_unload_platforms(self, entry, platforms):
        return True

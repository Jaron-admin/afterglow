"""Afterglow LAN camera and on-device recordings."""
from __future__ import annotations

from datetime import timedelta
import logging

from homeassistant.const import CONF_HOST, CONF_PORT, CONF_USERNAME, CONF_PASSWORD, Platform
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import AfterglowApi, ApiError, AuthError
from .const import DOMAIN, DEFAULT_PORT
from .views import RecordingProxyView, RecordingThumbnailView

_LOGGER = logging.getLogger(__name__)
PLATFORMS = [Platform.CAMERA]


class AfterglowCoordinator(DataUpdateCoordinator):
    """Refresh availability/capabilities; each refresh verifies identity first."""

    def __init__(self, hass, entry, api):
        super().__init__(hass, _LOGGER, name=DOMAIN, update_interval=timedelta(seconds=60))
        self.api = api
        self.expected_id = entry.unique_id

    async def _async_update_data(self):
        try:
            info = await self.api.info(expected_id=self.expected_id)
            await self.api.status()
            return info
        except AuthError as err:
            raise ConfigEntryAuthFailed("Phone credentials were rejected") from err
        except ApiError as err:
            raise UpdateFailed("Cannot connect to the configured Afterglow device") from err


async def async_setup_entry(hass, entry):
    data = entry.data
    api = AfterglowApi(async_get_clientsession(hass), data[CONF_HOST],
        data.get(CONF_PORT, DEFAULT_PORT), data.get(CONF_USERNAME, ""), data.get(CONF_PASSWORD, ""))
    coordinator = AfterglowCoordinator(hass, entry, api)
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator
    shared = hass.data.setdefault(DOMAIN, {})
    if not shared.get("proxy_registered"):
        hass.http.register_view(RecordingProxyView(hass))
        hass.http.register_view(RecordingThumbnailView(hass))
        shared["proxy_registered"] = True
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass, entry):
    if await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        entry.runtime_data = None
        return True
    return False

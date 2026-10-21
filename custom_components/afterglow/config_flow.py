"""Configure a phone manually or discover its persistent LAN identity."""
from __future__ import annotations

import re
from yarl import URL

try:
    import probatio as vol
except ImportError:  # Home Assistant releases before the schema-library rename.
    import voluptuous as vol

from homeassistant.config_entries import ConfigFlow
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_USERNAME, CONF_PASSWORD
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import AfterglowApi, ApiError, AuthError, IdentityError, device_id
from .const import DEFAULT_PORT, DOMAIN

INSTANCE_ID = re.compile(r"^Afterglow-([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})")


class AfterglowConfigFlow(ConfigFlow, domain=DOMAIN):
    """Keep stable identity separate from a changing host address."""

    VERSION = 1

    def __init__(self):
        self._suggested = {}
        self._expected_id = None
        self._updating_entry = None
        self._update_reason = "reconfigure_successful"

    async def async_step_user(self, user_input=None):
        return await self._configure("user", user_input)

    async def async_step_reconfigure(self, user_input=None):
        self._updating_entry = self._get_reconfigure_entry()
        self._expected_id = self._updating_entry.unique_id
        self._suggested = dict(self._updating_entry.data)
        self._update_reason = "reconfigure_successful"
        return await self._configure("reconfigure", user_input)

    async def async_step_reauth(self, entry_data):
        self._updating_entry = self._get_reauth_entry()
        self._expected_id = self._updating_entry.unique_id
        self._suggested = dict(entry_data)
        self._update_reason = "reauth_successful"
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input=None):
        return await self._configure("reauth_confirm", user_input)

    async def _configure(self, step_id, user_input):
        errors = {}
        if user_input is not None:
            self._suggested = dict(user_input)
            data = {CONF_HOST: user_input[CONF_HOST].strip(),
                    CONF_PORT: user_input.get(CONF_PORT, DEFAULT_PORT),
                    CONF_USERNAME: user_input.get(CONF_USERNAME, ""),
                    CONF_PASSWORD: user_input.get(CONF_PASSWORD, "")}
            try:
                if data[CONF_HOST].startswith("http://"):
                    url = URL(data[CONF_HOST])
                    if (not url.host or url.user is not None or url.path not in ("", "/")
                            or url.query_string or url.fragment):
                        raise ValueError("Use the phone's base address without credentials")
                    data[CONF_HOST], data[CONF_PORT] = url.host, url.port
                api = AfterglowApi(async_get_clientsession(self.hass), data[CONF_HOST],
                    data[CONF_PORT], data[CONF_USERNAME], data[CONF_PASSWORD])
                info = await api.info(expected_id=self._expected_id)
                await api.status()
            except IdentityError:
                errors["base"] = "wrong_device"
            except AuthError:
                errors["base"] = "invalid_auth"
            except (ApiError, ValueError):
                errors["base"] = "cannot_connect"
            else:
                if self._updating_entry is not None:
                    return self.async_update_reload_and_abort(self._updating_entry,
                        data_updates=data, reason=self._update_reason)
                await self.async_set_unique_id(info["device_id"])
                self._abort_if_unique_id_configured()
                return self.async_create_entry(title=info["name"], data=data)
        defaults = self._suggested
        return self.async_show_form(step_id=step_id, errors=errors, data_schema=vol.Schema({
            vol.Required(CONF_HOST, default=defaults.get(CONF_HOST, "")): str,
            vol.Required(CONF_PORT, default=defaults.get(CONF_PORT, DEFAULT_PORT)): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
            vol.Optional(CONF_USERNAME, default=defaults.get(CONF_USERNAME, "")): str,
            vol.Optional(CONF_PASSWORD, default=defaults.get(CONF_PASSWORD, "")): str,
        }))

    async def async_step_zeroconf(self, discovery_info):
        identity = discovery_info.properties.get("id")
        if isinstance(identity, bytes):
            identity = identity.decode("ascii", errors="replace")
        if not identity:
            match = INSTANCE_ID.match(discovery_info.name)
            identity = match.group(1) if match else None
        try:
            identity = device_id(identity)
            api = AfterglowApi(async_get_clientsession(self.hass),
                discovery_info.host, discovery_info.port)
            # Discovery data is only a hint. Probe without credentials before updating.
            await api.info(expected_id=identity)
        except (ApiError, ValueError, TypeError):
            return self.async_abort(reason="cannot_connect")
        await self.async_set_unique_id(identity)
        self._abort_if_unique_id_configured(updates={
            CONF_HOST: discovery_info.host, CONF_PORT: discovery_info.port})
        self._expected_id = identity
        self._suggested = {CONF_HOST: discovery_info.host, CONF_PORT: discovery_info.port}
        self.context["title_placeholders"] = {"name": f"Afterglow-{identity[:8]}"}
        return await self.async_step_user()

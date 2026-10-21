"""Packaging regressions for installing this repository through HACS."""
import json
from pathlib import Path
import struct
import unittest


REPO = Path(__file__).resolve().parents[2]
INTEGRATION = REPO / "custom_components" / "afterglow"


class LayoutTests(unittest.TestCase):
    def test_single_integration_is_at_repository_root(self):
        components = REPO / "custom_components"
        self.assertTrue(components.is_dir(), "HACS needs root custom_components/")
        domains = sorted(path.name for path in components.iterdir()
                         if path.is_dir() and not path.name.startswith("."))
        self.assertEqual(domains, ["afterglow"])
        self.assertFalse((REPO / "homeassistant" / "custom_components" / "afterglow").exists(),
                         "Keep only one installable source of the integration")
        for filename in ("__init__.py", "api.py", "camera.py", "config_flow.py",
                         "const.py", "media_source.py", "streaming.py", "views.py",
                         "manifest.json", "strings.json", "translations/en.json",
                         "translations/zh-Hans.json"):
            with self.subTest(filename=filename):
                self.assertTrue((INTEGRATION / filename).is_file(), filename)

    def test_hacs_downloads_the_integration_directory(self):
        path = REPO / "hacs.json"
        self.assertTrue(path.is_file(), "HACS metadata must be at the repository root")
        metadata = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(metadata["name"], "Afterglow")
        self.assertFalse(metadata.get("content_in_root", False))
        self.assertFalse(metadata.get("zip_release", False),
                         "Release APKs and source archives are not HACS integration ZIPs")

    def test_integration_manifest_has_installation_metadata(self):
        path = INTEGRATION / "manifest.json"
        self.assertTrue(path.is_file(), "HACS must discover the manifest in the root integration")
        manifest = json.loads(path.read_text(encoding="utf-8"))
        for key in ("domain", "name", "documentation", "issue_tracker", "codeowners", "version"):
            with self.subTest(key=key):
                self.assertTrue(manifest.get(key), key)
        self.assertEqual(manifest["domain"], INTEGRATION.name)
        self.assertRegex(manifest["version"], r"^\d+\.\d+\.\d+$")
        self.assertTrue(manifest["documentation"].startswith("https://"))
        self.assertTrue(manifest["issue_tracker"].startswith("https://"))

    def test_brand_icon_is_shipped_with_the_integration(self):
        path = INTEGRATION / "brand" / "icon.png"
        self.assertTrue(path.is_file(), "HACS can use a local brand icon without store approval")
        icon = path.read_bytes()
        self.assertEqual(icon[:8], b"\x89PNG\r\n\x1a\n")
        width, height = struct.unpack(">II", icon[16:24])
        self.assertEqual(width, height, "The integration icon must be square")
        self.assertGreater(width, 0)


if __name__ == "__main__":
    unittest.main()

"""Spec 011 T025/T029: OpenAPI and text files (FR-010, FR-011, FR-012, FR-024, research R5/R6)."""
import json
import os
import re
import shutil
import subprocess
import unittest
from unittest import mock

from support import BASE, GITHUB, REPO_ROOT, ConverterTest, conv


PINNED = ("--commit", "0" * 40, "--wiki-commit", "1" * 40)


def redoc_available():
    """The real Redocly run needs npm and network access for the first install; opt in explicitly."""
    return os.environ.get("DOCS_SITE_REDOC_TESTS") == "1" and shutil.which("npm") is not None


class ReferenceFilesTest(ConverterTest):

    def setUp(self):
        super().setUp()
        self.repo = self.fixture("repo")
        self.wiki_dir = self.fixture("wiki-basic")

    def test_text_files_get_download_and_code_view_page(self):
        stdout, _ = self.build(self.wiki_dir, self.repo)
        home = self.page("/")[1]
        for path in (".env.example", "other.yaml", "run.sh", "data.json"):
            with open(os.path.join(self.repo, path), encoding="utf-8") as fh:
                content = fh.read()
            with open(os.path.join(self.out, "_downloads", "reference", path), encoding="utf-8") as fh:
                self.assertEqual(fh.read(), content, path)
            fm, body = self.page("/reference/%s.html" % path)
            self.assertEqual(fm["title"], os.path.basename(path))
            self.assertEqual(fm["parent"], "Reference")
            self.assertIs(fm["render_with_liquid"], False)
            self.assertIn(content, body)
            self.assertIn("(%s/reference/%s)" % (BASE, path), body)  # download link
            self.assertIn("(%s/reference/%s.html)" % (BASE, path), home)
        self.assertIn("reference: markdown=3 openapi=1 text=4 assets=1", stdout)

    def test_fence_is_longer_than_any_backtick_run(self):
        self.build(self.wiki_dir, self.repo)
        body = self.page("/reference/run.sh.html")[1]
        self.assertIn("\n````", body)
        self.assertIn("echo '```'", body)

    def test_downloads_are_listed_for_the_jekyll_plugin(self):
        # site/_plugins/downloads.rb publishes _downloads/<path> byte for byte (dot-files and "---" headers too)
        self.build(self.wiki_dir, self.repo)
        with open(os.path.join(self.out, "_config.generated.yml")) as fh:
            downloads = json.load(fh)["docs_downloads"]
        self.assertEqual(downloads, sorted([
            "reference/.env.example", "reference/api/openapi.yaml", "reference/data.json",
            "reference/docs/img/x.png", "reference/other.yaml", "reference/run.sh"]))

    def test_openapi_page_placeholder_without_redoc(self):
        self.build(self.wiki_dir, self.repo)
        fm, body = self.page("/reference/api/openapi.yaml.html")
        self.assertEqual(fm["title"], "API reference")
        self.assertEqual(fm["parent"], "Reference")
        self.assertIs(fm["search_exclude"], True)
        self.assertIn("%s/reference/api/openapi.yaml" % BASE, body)
        self.assertTrue(os.path.isfile(os.path.join(self.out, "_downloads", "reference", "api", "openapi.yaml")))

    def test_yaml_without_openapi_key_is_a_text_file(self):
        self.build(self.wiki_dir, self.repo)
        self.assertEqual(self.page("/reference/other.yaml.html")[0]["title"], "other.yaml")

    def test_redocly_invocation_is_an_argument_list(self):
        calls = []

        def fake_run(argv, **kwargs):
            calls.append((argv, kwargs))
            if "build-docs" in argv:
                with open(argv[argv.index("--output") + 1], "w") as fh:
                    fh.write("<!DOCTYPE html><html><body>REDOC</body></html>")
            return subprocess.CompletedProcess(argv, 0, "", "")

        with mock.patch.object(conv.subprocess, "run", side_effect=fake_run), \
                mock.patch.object(conv, "redoc_installed", return_value=False):
            code, out, err = self.run_conv("--wiki", self.wiki_dir, "--repo", self.repo, "--out", self.out, *PINNED)
        self.assertEqual(code, 0, err)
        install, build = [c for c, _ in calls]
        redoc_dir = os.path.join(REPO_ROOT, "site", "_redoc")
        self.assertEqual(install[:4], ["npm", "ci", "--prefix", redoc_dir])
        self.assertIn("--ignore-scripts", install)
        self.assertEqual(build[0], os.path.join(redoc_dir, "node_modules", ".bin", "redocly"))
        self.assertIn("build-docs", build)
        self.assertIn("--inlineBundle", build)  # Redoc is embedded, never loaded from the Redocly CDN
        self.assertNotIn("--disableGoogleFont", build)  # removed in Redocly CLI 2 (an error there)
        self.assertEqual(calls[1][1]["env"]["REDOCLY_TELEMETRY"], "off")
        self.assertEqual(build[build.index("--template") + 1], os.path.join(redoc_dir, "template.hbs"))
        for _, kwargs in calls:
            self.assertFalse(kwargs.get("shell"))
        fm, body = self.page("/reference/api/openapi.yaml.html")
        self.assertIsNone(fm["layout"])
        self.assertIn("REDOC", body)

    def test_redocly_failure_is_an_error(self):
        def failing(argv, **kwargs):
            return subprocess.CompletedProcess(argv, 3 if "build-docs" in argv else 0, "", "boom")

        with mock.patch.object(conv.subprocess, "run", side_effect=failing), \
                mock.patch.object(conv, "redoc_installed", return_value=True):
            code, _, err = self.run_conv("--wiki", self.wiki_dir, "--repo", self.repo, "--out", self.out, *PINNED)
        self.assertEqual(code, 1)
        self.assertIn("error: api/openapi.yaml: Redocly build-docs failed (exit 3)", err)

    @unittest.skipUnless(redoc_available(), "set DOCS_SITE_REDOC_TESTS=1 (needs npm and Node >= 20.19)")
    def test_real_redoc_page_is_self_contained(self):
        code, _, err = self.run_conv("--wiki", self.wiki_dir, "--repo", self.repo, "--out", self.out)
        self.assertEqual(code, 0, err)
        html = self.page("/reference/api/openapi.yaml.html")[1]
        # nothing is loaded from another site: no external scripts, stylesheets, preloads, images, module imports
        # or CSS urls (plain hyperlinks, e.g. Redoc's attribution link, are not resources)
        loaded = re.findall(r'\ssrc=["\'](https?:[^"\']+)', html)
        loaded += re.findall(r'<link\b[^>]*\bhref=["\'](https?:[^"\']+)', html)
        loaded += re.findall(r'\bimport\b[^;"\'<>]*["\'](https?:[^"\']+)', html)
        loaded += re.findall(r'url\(\s*["\']?(https?:[^)"\']+)', html)
        loaded += re.findall(r'@import\s+["\'](https?:[^"\']+)', html)
        self.assertEqual(loaded, [])
        self.assertIn("Test API", html)
        self.assertIn("%s/reference/api/openapi.yaml" % BASE, html)

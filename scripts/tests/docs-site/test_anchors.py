"""Spec 011 T011: GitHub-compatible heading ids and literal content (FR-014, FR-019, SC-005, SC-007)."""
import json
import os
import re
import shutil
import subprocess
import unittest

from support import FIXTURES, REPO_ROOT, ConverterTest

ID_LINE = re.compile(r'^\{: id="([^"]*)"\}$', re.M)


def jekyll_available():
    return os.environ.get("DOCS_SITE_JEKYLL_TESTS") == "1" and shutil.which("bundle") is not None


class AnchorsTest(ConverterTest):

    def test_real_wiki_ids_match_github(self):
        with open(os.path.join(FIXTURES, "github-anchors.json"), encoding="utf-8") as fh:
            expected = json.load(fh)["anchors"]
        self.build(self.fixture("wiki-real"), None, "--commit", "0" * 40)
        pages = self.pages()
        for name, ids in expected.items():
            body = pages["/" if name == "Home" else "/" + name][1]
            self.assertEqual(ID_LINE.findall(body), ids, name)

    def test_edge_case_headings(self):
        with open(os.path.join(FIXTURES, "headings.md"), encoding="utf-8") as fh:
            wiki = self.wiki({"Home.md": fh.read()})
        self.build(wiki)
        body = self.page("/")[1]
        self.assertEqual(ID_LINE.findall(body), [
            "headings", "110---2026-11-16", "überblick", "rocket--launch", "closed-heading",
            "fv_tag-and-emphasis", "duplicate", "duplicate-1"])
        # the id line follows its heading directly; the header-id form {#…} is never written
        self.assertIn('## [1.1.0] - 2026-11-16\n{: id="110---2026-11-16"}\n', body)
        self.assertNotIn("{#", body)

    def test_headings_in_code_get_no_id(self):
        self.build(self.fixture("wiki-real"), None, "--commit", "0" * 40)
        body = self.page("/")[1]
        self.assertIn("# create compose.yaml as shown in the Installation Guide (option A), then:\n", body)
        self.assertNotIn('{: id="create-composeyaml', body)

    def test_liquid_is_literal(self):
        self.build(self.fixture("wiki-basic"), self.fixture("repo"))
        fm, body = self.page("/")
        self.assertIs(fm["render_with_liquid"], False)
        self.assertIn("{{ x }} {% endraw %}", body)
        self.assertIn("`{{ inline }}`", body)

    @unittest.skipUnless(jekyll_available(), "set DOCS_SITE_JEKYLL_TESTS=1 with Bundler on PATH to build with Jekyll")
    def test_jekyll_renders_the_ids(self):
        with open(os.path.join(FIXTURES, "headings.md"), encoding="utf-8") as fh:
            wiki = self.wiki({"Home.md": fh.read() + "\n```\n{{ x }} {% endraw %}\n```\n"})
        self.build(wiki)
        site = os.path.join(self.tmp, "_site")
        subprocess.run(["bundle", "exec", "jekyll", "build", "-q", "-s", self.out, "-d", site, "--config",
                        "%s/_config.yml,%s/_config.generated.yml" % (self.out, self.out)], check=True,
                       env=dict(os.environ, BUNDLE_GEMFILE=os.path.join(REPO_ROOT, "site", "Gemfile")))
        with open(os.path.join(site, "index.html"), encoding="utf-8") as fh:
            html = fh.read()
        for hid in ("110---2026-11-16", "überblick", "rocket--launch", "duplicate-1"):
            self.assertIn('id="%s"' % hid, html)
        self.assertNotIn("{:", html.split("<main")[1])
        self.assertIn("{{ x }} {% endraw %}", html)

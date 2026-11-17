"""Spec 011 T018: --list-sources prints the repository paths the site publishes (contracts/converter-cli.md)."""
import os

from support import ConverterTest


class ListSourcesTest(ConverterTest):

    def test_lists_published_repository_paths_and_writes_nothing(self):
        code, out, err = self.run_conv("--wiki", self.fixture("wiki-basic"), "--repo", self.fixture("repo"),
                                       "--out", self.out, "--list-sources")
        self.assertEqual(code, 0, err)
        self.assertEqual(out.splitlines(), sorted([
            ".env.example", "CHANGELOG.md", "api/openapi.yaml", "data.json", "docs/a.md", "docs/b.md",
            "docs/img/x.png", "other.yaml", "run.sh"]))
        self.assertFalse(os.path.exists(self.out))

    def test_unresolved_wiki_link_still_fails(self):
        code, _, err = self.run_conv("--wiki", self.fixture("wiki-broken"), "--out", self.out, "--list-sources")
        self.assertEqual(code, 1)
        self.assertIn("Missing Page", err)

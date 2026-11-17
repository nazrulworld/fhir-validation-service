"""Spec 011 T009: wiki pages become site pages (FR-001–FR-004, FR-015, FR-019a, FR-033)."""
import os

from support import ConverterTest, GITHUB


class WikiPagesTest(ConverterTest):

    def test_pages_titles_permalinks_and_edit_links(self):
        self.build(self.fixture("wiki-basic"), self.fixture("repo"))
        pages = self.pages()
        home, _ = pages["/"]
        self.assertEqual(home["title"], "Home")
        self.assertEqual(home["wiki_edit_url"], GITHUB + "/wiki/Home/_edit")
        guide, _ = pages["/Installation-Guide"]
        self.assertEqual(guide["title"], "Installation Guide")
        self.assertEqual(guide["wiki_edit_url"], GITHUB + "/wiki/Installation-Guide/_edit")
        api, _ = pages["/API-Documentation"]
        self.assertEqual(api["title"], "API Documentation")

    def test_default_order_home_first_then_alphabetical(self):
        self.build(self.fixture("wiki-basic"), self.fixture("repo"))
        pages = self.pages()
        order = sorted((fm["nav_order"], p) for p, (fm, _) in pages.items() if "wiki_edit_url" in fm)
        self.assertEqual([p for _, p in order], ["/", "/API-Documentation", "/Installation-Guide"])

    def test_sidebar_order_and_special_pages_not_published(self):
        self.build(self.fixture("wiki-sidebar"))
        pages = self.pages()
        self.assertNotIn("/_Sidebar", pages)
        self.assertNotIn("/_Footer", pages)
        order = sorted((fm["nav_order"], p) for p, (fm, _) in pages.items())
        self.assertEqual([p for _, p in order], ["/API-Documentation", "/", "/Zeta", "/Alpha"])
        for _, body in pages.values():
            self.assertNotIn("Footer text", body)

    def test_hostile_page_names_are_data(self):
        wiki = self.wiki({"Home.md": "# Home\n", "a$(touch pwned);b.md": "# x\n"})
        self.build(wiki)
        self.assertIn("/a$(touch pwned);b", self.pages())
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "pwned")))

    def test_names_that_escape_or_start_with_dot_or_underscore_are_errors(self):
        for bad in (".hidden.md", "_Private.md"):
            wiki = self.wiki({"Home.md": "# Home\n", bad: "# x\n"})
            _, err = self.build(wiki, None, expect=1)
            self.assertIn("invalid wiki page name", err)

    def test_pages_whose_keys_collide_are_errors(self):
        wiki = self.wiki({"Home.md": "# Home\n", "Foo Bar.md": "# a\n", "foo-bar.md": "# b\n"})
        _, err = self.build(wiki, None, expect=1)
        self.assertIn("same wiki page name", err)

    def test_missing_home_is_an_error(self):
        wiki = self.wiki({"Other.md": "# Other\n"})
        _, err = self.build(wiki, None, expect=1)
        self.assertIn("Home", err)

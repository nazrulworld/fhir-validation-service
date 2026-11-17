"""Spec 011 T024: linked repository Markdown becomes Reference pages (FR-007–FR-009, FR-012a, FR-013, FR-019a)."""
import os

from support import BASE, GITHUB, ConverterTest, git


class ReferenceMarkdownTest(ConverterTest):

    def setUp(self):
        super().setUp()
        self.repo = self.fixture("repo")
        self.commit = git(self.repo, "rev-parse", "HEAD")
        self.stdout, self.stderr = self.build(self.fixture("wiki-basic"), self.repo)
        self.home = self.page("/")[1]
        self.pages_ = self.pages()

    def test_wiki_links_point_to_reference_pages(self):
        self.assertIn("[Changelog](%s/reference/CHANGELOG)" % BASE, self.home)
        # /raw/ and a ref other than main are treated alike; the anchor is kept
        self.assertIn("[Doc A](%s/reference/docs/a#section-a)" % BASE, self.home)

    def test_reference_page_front_matter_and_content_from_repo(self):
        fm, body = self.pages_["/reference/CHANGELOG"]
        self.assertEqual(fm["title"], "Changelog")
        self.assertEqual(fm["parent"], "Reference")
        self.assertIs(fm["render_with_liquid"], False)
        self.assertNotIn("wiki_edit_url", fm)
        self.assertIn("Text with {{ braces }}.", body)
        self.assertIn('## [1.1.0] - 2026-11-16\n{: id="110---2026-11-16"}', body)

    def test_title_is_the_first_heading_else_the_file_name(self):
        self.assertEqual(self.pages_["/reference/docs/b"][0]["title"], "Part B")
        repo = self.fixture("repo-plain", {"notes.md": "No heading here.\n\n```\n# not a heading\n```\n"})
        wiki = self.wiki({"Home.md": "[n](%s/blob/main/notes.md)\n" % GITHUB})
        code, _, err = self.run_conv("--wiki", wiki, "--repo", repo, "--out", self.out + "-2", "--no-redoc")
        self.assertEqual(code, 0, err)
        self.out = self.out + "-2"
        self.assertEqual(self.page("/reference/notes")[0]["title"], "notes.md")

    def test_transitive_and_cycle_publish_each_file_once(self):
        a = self.pages_["/reference/docs/a"][1]
        b = self.pages_["/reference/docs/b"][1]
        self.assertIn("[b](%s/reference/docs/b)" % BASE, a)
        self.assertIn("[b part](%s/reference/docs/b#part-b)" % BASE, a)
        self.assertIn("[full](%s/reference/docs/b)" % BASE, a)
        self.assertIn("[a](%s/reference/docs/a)" % BASE, b)
        self.assertIn("reference: markdown=3 ", self.stdout)

    def test_relative_links_to_unpublished_paths_go_to_github_at_the_commit(self):
        a = self.pages_["/reference/docs/a"][1]
        self.assertIn("[java](%s/blob/%s/src/Foo.java)" % (GITHUB, self.commit), a)
        self.assertIn("[sources](%s/tree/%s/src)" % (GITHUB, self.commit), a)
        self.assertIn("[gone](%s/blob/%s/docs/gone.md)" % (GITHUB, self.commit), a)
        self.assertIn("warning: docs/a.md: link to missing file docs/gone.md", self.stderr)
        self.assertIn("[root](%s/reference/CHANGELOG)" % BASE, a)

    def test_link_escaping_the_repository_keeps_text_only(self):
        a = self.pages_["/reference/docs/a"][1]
        self.assertIn("outside,", a.replace("\n", " ").split("[sources]")[1])
        self.assertNotIn("outside.md", a)
        self.assertIn("warning: docs/a.md: link outside the repository: ../../outside.md", self.stderr)

    def test_untouched_links(self):
        a = self.pages_["/reference/docs/a"][1]
        self.assertIn("[external](https://example.org/x)", a)
        self.assertIn("[self](#section-a)", a)

    def test_images_are_copied(self):
        a = self.pages_["/reference/docs/a"][1]
        self.assertIn("![diagram](%s/reference/docs/img/x.png)" % BASE, a)
        self.assertTrue(os.path.isfile(os.path.join(self.out, "_downloads", "reference", "docs", "img", "x.png")))

    def test_reference_section_parent_and_order(self):
        fm, _ = self.pages_["/reference/"]
        self.assertEqual(fm["title"], "Reference")
        self.assertIs(fm["has_children"], True)
        guide_orders = [f["nav_order"] for f, _ in self.pages_.values() if "wiki_edit_url" in f]
        self.assertGreater(fm["nav_order"], max(guide_orders))
        children = sorted((f["nav_order"], p) for p, (f, _) in self.pages_.items() if f.get("parent") == "Reference")
        # breadth-first from the guide pages, in link order on Home
        self.assertEqual([p for _, p in children][:4], [
            "/reference/CHANGELOG", "/reference/docs/a", "/reference/api/openapi.yaml.html",
            "/reference/other.yaml.html"])
        self.assertEqual([p for _, p in children][-1], "/reference/docs/b")

    def test_full_github_links_to_dirs_and_missing_files_stay_external(self):
        self.assertIn("[Sources](%s/tree/main/src)" % GITHUB, self.home)
        self.assertIn("[Sources via blob](%s/blob/main/src)" % GITHUB, self.home)
        self.assertIn("[Missing](%s/blob/main/missing.md)" % GITHUB, self.home)
        self.assertIn("[Java](%s/blob/main/src/Foo.java)" % GITHUB, self.home)
        self.assertIn("warning: Home: link to missing file missing.md", self.stderr)

    def test_address_collision_is_an_error(self):
        # x.yaml's code-view page and the Markdown file x.yaml.html.md both map to /reference/x.yaml.html
        repo = self.fixture("repo-collide", {"x.yaml": "a: 1\n", "x.yaml.html.md": "# Clash\n"})
        wiki = self.wiki({"Home.md": "[y](%s/blob/main/x.yaml) [m](%s/blob/main/x.yaml.html.md)\n" % (GITHUB, GITHUB)})
        code, _, err = self.run_conv("--wiki", wiki, "--repo", repo, "--out", self.out + "-2", "--no-redoc")
        self.assertEqual(code, 1)
        self.assertIn("address /reference/x.yaml.html is produced by x.yaml and x.yaml.html.md", err)

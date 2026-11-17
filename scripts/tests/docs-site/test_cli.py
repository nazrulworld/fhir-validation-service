"""Spec 011 T006: converter command line, output directory, build metadata, diagnostics (contracts/converter-cli.md)."""
import json
import os
import shutil

from support import ConverterTest, REPO_ROOT, conv, git, make_tree

HOME_ONLY = {"Home.md": "# Home\n\nHello.\n"}


class CliTest(ConverterTest):

    def test_usage_error_exits_2(self):
        code, _, err = self.run_conv("--no-such-option")
        self.assertEqual(code, 2)
        code, _, _ = self.run_conv()  # --wiki is required
        self.assertEqual(code, 2)

    def test_refuses_to_delete_an_unrelated_non_empty_out_dir(self):
        wiki = self.wiki(HOME_ONLY)
        os.makedirs(self.out)
        keep = os.path.join(self.out, "precious.txt")
        make_tree(self.out, {"precious.txt": "keep me"})
        code, _, err = self.run_conv("--wiki", wiki, "--out", self.out, "--no-redoc")
        self.assertEqual(code, 2)
        self.assertTrue(os.path.exists(keep))
        self.assertIn("--out", err)

    def test_out_is_recreated_and_stale_files_disappear(self):
        wiki = self.wiki(HOME_ONLY)
        self.build(wiki)
        stale = os.path.join(self.out, "stale.md")
        make_tree(self.out, {"stale.md": "old"})
        self.build(wiki)
        self.assertFalse(os.path.exists(stale))

    def test_default_out_is_under_repo_target(self):
        self.assertEqual(conv.default_out(REPO_ROOT), os.path.join(REPO_ROOT, "target", "docs-site", "src"))

    def test_generated_config_carries_commits_and_baseurl(self):
        wiki = self.wiki(HOME_ONLY)
        repo = self.fixture("repo")
        wiki_head = git(wiki, "rev-parse", "HEAD")
        repo_head = git(repo, "rev-parse", "HEAD")
        self.build(wiki, repo)
        with open(os.path.join(self.out, "_config.generated.yml")) as fh:
            cfg = json.load(fh)  # JSON is valid YAML
        self.assertEqual(cfg["baseurl"], "/fhir-validation-service")
        self.assertEqual(cfg["build"]["commit"], repo_head)
        self.assertEqual(cfg["build"]["commit_short"], repo_head[:7])
        self.assertEqual(cfg["build"]["wiki_commit"], wiki_head)
        self.assertEqual(cfg["build"]["wiki_commit_short"], wiki_head[:7])

    def test_commit_prefers_github_sha_then_option(self):
        wiki = self.wiki(HOME_ONLY)
        code, _, _ = self.run_conv("--wiki", wiki, "--out", self.out, "--no-redoc", env={"GITHUB_SHA": "a" * 40})
        self.assertEqual(code, 0)
        with open(os.path.join(self.out, "_config.generated.yml")) as fh:
            self.assertEqual(json.load(fh)["build"]["commit"], "a" * 40)
        self.build(wiki, None, "--commit", "b" * 40, "--wiki-commit", "c" * 40)
        with open(os.path.join(self.out, "_config.generated.yml")) as fh:
            build = json.load(fh)["build"]
        self.assertEqual((build["commit"], build["wiki_commit"]), ("b" * 40, "c" * 40))

    def test_scaffolding_is_copied_without_redoc_sources(self):
        wiki = self.wiki(HOME_ONLY)
        self.build(wiki)
        self.assertTrue(os.path.isfile(os.path.join(self.out, "_config.yml")))
        self.assertTrue(os.path.isfile(os.path.join(self.out, "_includes", "footer_custom.html")))
        self.assertFalse(os.path.exists(os.path.join(self.out, "_redoc")))
        self.assertFalse(os.path.exists(os.path.join(self.out, "Gemfile")))

    def test_summary_lines(self):
        wiki = self.wiki({"Home.md": "# Home\n\n[[Other]]\n", "Other.md": "# Other\n"})
        out, _ = self.build(wiki)
        self.assertIn("pages: 2", out)
        self.assertRegex(out, r"reference: markdown=0 openapi=0 text=0 assets=0")
        self.assertIn("links rewritten: 1", out)
        self.assertIn("warnings: 0", out)

    def test_errors_exit_1_and_annotate_on_github_actions(self):
        wiki = self.wiki({"Home.md": "# Home\n\n[[Nowhere]]\n"})
        code, _, err = self.run_conv("--wiki", wiki, "--out", self.out, "--no-redoc", env={"GITHUB_ACTIONS": "true"})
        self.assertEqual(code, 1)
        self.assertIn('error: Home: wiki link to missing page "Nowhere"', err)
        self.assertIn("::error file=Home.md::", err)

    def test_every_generated_page_disables_liquid(self):
        wiki = self.wiki({"Home.md": "# Home\n\n{{ x }}\n", "Other.md": "{% raw %}\n"})
        self.build(wiki)
        pages = self.pages()
        self.assertEqual(len(pages), 2)
        for permalink, (fm, _) in pages.items():
            self.assertIs(fm.get("render_with_liquid"), False, permalink)

    def test_front_matter_round_trip(self):
        text = conv.front_matter({"title": 'A "quoted": title', "nav_order": 3, "render_with_liquid": False}) + "body\n"
        fm, body = conv.split_front_matter(text)
        self.assertEqual(fm, {"title": 'A "quoted": title', "nav_order": 3, "render_with_liquid": False})
        self.assertEqual(body, "body\n")

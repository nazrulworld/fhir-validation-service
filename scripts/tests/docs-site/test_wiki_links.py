"""Spec 011 T010: wiki links are rewritten to site links (FR-005, FR-006, FR-012)."""
from support import BASE, GITHUB, ConverterTest


class WikiLinksTest(ConverterTest):

    def setUp(self):
        super().setUp()
        self.out_text, self.err_text = self.build(self.fixture("wiki-basic"), self.fixture("repo"))
        self.home = self.page("/")[1]

    def test_wiki_link_forms(self):
        self.assertIn("[API Documentation](%s/API-Documentation)" % BASE, self.home)
        self.assertIn("[the guide](%s/Installation-Guide)" % BASE, self.home)
        self.assertIn("[auth](%s/API-Documentation#authentication)" % BASE, self.home)
        self.assertIn("[home](%s/)" % BASE, self.home)
        self.assertIn("[home page](%s/)" % BASE, self.home)

    def test_matching_ignores_case_and_space_hyphen_and_keeps_anchor(self):
        guide = self.page("/Installation-Guide")[1]
        self.assertIn("[Home](%s/)" % BASE, guide)
        self.assertIn("[api documentation](%s/API-Documentation#authentication)" % BASE, guide)

    def test_code_is_untouched(self):
        self.assertIn("[[Not A Link]] {{ x }} {% endraw %}", self.home)
        self.assertIn(GITHUB + "/wiki/Home\n", self.home)
        self.assertIn("`[[Inline Code]]`", self.home)

    def test_non_file_github_links_stay_external(self):
        self.assertIn("(%s/releases)" % GITHUB, self.home)
        self.assertIn("<%s/issues>" % GITHUB, self.home)
        self.assertIn("(%s/releases/download/v${VERSION}/fhir-validation-service-${VERSION}.jar)" % GITHUB, self.home)

    def test_missing_page_fails(self):
        _, err = self.build(self.fixture("wiki-broken"), None, expect=1)
        self.assertIn('error: Home: wiki link to missing page "Missing Page"', err)

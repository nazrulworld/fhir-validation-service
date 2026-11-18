"""Problem type URIs (https://<site>/problems/<type>) redirect to their row in the API documentation."""
import unittest

from support import BASE, ConverterTest

API = """# API Documentation

### Problem types

| Type | Status |
|------|--------|
| <a id="package-not-found"></a>`package-not-found` | 404 |
| <a id="not-ready"></a>`not-ready` | 503 |
| <a id="other"></a>`mismatch` | 400 |

```text
| <a id="in-fence"></a>`in-fence` | 400 |
```
"""


class ProblemRedirectsTest(ConverterTest):

    def test_each_documented_type_gets_a_redirect_page(self):
        wiki = self.wiki({"Home.md": "# Home\n\n[[API Documentation]]\n", "API-Documentation.md": API})
        self.build(wiki)
        pages = self.pages()
        problems = sorted(p for p in pages if p.startswith("/problems/"))
        # rows whose anchor differs from the code text, and rows inside a code fence, are not problem types
        self.assertEqual(problems, ["/problems/not-ready", "/problems/package-not-found"])
        fm, body = pages["/problems/package-not-found"]
        self.assertIsNone(fm["layout"])
        self.assertIs(fm["nav_exclude"], True)
        self.assertIs(fm["search_exclude"], True)
        self.assertIs(fm["render_with_liquid"], False)
        target = BASE + "/API-Documentation#package-not-found"
        self.assertIn('<meta http-equiv="refresh" content="0; url=%s">' % target, body)
        self.assertIn('<link rel="canonical" href="%s">' % target, body)

    def test_no_problem_rows_no_redirects(self):
        wiki = self.wiki({"Home.md": "# Home\n"})
        self.build(wiki)
        self.assertEqual([p for p in self.pages() if p.startswith("/problems/")], [])

    def test_a_type_documented_twice_is_an_error(self):
        row = '| <a id="conflict"></a>`conflict` | 409 |\n'
        wiki = self.wiki({"Home.md": "# Home\n\n| T | S |\n|---|---|\n" + row,
                          "Other.md": "# Other\n\n| T | S |\n|---|---|\n" + row})
        _, err = self.build(wiki, expect=1)
        self.assertIn("problem type conflict is documented on", err)


if __name__ == "__main__":
    unittest.main()

"""Spec 011 T008: Markdown scanner (code is never touched) and GitHub-compatible heading ids (research R4)."""
import unittest

from support import conv


class SegmentsTest(unittest.TestCase):

    def kinds(self, text):
        return [(k, s) for k, s in conv.segments(text)]

    def test_round_trip(self):
        text = "a `b` c\n```\nx\n```\nd ``e ` f`` g\n~~~~\n```\n~~~~\nh\n"
        self.assertEqual("".join(s for _, s in conv.segments(text)), text)

    def test_fences_and_code_spans_are_not_text(self):
        text = "see [[A]]\n```text\n[[B]]\n```\nand `[[C]]` but [[D]]\n"
        text_parts = "".join(s for k, s in conv.segments(text) if k == "text")
        self.assertIn("[[A]]", text_parts)
        self.assertIn("[[D]]", text_parts)
        self.assertNotIn("[[B]]", text_parts)
        self.assertNotIn("[[C]]", text_parts)

    def test_longer_fence_closes_only_with_enough_markers(self):
        text = "````\n```\n[[X]]\n```\n````\n[[Y]]\n"
        text_parts = "".join(s for k, s in conv.segments(text) if k == "text")
        self.assertNotIn("[[X]]", text_parts)
        self.assertIn("[[Y]]", text_parts)

    def test_unclosed_fence_runs_to_end(self):
        text = "a\n```\n[[X]]\n"
        text_parts = "".join(s for k, s in conv.segments(text) if k == "text")
        self.assertNotIn("[[X]]", text_parts)

    def test_unmatched_backtick_is_text(self):
        text = "a ` b [[X]]\n"
        self.assertEqual(conv.segments(text), [("text", text)])


class AnchorTest(unittest.TestCase):

    def ids(self, *headings):
        seen = {}
        return [conv.github_anchor(conv.heading_text(h), seen) for h in headings]

    def test_github_rules(self):
        self.assertEqual(self.ids("POST /{version}/validate"), ["post-versionvalidate"])
        self.assertEqual(self.ids("[1.1.0] - 2026-11-16"), ["110---2026-11-16"])
        self.assertEqual(self.ids("Überblick"), ["überblick"])
        self.assertEqual(self.ids("Rocket 🚀 launch"), ["rocket--launch"])
        self.assertEqual(self.ids("`FV_TAG` and *emphasis*"), ["fv_tag-and-emphasis"])
        self.assertEqual(self.ids("Reference (in the repository)"), ["reference-in-the-repository"])
        self.assertEqual(self.ids("Option A: Docker Compose"), ["option-a-docker-compose"])

    def test_link_text_only(self):
        self.assertEqual(self.ids("See [the guide](http://x/y) and [[Other Page]]"), ["see-the-guide-and-other-page"])
        self.assertEqual(self.ids("Use [[label|Target]]"), ["use-label"])

    def test_duplicates_get_suffixes(self):
        self.assertEqual(self.ids("Notes", "Notes", "Notes"), ["notes", "notes-1", "notes-2"])
        self.assertEqual(self.ids("a", "a-1", "a"), ["a", "a-1", "a-2"])


if __name__ == "__main__":
    unittest.main()

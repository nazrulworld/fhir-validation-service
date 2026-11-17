"""Spec 011 T031: same inputs give byte-identical output (contracts/converter-cli.md, SC-008)."""
import filecmp
import os

from support import ConverterTest


def tree(root):
    files = []
    for dirpath, _, names in os.walk(root):
        for name in names:
            files.append(os.path.relpath(os.path.join(dirpath, name), root))
    return sorted(files)


class DeterminismTest(ConverterTest):

    def test_two_runs_are_identical(self):
        wiki, repo = self.fixture("wiki-basic"), self.fixture("repo")
        first, second = self.out + "-1", self.out + "-2"
        for out in (first, second):
            code, _, err = self.run_conv("--wiki", wiki, "--repo", repo, "--out", out, "--no-redoc")
            self.assertEqual(code, 0, err)
        self.assertEqual(tree(first), tree(second))
        _, mismatch, errors = filecmp.cmpfiles(first, second, tree(first), shallow=False)
        self.assertEqual((mismatch, errors), ([], []))

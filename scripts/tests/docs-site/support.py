"""Spec 011: shared helpers for the converter tests (run: python3 -I -m unittest discover -s scripts/tests/docs-site)."""
import contextlib
import importlib.util
import io
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "fixtures")
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
SCRIPT = os.path.join(REPO_ROOT, "scripts", "build-docs-site.py")
BASE = "/fhir-validation-service"
GITHUB = "https://github.com/nazrulworld/fhir-validation-service"


def load_converter():
    spec = importlib.util.spec_from_file_location("build_docs_site", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


conv = load_converter()


def git(cwd, *args):
    return subprocess.run(["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                           "-c", "commit.gpgsign=false", *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


def make_tree(root, files):
    for rel, text in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb" if isinstance(text, bytes) else "w") as fh:
            fh.write(text)


def git_init(root):
    git(root, "init", "-q")
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "fixture")
    return git(root, "rev-parse", "HEAD")


class ConverterTest(unittest.TestCase):
    """Creates a scratch directory per test; copies fixtures into it as git repositories."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="docs-site-test-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.out = os.path.join(self.tmp, "out")

    def fixture(self, name, extra=None):
        """Copies fixtures/<name> to the scratch dir (plus extra files), commits it, returns the path."""
        dest = os.path.join(self.tmp, name)
        if name in os.listdir(FIXTURES):
            shutil.copytree(os.path.join(FIXTURES, name), dest)
        else:
            os.makedirs(dest)
        if extra:
            make_tree(dest, extra)
        git_init(dest)
        return dest

    def wiki(self, files):
        dest = os.path.join(self.tmp, "wiki-%d" % len(os.listdir(self.tmp)))
        os.makedirs(dest)
        make_tree(dest, files)
        git_init(dest)
        return dest

    def run_conv(self, *argv, env=None):
        """Runs main(argv) in-process; returns (exit_code, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        saved = dict(os.environ)
        os.environ.pop("GITHUB_SHA", None)
        os.environ.pop("GITHUB_ACTIONS", None)
        os.environ.update(env or {})
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                try:
                    code = conv.main(list(argv))
                except SystemExit as exc:
                    code = exc.code
        finally:
            os.environ.clear()
            os.environ.update(saved)
        return code, out.getvalue(), err.getvalue()

    def build(self, wiki, repo=None, *extra, expect=0):
        argv = ["--wiki", wiki, "--out", self.out, "--no-redoc"]
        if repo:
            argv += ["--repo", repo]
        code, out, err = self.run_conv(*argv, *extra)
        self.assertEqual(code, expect, "stdout:\n%s\nstderr:\n%s" % (out, err))
        return out, err

    def page(self, permalink):
        """Returns (front_matter, body) of the generated page with the given permalink."""
        for dirpath, _, names in os.walk(self.out):
            for name in names:
                if not name.endswith((".md", ".html")):
                    continue
                path = os.path.join(dirpath, name)
                with open(path, encoding="utf-8") as fh:
                    text = fh.read()
                if not text.startswith("---\n"):
                    continue
                fm, body = conv.split_front_matter(text)
                if fm.get("permalink") == permalink:
                    return fm, body
        self.fail("no generated page with permalink %r" % permalink)

    def pages(self):
        found = {}
        for dirpath, _, names in os.walk(self.out):
            for name in names:
                path = os.path.join(dirpath, name)
                if name.endswith((".md", ".html")):
                    with open(path, encoding="utf-8") as fh:
                        text = fh.read()
                    if text.startswith("---\n"):
                        fm, body = conv.split_front_matter(text)
                        if "permalink" in fm:
                            found[fm["permalink"]] = (fm, body)
        return found

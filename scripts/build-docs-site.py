#!/usr/bin/env python3
"""Spec 011: builds the documentation-site source (Jekyll, Just the Docs) from the GitHub wiki.

Standard library only. Run with `python3 -I scripts/build-docs-site.py --wiki <wiki checkout>`; then build the site
with Jekyll (see CONTRIBUTING.md, "Documentation site", and specs/011-docs-github-pages/contracts/converter-cli.md).

What it does:
  - every wiki page except _Sidebar/_Footer becomes a page (Home at the site root, others at /<Page>), titled by the
    page name, ordered by _Sidebar.md (or Home first, then alphabetically);
  - wiki links ([[Page]], [[Text|Page]], full wiki URLs) become site links; a link to a missing page is an error;
  - repository files linked as https://github.com/<repo>/(blob|raw)/<ref>/<path> are published from the checkout
    given by --repo: Markdown as Reference pages (followed transitively), the OpenAPI definition as a Redoc page,
    other text files as downloads with a code view; other links stay external;
  - headings get GitHub-compatible ids, and Liquid is disabled on every generated page, so content is literal.

Exit codes: 0 success (warnings allowed), 1 build error, 2 usage error. The output is deterministic.
"""
import argparse
import hashlib
import html
import json
import os
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata
from collections import deque
from urllib.parse import quote, unquote

SCRIPT_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE_DIR = os.path.join(SCRIPT_REPO, "site")
REDOC_DIR = os.path.join(SITE_DIR, "_redoc")
GITHUB = "https://github.com/nazrulworld/fhir-validation-service"
DEFAULT_BASE_PATH = "/fhir-validation-service"
SPECIAL_PAGES = ("_Sidebar", "_Footer")
OUT_MARKER = ".docs-site-build"
SCAFFOLD_SKIP = {"_redoc", "Gemfile", "Gemfile.lock", ".bundle", "vendor", ".jekyll-cache", "_site"}
IMAGE_EXT = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"}
LANGUAGE = {".yaml": "yaml", ".yml": "yaml", ".json": "json", ".sh": "bash"}
# Redocly CLI: version pinned in site/_redoc/package.json (+ lock). No usage telemetry, no update check.
REDOCLY_ENV = {"REDOCLY_TELEMETRY": "off", "REDOCLY_SUPPRESS_UPDATE_NOTICE": "true", "DO_NOT_TRACK": "1"}

FRONT_MATTER_ORDER = ["title", "parent", "nav_order", "has_children", "permalink", "layout", "search_exclude",
                      "wiki_edit_url", "render_with_liquid"]

FENCE_OPEN = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
BACKTICKS = re.compile(r"`+")
ATX_HEADING = re.compile(r"^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$")
WIKI_LINK = re.compile(r"\[\[([^\[\]\n]+?)\]\]")
INLINE_LINK = re.compile(r"(!?)\[((?:[^\[\]\n]|\[[^\[\]\n]*\])*)\]\([ \t]*(<[^<>\n]*>|[^\s()<>]*)"
                         r"((?:[ \t]+(?:\"[^\"\n]*\"|'[^'\n]*'|\([^()\n]*\)))?[ \t]*)\)")
AUTOLINK = re.compile(r"<(https?://[^<>\s]+)>")
REFERENCE_DEF = re.compile(r"^( {0,3}\[[^\]\n]+\]:[ \t]*)(<[^<>\n]*>|\S+)", re.M)
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
GITHUB_URL = re.compile(r"^https?://github\.com/nazrulworld/fhir-validation-service(?:/(.*))?$")


# ---------------------------------------------------------------------------------------------------------------
# Front matter (values are JSON, which is valid YAML)

def front_matter(fields):
    def order(key):
        return (FRONT_MATTER_ORDER.index(key) if key in FRONT_MATTER_ORDER else len(FRONT_MATTER_ORDER), key)
    lines = ["---"] + ["%s: %s" % (k, json.dumps(fields[k], ensure_ascii=False)) for k in sorted(fields, key=order)]
    return "\n".join(lines + ["---"]) + "\n"


def split_front_matter(text):
    if not text.startswith("---\n"):
        return {}, text
    end = text.index("\n---\n", 3)
    fields = {}
    for line in text[4:end].splitlines():
        key, _, value = line.partition(": ")
        fields[key] = json.loads(value)
    return fields, text[end + 5:]


# ---------------------------------------------------------------------------------------------------------------
# Markdown scanning: fenced code blocks and code spans are never changed

def fence_flags(lines):
    """For each line: True when it belongs to a fenced code block (including the fence lines)."""
    flags, closing = [], None
    for line in lines:
        bare = line.rstrip("\r\n")
        if closing is not None:
            flags.append(True)
            if closing.match(bare):
                closing = None
            continue
        match = FENCE_OPEN.match(bare)
        if match and not (match.group(1)[0] == "`" and "`" in match.group(2)):
            marker = match.group(1)
            closing = re.compile(r"^ {0,3}%s{%d,}[ \t]*$" % (re.escape(marker[0]), len(marker)))
            flags.append(True)
        else:
            flags.append(False)
    return flags


def split_code_spans(chunk):
    parts, start, pos = [], 0, 0
    while True:
        opening = BACKTICKS.search(chunk, pos)
        if not opening:
            break
        closing = next((m for m in BACKTICKS.finditer(chunk, opening.end())
                        if len(m.group()) == len(opening.group())), None)
        if closing is None:
            pos = opening.end()
            continue
        if opening.start() > start:
            parts.append(("text", chunk[start:opening.start()]))
        parts.append(("code", chunk[opening.start():closing.end()]))
        start = pos = closing.end()
    if start < len(chunk):
        parts.append(("text", chunk[start:]))
    return parts


def segments(text):
    """Splits Markdown into ("fence" | "code" | "text", source) parts; joining them gives the input back."""
    lines = text.splitlines(keepends=True)
    out, buf, buf_fence = [], [], None
    for line, in_fence in zip(lines, fence_flags(lines)):
        if buf and in_fence != buf_fence:
            out.extend([("fence", "".join(buf))] if buf_fence else split_code_spans("".join(buf)))
            buf = []
        buf_fence = in_fence
        buf.append(line)
    if buf:
        out.extend([("fence", "".join(buf))] if buf_fence else split_code_spans("".join(buf)))
    return out


def rewrite_text(text, func):
    """Applies func to every part outside fenced blocks; code spans are masked so func never sees or changes them."""
    out = []
    for kind, part in _blocks(text):
        if kind == "fence":
            out.append(part)
            continue
        spans = []

        def mask(piece):
            spans.append(piece)
            return "\x00%d\x00" % (len(spans) - 1)
        masked = "".join(mask(p) if k == "code" else p for k, p in split_code_spans(part))
        result = func(masked)
        out.append(re.sub(r"\x00(\d+)\x00", lambda m: spans[int(m.group(1))], result))
    return "".join(out)


def _blocks(text):
    lines = text.splitlines(keepends=True)
    blocks = []
    for line, in_fence in zip(lines, fence_flags(lines)):
        kind = "fence" if in_fence else "text"
        if blocks and blocks[-1][0] == kind:
            blocks[-1][1].append(line)
        else:
            blocks.append((kind, [line]))
    return [(kind, "".join(ls)) for kind, ls in blocks]


# ---------------------------------------------------------------------------------------------------------------
# GitHub-compatible heading ids (research R4)

def heading_text(source):
    text = re.sub(r"\[\[([^\[\]|\n]+)\|([^\[\]\n]+)\]\]", r"\1", source)
    text = re.sub(r"\[\[([^\[\]\n]+)\]\]", lambda m: m.group(1).split("#")[0], text)
    text = re.sub(r"!\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\[[^\]]*\]", r"\1", text)
    text = re.sub(r"<[^>\n]+>", "", text)
    text = text.replace("`", "")
    text = re.sub(r"(?<![\w\\])_+([^_\n]+?)_+(?!\w)", r"\1", text)
    text = re.sub(r"\\([!-/:-@\[-`{-~])", r"\1", text)
    return html.unescape(text).strip()


def github_anchor(text, seen):
    """github-slugger: lower-case, keep letters, marks, digits, connector punctuation, '-' and ' ' (→ '-')."""
    slug = []
    for ch in text.lower():
        if ch == " ":
            slug.append("-")
        elif ch == "-" or unicodedata.category(ch)[0] in "LMN" or unicodedata.category(ch) == "Pc":
            slug.append(ch)
    original = result = "".join(slug)
    while result in seen:
        seen[original] += 1
        result = "%s-%d" % (original, seen[original])
    seen[result] = 0
    return result


def add_heading_ids(text):
    """Adds a kramdown block attribute line {: id="…"} after every ATX heading outside fenced blocks."""
    lines = text.splitlines(keepends=True)
    out, seen, ids = [], {}, []
    for line, in_fence in zip(lines, fence_flags(lines)):
        out.append(line)
        if in_fence:
            continue
        match = ATX_HEADING.match(line.rstrip("\r\n"))
        if not match:
            continue
        anchor = github_anchor(heading_text(match.group(2) or ""), seen)
        if not anchor:
            continue
        ids.append(anchor)
        if not line.endswith("\n"):
            out[-1] = line + "\n"
        out.append('{: id="%s"}\n' % anchor)
    return "".join(out), ids


# ---------------------------------------------------------------------------------------------------------------
# Model

class Diagnostics:
    def __init__(self, stream):
        self.stream = stream
        self.warnings = []
        self.errors = []
        self.annotate = os.environ.get("GITHUB_ACTIONS") == "true"

    def _emit(self, level, source, message):
        print("%s: %s: %s" % (level, source.label, message), file=self.stream)
        if self.annotate:
            print("::%s file=%s::%s" % (level, source.file, message.replace("\n", " ")), file=self.stream)

    def warn(self, source, message):
        self.warnings.append(message)
        self._emit("warning", source, message)

    def error(self, source, message):
        self.errors.append(message)
        self._emit("error", source, message)


class Source:
    def __init__(self, label, file):
        self.label, self.file = label, file


class WikiPage(Source):
    def __init__(self, name, text):
        super().__init__(name, name + ".md")
        self.name = name
        self.text = text
        self.key = page_key(name)
        self.title = name.replace("-", " ")
        self.nav_order = 0
        self.body = ""


class Reference(Source):
    def __init__(self, path, kind, order, discovered_from):
        super().__init__(path, path)
        self.path = path
        self.kind = kind
        self.order = order
        self.discovered_from = discovered_from
        self.title = posixpath.basename(path)
        self.body = ""


def page_key(name):
    return re.sub(r"[ \-]+", "-", name.strip()).lower()


def is_openapi(path, ext):
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except (OSError, UnicodeDecodeError):
        return False
    if ext == ".json":
        try:
            data = json.loads(text)
        except ValueError:
            return False
        return isinstance(data, dict) and "openapi" in data
    return re.search(r"^openapi[ \t]*:", text, re.M) is not None


def classify(rel, full):
    base = posixpath.basename(rel)
    ext = posixpath.splitext(rel)[1].lower()
    if ext == ".md":
        return "markdown"
    if ext in IMAGE_EXT:
        return "asset"
    if ext in (".yaml", ".yml", ".json"):
        return "openapi" if is_openapi(full, ext) else "text"
    if ext in (".sh", ".env") or base.startswith(".env") or base.endswith(".env"):
        return "text"
    return None


# ---------------------------------------------------------------------------------------------------------------
# The build

class Build:
    def __init__(self, args, diag):
        self.args = args
        self.diag = diag
        self.repo = os.path.realpath(args.repo)
        self.wiki = os.path.realpath(args.wiki)
        self.base = args.base_path
        self.commit = args.commit
        self.wiki_commit = args.wiki_commit
        self.pages = {}            # key -> WikiPage
        self.ordered = []          # WikiPage in navigation order
        self.references = {}       # repo path -> Reference
        self.queue = deque()
        self.addresses = {}        # canonical address -> source label
        self.links_rewritten = 0

    # -- addresses ----------------------------------------------------------------------------------------------

    def url(self, path):
        return self.base + quote(path, safe="/-._~!$&'*+,;=:@")

    def claim(self, address, owner, source):
        # "/x/" and "/x/index" are written to the same file
        canonical = address + "index" if address.endswith("/") else address
        if canonical in self.addresses and self.addresses[canonical] != owner:
            self.diag.error(source, "address %s is produced by %s and %s" % (address, self.addresses[canonical], owner))
            return False
        self.addresses[canonical] = owner
        return True

    # -- wiki pages ---------------------------------------------------------------------------------------------

    def collect_pages(self):
        names = sorted(n[:-3] for n in os.listdir(self.wiki) if n.endswith(".md") and
                       os.path.isfile(os.path.join(self.wiki, n)))
        for name in names:
            if name in SPECIAL_PAGES:
                continue
            source = Source(name, name + ".md")
            if not name or name[0] in "._" or re.search(r"[/\\\x00]", name):
                self.diag.error(source, "invalid wiki page name")
                continue
            with open(os.path.join(self.wiki, name + ".md"), encoding="utf-8") as fh:
                page = WikiPage(name, fh.read())
            if page.key in self.pages:
                self.diag.error(page, "same wiki page name: %r and %r" % (self.pages[page.key].name, name))
                continue
            self.pages[page.key] = page
        if "home" not in self.pages:
            self.diag.error(Source("wiki", "Home.md"), "the wiki has no Home page")
            return
        order = []
        sidebar = os.path.join(self.wiki, "_Sidebar.md")
        if os.path.isfile(sidebar):
            with open(sidebar, encoding="utf-8") as fh:
                for key in self.sidebar_keys(fh.read()):
                    if key in self.pages and self.pages[key] not in order:
                        order.append(self.pages[key])
            rest = [p for p in self.pages.values() if p not in order]
        else:
            order.append(self.pages["home"])
            rest = [p for p in self.pages.values() if p.key != "home"]
        order.extend(sorted(rest, key=lambda p: (p.title.casefold(), p.name)))
        for number, page in enumerate(order, 1):
            page.nav_order = number
        self.ordered = order
        for page in order:
            self.claim("/" if page.key == "home" else "/" + page.name, page.label, page)

    def sidebar_keys(self, text):
        keys = []

        def collect(chunk):
            for match in re.finditer(r"\[\[([^\[\]\n]+?)\]\]|\]\(\s*<?([^\s)>]+)", chunk):
                if match.group(1):
                    target = match.group(1).split("|", 1)[-1]
                    keys.append(page_key(target.split("#")[0]))
                else:
                    wiki = self.wiki_target(match.group(2))
                    if wiki is not None:
                        keys.append(page_key(wiki[0]))
            return chunk
        rewrite_text(text, collect)
        return keys

    def page_url(self, page, anchor=""):
        path = "/" if page.key == "home" else "/" + page.name
        return self.url(path) + anchor

    def wiki_target(self, dest):
        """(page name, '#anchor') for a full wiki URL; None otherwise."""
        bare, anchor = split_anchor(dest)
        match = GITHUB_URL.match(bare)
        if not match:
            return None
        rest = (match.group(1) or "").rstrip("/")
        if rest == "wiki":
            return "Home", anchor
        if rest.startswith("wiki/") and "/" not in rest[5:]:
            return unquote(rest[5:]), anchor
        return None

    # -- link rewriting -----------------------------------------------------------------------------------------

    def rewrite(self, text, source):
        def links(chunk):
            chunk = INLINE_LINK.sub(lambda m: self.inline_link(m, source), chunk)
            chunk = AUTOLINK.sub(lambda m: self.autolink(m, source), chunk)
            chunk = REFERENCE_DEF.sub(lambda m: self.reference_def(m, source), chunk)
            return WIKI_LINK.sub(lambda m: self.wiki_link(m, source), chunk)
        return rewrite_text(text, links)

    def inline_link(self, match, source):
        image, label, dest, title = match.groups()
        wrapped = dest.startswith("<")
        outcome = self.resolve(dest[1:-1] if wrapped else dest, source, image=bool(image))
        if outcome is None:
            return match.group(0)
        self.links_rewritten += 1
        if outcome == "":
            return label
        return "%s[%s](%s%s)" % (image, label, "<%s>" % outcome if wrapped else outcome, title)

    def autolink(self, match, source):
        outcome = self.resolve(match.group(1), source)
        if not outcome:
            return match.group(0)
        self.links_rewritten += 1
        return "[%s](%s)" % (match.group(1), outcome)

    def reference_def(self, match, source):
        dest = match.group(2)
        wrapped = dest.startswith("<")
        outcome = self.resolve(dest[1:-1] if wrapped else dest, source)
        if not outcome:
            return match.group(0)
        self.links_rewritten += 1
        return match.group(1) + outcome

    def wiki_link(self, match, source):
        inner = match.group(1)
        label, _, target = inner.partition("|") if "|" in inner else (inner, "", inner)
        name, _, anchor = target.strip().partition("#")
        label = label.strip() if "|" in inner else name.strip()
        page = self.pages.get(page_key(name))
        if page is None:
            self.diag.error(source, 'wiki link to missing page "%s"' % name.strip())
            return match.group(0)
        self.links_rewritten += 1
        return "[%s](%s)" % (label, self.page_url(page, "#" + anchor if anchor else ""))

    def resolve(self, dest, source, image=False):
        """New destination, "" to keep the text only, or None to leave the link unchanged."""
        if not dest or dest.startswith("#") or dest.startswith("//"):
            return None
        if GITHUB_URL.match(split_anchor(dest)[0]):
            return self.resolve_github(dest, source)
        if SCHEME.match(dest):
            return None
        if isinstance(source, Reference):
            return self.resolve_relative(dest, source)
        if isinstance(source, WikiPage) and not dest.startswith("/"):
            bare, anchor = split_anchor(dest)
            page = self.pages.get(page_key(unquote(bare)))
            if page is not None:
                return self.page_url(page, anchor)
            if os.path.isfile(os.path.join(self.wiki, unquote(bare))):
                return GITHUB + "/wiki/" + bare + anchor
        return None

    def resolve_github(self, dest, source):
        wiki = self.wiki_target(dest)
        if wiki is not None:
            name, anchor = wiki
            page = self.pages.get(page_key(name))
            if page is None:
                self.diag.error(source, 'wiki link to missing page "%s"' % name)
                return None
            return self.page_url(page, anchor)
        bare, anchor = split_anchor(dest)
        bare = bare.split("?", 1)[0]
        match = re.match(r"^https?://github\.com/nazrulworld/fhir-validation-service/(?:blob|raw)/[^/]+/(.+)$", bare)
        if not match:
            return None
        rel = self.normalise(unquote(match.group(1)).rstrip("/"))
        if rel is None:
            return None
        full = os.path.join(self.repo, rel)
        if not os.path.lexists(full):
            self.diag.warn(source, "link to missing file %s" % rel)
            return None
        if not os.path.isfile(full):
            return None
        kind = classify(rel, full)
        if kind not in ("markdown", "openapi", "text"):
            return None
        ref = self.reference(rel, kind, source)
        return None if ref is None else self.reference_url(ref) + anchor

    def resolve_relative(self, dest, source):
        bare, anchor = split_anchor(dest)
        bare = bare.split("?", 1)[0]
        path = unquote(bare)
        joined = path.lstrip("/") if path.startswith("/") else posixpath.join(posixpath.dirname(source.path), path)
        rel = self.normalise(joined)
        if rel is None:
            self.diag.warn(source, "link outside the repository: %s" % dest)
            return ""
        full = os.path.join(self.repo, rel) if rel != "." else self.repo
        if os.path.isdir(full):
            tree = "%s/tree/%s/%s" % (GITHUB, self.commit, quote(rel if rel != "." else "", safe="/-._~"))
            return tree.rstrip("/") + anchor
        if not os.path.isfile(full):
            self.diag.warn(source, "link to missing file %s" % rel)
            return "%s/blob/%s/%s%s" % (GITHUB, self.commit, quote(rel, safe="/-._~"), anchor)
        kind = classify(rel, full)
        if kind is None:
            return "%s/blob/%s/%s%s" % (GITHUB, self.commit, quote(rel, safe="/-._~"), anchor)
        ref = self.reference(rel, kind, source)
        return None if ref is None else self.reference_url(ref) + anchor

    def normalise(self, path):
        rel = posixpath.normpath(path)
        if rel == ".." or rel.startswith("../") or posixpath.isabs(rel):
            return None
        full = os.path.realpath(os.path.join(self.repo, rel))
        if full != self.repo and not full.startswith(self.repo + os.sep):
            return None
        return rel

    # -- reference files ----------------------------------------------------------------------------------------

    def reference(self, rel, kind, source):
        if rel in self.references:
            return self.references[rel]
        ref = Reference(rel, kind, len(self.references), source.label)
        owner = rel
        ok = self.claim(self.reference_url(ref, address=True), owner, source)
        if kind in ("openapi", "text"):
            ok = self.claim("/reference/" + rel, owner, source) and ok
        if not ok:
            return None
        self.references[rel] = ref
        if kind != "asset":
            self.queue.append(ref)
        return ref

    def reference_url(self, ref, address=False):
        if ref.kind == "markdown":
            path = "/reference/" + ref.path[:-3]
        elif ref.kind == "asset":
            path = "/reference/" + ref.path
        else:
            path = "/reference/" + ref.path + ".html"
        return path if address else self.url(path)

    def process_queue(self):
        while self.queue:
            ref = self.queue.popleft()
            full = os.path.join(self.repo, ref.path)
            if ref.kind == "markdown":
                with open(full, encoding="utf-8") as fh:
                    text = fh.read()
                for line, in_fence in zip(text.splitlines(), fence_flags(text.splitlines())):
                    match = None if in_fence else ATX_HEADING.match(line)
                    if match and match.group(2):
                        ref.title = heading_text(match.group(2))
                        break
                ref.body = self.rewrite(text, ref)
            elif ref.kind == "openapi":
                ref.title = "API reference"

    def collect(self):
        self.collect_pages()
        for page in self.ordered:
            page.body = self.rewrite(page.text, page)
        self.process_queue()

    # -- output -------------------------------------------------------------------------------------------------

    def write(self, out):
        prepare_out(out)
        written, downloads = [], []
        pages_dir = os.path.join(out, "pages")
        os.makedirs(pages_dir)

        def write_file(rel, content):
            path = os.path.join(out, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(content)
            written.append(rel)

        for page in self.ordered:
            body, _ = add_heading_ids(page.body)
            fields = {"title": page.title, "nav_order": page.nav_order,
                      "permalink": "/" if page.key == "home" else "/" + page.name,
                      "wiki_edit_url": "%s/wiki/%s/_edit" % (GITHUB, quote(page.name, safe="-._~")),
                      "render_with_liquid": False}
            write_file("pages/guide-%03d.md" % page.nav_order, front_matter(fields) + body)

        reference_order = len(self.ordered) + 1
        refs = sorted(self.references.values(), key=lambda r: r.order)
        if refs:
            write_file("pages/reference.md", front_matter({
                "title": "Reference", "nav_order": reference_order, "has_children": True,
                "permalink": "/reference/", "render_with_liquid": False}) + (
                "# Reference\n\nRepository files the guide links to, published from commit [`%s`](%s/commit/%s).\n"
                % (self.commit[:7], GITHUB, self.commit)))
        nav = 0
        for ref in refs:
            source = os.path.join(self.repo, ref.path)
            if ref.kind in ("asset", "openapi", "text"):
                target = os.path.join(out, "_downloads", "reference", ref.path)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                shutil.copyfile(source, target)
                downloads.append("reference/" + ref.path)
            if ref.kind == "asset":
                continue
            nav += 1
            fields = {"title": ref.title, "parent": "Reference", "nav_order": nav,
                      "permalink": self.reference_url(ref, address=True), "render_with_liquid": False}
            if ref.kind == "markdown":
                body, _ = add_heading_ids(ref.body)
                write_file("pages/ref-%03d.md" % nav, front_matter(fields) + body)
            elif ref.kind == "text":
                write_file("pages/ref-%03d.md" % nav, front_matter(fields) + self.code_view(ref, source))
            else:
                fields["search_exclude"] = True
                html_page = None if self.args.no_redoc else self.render_redoc(ref, source)
                if html_page is None:
                    write_file("pages/ref-%03d.md" % nav, front_matter(fields) + self.redoc_placeholder(ref))
                else:
                    fields["layout"] = None
                    write_file("pages/ref-%03d.html" % nav, front_matter(fields) + html_page)

        config = {
            "baseurl": self.base,
            "build": {"commit": self.commit, "commit_short": self.commit[:7],
                      "wiki_commit": self.wiki_commit, "wiki_commit_short": self.wiki_commit[:7]},
            "docs_downloads": sorted(downloads),
        }
        write_file("_config.generated.yml", json.dumps(config, indent=2, ensure_ascii=False) + "\n")
        return written

    def code_view(self, ref, source):
        with open(source, encoding="utf-8") as fh:
            content = fh.read()
        if not content.endswith("\n"):
            content += "\n"
        fence = "`" * max(3, 1 + max((len(m) for m in re.findall(r"`+", content)), default=0))
        language = LANGUAGE.get(posixpath.splitext(ref.path)[1].lower(), "bash")
        return ("# %s\n\nRepository file `%s` at commit [`%s`](%s/blob/%s/%s).\n\n[Download %s](%s)\n\n%s%s\n%s%s\n"
                % (posixpath.basename(ref.path), ref.path, self.commit[:7], GITHUB, self.commit,
                   quote(ref.path, safe="/-._~"), posixpath.basename(ref.path), self.url("/reference/" + ref.path),
                   fence, language, content, fence))

    def redoc_placeholder(self, ref):
        return ("# API reference\n\nThe rendered API reference is not part of this build (`--no-redoc`).\n\n"
                "[Download %s](%s)\n" % (posixpath.basename(ref.path), self.url("/reference/" + ref.path)))

    def render_redoc(self, ref, source):
        if not redoc_installed():
            install = subprocess.run(["npm", "ci", "--prefix", REDOC_DIR, "--ignore-scripts", "--no-audit",
                                      "--no-fund"], capture_output=True, text=True, env=dict(os.environ, **REDOCLY_ENV))
            if install.returncode != 0:
                self.diag.error(ref, "Redocly build-docs failed (exit %d): npm ci failed: %s"
                                % (install.returncode, (install.stderr or "").strip()[-500:]))
                return None
            mark_redoc_installed()
        with tempfile.TemporaryDirectory() as tmp:
            output = os.path.join(tmp, "redoc.html")
            argv = [os.path.join(REDOC_DIR, "node_modules", ".bin", "redocly"), "build-docs", source,
                    "--output", output, "--inlineBundle",
                    "--template", os.path.join(REDOC_DIR, "template.hbs"),
                    "--templateOptions.siteHome=" + self.url("/"),
                    "--templateOptions.yamlUrl=" + self.url("/reference/" + ref.path),
                    "--templateOptions.yamlName=" + posixpath.basename(ref.path),
                    "--templateOptions.commit=" + self.commit,
                    "--templateOptions.commitShort=" + self.commit[:7],
                    "--templateOptions.github=" + GITHUB]
            result = subprocess.run(argv, capture_output=True, text=True, env=dict(os.environ, **REDOCLY_ENV))
            if result.returncode != 0 or not os.path.isfile(output):
                self.diag.error(ref, "Redocly build-docs failed (exit %d)" % result.returncode)
                for line in (result.stderr or "").strip().splitlines()[-20:]:
                    print("  " + line, file=self.diag.stream)
                return None
            with open(output, encoding="utf-8") as fh:
                return fh.read()

    def summary(self, stream):
        counts = {k: sum(1 for r in self.references.values() if r.kind == k)
                  for k in ("markdown", "openapi", "text", "asset")}
        print("pages: %d" % len(self.ordered), file=stream)
        print("reference: markdown=%(markdown)d openapi=%(openapi)d text=%(text)d assets=%(asset)d" % counts,
              file=stream)
        print("links rewritten: %d" % self.links_rewritten, file=stream)
        print("warnings: %d" % len(self.diag.warnings), file=stream)


def split_anchor(dest):
    bare, sep, anchor = dest.partition("#")
    return bare, sep + anchor


def redoc_lock_digest():
    with open(os.path.join(REDOC_DIR, "package-lock.json"), "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def redoc_installed():
    stamp = os.path.join(REDOC_DIR, "node_modules", ".fv-lock-sha256")
    try:
        with open(stamp, encoding="utf-8") as fh:
            return fh.read().strip() == redoc_lock_digest()
    except OSError:
        return False


def mark_redoc_installed():
    modules = os.path.join(REDOC_DIR, "node_modules")
    if os.path.isdir(modules):
        with open(os.path.join(modules, ".fv-lock-sha256"), "w", encoding="utf-8") as fh:
            fh.write(redoc_lock_digest() + "\n")


# ---------------------------------------------------------------------------------------------------------------
# Command line

def default_out(repo):
    return os.path.join(repo, "target", "docs-site", "src")


def out_is_safe(out, repo):
    if not os.path.exists(out):
        return True
    if not os.path.isdir(out):
        return False
    target = os.path.join(os.path.realpath(repo), "target") + os.sep
    return (os.path.realpath(out) + os.sep).startswith(target) or not os.listdir(out) \
        or os.path.isfile(os.path.join(out, OUT_MARKER))


def prepare_out(out):
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(SITE_DIR, out, ignore=lambda d, names: [n for n in names if os.path.realpath(d) == SITE_DIR
                                                             and n in SCAFFOLD_SKIP])
    with open(os.path.join(out, OUT_MARKER), "w", encoding="utf-8") as fh:
        fh.write("Generated by scripts/build-docs-site.py; deleted and recreated on every run.\n")


def git_head(path):
    try:
        result = subprocess.run(["git", "-C", path, "rev-parse", "HEAD"], capture_output=True, text=True)
    except OSError:
        return None
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else None


def parse_args(argv):
    parser = argparse.ArgumentParser(prog="build-docs-site.py", description=__doc__.split("\n")[0])
    parser.add_argument("--wiki", required=True, help="checkout of fhir-validation-service.wiki.git")
    parser.add_argument("--repo", default=SCRIPT_REPO, help="main-repository checkout (default: this repository)")
    parser.add_argument("--out", help="Jekyll source to create (default: <repo>/target/docs-site/src)")
    parser.add_argument("--commit", help="main-repository commit (default: $GITHUB_SHA, else git HEAD of --repo)")
    parser.add_argument("--wiki-commit", help="wiki revision (default: git HEAD of --wiki)")
    parser.add_argument("--base-path", default=DEFAULT_BASE_PATH, help="site base path (default: %(default)s)")
    parser.add_argument("--no-redoc", action="store_true", help="write a placeholder API reference page")
    parser.add_argument("--list-sources", action="store_true",
                        help="print the repository paths the site would publish and exit")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.wiki):
        parser.error("--wiki %s is not a directory" % args.wiki)
    if not os.path.isdir(args.repo):
        parser.error("--repo %s is not a directory" % args.repo)
    if args.base_path and (not args.base_path.startswith("/") or args.base_path.endswith("/")):
        parser.error("--base-path must start with '/' and not end with '/'")
    args.out = os.path.abspath(args.out or default_out(args.repo))
    if not args.list_sources and not out_is_safe(args.out, args.repo):
        parser.error("--out %s is not empty and was not created by this script; refusing to delete it" % args.out)
    args.commit = args.commit or os.environ.get("GITHUB_SHA") or git_head(args.repo)
    args.wiki_commit = args.wiki_commit or git_head(args.wiki)
    for name in ("commit", "wiki_commit"):
        if not getattr(args, name):
            parser.error("cannot determine --%s (not a git checkout); pass it explicitly" % name.replace("_", "-"))
    return args


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    diag = Diagnostics(sys.stderr)
    build = Build(args, diag)
    build.collect()
    if args.list_sources:
        for path in sorted(build.references):
            print(path)
        return 1 if diag.errors else 0
    if diag.errors:
        build.summary(sys.stdout)
        return 1
    build.write(args.out)
    build.summary(sys.stdout)
    return 1 if diag.errors else 0


if __name__ == "__main__":
    sys.exit(main())

"""Structure-aware, XSS-safe HTML diff for Quill article revisions.

Diffs the TEXT content of block elements only; output markup is rebuilt from
escaped text, so tags can never be split and injected HTML can never execute.
Zero new deps: BeautifulSoup (requirements.txt) + difflib (stdlib).
"""
import html
from difflib import SequenceMatcher

from bs4 import BeautifulSoup

_BLOCK_TAGS = ("p", "li", "h1", "h2", "h3", "h4", "h5", "h6",
               "blockquote", "pre", "td", "th")

INS = '<ins class="bg-green-200/60 dark:bg-green-900/60 no-underline rounded px-1 font-semibold text-green-950 dark:text-green-200">{}</ins>'
DEL = '<del class="bg-red-200/60 dark:bg-red-900/60 line-through rounded px-1 text-red-950 dark:text-red-300">{}</del>'


def _block_text(node) -> str:
    """Text of a block PLUS structural tokens for links/media.

    Asset-change blindness fix: if a block contains <a>/<img>, fold their
    href/src (and alt) into the token stream so SequenceMatcher flags an
    altered link target or swapped image even when the visible text is
    byte-for-byte identical. Tokens are plain text -> still tag-safe.
    """
    parts = [node.get_text(" ", strip=True)]
    for a in node.find_all("a"):
        href = (a.get("href") or "").strip()
        if href:
            parts.append(f"[Link: {href}]")
    for img in node.find_all("img"):
        src = (img.get("src") or "").strip()
        alt = (img.get("alt") or "").strip()
        if src:
            parts.append(f"[Image: {src}]" + (f" [Alt: {alt}]" if alt else ""))
    return " ".join(p for p in parts if p)


def _blocks(raw_html: str) -> list[str]:
    """Flatten a Quill document into an ordered list of block text strings,
    each enriched with link/media structural tokens (see _block_text)."""
    soup = BeautifulSoup(raw_html or "", "html.parser")
    nodes = soup.find_all(_BLOCK_TAGS)
    if not nodes:                              # plain/inline-only content
        text = _block_text(soup)              # still capture top-level links/images
        return [text] if text else []
    out = []
    for n in nodes:
        t = _block_text(n)
        if t:
            out.append(t)
    return out


def _word_diff(old: str, new: str) -> str:
    """Inline word-level diff of two block strings -> safe HTML."""
    o, n = old.split(), new.split()
    sm = SequenceMatcher(a=o, b=n, autojunk=False)
    parts = []
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            parts.append(html.escape(" ".join(o[i1:i2])))
        elif op == "delete":
            parts.append(DEL.format(html.escape(" ".join(o[i1:i2]))))
        elif op == "insert":
            parts.append(INS.format(html.escape(" ".join(n[j1:j2]))))
        elif op == "replace":
            parts.append(DEL.format(html.escape(" ".join(o[i1:i2]))))
            parts.append(INS.format(html.escape(" ".join(n[j1:j2]))))
    return " ".join(p for p in parts if p)


def diff_html(old_html: str, new_html: str) -> dict:
    """Return {'html': <fragment>, 'added': int, 'removed': int}.

    `added`/`removed` are block counts -- used for the SSE summary badge.
    """
    a, b = _blocks(old_html), _blocks(new_html)
    sm = SequenceMatcher(a=a, b=b, autojunk=False)
    rows, added, removed = [], 0, 0
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            for blk in a[i1:i2]:
                rows.append(f'<div class="diff-line diff-equal flex items-start gap-2 px-3 py-1.5 text-gray-600 dark:text-zinc-400 border-l-2 border-transparent"><span class="select-none text-gray-300 dark:text-zinc-700 w-4 font-mono text-center">•</span><span class="flex-1">{html.escape(blk)}</span></div>')
        elif op == "delete":
            removed += (i2 - i1)
            for blk in a[i1:i2]:
                rows.append(f'<div class="diff-line diff-delete flex items-start gap-2 border-l-2 border-red-500 bg-red-50/40 dark:bg-red-950/20 px-3 py-1.5 rounded-r my-1 text-red-900 dark:text-red-200"><span class="select-none text-red-400 font-bold w-4 font-mono text-center">-</span><span class="flex-1">{html.escape(blk)}</span></div>')
        elif op == "insert":
            added += (j2 - j1)
            for blk in b[j1:j2]:
                rows.append(f'<div class="diff-line diff-insert flex items-start gap-2 border-l-2 border-green-500 bg-green-50/40 dark:bg-green-950/20 px-3 py-1.5 rounded-r my-1 text-green-900 dark:text-green-200"><span class="select-none text-green-400 font-bold w-4 font-mono text-center">+</span><span class="flex-1">{html.escape(blk)}</span></div>')
        elif op == "replace":
            added += (j2 - j1)
            removed += (i2 - i1)
            # Pair blocks positionally for inline word diff; spill extras as add/del.
            for k in range(max(i2 - i1, j2 - j1)):
                ob = a[i1 + k] if i1 + k < i2 else ""
                nb = b[j1 + k] if j1 + k < j2 else ""
                rows.append(f'<div class="diff-line diff-modified flex items-start gap-2 border-l-2 border-blue-500 bg-blue-50/20 dark:bg-blue-950/10 px-3 py-1.5 rounded-r my-1 text-gray-800 dark:text-zinc-200"><span class="select-none text-blue-400 font-bold w-4 font-mono text-center">✎</span><span class="flex-1">{_word_diff(ob, nb)}</span></div>')
    return {"html": "\n".join(rows), "added": added, "removed": removed}

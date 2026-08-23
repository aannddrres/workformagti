"""
Attachments: the legacy pod's filesystem -> Oracle's stored_files (V31).

This is the one part of the migration with no table on the source side. The
Python portal wrote uploads to `/app/uploads` on local disk; the Java backend
keeps them as BLOBs in Oracle, because a pod-local directory loses every
attachment on restart (audit PR-03). So the ETL walks the directory, and the
coverage check answers the question that actually matters on cutover day:
*is every attachment the content still points at present in the bytes we
carried over?*

Two independent failures are reported separately, because they mean opposite
things:

* referenced-but-missing -- an article links to a file that is already gone.
  These are pre-existing 404s inherited from the pod-restart bug, not damage
  the migration caused; migrating changes nothing about them, but the owner
  should see the list.
* present-but-unreferenced -- a file nothing links to. Usually a draft
  attachment or a since-edited article. They are migrated anyway: a file the
  portal accepted is evidence, and deciding to drop it is a retention call.
"""
from __future__ import annotations

import mimetypes
import os
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Iterator

from .db import BATCH

UPLOAD_REFERENCE = re.compile(r"/uploads/([A-Za-z0-9_.\-]+)")
FILENAME_MAX_CHARS = 100  # stored_files.filename VARCHAR2(100 CHAR), V31

# Magic bytes, in the same spirit as FileTypeVerifier (SEC-09): the extension
# is a claim, the first bytes are evidence. Only used to pick a content type
# for the migrated row -- the ETL never rejects a file the portal accepted.
MAGIC: tuple[tuple[bytes, str], ...] = (
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
    (b"%PDF-", "application/pdf"),
    (b"PK\x03\x04", "application/zip"),
)


@dataclass
class UploadsReport:
    directory: str
    files_found: int = 0
    bytes_total: int = 0
    rows_written: int = 0
    referenced: int = 0
    missing: list[str] = field(default_factory=list)
    unreferenced: list[str] = field(default_factory=list)
    oversized_names: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.oversized_names


def sniff_content_type(path: str) -> str:
    try:
        with open(path, "rb") as handle:
            head = handle.read(8)
    except OSError:
        head = b""
    for prefix, content_type in MAGIC:
        if head.startswith(prefix):
            return content_type
    guessed, _ = mimetypes.guess_type(path)
    return guessed or "application/octet-stream"


def scan(directory: str) -> list[str]:
    if not os.path.isdir(directory):
        return []
    return sorted(
        name
        for name in os.listdir(directory)
        if os.path.isfile(os.path.join(directory, name)) and not name.startswith(".")
    )


def referenced_filenames(source) -> set[str]:
    """Every `/uploads/...` name the surviving content still points at."""
    found: set[str] = set()
    queries = (
        ("articles", ("attachment_url", "content")),
        ("news", ("attachment_url", "content")),
        ("news_history", ("attachment_url", "content")),
        ("article_history", ("content",)),
    )
    for table, columns in queries:
        if not source.table_exists(table):
            continue
        for row in source.stream(table, list(columns), "id", BATCH):
            for value in row:
                if not value:
                    continue
                found.update(UPLOAD_REFERENCE.findall(str(value)))
    return found


def _rows(directory: str, names: list[str]) -> Iterator[tuple]:
    for name in names:
        path = os.path.join(directory, name)
        with open(path, "rb") as handle:
            content = handle.read()
        created = datetime.fromtimestamp(os.path.getmtime(path)).replace(microsecond=0)
        # uploaded_by stays NULL: the legacy filesystem records no uploader,
        # and guessing one from an article's author would put a fabricated
        # fact into an evidence table.
        yield (name, sniff_content_type(path), len(content), None, created, content)


def load(source, target, directory: str, *, dry_run: bool = False, batch: int = 50) -> UploadsReport:
    report = UploadsReport(directory=directory)
    names = scan(directory)
    report.files_found = len(names)
    report.oversized_names = [n for n in names if len(n) > FILENAME_MAX_CHARS]

    references = referenced_filenames(source)
    report.referenced = len(references)
    on_disk = set(names)
    report.missing = sorted(references - on_disk)
    report.unreferenced = sorted(on_disk - references)

    if report.oversized_names:
        return report  # fail-closed: the load would be rejected by Oracle anyway

    columns = ["filename", "content_type", "byte_size", "uploaded_by", "created_at", "content"]
    lobs = {"content": "blob"}
    chunk: list[tuple] = []
    for row in _rows(directory, names):
        report.bytes_total += row[2]
        chunk.append(row)
        if len(chunk) >= batch:
            if not dry_run:
                target.insert_many("stored_files", columns, lobs, chunk)
                report.rows_written += len(chunk)
            chunk = []
    if chunk and not dry_run:
        target.insert_many("stored_files", columns, lobs, chunk)
        report.rows_written += len(chunk)
    if not dry_run:
        target.commit()
    return report


def verify(target, report: UploadsReport) -> dict[str, Any]:
    """Post-load: the row count and byte total Oracle actually holds."""
    rows = target.count("stored_files")
    total = target.scalar("SELECT COALESCE(SUM(byte_size), 0) FROM stored_files") or 0
    ok = rows == report.files_found and int(total) == report.bytes_total
    return {
        "ok": ok,
        "rows": rows,
        "bytes": int(total),
        "detail": (
            f"{rows} file(s) / {int(total)} bytes in stored_files vs "
            f"{report.files_found} file(s) / {report.bytes_total} bytes on disk"
        ),
    }

"""
The run report.

Two artefacts per run, written side by side: a Georgian Markdown document
for the change record, and the same facts as JSON so a later run (or CI) can
diff two cutover rehearsals mechanically.

The report always states which target produced it. A green rehearsal against
the SQLite target proves the plan's wiring; only a green run against Oracle
proves the migration -- and a report that did not say which one it was would
invite the first from being read as the second.
"""
from __future__ import annotations

import json
import os
from dataclasses import asdict, is_dataclass
from datetime import datetime
from typing import Any

from .spec import DECISION_REQUIRED, NOT_MIGRATED, SOURCE_ONLY


def _plain(value: Any) -> Any:
    if is_dataclass(value):
        return {k: _plain(v) for k, v in asdict(value).items()}
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def build(
    *,
    target_kind: str,
    source_dsn: str,
    dry_run: bool,
    gated_included: list[str],
    checks: list = (),
    load_result: Any = None,
    uploads_result: Any = None,
    uploads_verify: dict | None = None,
    reconcile_result: Any = None,
) -> dict:
    return {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "target": target_kind,
        "source": _redact(source_dsn),
        "dry_run": dry_run,
        "proves_oracle_behaviour": target_kind == "oracle",
        "decision_gated": {
            "available": sorted(DECISION_REQUIRED),
            "included": sorted(gated_included),
            "skipped": sorted(set(DECISION_REQUIRED) - set(gated_included)),
        },
        # `status` is a property, so asdict() leaves it out -- and the
        # Markdown renderer only reads it for a check that is *not* ok, which
        # is why every green rehearsal missed it.
        "preflight": [{**_plain(c), "status": getattr(c, "status", "FAIL")} for c in checks],
        "load": _plain(load_result) if load_result else None,
        "uploads": _plain(uploads_result) if uploads_result else None,
        "uploads_verify": uploads_verify,
        "reconcile": _plain(reconcile_result) if reconcile_result else None,
        "not_migrated": NOT_MIGRATED,
        "source_only": SOURCE_ONLY,
    }


def _redact(dsn: str) -> str:
    """Never write a password into the change record."""
    if "://" not in dsn:
        return dsn
    scheme, rest = dsn.split("://", 1)
    if "@" in rest:
        credentials, host = rest.split("@", 1)
        user = credentials.split(":", 1)[0]
        return f"{scheme}://{user}:***@{host}"
    return dsn


def _verdict(report: dict) -> str:
    if report["dry_run"]:
        return "🔎 მშრალი გაშვება — ჩაწერა არ მომხდარა"
    reconcile = report.get("reconcile")
    if reconcile is None:
        return "⏳ დატვირთვა შესრულდა, შედარება ჯერ არ გაშვებულა"
    tables_ok = all(t["source_rows"] == t["target_rows"] and not t["mismatched"] for t in reconcile["tables"])
    audit = reconcile.get("audit_hash") or {}
    if not tables_ok or not audit.get("ok", True):
        return "❌ აღმოჩენილია განსხვავება — იხილეთ ქვემოთ"
    integrity = reconcile.get("audit_chain_integrity") or {}
    if not integrity.get("ok", True):
        return "❌ აუდიტის ჯაჭვის მთლიანობა დარღვეულია — იხილეთ ქვემოთ"
    if audit.get("skipped"):
        # Never let a skipped check read as a passed one.
        if integrity and not integrity.get("skipped"):
            return "✅ ცხრილები ემთხვევა; ჯაჭვი დამოუკიდებლად გადამოწმდა — პირდაპირი შედარება არ იყო გამოსადეგი"
        return "✅ ცხრილები ემთხვევა — ⚠️ აუდიტის ჰეშ-ჯაჭვი შემოწმებული არ არის"
    return "✅ ყველა ცხრილი და აუდიტის ჰეშ-ჯაჭვი ემთხვევა"


def to_markdown(report: dict) -> str:
    lines: list[str] = []
    add = lines.append

    add("# მონაცემთა მიგრაცია Postgres → Oracle — გაშვების ანგარიში")
    add("")
    add(f"**თარიღი:** {report['generated_at']}")
    add(f"**წყარო:** `{report['source']}`")
    add(f"**სამიზნე:** `{report['target']}`")
    add(f"**რეჟიმი:** {'მშრალი გაშვება (dry run)' if report['dry_run'] else 'რეალური დატვირთვა'}")
    add("")
    add(f"## შედეგი: {_verdict(report)}")
    add("")
    if not report["proves_oracle_behaviour"]:
        add(
            "> ⚠️ ეს გაშვება **SQLite-რეპეტიციაა**. ის ამოწმებს გეგმის სისწორეს, "
            "თანმიმდევრობასა და შედარების ლოგიკას — მაგრამ **არ ამტკიცებს** Oracle-ის "
            "ქცევას: არც IDENTITY, არც V28-ის ჰეშ-ჯაჭვის trigger, არც VARCHAR2-ის "
            "სიგანე აქ არ მოქმედებს."
        )
        add("")

    gated = report["decision_gated"]
    if gated["skipped"]:
        add("## გადაწყვეტილების მომლოდინე ცხრილები")
        add("")
        add("ეს ცხრილები **განზრახ არ გადმოვიდა**. გადაწყვეტილება მფლობელისა და DPO-ისაა:")
        add("")
        for name in gated["skipped"]:
            add(f"- **`{name}`** — {DECISION_REQUIRED[name]}")
        add("")
        if gated["included"]:
            add(f"ჩართული იყო: {', '.join(f'`{n}`' for n in gated['included'])}")
            add("")

    checks = report.get("preflight") or []
    if checks:
        failed = [c for c in checks if not c["ok"] and c["fatal"]]
        warned = [c for c in checks if not c["ok"] and not c["fatal"]]
        add("## Preflight")
        add("")
        add(f"სულ {len(checks)} შემოწმება — {len(failed)} ბლოკერი, {len(warned)} გაფრთხილება.")
        add("")
        for check in checks:
            if check["ok"] and not failed:
                continue
            if check["ok"]:
                continue
            add(f"- **{check['status']}** `{check['name']}` — {check['detail']}")
            if check["samples"]:
                add(f"  - ნიმუშები: `{check['samples']}`")
        if not failed and not warned:
            add("- ყველა შემოწმება წარმატებულია.")
        add("")

    load = report.get("load")
    if load:
        add("## დატვირთვა")
        add("")
        add("| ცხრილი | წაკითხული | ჩაწერილი | self-FK | წამი |")
        add("|---|---:|---:|---:|---:|")
        for table in load["tables"]:
            add(
                f"| `{table['target']}` | {table['rows_read']} | {table['rows_written']} | "
                f"{table['self_fk_patched']} | {table['seconds']} |"
            )
        add("")
        for step in load["global_steps"]:
            add(f"- {step}")
        add("")

    uploads = report.get("uploads")
    if uploads:
        add("## დანართები (filesystem → `stored_files`)")
        add("")
        add(f"- დისკზე ნაპოვნი: **{uploads['files_found']}** ფაილი, {uploads['bytes_total']} ბაიტი")
        add(f"- ჩაწერილი: **{uploads['rows_written']}**")
        add(f"- კონტენტიდან მითითებული უნიკალური ფაილი: {uploads['referenced']}")
        if uploads["missing"]:
            add(
                f"- ❗ **{len(uploads['missing'])} ფაილი მითითებულია, მაგრამ დისკზე არ არის** "
                "(ეს მემკვიდრეობითი 404-ებია, არა მიგრაციის დაზიანება): "
                f"`{uploads['missing'][:10]}`"
            )
        if uploads["unreferenced"]:
            add(f"- {len(uploads['unreferenced'])} ფაილზე ბმული აღარ არსებობს — მაინც გადმოვიდა")
        if uploads["oversized_names"]:
            add(f"- ❌ სახელი 100 სიმბოლოზე გრძელია: `{uploads['oversized_names']}`")
        if report.get("uploads_verify"):
            add(f"- გადამოწმება: {report['uploads_verify']['detail']}")
        add("")

    reconcile = report.get("reconcile")
    if reconcile:
        add("## შედარება (reconciliation)")
        add("")
        add("| ცხრილი | წყარო | სამიზნე | განსხვავებული | აკლია | ზედმეტი |")
        add("|---|---:|---:|---:|---:|---:|")
        for table in reconcile["tables"]:
            mark = "" if table["source_rows"] == table["target_rows"] and not table["mismatched"] else " ❌"
            add(
                f"| `{table['target']}`{mark} | {table['source_rows']} | {table['target_rows']} | "
                f"{table['mismatched']} | {len(table['missing_in_target'])} | "
                f"{len(table['extra_in_target'])} |"
            )
        add("")
        audit = reconcile.get("audit_hash") or {}
        if audit:
            add("### აუდიტის ჰეშ-ჯაჭვი")
            add("")
            add(f"{'✅' if audit['ok'] else '❌'} {audit['detail']}")
            if audit.get("samples"):
                add("")
                add(f"განსხვავებული ID-ები: `{[s['id'] for s in audit['samples']]}`")
            add("")
        integrity = reconcile.get("audit_chain_integrity") or {}
        if integrity and not integrity.get("skipped"):
            add("### აუდიტის ჯაჭვის დამოუკიდებელი გადამოწმება")
            add("")
            add(f"{'✅' if integrity['ok'] else '❌'} {integrity['detail']}")
            if integrity.get("broken_hash") or integrity.get("broken_link"):
                add("")
                add(f"გატეხილი კავშირი: `{integrity.get('broken_link')}`")
                add(f"გატეხილი ჰეში: `{integrity.get('broken_hash')}`")
            add("")
        if reconcile.get("identity"):
            add("### IDENTITY-ის მაღალი ნიშნული")
            add("")
            for item in reconcile["identity"]:
                add(f"- {'✅' if item['ok'] else '❌'} `{item['table']}` — {item['detail']}")
            add("")
        if reconcile.get("untouched"):
            bad = [u for u in reconcile["untouched"] if not u["ok"]]
            add("### ხელუხლებელი ცხრილები")
            add("")
            if bad:
                for item in bad:
                    add(f"- ❌ `{item['table']}` — {item['detail']}")
            else:
                add("- ✅ ყველა ცხრილი, რომელსაც ETL არ ეხება, მოსალოდნელ მდგომარეობაშია.")
            add("")

    add("## რა არ გადმოდის და რატომ")
    add("")
    for table, reason in sorted(report["not_migrated"].items()):
        add(f"- **`{table}`** — {reason}")
    add("")
    add("### წყაროს ცხრილები Oracle-ის შესატყვისის გარეშე")
    add("")
    for table, reason in sorted(report["source_only"].items()):
        add(f"- **`{table}`** — {reason}")
    add("")
    return "\n".join(lines)


def write(report: dict, directory: str) -> tuple[str, str]:
    os.makedirs(directory, exist_ok=True)
    stamp = report["generated_at"].replace(":", "").replace("-", "")
    md_path = os.path.join(directory, f"etl-{stamp}.md")
    json_path = os.path.join(directory, f"etl-{stamp}.json")
    with open(md_path, "w", encoding="utf-8") as handle:
        handle.write(to_markdown(report))
    with open(json_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2, default=str)
    return md_path, json_path

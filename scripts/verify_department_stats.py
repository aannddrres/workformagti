"""Quick check: team-stats must expose 3 Magti departments."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from database import SessionLocal
from main import DEPARTMENT_WHITELIST, build_department_stats

EXPECTED = ["ტექნიკური", "საინფორმაციო", "ოფისი"]


def main() -> int:
    print("DEPARTMENT_WHITELIST =", DEPARTMENT_WHITELIST)
    if list(DEPARTMENT_WHITELIST) != EXPECTED:
        print("FAIL: whitelist order/content mismatch")
        return 1
    db = SessionLocal()
    try:
        stats = build_department_stats(db)
        names = [d["name"] for d in stats["departments"]]
        print("departments =", names)
        if names != EXPECTED:
            print("FAIL: build_department_stats names", names)
            return 1
        for d in stats["departments"]:
            print(
                f"  {d['name']}: groups={d['group_count']} "
                f"members={d['member_count']} empty={d['is_empty']}"
            )
        office = next(d for d in stats["departments"] if d["name"] == "ოფისი")
        if office["is_empty"]:
            print("WARN: ოფისი is empty — run: python scripts/seed_portal.py org")
        print("OK: 3 departments present")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())

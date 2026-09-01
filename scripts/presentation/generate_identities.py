"""Generate realistic fictional AD-style identities for demo Oracle users.

Overwrites only users.name / users.position / users.phone — the exact three
fields a real Active Directory sync would supply as demographic payload.
Never touches email/role/department/team_id/hashed_password/permissions,
which are identity/authorization data, not AD demographics.

Deterministic (fixed random seed) so re-running produces the same output.
Idempotent: re-running regenerates the same rows in the same order.

Usage:
    ORACLE_DSN=127.0.0.1:1523/XEPDB1 \
    ORACLE_USER=magti_app \
    ORACLE_PASSWORD=local_only_presentation_app_pw \
    python scripts/presentation/generate_identities.py
"""

from __future__ import annotations

import csv
import io
import os
import random
import sys
from pathlib import Path

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import oracledb

SEED = 20260901

MALE_FIRST_NAMES = [
    "გიორგი", "დავით", "ლევან", "ნიკოლოზ", "ალექსანდრე", "ზურაბ", "თემურ", "ვახტანგ", "გია", "ლაშა",
    "ირაკლი", "ტარიელ", "მამუკა", "ბექა", "გელა", "სოსო", "გურამ", "ავთანდილ", "ზაზა", "ვასილ",
    "ომარ", "კახა", "ბადრი", "გოგი", "თორნიკე", "სანდრო", "ილია", "ბესარიონ", "არჩილ", "გიგა",
    "რევაზ", "ვახო", "ედუარდ", "ნოდარ", "ჯაბა", "პაატა", "დათო", "სერგო", "რამაზ", "კობა",
    "შალვა", "ოთარ", "გურგენ", "მერაბ", "ზვიად", "ვაჟა", "ბიძინა", "ლუკა", "ანზორ", "გოჩა",
    "ნიკა", "დათა", "ალექსი", "როსტომ", "გრიგოლ",
]

FEMALE_FIRST_NAMES = [
    "ნინო", "თამარ", "მარიამ", "ეკატერინე", "ანა", "სალომე", "ნატია", "ხატია", "ლელა", "მაია",
    "ირინა", "ეთერ", "მანანა", "ნანა", "დარეჯან", "ლალი", "ია", "ცისანა", "ნატალია", "სოფიო",
    "ლიკა", "ქეთევან", "ნონა", "მედეა", "ხათუნა", "რუსუდან", "ლილი", "გვანცა", "დიანა", "ელენე",
    "ინგა", "მარინა", "თეა", "ნინიკო", "ტატიანა", "ვიოლა", "ლანა", "სვეტლანა", "გულნარა", "ნატო",
    "ეკა", "ლამარა", "ნატალი", "ვიკა", "ციალა", "სოფო", "ნესტანი", "ფატიმა", "მაყვალა", "ვარვარა",
    "ნატა", "სოფია", "მზია", "დოდო", "ბელა",
]

SURNAMES = [
    # -ძე
    "ბერიძე", "მაისურაძე", "ხარაძე", "ლომიძე", "დოლიძე", "წულაძე", "გაბაძე", "ბარამიძე",
    "კუპრაძე", "ჯანელიძე", "ცერცვაძე", "ხვედელიძე", "ტყემალაძე", "საათაძე", "ბოლქვაძე",
    # -შვილი
    "დავითაშვილი", "გელაშვილი", "ბერიაშვილი", "თაბუკაშვილი", "ნადირაშვილი", "სააკაშვილი",
    "ხუციშვილი", "ჯავახიშვილი", "მაისურაშვილი", "გურგენიშვილი", "ლომაშვილი", "ვახტანგაშვილი",
    "სამხარაშვილი", "ბარამაშვილი", "ცერცვაშვილი",
    # -ია
    "კვარაცხელია", "გაბუნია", "ჩხაია", "წულუკია", "ბოლქვია", "საათია", "ჯანელია", "დათუნაია",
    "სვანია", "თოფურია", "გურგენია", "ლომია", "ხარატია", "წერეთია", "ნადირია",
    # -ავა
    "ბერულავა", "ხაინდრავა", "გერგედავა", "ბჟალავა", "ჯანჯღავა", "ხუბულავა", "სართანავა",
    "ლორთქიფანავა", "გელავა", "ხარავა", "ლომავა", "დოლავა", "წულავა", "ბარამავა", "კუპარავა",
    # -იანი
    "ონიანი", "ბერიანი", "სააკიანი", "გაბრიელიანი", "დავითიანი", "გელიანი", "ხარიანი",
    "ლომიანი", "დოლიანი", "წულიანი",
    # -ური
    "წიკლაური", "გელაური", "ხარაური", "ლომაური", "დოლაური", "წულაური", "ბარამური",
    "საათური", "ჯანელური", "ნადირური",
]

OUTPUT_CSV = Path(__file__).with_name("generated_identities.csv")


def pick_position(role: str, department: str) -> str:
    department = department or ""
    if role == "admin":
        return "სისტემის ადმინისტრატორი"
    if role == "content_admin":
        return "კონტენტ-მენეჯერი"
    if role == "manager":
        return "ჯგუფის ხელმძღვანელი"
    # operator
    if department.startswith("ტექნიკური"):
        return "ტექნიკური მხარდაჭერის ოპერატორი"
    if department.startswith("საინფორმაციო"):
        return "საინფორმაციო სერვისის ოპერატორი"
    return "ქოლ-ცენტრის ოპერატორი"


def make_generators(seed: int):
    rng = random.Random(seed)
    used_names: set[str] = set()
    used_phones: set[str] = set()

    def next_name() -> tuple[str, str]:
        while True:
            is_male = rng.random() < 0.5
            first = rng.choice(MALE_FIRST_NAMES if is_male else FEMALE_FIRST_NAMES)
            last = rng.choice(SURNAMES)
            full = f"{first} {last}"
            if full not in used_names:
                used_names.add(full)
                return first, full

    def next_phone() -> str:
        while True:
            digits = f"5{rng.randint(0, 99999999):08d}"
            phone = f"{digits[:3]} {digits[3:6]} {digits[6:9]}"
            if phone not in used_phones:
                used_phones.add(phone)
                return phone

    return next_name, next_phone


def connect() -> oracledb.Connection:
    dsn = os.getenv("ORACLE_DSN", "127.0.0.1:1523/XEPDB1")
    user = os.getenv("ORACLE_USER", "magti_app")
    password = os.getenv("ORACLE_PASSWORD", "")
    if not password:
        raise SystemExit("ORACLE_PASSWORD is required")
    return oracledb.connect(user=user, password=password, dsn=dsn)


def main() -> None:
    next_name, next_phone = make_generators(SEED)
    connection = connect()
    try:
        cursor = connection.cursor()
        cursor.execute("SELECT email, role, department FROM users ORDER BY id")
        rows = cursor.fetchall()

        records: list[tuple[str, str, str, str]] = []
        for email, role, department in rows:
            _, full_name = next_name()
            position = pick_position(str(role), str(department or ""))
            phone = next_phone()
            records.append((str(email), full_name, position, phone))

        OUTPUT_CSV.parent.mkdir(parents=True, exist_ok=True)
        with OUTPUT_CSV.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["email", "name", "position", "phone"])
            writer.writerows(records)

        update_cursor = connection.cursor()
        for email, name, position, phone in records:
            update_cursor.execute(
                "UPDATE users SET name=:name, position=:position, phone=:phone WHERE email=:email",
                name=name, position=position, phone=phone, email=email,
            )
        connection.commit()

        name_values = [record[1] for record in records]
        phone_values = [record[3] for record in records]
        duplicate_names = len(name_values) - len(set(name_values))
        duplicate_phones = len(phone_values) - len(set(phone_values))

        print(f"Updated {len(records)} accounts.")
        print(f"Duplicate names: {duplicate_names}, duplicate phones: {duplicate_phones}")
        print("Sample:")
        for record in records[:5]:
            print(f"  {record[0]:<45} {record[1]:<28} {record[2]:<40} {record[3]}")
    finally:
        connection.close()


if __name__ == "__main__":
    main()

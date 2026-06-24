import os
import random
import sys
from datetime import datetime, timedelta

# Ensure parent directory is in path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

# Windows consoles often default to a non-UTF-8 codepage (cp1252), which can't
# encode the Georgian text in this script's print() calls and crashes the
# seeder mid-run with UnicodeEncodeError - after the DB commit already
# succeeded, so it looks like a failure when the data actually landed. Force
# UTF-8 stdout/stderr where supported (Python 3.7+); harmless no-op elsewhere.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

from database import SessionLocal, engine, Base
import models
from models import (
    User, Category, VideoInstruction, News, Article, RequiredReading,
    ReadStatus, Message, Favorite,
)
from security import get_password_hash

DEFAULT_PASSWORD = "password"

# ─────────────────────────────────────────────────────────────────────────────
# Rich article bodies (server-render-safe HTML).
#
# These replace the old one-line placeholder strings. The reader modal
# (base-layout.html -> openArticleModal/renderArticleModal) detects structural
# HTML tags and renders the body through DOMPurify -> innerHTML, so operators get
# the full technical tables and step lists instead of a summary line. Keep the
# tag set within the modal's DOMPurify allowlist: h1-h6, p, ul/ol/li, table/
# thead/tbody/tr/th/td, strong/b/em/i, a, br, blockquote, hr.
# ─────────────────────────────────────────────────────────────────────────────

IPTV_REMOTE_CODES_HTML = """
<h3>უნივერსალური პულტის კოდები</h3>
<p>მაგთის IPTV მიმღების (STB) უნივერსალური პულტი შესაძლებელია დააპროგრამოთ ტელევიზორის
ხმისა და ჩართვა/გამორთვის სამართავად. აირჩიეთ ტელევიზორის მწარმოებელი და სცადეთ
შესაბამისი კოდები თანმიმდევრობით.</p>
<table>
  <thead>
    <tr><th>მწარმოებელი</th><th>კოდები (სცადეთ თანმიმდევრობით)</th></tr>
  </thead>
  <tbody>
    <tr><td>Samsung</td><td>0001, 0002, 0102, 0812, 1059</td></tr>
    <tr><td>LG</td><td>0004, 0005, 0017, 0050, 1423</td></tr>
    <tr><td>Sony</td><td>0010, 0036, 0080, 1100</td></tr>
    <tr><td>Philips</td><td>0006, 0007, 0028, 0556</td></tr>
    <tr><td>Panasonic</td><td>0008, 0009, 0263, 1480</td></tr>
    <tr><td>Toshiba</td><td>0012, 0013, 0109, 1256</td></tr>
    <tr><td>Hisense</td><td>0748, 0768, 1660</td></tr>
    <tr><td>TCL</td><td>0698, 0768, 1822</td></tr>
  </tbody>
</table>
<h3>დაპროგრამების ინსტრუქცია</h3>
<ol>
  <li>ჩართეთ ტელევიზორი ხელით.</li>
  <li>პულტზე ერთდროულად დააჭირეთ <strong>SETUP</strong> ღილაკს და გეჭიროთ სანამ
      ინდიკატორი არ აანთებს მუდმივ შუქს.</li>
  <li>აკრიფეთ ცხრილში მითითებული 4-ნიშნა კოდი — ინდიკატორი ჩაქრება.</li>
  <li>მიმართეთ პულტი ტელევიზორისკენ და დააჭირეთ <strong>POWER</strong>. თუ ტელევიზორი
      გამოირთო — კოდი სწორია.</li>
  <li>თუ ტელევიზორი არ რეაგირებს, გაიმეორეთ პროცესი შემდეგი კოდით.</li>
</ol>
<blockquote>თუ არცერთი კოდი არ მუშაობს, გამოიყენეთ ავტომატური ძიების რეჟიმი:
SETUP-ის შემდეგ აკრიფეთ <strong>991</strong> და თანმიმდევრულად დააჭირეთ
<strong>CH+</strong>-ს სანამ ტელევიზორი არ გამოირთვება.</blockquote>
"""

MOBILE_APN_HTML = """
<h3>მობილური ინტერნეტის პარამეტრები (APN)</h3>
<p>თუ მონაცემთა გადაცემა (ინტერნეტი) ავტომატურად არ ეწერება, ხელით შეიყვანეთ APN
პარამეტრები ქვემოთ მოცემული ცხრილის მიხედვით. ცარიელი ველები დატოვეთ ცარიელად.</p>
<h3>Android</h3>
<p>გზა: <strong>Settings → Network &amp; internet → SIM → Access Point Names → +</strong></p>
<table>
  <thead><tr><th>ველი</th><th>მნიშვნელობა</th></tr></thead>
  <tbody>
    <tr><td>Name</td><td>Magti Internet</td></tr>
    <tr><td>APN</td><td>internet.magticom.ge</td></tr>
    <tr><td>MCC</td><td>282</td></tr>
    <tr><td>MNC</td><td>02</td></tr>
    <tr><td>APN type</td><td>default,supl</td></tr>
    <tr><td>APN protocol</td><td>IPv4/IPv6</td></tr>
    <tr><td>Authentication type</td><td>None</td></tr>
  </tbody>
</table>
<h3>iOS (iPhone / iPad)</h3>
<p>გზა: <strong>Settings → Cellular → Cellular Data Network</strong></p>
<table>
  <thead><tr><th>ველი</th><th>მნიშვნელობა</th></tr></thead>
  <tbody>
    <tr><td>APN</td><td>internet.magticom.ge</td></tr>
    <tr><td>Username</td><td>(ცარიელი)</td></tr>
    <tr><td>Password</td><td>(ცარიელი)</td></tr>
  </tbody>
</table>
<h3>MMS პარამეტრები</h3>
<table>
  <thead><tr><th>ველი</th><th>მნიშვნელობა</th></tr></thead>
  <tbody>
    <tr><td>MMS APN</td><td>mms.magticom.ge</td></tr>
    <tr><td>MMSC</td><td>http://mms.magticom.ge</td></tr>
    <tr><td>MMS proxy</td><td>10.11.50.50:8080</td></tr>
  </tbody>
</table>
<blockquote>ცვლილებების შემდეგ აუცილებელია მოწყობილობის გადატვირთვა. 5G პარამეტრები
იდენტურია — საჭიროა მხოლოდ 5G-ის მხარდამჭერი მოწყობილობა და SIM.</blockquote>
"""

ROAMING_TARIFFS_HTML = """
<h3>როუმინგული ტარიფები</h3>
<p>მიმდინარე როუმინგული ტარიფები მაგთის აბონენტებისთვის. ფასები მითითებულია ლარში,
დღგ-ს ჩათვლით.</p>
<table>
  <thead><tr><th>ზონა</th><th>ზარი (შემომავალი)</th><th>ზარი (გამავალი)</th><th>SMS</th><th>ინტერნეტი (1 MB)</th></tr></thead>
  <tbody>
    <tr><td>ზონა 1 (ევროკავშირი, თურქეთი)</td><td>0.30</td><td>0.90</td><td>0.30</td><td>0.20</td></tr>
    <tr><td>ზონა 2 (დსთ, აზია)</td><td>0.80</td><td>1.90</td><td>0.50</td><td>0.50</td></tr>
    <tr><td>ზონა 3 (დანარჩენი მსოფლიო)</td><td>1.50</td><td>3.50</td><td>0.80</td><td>1.20</td></tr>
  </tbody>
</table>
<p>რეკომენდებულია მოგზაურობის წინ როუმინგული პაკეტის გააქტიურება — ის მნიშვნელოვნად
ამცირებს ხარჯს სტანდარტულ ტარიფთან შედარებით.</p>
"""

ROAMING_ACTIVATION_HTML = """
<h3>როუმინგის გააქტიურება</h3>
<p>როუმინგის ჩართვა შესაძლებელია სამი გზით:</p>
<ol>
  <li><strong>MyMagti აპლიკაცია:</strong> სერვისები → როუმინგი → ჩართვა.</li>
  <li><strong>SMS:</strong> გააგზავნეთ <strong>ROAM</strong> ნომერზე <strong>100</strong>.</li>
  <li><strong>მაგთის სერვის-ცენტრი</strong> ან ცხელი ხაზი <strong>100</strong>.</li>
</ol>
<h3>გასათვალისწინებელი</h3>
<ul>
  <li>როუმინგი აქტიურდება მაქსიმუმ 4 საათში.</li>
  <li>აბონენტის ბალანსი უნდა იყოს დადებითი.</li>
  <li>კონტრაქტული აბონენტებისთვის შესაძლოა საჭირო იყოს დეპოზიტი.</li>
</ul>
"""

FTTH_INSTALL_HTML = """
<h3>ბოჭკოვანი ინტერნეტის (FTTH) ინსტალაცია</h3>
<p>ოპტიკურ-ბოჭკოვანი ინტერნეტის მიერთების ტექნიკური პროცედურა.</p>
<ol>
  <li>მისამართის დაფარვის შემოწმება ბილინგ-სისტემაში.</li>
  <li>განაცხადის რეგისტრაცია და ვიზიტის დაგეგმვა.</li>
  <li>ოპტიკური კაბელის შეყვანა და ONT მოწყობილობის მონტაჟი.</li>
  <li>სერვისის გააქტიურება და სიჩქარის ტესტი.</li>
</ol>
<table>
  <thead><tr><th>სერვისი</th><th>სტანდარტული ღირებულება</th></tr></thead>
  <tbody>
    <tr><td>ერთი სერვისი (ინტერნეტი)</td><td>50 ₾</td></tr>
    <tr><td>ორი ან მეტი სერვისი ერთდროულად</td><td>40 ₾ თითო წერტილზე</td></tr>
  </tbody>
</table>
<blockquote>ამ ეტაპზე მოქმედებს უფასო მონტაჟის აქცია — შეამოწმეთ მიმდინარე პირობები
სანამ აბონენტს ღირებულებას დაუდასტურებთ.</blockquote>
"""

GPON_HUAWEI_HTML = """
<h3>GPON / Huawei ONT კონფიგურაცია</h3>
<p>Huawei ONT მოწყობილობების კონფიგურაცია ინტერნეტისა და ტელევიზიის გასაწერად.</p>
<table>
  <thead><tr><th>პარამეტრი</th><th>მნიშვნელობა</th></tr></thead>
  <tbody>
    <tr><td>მართვის მისამართი</td><td>192.168.100.1</td></tr>
    <tr><td>მომხმარებელი</td><td>telecomadmin</td></tr>
    <tr><td>ნაგულისხმევი პაროლი</td><td>admintelecom</td></tr>
    <tr><td>WAN რეჟიმი</td><td>Route (PPPoE)</td></tr>
  </tbody>
</table>
<h3>ნაბიჯები</h3>
<ol>
  <li>შედით ვებ-ინტერფეისში მართვის მისამართით.</li>
  <li>WAN სექციაში შექმენით PPPoE კავშირი აბონენტის ლოგინ/პაროლით.</li>
  <li>IPTV-სთვის გაააქტიურეთ ცალკე bridge VLAN პროფილი.</li>
  <li>შეინახეთ და გადატვირთეთ მოწყობილობა.</li>
</ol>
"""

PORTING_HTML = """
<h3>მობილური ნომრის პორტირება</h3>
<p>სხვა ოპერატორიდან ნომრის მაგთიში გადმოყვანის სრული პროცედურა.</p>
<h3>საჭირო დოკუმენტები</h3>
<ul>
  <li>პირადობის დამადასტურებელი მოწმობა.</li>
  <li>მოქმედი SIM ბარათი (ან ნომრის მფლობელობის დამადასტურებელი).</li>
</ul>
<h3>ეტაპები</h3>
<ol>
  <li>აბონენტი ავსებს პორტირების განაცხადს მაგთის ფილიალში.</li>
  <li>ნომერი გადმოდის 1 სამუშაო დღეში (24 საათი).</li>
  <li>გადმოყვანის მომენტში ძველი SIM ითიშება, ახალი აქტიურდება.</li>
</ol>
<blockquote>აბონენტს ძველ ოპერატორთან არ უნდა ჰქონდეს ვადაგასული დავალიანება, წინააღმდეგ
შემთხვევაში პორტირება უარყოფილი იქნება.</blockquote>
"""

MYMAGTI_REG_HTML = """
<h3>MyMagti — რეგისტრაცია</h3>
<p>ახალი მომხმარებლის რეგისტრაცია MyMagti აპლიკაციაში.</p>
<ol>
  <li>ჩამოტვირთეთ აპლიკაცია App Store-დან ან Google Play-დან.</li>
  <li>აირჩიეთ „რეგისტრაცია“ და შეიყვანეთ მაგთის ნომერი.</li>
  <li>დაადასტურეთ SMS-ით მიღებული ერთჯერადი კოდი.</li>
  <li>შექმენით პაროლი (მინ. 8 სიმბოლო, ციფრი და დიდი ასო).</li>
</ol>
<p>რეგისტრაციის შემდეგ აბონენტს შეუძლია ნახოს ბალანსი, შეიძინოს პაკეტები და მართოს
სერვისები.</p>
"""

DEFAULT_PERMISSIONS_BY_ROLE = {
    "admin": [
        "articles.publish", "articles.verify", "articles.nudge", "articles.history",
        "news.publish", "news.history", "videos.manage", "users.manage", "reports.export"
    ],
    "content_admin": [
        "articles.publish", "articles.verify", "articles.history", "news.publish", "news.history", "videos.manage"
    ],
    "manager": [
        "articles.nudge", "reports.export"
    ],
    "operator": []
}

# ─────────────────────────────────────────────────────────────────────────────
# Realistic compliance distribution, shared by seed_database() and
# seed_org_hierarchy(). Demo data should never sit at a flat 0% - that reads as
# "nobody uses this" rather than "this is a working compliance tool".
#
#   ~80% of operators: 90-100% read  (high performers)
#   ~15% of operators: 70-89% read   (needs minor attention)
#   ~5%  of operators: 15-69% read   (clear outliers - the Admin "nudge" demo)
# ─────────────────────────────────────────────────────────────────────────────
_COMPLIANCE_TIERS = [
    (0.80, 90, 100),
    (0.95, 70, 89),
    (1.00, 15, 69),
]


def _pick_target_percentage() -> float:
    r = random.random()
    cumulative = 0.0
    for threshold, lo, hi in _COMPLIANCE_TIERS:
        cumulative = threshold
        if r < cumulative:
            return random.uniform(lo, hi)
    return random.uniform(*_COMPLIANCE_TIERS[-1][1:])  # r >= last threshold (rounding edge)


def _assign_realistic_compliance(db, operators, now):
    """Marks a random subset of each operator's applicable required readings as
    'read' so their percentage lands in one of the bands above.

    "Applicable" mirrors main.py's _reading_progress: readings targeting "All"
    plus readings targeting the operator's own department string - which for
    the org-hierarchy seeder's unique per-group labels means just "All".

    Idempotent: skips any operator who already has a ReadStatus row, so a
    re-run of `seed.py org` doesn't reshuffle numbers a demo already relies on.
    """
    all_readings = db.query(RequiredReading).all()
    if not all_readings:
        return 0  # nothing to mark read yet (seed_database() hasn't run)

    by_dept = {}
    for r in all_readings:
        by_dept.setdefault(r.target_department, []).append(r)
    by_all = by_dept.get("All", [])

    new_rows = []
    for user in operators:
        if db.query(ReadStatus.id).filter(ReadStatus.user_id == user.id).first():
            continue  # already has compliance data - leave it alone

        applicable = {r.id: r for r in (by_all + by_dept.get(user.department, []))}
        applicable = list(applicable.values())
        if not applicable:
            continue

        n_read = round(_pick_target_percentage() / 100 * len(applicable))
        n_read = max(0, min(n_read, len(applicable)))
        for r in random.sample(applicable, n_read):
            new_rows.append(ReadStatus(
                user_id=user.id, required_reading_id=r.id, status="read",
                read_at=now - timedelta(days=random.randint(0, 6), hours=random.randint(0, 23)),
            ))

    if new_rows:
        db.add_all(new_rows)
    return len(new_rows)


def seed_database():
    db = SessionLocal()
    try:
        print("Cleaning old data...")
        for model in (
            ReadStatus, models.ArticleHistory, models.AuditLog, models.SearchLog, Message, Favorite,
            RequiredReading, Article, News, VideoInstruction, Category, User,
        ):
            try:
                db.query(model).delete()
            except Exception:
                pass
        db.commit()

        hashed_pw = get_password_hash(DEFAULT_PASSWORD)

        # ── Users (all four roles; @magti.ge to match the live environment) ──
        print("Seeding users...")
        admin = User(name="სისტემის ადმინისტრატორი", email="admin@magti.ge",
                     role="admin", department="Administration",
                     position="Chief Admin Officer", is_active=True, hashed_password=hashed_pw,
                     permissions=DEFAULT_PERMISSIONS_BY_ROLE["admin"])
        content_admin = User(name="კონტენტის ადმინისტრატორი", email="content@magti.ge",
                             role="content_admin", department="Content Creation",
                             position="Content Manager", is_active=True, hashed_password=hashed_pw,
                             permissions=DEFAULT_PERMISSIONS_BY_ROLE["content_admin"])
        
        # Support team
        manager = User(name="ჯგუფის მენეჯერი (Support)", email="manager@magti.ge",
                       role="manager", department="Support",
                       position="Support Team Lead", is_active=True, hashed_password=hashed_pw,
                       permissions=DEFAULT_PERMISSIONS_BY_ROLE["manager"])
        nino = User(name="ნინო ჩიტიშვილი", email="nino@magti.ge",
                    role="operator", department="Support",
                    position="Helpdesk Specialist", phone="555 00 00 00", is_active=True, hashed_password=hashed_pw,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])
        tech = User(name="ტექნიკური ოპერატორი", email="tech@magti.ge",
                    role="operator", department="Support",
                    position="Technical Operator", is_active=True, hashed_password=hashed_pw,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])
        info = User(name="საინფორმაციო ოპერატორი", email="info@magti.ge",
                    role="operator", department="Informational",
                    position="Service Desk Operator", is_active=True, hashed_password=hashed_pw,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])

        # NOTE: Billing and Sales departments were retired (see migrate_departments.py).
        # Do not re-add dedicated Billing/Sales User accounts here — that would
        # silently re-introduce the departments this seed script is meant to keep gone.

        db.add_all([
            admin, content_admin, manager, nino, tech, info,
        ])
        db.flush()  # assign IDs

        # ── Categories ──────────────────────────────────────────────────────
        print("Seeding categories...")
        cats = [
            Category(name="როუმინგი"),
            Category(name="ინტერნეტი"),
            Category(name="IPTV"),
            Category(name="ტექნიკური"),
            Category(name="პორტირება"),
            Category(name="MyMagti"),
        ]
        db.add_all(cats)
        db.flush()
        cat = {c.name: c.id for c in cats}

        # ── Articles (knowledge base) ───────────────────────────────────────
        # IMPORTANT: content is rich, server-render-safe HTML (tables / lists /
        # headings). The reader modal in base-layout.html detects structural tags
        # and renders article.content through DOMPurify -> innerHTML, so operators
        # see the full technical tables — NOT a one-line placeholder. Do not
        # downgrade these back to single-sentence strings (that was the regression
        # that hid IPTV codes and APN settings behind a summary line).
        print("Seeding articles...")
        articles = [
            Article(title="როუმინგული ტარიფები", content=ROAMING_TARIFFS_HTML, category_id=cat["როუმინგი"], target_department="All", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="როუმინგის აქტივაცია", content=ROAMING_ACTIVATION_HTML, category_id=cat["როუმინგი"], target_department="All", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="ბოჭკოვანი ინტერნეტის ინსტალაცია", content=FTTH_INSTALL_HTML, category_id=cat["ინტერნეტი"], target_department="Informational", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="GPON პარამეტრები (Huawei)", content=GPON_HUAWEI_HTML, category_id=cat["ტექნიკური"], target_department="Support", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="IPTV პულტის კოდები", content=IPTV_REMOTE_CODES_HTML, category_id=cat["IPTV"], target_department="Support", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="მობილური პორტირების პროცედურა", content=PORTING_HTML, category_id=cat["პორტირება"], target_department="Informational", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="MyMagti რეგისტრაცია", content=MYMAGTI_REG_HTML, category_id=cat["MyMagti"], target_department="Informational", status="published", author_id=content_admin.id, is_draft=False),
            Article(title="მობილური ინტერნეტის პარამეტრები", content=MOBILE_APN_HTML, category_id=cat["ინტერნეტი"], target_department="All", status="published", author_id=content_admin.id, is_draft=False),
        ]
        db.add_all(articles)
        db.flush()

        # ── Video instructions (centralised) ──────────────────────────────────
        print("Seeding video instructions...")
        videos = [
            VideoInstruction(title="PIN კოდის მართვა", video_url="https://www.youtube.com/watch?v=dQw4w9WgXcQ", category="ტექნიკური", target_department="All", views_count=42),
            VideoInstruction(title="სინქრონიზაცია", video_url="https://www.youtube.com/watch?v=dQw4w9WgXcQ", category="ტექნიკური", target_department="All", views_count=17),
            VideoInstruction(title="დილერების აქტივაცია", video_url="https://www.youtube.com/watch?v=dQw4w9WgXcQ", category="Support", target_department="Support", views_count=8),
        ]
        db.add_all(videos)
        db.flush()

        # ── News ────────────────────────────────────────────────────────────
        print("Seeding news...")
        news = [
            News(title="IPTV ახალი არხების დამატება", content="დაემატა ახალი არხები IPTV პაკეტში.", target_department="All", is_draft=False),
            News(title="„როუმერის“ ახალი პარტნიორი ოპერატორები", content="გაფართოვდა როუმინგის პარტნიორების სია.", target_department="All", is_draft=False),
            News(title="Support: განახლებული სკრიპტები", content="Support გუნდისთვის ახალი სასაუბრო სკრიპტები.", target_department="Support", is_draft=False),
            # Two extra "All"-targeted items purely so there are 10 "All" required
            # readings total - see the comment by the required-readings block below.
            News(title="სისტემის გეგმური პროფილაქტიკა", content="ამ შაბათ-კვირას მოსალოდნელია მოკლევადიანი მომსახურების შეფერხება.", target_department="All", is_draft=False),
            News(title="ახალი ჩატის სკრიპტების ბაზა", content="დაემატა განახლებული საუბრის შაბლონები ყველა დეპარტამენტისთვის.", target_department="All", is_draft=False),
        ]
        db.add_all(news)
        db.flush()

        # ── Required readings (compliance) ──────────────────────────────────
        # Mix of departments + due dates so "to-read / read / overdue" all appear.
        print("Seeding required readings...")
        now = datetime.utcnow()
        readings = [
            RequiredReading(item_type="article", item_id=articles[0].id, target_department="All",
                            due_date=now - timedelta(days=3), priority="high"),    # overdue if unread
            RequiredReading(item_type="article", item_id=articles[1].id, target_department="All",
                            due_date=now + timedelta(days=10), priority="normal"),
            RequiredReading(item_type="article", item_id=articles[3].id, target_department="Support",
                            due_date=now + timedelta(days=5), priority="high"),
            RequiredReading(item_type="article", item_id=articles[4].id, target_department="Support",
                            due_date=now + timedelta(days=14), priority="normal"),
            RequiredReading(item_type="article", item_id=articles[2].id, target_department="Informational",
                            due_date=now + timedelta(days=5), priority="high"),
            RequiredReading(item_type="article", item_id=articles[5].id, target_department="Informational",
                            due_date=now + timedelta(days=7), priority="normal"),
            # Extra "All"-targeted items purely for compliance-percentage granularity.
            # 10 "All" readings total gives 10-point steps AND keeps round(0.90*10)=9
            # safely at exactly the 90% high-tier boundary (round(0.90*8)=7 -> 87.5%
            # would wrongly fall into the "needs attention" bucket).
            RequiredReading(item_type="article", item_id=articles[6].id, target_department="All",
                            due_date=now + timedelta(days=12), priority="normal"),
            RequiredReading(item_type="article", item_id=articles[7].id, target_department="All",
                            due_date=now + timedelta(days=9), priority="normal"),
            RequiredReading(item_type="video", item_id=videos[0].id, target_department="All",
                            due_date=now + timedelta(days=15), priority="normal"),
            RequiredReading(item_type="video", item_id=videos[1].id, target_department="All",
                            due_date=now + timedelta(days=15), priority="normal"),
        ]

        if news:
            for n in (news[0], news[1], news[3], news[4]):
                readings.append(RequiredReading(item_type="news", item_id=n.id, target_department="All",
                                                due_date=now + timedelta(days=7), priority="normal"))

        db.add_all(readings)
        db.flush()

        # ── Read statuses ───────────────────────────────────────────────────
        # Realistic compliance distribution instead of a flat 0% - see
        # _assign_realistic_compliance above. Applies to nino/tech (Support)
        # and info (Informational) alike.
        print("Seeding read statuses...")
        _assign_realistic_compliance(db, [nino, tech, info], now)

        # ── A welcome message from the manager to a team member ─────────────
        db.add(Message(user_id=nino.id, sender_id=manager.id,
                       content="გამარჯობა, ნინო. გთხოვ, გაეცანი სავალდებულო მასალებს.",
                       is_read=False))

        db.commit()
        print("Seed completed successfully!\n")

        print("==========================================================")
        print("ACCESS URLS:")
        print("Application URL: http://127.0.0.1:8000")
        print("----------------------------------------------------------")
        print("LOGIN ACCOUNTS (password: password)")
        print("  admin@magti.ge     -> admin          (system administrator)")
        print("  content@magti.ge   -> content_admin  (content management)")
        print("  manager@magti.ge   -> manager        (Support supervisor)")
        print("  nino@magti.ge      -> operator       (Support)")
        print("  tech@magti.ge      -> operator       (Support)")
        print("  info@magti.ge      -> operator       (Informational)")
        print("==========================================================")

    except Exception as e:
        db.rollback()
        print(f"An error occurred while seeding: {e}")
        raise
    finally:
        db.close()


# ─────────────────────────────────────────────────────────────────────────────
# ORG-HIERARCHY SEEDER  —  `python seed.py org`
#
# IDEMPOTENT & NON-DESTRUCTIVE. Unlike seed_database() above (which wipes the DB),
# this ONLY upserts a fixed set of 165 employees by email. It never deletes rows,
# so existing users, audit logs and required-reading states are left intact.
#
# Structure (exactly):
#   • საინფორმაციო სამსახური (Information = Desk + Mob) — 10 groups
#   • ტექნიკური სამსახური (Technical)                  — 5 groups
#   Every group = 1 ჯგუფის უფროსი (manager) + 10 თანამშრომელი = 11 members.
#   15 groups × 11 = 165 users.
#
# Each group maps to its own `department` value, because the manager Team-Stats
# view groups by department — so every group becomes a real team led by its
# manager. Employee roles are distributed: content_admin (1/group) and a couple
# of cross-team system admins, the rest operators.
# ─────────────────────────────────────────────────────────────────────────────

_ORG_FIRST_NAMES = [
    "გიორგი", "ნინო", "დავით", "მარიამ", "ლევან", "თამარ", "ირაკლი", "ანა",
    "ზურაბ", "ქეთევან", "ნიკა", "სოფიო", "ბექა", "ელენე", "გელა", "თეა",
    "ვახტანგ", "ლია", "სანდრო", "ნათია", "ოთარ", "მაია", "გოჩა", "ხატია",
]
_ORG_LAST_NAMES = [
    "ბერიძე", "მაისურაძე", "გელაშვილი", "ლომიძე", "წერეთელი", "კვარაცხელია",
    "მამედაშვილი", "ჩხეიძე", "ფირცხალავა", "კიკნაძე", "გოგიჩაიშვილი", "თავაძე",
    "ნადირაძე", "ჯაფარიძე", "ხურციძე", "ბოლქვაძე", "სანიკიძე", "წიკლაური",
    "აბაშიძე", "გვასალია", "შენგელია", "მელაძე", "ქავთარაძე", "დოლიძე",
]


def _org_name(seq):
    """Deterministic Georgian full name from a sequence index (stable re-runs)."""
    first = _ORG_FIRST_NAMES[seq % len(_ORG_FIRST_NAMES)]
    last = _ORG_LAST_NAMES[(seq // len(_ORG_FIRST_NAMES) + seq) % len(_ORG_LAST_NAMES)]
    return f"{first} {last}"


def _org_upsert(db, email, name, department, position, role, counters):
    """Insert-or-update one user by email. Password is only set on first insert."""
    u = db.query(User).filter(User.email == email).first()
    if not u:
        u = User(email=email, hashed_password=get_password_hash(DEFAULT_PASSWORD))
        db.add(u)
        counters["created"] += 1
    else:
        counters["updated"] += 1
    u.name = name
    u.department = department
    u.position = position
    u.role = role
    u.permissions = DEFAULT_PERMISSIONS_BY_ROLE.get(role, [])
    u.is_active = True
    counters["roles"][role] = counters["roles"].get(role, 0) + 1
    return u


def seed_org_hierarchy():
    """Idempotently upsert the 15-group / 165-user org hierarchy."""
    db = SessionLocal()
    # (department prefix, email code, number of groups)
    departments = [
        ("საინფორმაციო სამსახური", "info", 10),
        ("ტექნიკური სამსახური", "tech", 5),
    ]
    counters = {"created": 0, "updated": 0, "groups": 0, "roles": {}, "renamed": 0}
    seq = 0
    now = datetime.utcnow()
    org_operators = []
    try:
        # Non-destructive migration: a prior run used `.empNN@` slot emails; rename
        # them in place to the spec's `.opNN@` so the upsert below matches existing
        # rows instead of creating 150 orphan duplicates. No deletes.
        legacy = db.query(User).filter(
            (User.email.like("info.%") | User.email.like("tech.%")),
            User.email.like("%.emp%"),
        ).all()
        for u in legacy:
            new_email = u.email.replace(".emp", ".op")
            if not db.query(User).filter(User.email == new_email).first():
                u.email = new_email
                counters["renamed"] += 1
        if counters["renamed"]:
            db.commit()
        for prefix, code, n_groups in departments:
            for g in range(1, n_groups + 1):
                dept_label = f"{prefix} — ჯგუფი {g:02d}"
                counters["groups"] += 1

                # 1 group leader (manager)
                _org_upsert(
                    db, f"{code}.g{g:02d}.lead@magti.ge", _org_name(seq),
                    dept_label, "ჯგუფის უფროსი", "manager", counters,
                )
                seq += 1

                # 10 employees — uniform slot emails op01..op10 (the role field,
                # not the email, distinguishes operators from cross-team admins).
                for e in range(1, 11):
                    if e == 1:
                        role, position = "content_admin", "კონტენტის ადმინისტრატორი"
                    elif e == 2 and g == 1:
                        # one cross-team system admin in the first group of each dept
                        role, position = "admin", "სისტემური ადმინისტრატორი"
                    else:
                        role, position = "operator", "ოპერატორი"
                    u = _org_upsert(
                        db, f"{code}.g{g:02d}.op{e:02d}@magti.ge", _org_name(seq),
                        dept_label, position, role, counters,
                    )
                    if role == "operator":
                        org_operators.append(u)
                    seq += 1

        db.flush()  # assign ids to newly-created users before compliance assignment
        n_compliance = _assign_realistic_compliance(db, org_operators, now)
        db.commit()
        total = counters["created"] + counters["updated"]
        print("=" * 58)
        print("ORG HIERARCHY SEED — idempotent, non-destructive")
        print(f"  დეპარტამენტები: 2 | ჯგუფები: {counters['groups']} (info 10 + tech 5)")
        print(f"  სულ მომხმარებელი: {total}  (ახალი: {counters['created']}, განახლდა: {counters['updated']})")
        print(f"  წევრები ჯგუფზე: 11 (1 ჯგუფის უფროსი + 10 თანამშრომელი)")
        print(f"  compliance read-statuses generated: {n_compliance} "
              f"({len(org_operators)} operators eligible)")
        print(f"  როლები: {counters['roles']}")
        print("  პაროლი ყველასთვის: password")
        print("=" * 58)
        return {"groups": counters["groups"], "total": total, **counters}
    except Exception as e:
        db.rollback()
        print(f"ORG seed error: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    # Make sure the schema exists before we try to clear / insert anything.
    # Running this script on a fresh checkout (no magti_portal.db yet) used to
    # blow up on the very first DELETE because the tables didn't exist; now we
    # create them on demand so `python seed.py` is a one-shot bootstrap.
    Base.metadata.create_all(bind=engine)
    # `python seed.py org` -> idempotent, non-destructive org-hierarchy seeder.
    # `python seed.py`     -> DESTRUCTIVE full demo reseed (wipes the DB first).
    if len(sys.argv) > 1 and sys.argv[1].lower() == "org":
        seed_org_hierarchy()
    else:
        seed_database()

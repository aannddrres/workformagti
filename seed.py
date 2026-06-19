import os
import sys
from datetime import datetime, timedelta

# Ensure parent directory is in path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

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
        admin = User(name="სისტემური ადმინისტრატორი", email="admin@magti.ge",
                     role="admin", department="IT Security",
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

        # Billing team
        billing_manager = User(name="ბილინგის მენეჯერი", email="billing_mgr@magti.ge",
                               role="manager", department="Billing",
                               position="Billing Supervisor", is_active=True, hashed_password=hashed_pw,
                               permissions=DEFAULT_PERMISSIONS_BY_ROLE["manager"])
        billing_op1 = User(name="გიორგი კალანდაძე", email="billing1@magti.ge",
                            role="operator", department="Billing",
                            position="Billing Specialist", is_active=True, hashed_password=hashed_pw,
                            permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])
        billing_op2 = User(name="მარიამ ბერიძე", email="billing2@magti.ge",
                            role="operator", department="Billing",
                            position="Billing Operator", is_active=True, hashed_password=hashed_pw,
                            permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])

        # Sales team
        sales_manager = User(name="გაყიდვების მენეჯერი", email="sales_mgr@magti.ge",
                             role="manager", department="Sales",
                             position="Sales Supervisor", is_active=True, hashed_password=hashed_pw,
                             permissions=DEFAULT_PERMISSIONS_BY_ROLE["manager"])
        sales_op1 = User(name="ეკატერინე მებონია", email="sales1@magti.ge",
                          role="operator", department="Sales",
                          position="Sales Operator", is_active=True, hashed_password=hashed_pw,
                          permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])
        sales_op2 = User(name="ლაშა ტაბატაძე", email="sales2@magti.ge",
                          role="operator", department="Sales",
                          position="Retail Agent", is_active=True, hashed_password=hashed_pw,
                          permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"])

        db.add_all([
            admin, content_admin, manager, nino, tech, info,
            billing_manager, billing_op1, billing_op2,
            sales_manager, sales_op1, sales_op2
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
            Article(title="როუმინგული ტარიფები", content=ROAMING_TARIFFS_HTML, category_id=cat["როუმინგი"], target_department="All", status="published", author_id=content_admin.id),
            Article(title="როუმინგის აქტივაცია", content=ROAMING_ACTIVATION_HTML, category_id=cat["როუმინგი"], target_department="All", status="published", author_id=content_admin.id),
            Article(title="ბოჭკოვანი ინტერნეტის ინსტალაცია", content=FTTH_INSTALL_HTML, category_id=cat["ინტერნეტი"], target_department="Billing", status="published", author_id=content_admin.id),
            Article(title="GPON პარამეტრები (Huawei)", content=GPON_HUAWEI_HTML, category_id=cat["ტექნიკური"], target_department="Support", status="published", author_id=content_admin.id),
            Article(title="IPTV პულტის კოდები", content=IPTV_REMOTE_CODES_HTML, category_id=cat["IPTV"], target_department="Support", status="published", author_id=content_admin.id),
            Article(title="მობილური პორტირების პროცედურა", content=PORTING_HTML, category_id=cat["პორტირება"], target_department="Sales", status="published", author_id=content_admin.id),
            Article(title="MyMagti რეგისტრაცია", content=MYMAGTI_REG_HTML, category_id=cat["MyMagti"], target_department="Informational", status="published", author_id=content_admin.id),
            Article(title="მობილური ინტერნეტის პარამეტრები", content=MOBILE_APN_HTML, category_id=cat["ინტერნეტი"], target_department="All", status="published", author_id=content_admin.id),
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
            News(title="IPTV ახალი არხების დამატება", content="დაემატა ახალი არხები IPTV პაკეტში.", target_department="All"),
            News(title="„როუმერის“ ახალი პარტნიორი ოპერატორები", content="გაფართოვდა როუმინგის პარტნიორების სია.", target_department="All"),
            News(title="Support: განახლებული სკრიპტები", content="Support გუნდისთვის ახალი სასაუბრო სკრიპტები.", target_department="Support"),
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
            RequiredReading(item_type="article", item_id=articles[2].id, target_department="Billing",
                            due_date=now + timedelta(days=5), priority="high"),
            RequiredReading(item_type="article", item_id=articles[5].id, target_department="Sales",
                            due_date=now + timedelta(days=7), priority="normal"),
        ]
        
        if news:
            readings.append(RequiredReading(item_type="news", item_id=news[0].id, target_department="All",
                                            due_date=now + timedelta(days=7), priority="normal"))
                                            
        db.add_all(readings)
        db.flush()

        # ── Read statuses ───────────────────────────────────────────────────
        # nino (Support) read one "All" reading; leaves the overdue one unread.
        # tech (Technical) read their department reading. → non-zero stats.
        print("Seeding read statuses...")
        db.add_all([
            # nino (Support) - read 2 out of 5 required readings (All + Support)
            ReadStatus(user_id=nino.id, required_reading_id=readings[1].id,
                       status="read", read_at=now - timedelta(days=1)),
            ReadStatus(user_id=nino.id, required_reading_id=readings[3].id,
                       status="read", read_at=now - timedelta(days=1)),

            # tech (Support) - read 2 out of 5
            ReadStatus(user_id=tech.id, required_reading_id=readings[0].id,
                       status="read", read_at=now - timedelta(days=2)),
            ReadStatus(user_id=tech.id, required_reading_id=readings[1].id,
                       status="read", read_at=now - timedelta(days=2)),

            # billing_op1 (Billing) - read 2 out of 4 (All + Billing required readings)
            ReadStatus(user_id=billing_op1.id, required_reading_id=readings[0].id,
                       status="read", read_at=now - timedelta(days=1)),
            ReadStatus(user_id=billing_op1.id, required_reading_id=readings[4].id, # Billing required reading
                       status="read", read_at=now - timedelta(days=1)),

            # billing_op2 (Billing) - read 4 out of 4 (100% compliance!)
            ReadStatus(user_id=billing_op2.id, required_reading_id=readings[0].id,
                       status="read", read_at=now - timedelta(days=2)),
            ReadStatus(user_id=billing_op2.id, required_reading_id=readings[1].id,
                       status="read", read_at=now - timedelta(days=2)),
            ReadStatus(user_id=billing_op2.id, required_reading_id=readings[4].id,
                       status="read", read_at=now - timedelta(days=2)),
            ReadStatus(user_id=billing_op2.id, required_reading_id=readings[6].id, # News read
                       status="read", read_at=now - timedelta(days=2)),

            # sales_op2 (Sales) - read 1 out of 4
            ReadStatus(user_id=sales_op2.id, required_reading_id=readings[1].id,
                       status="read", read_at=now - timedelta(days=1)),
        ])

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
        print("  billing_mgr@magti.ge -> manager      (Billing supervisor)")
        print("  sales_mgr@magti.ge   -> manager      (Sales supervisor)")
        print("  nino@magti.ge      -> operator       (Support)")
        print("  tech@magti.ge      -> operator       (Support)")
        print("  billing1@magti.ge  -> operator       (Billing)")
        print("  billing2@magti.ge  -> operator       (Billing)")
        print("  sales1@magti.ge    -> operator       (Sales)")
        print("  sales2@magti.ge    -> operator       (Sales)")
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
                    _org_upsert(
                        db, f"{code}.g{g:02d}.op{e:02d}@magti.ge", _org_name(seq),
                        dept_label, position, role, counters,
                    )
                    seq += 1

        db.commit()
        total = counters["created"] + counters["updated"]
        print("=" * 58)
        print("ORG HIERARCHY SEED — idempotent, non-destructive")
        print(f"  დეპარტამენტები: 2 | ჯგუფები: {counters['groups']} (info 10 + tech 5)")
        print(f"  სულ მომხმარებელი: {total}  (ახალი: {counters['created']}, განახლდა: {counters['updated']})")
        print(f"  წევრები ჯგუფზე: 11 (1 ჯგუფის უფროსი + 10 თანამშრომელი)")
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

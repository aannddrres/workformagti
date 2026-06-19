import os, sys
import csv
from datetime import datetime
sys.path.append(os.getcwd())
from database import SessionLocal
from models import Article

# Configuration
PLACEHOLDER_PATH = os.path.join(os.path.dirname(__file__), 'static', 'placeholder.png')
DRY_RUN = '--apply' not in sys.argv

session = SessionLocal()
articles = session.query(Article).order_by(Article.id).all()

# Heuristic for department suggestion based on title keywords (Georgian)
TECH_KEYWORDS = ['ტექნიკური', 'IPTV', 'აპარატურა', 'ტექნიკა']
SUPPORT_KEYWORDS = ['მობილური', 'საინფორმაციო', 'მობილურ', 'ინფორმაცია']

def suggest_department(title):
    low = title.lower()
    if any(k.lower() in low for k in TECH_KEYWORDS):
        return 'Technical'
    if any(k.lower() in low for k in SUPPORT_KEYWORDS):
        return 'Support'
    return 'Support'  # default fallback

report_rows = []
for article in articles:
    suggested = suggest_department(article.title)
    has_image = bool(article.attachment_url)
    placeholder_assigned = False
    if DRY_RUN:
        # just report
        pass
    else:
        # Apply fixes
        if article.target_department != suggested:
            article.target_department = suggested
        if not has_image:
            article.attachment_url = PLACEHOLDER_PATH
            placeholder_assigned = True
        session.add(article)
    report_rows.append([
        article.id,
        article.title,
        article.target_department,
        suggested,
        'Yes' if has_image else 'No',
        'Yes' if placeholder_assigned else 'No'
    ])

if not DRY_RUN:
    session.commit()

# Write CSV report
report_path = os.path.join(os.path.dirname(__file__), 'audit_report.csv')
with open(report_path, 'w', newline='', encoding='utf-8') as f:
    writer = csv.writer(f)
    writer.writerow(['ID', 'Title', 'Current Dept', 'Suggested Dept', 'Has Image', 'Placeholder Assigned'])
    writer.writerows(report_rows)

print(f"Audit report written to {report_path}")
if not DRY_RUN:
    print('Changes have been applied.')
else:
    print('Dry run only – no database changes were made.')

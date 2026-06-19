import os, sys
sys.path.append(os.getcwd())
from database import SessionLocal
from models import Article, Category
session = SessionLocal()
articles = session.query(Article).order_by(Article.id).all()
lines = []
lines.append("ID | Title | Department | Attachment URL")
lines.append("---|---|---|---")
for a in articles:
    attachment = a.attachment_url if a.attachment_url else "(none)"
    # escape pipes
    title = a.title.replace('|','/')
    lines.append(f"{a.id} | {title} | {a.target_department} | {attachment}")
report_path = os.path.join(os.getcwd(),'verify_report.txt')
with open(report_path,'w',encoding='utf-8') as f:
    f.write('\n'.join(lines))
print(f'Report written to {report_path}')

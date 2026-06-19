import os, sys
sys.path.append(os.getcwd())
# Ensure UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')
from database import SessionLocal
from models import Article, Category
session = SessionLocal()
articles = session.query(Article).order_by(Article.id).all()
lines = []
lines.append("ID | Title | Department | Category | Tags | Content Length")
lines.append("---|---|---|---|---|---")
for a in articles:
    cat_obj = session.query(Category).filter(Category.id == a.category_id).first()
    cat_name = cat_obj.name if cat_obj else 'N/A'
    # Escape pipe characters in title
    title = a.title.replace('|','/')
    tags = a.tags.replace('|','/') if a.tags else ''
    lines.append(f"{a.id} | {title} | {a.target_department} | {cat_name} | {tags} | {len(a.content)}")
report_path = os.path.join(os.getcwd(), 'articles_report.txt')
with open(report_path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines))
print(f'Report written to {report_path}')

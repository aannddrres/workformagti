import os, sys
sys.path.append(os.getcwd())
from database import SessionLocal
from models import Article, Category
session = SessionLocal()
articles = session.query(Article).order_by(Article.id).all()
for a in articles:
    cat = session.query(Category).filter(Category.id == a.category_id).first()
    print(f"ID:{a.id} | Title:{a.title} | Dept:{a.target_department} | Category:{cat.name if cat else 'N/A'} | Tags:{a.tags or ''} | ContentLen:{len(a.content)}")

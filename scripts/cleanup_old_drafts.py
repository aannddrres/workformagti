import sys
from datetime import datetime, timedelta

# Ensure the project root is in the Python path
sys.path.append('.')

from sqlalchemy.orm import sessionmaker
from database import engine
import models

def cleanup_drafts():
    """
    Finds all articles where is_draft == True AND updated_at is older than 30 days,
    deletes them, and commits the changes.
    """
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        cutoff_date = datetime.utcnow() - timedelta(days=30)
        drafts_to_delete = db.query(models.Article).filter(
            models.Article.is_draft == True,
            models.Article.updated_at < cutoff_date
        ).all()
        
        count = len(drafts_to_delete)
        if count > 0:
            print(f"Found {count} old drafts to delete.")
            for draft in drafts_to_delete:
                db.delete(draft)
            db.commit()
            print(f"Successfully deleted {count} drafts.")
        else:
            print("No drafts older than 30 days found.")
            
    except Exception as e:
        print(f"An error occurred during draft cleanup: {e}")
        db.rollback()
        raise e
    finally:
        db.close()

if __name__ == "__main__":
    cleanup_drafts()

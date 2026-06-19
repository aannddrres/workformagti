import sys
from datetime import datetime, timedelta

# Ensure the project root is in the Python path
sys.path.append('.')

from sqlalchemy.orm import sessionmaker
from database import engine
import models

# This script should be run from the root of the project inside the container
# Example: docker-compose exec app python scripts/test/create_overdue_reading.py

def create_overdue_test_case():
    """
    Creates a test case for an overdue required reading for the 'Support' department.
    """
    Session = sessionmaker(bind=engine)
    db = Session()

    try:
        print("Creating overdue reading test case...")

        # 1. Find a user in the 'Support' department to verify assignment
        target_user = db.query(models.User).filter(models.User.department == "Support").first()
        if not target_user:
            print("❌ Error: No user found in 'Support' department. Cannot create test case.")
            return

        print(f"✅ Found target user: {target_user.name} in department '{target_user.department}'")

        # 2. Find an article to assign. Let's use the "5G ქსელის დაფარვის ზონები" article.
        target_article = db.query(models.Article).filter(models.Article.title.like("%5G ქსელის%")).first()
        if not target_article:
            print("❌ Error: Could not find the target article '5G ქსელის...'. Seeding might have failed.")
            return
        
        print(f"✅ Found target article: '{target_article.title}' (ID: {target_article.id})")

        # 3. Check if this reading is already assigned to prevent duplicates
        existing_reading = db.query(models.RequiredReading).filter(
            models.RequiredReading.item_type == "article",
            models.RequiredReading.item_id == target_article.id,
            models.RequiredReading.target_department == "Support"
        ).first()

        if existing_reading:
            print(f"⚠️ A required reading for this article already exists (ID: {existing_reading.id}).")
            print("Updating its due_date to yesterday to ensure it's overdue.")
            existing_reading.due_date = datetime.utcnow() - timedelta(days=1)
            db.commit()
            print("✅ Due date updated successfully.")
        else:
            # 4. Create a new RequiredReading entry with a due date of yesterday
            print("Creating new RequiredReading with a past due date...")
            overdue_reading = models.RequiredReading(
                item_type="article",
                item_id=target_article.id,
                target_department="Support",
                due_date=datetime.utcnow() - timedelta(days=1),
                priority="high"
            )
            db.add(overdue_reading)
            db.commit()
            db.refresh(overdue_reading)
            print(f"✅ New required reading created (ID: {overdue_reading.id}).")

        print("\n🎉 Test case setup complete!")
        print("Log in as 'nino@magti.ge' or 'tech@magti.ge' and go to 'სავალდებულოდ გასაცნობი'.")
        print(f"You should see the article '{target_article.title}' with a 'ვადაგადაცილებული' status.")

    except Exception as e:
        print(f"An error occurred: {e}")
        db.rollback()
    finally:
        db.close()

if __name__ == "__main__":
    create_overdue_test_case()
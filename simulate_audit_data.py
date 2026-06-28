import argparse
import random
import sys
from datetime import datetime, timedelta
from database import SessionLocal, get_tbilisi_time
import models

def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except AttributeError:
        pass
    parser = argparse.ArgumentParser(description="Seed compliance read-receipt tracker data for testing.")
    parser.add_argument("--article_id", type=int, required=True, help="Target article ID to attach receipts to.")
    args = parser.parse_args()

    db = SessionLocal()
    try:
        # 1. Fetch Article (with fallback if ID not found)
        article = db.query(models.Article).filter(models.Article.id == args.article_id).first()
        if not article:
            print(f"Article with ID {args.article_id} not found. Falling back to the first available article...")
            article = db.query(models.Article).first()
            if not article:
                print("Error: No articles found in the database.")
                sys.exit(1)
        
        version = article.version

        # 2. Check/Insert RequiredReading deadline
        required = db.query(models.RequiredReading).filter(
            models.RequiredReading.item_type == "article",
            models.RequiredReading.item_id == article.id
        ).first()
        
        if not required:
            due_date = get_tbilisi_time() - timedelta(days=2)
            required = models.RequiredReading(
                item_type="article",
                item_id=article.id,
                target_department="All",
                due_date=due_date,
                priority="normal"
            )
            db.add(required)
            db.flush()
        else:
            due_date = required.due_date

        # 3. Fetch actual operators
        active_users = db.query(models.User).filter(
            models.User.is_active == True,
            models.User.role.notin_(["admin", "content_admin"])
        ).all()

        if not active_users:
            print("Error: No active operators found in the database.")
            sys.exit(1)

        print(f"Targeting article: '{article.title}' (ID: {article.id}, version {version})")
        print(f"Compliance Deadline: {due_date}")
        print(f"Active Operators Count: {len(active_users)}")

        # 4. Strict Group Distribution
        random.shuffle(active_users)
        total = len(active_users)
        
        unread_limit = int(total * 0.30)
        ontime_limit = unread_limit + int(total * 0.50)

        unread_count = 0
        ontime_count = 0
        late_count = 0

        depts = ['Support', 'Billing', 'VIP Concierge', 'Technical Operators']

        for idx, user in enumerate(active_users):
            operator_id = user.id
            name = user.name
            email = user.email
            dept = user.department or random.choice(depts)

            if idx < unread_limit:
                # 30% Unread
                unread_count += 1
                db.query(models.ArticleReadReceipt).filter(
                    models.ArticleReadReceipt.article_id == article.id,
                    models.ArticleReadReceipt.operator_id == operator_id,
                    models.ArticleReadReceipt.article_version == version
                ).delete()
            elif idx < ontime_limit:
                # 50% On-Time
                ontime_count += 1
                read_at = due_date - timedelta(days=4)
                
                receipt = db.query(models.ArticleReadReceipt).filter(
                    models.ArticleReadReceipt.article_id == article.id,
                    models.ArticleReadReceipt.operator_id == operator_id,
                    models.ArticleReadReceipt.article_version == version
                ).first()
                if not receipt:
                    receipt = models.ArticleReadReceipt(
                        article_id=article.id,
                        operator_id=operator_id,
                        article_version=version,
                        article_title_snapshot=article.title
                    )
                    db.add(receipt)
                receipt.operator_name_snapshot = name
                receipt.operator_email_snapshot = email
                receipt.operator_department_snapshot = dept
                receipt.read_at = read_at
            else:
                # 20% Late
                late_count += 1
                read_at = due_date + timedelta(days=2)
                
                receipt = db.query(models.ArticleReadReceipt).filter(
                    models.ArticleReadReceipt.article_id == article.id,
                    models.ArticleReadReceipt.operator_id == operator_id,
                    models.ArticleReadReceipt.article_version == version
                ).first()
                if not receipt:
                    receipt = models.ArticleReadReceipt(
                        article_id=article.id,
                        operator_id=operator_id,
                        article_version=version,
                        article_title_snapshot=article.title
                    )
                    db.add(receipt)
                receipt.operator_name_snapshot = name
                receipt.operator_email_snapshot = email
                receipt.operator_department_snapshot = dept
                receipt.read_at = read_at

        db.commit()
        print("\n--- Strict Simulation Summary ---")
        print(f"Total processed users: {total}")
        print(f"Unread (Deleted):      {unread_count} ({unread_count/total*100:.1f}%)")
        print(f"Read On-Time (-4 days): {ontime_count} ({ontime_count/total*100:.1f}%)")
        print(f"Read Late (+2 days):   {late_count} ({late_count/total*100:.1f}%)")
        print("---------------------------------")

    except Exception as e:
        db.rollback()
        print(f"Database transaction error: {e}")
        sys.exit(1)
    finally:
        db.close()

if __name__ == "__main__":
    main()

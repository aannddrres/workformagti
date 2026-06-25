import sys
# Ensure the project root is importable when run from the repo root
sys.path.append('.')
from sqlalchemy.orm import Session

from database import SessionLocal
from models import User
from security import get_password_hash, DEFAULT_PERMISSIONS_BY_ROLE

def seed_test_users():
    db: Session = SessionLocal()
    try:
        print("Seeding 300 test operators...")
        for i in range(1, 301):
            email = f"test_operator_{i}@magti.ge"
            if not db.query(User).filter(User.email == email).first():
                user = User(
                    email=email,
                    name=f"Test Operator {i}",
                    hashed_password=get_password_hash(f"MagtiTest{i}!"),
                    role="operator",
                    department="Support",
                    is_active=True,
                    permissions=DEFAULT_PERMISSIONS_BY_ROLE["operator"],
                )
                db.add(user)
        db.commit()
        print("Successfully seeded 300 test users.")
    finally:
        db.close()

if __name__ == "__main__":
    seed_test_users()
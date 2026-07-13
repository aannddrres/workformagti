import os
import shutil
import datetime
import subprocess
from config import settings

def create_backup():
    """
    ქმნის პროექტის მონაცემთა ბაზისა და ატვირთული ფაილების სარეზერვო ასლს (ZIP ფორმატში).
    ინახავს მას 'backups' დირექტორიაში.
    """
    # ფაილების და ფოლდერების სახელები
    uploads_dir = settings.UPLOAD_DIR
    backup_dir = "backups"
    
    # თარიღის გენერაცია ფაილის სახელისთვის
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    backup_filename = f"magti_portal_backup_{timestamp}"
    backup_path = os.path.join(backup_dir, backup_filename)
    
    # ვქმნით backups ფოლდერს თუ არ არსებობს
    os.makedirs(backup_dir, exist_ok=True)
    
    # ვქმნით დროებით ფოლდერს ფაილების შესაგროვებლად
    temp_dir = os.path.join(backup_dir, "temp_" + timestamp)
    os.makedirs(temp_dir, exist_ok=True)
    
    try:
        print("მიმდინარეობს სარეზერვო ასლის შექმნა...")
        
        # 1. მონაცემთა ბაზის კოპირება
        if settings.is_sqlite:
            db_file = "magti_portal.db"
            if os.path.exists(db_file):
                shutil.copy2(db_file, temp_dir)
                print(f"  - დაკოპირდა {db_file}")
        else:
            print("  - მიმდინარეობს PostgreSQL ბაზის ექსპორტი (pg_dump)...")
            # pg_dump-ს ჭირდება სუფთა postgresql:// ლინკი (+psycopg2-ის გარეშე)
            db_url = settings.DATABASE_URL.replace("+psycopg2", "")
            dump_path = os.path.join(temp_dir, "database.dump")
            subprocess.run(["pg_dump", db_url, "-F", "c", "-f", dump_path], check=True)
            print("  - ბაზის ექსპორტი დასრულდა.")
            
        # 2. Uploads ფოლდერის კოპირება
        if os.path.exists(uploads_dir):
            shutil.copytree(uploads_dir, os.path.join(temp_dir, "uploads"))
            print(f"  - დაკოპირდა {uploads_dir}/ დირექტორია")
            
        # 3. დაზიპვა
        shutil.make_archive(backup_path, 'zip', temp_dir)
        print(f"\n✅ სარეზერვო ასლი წარმატებით შეიქმნა: {backup_path}.zip")
        
    except Exception as e:
        print(f"\n❌ შეცდომა ბექაფის შექმნისას: {e}")
    finally:
        if os.path.exists(temp_dir):
            shutil.rmtree(temp_dir)

if __name__ == "__main__":
    create_backup()
    # Data-lifecycle companion: archive-then-purge audit/view rows older than
    # the retention window (see retention.py). Runs after the backup so the
    # freshly-purged rows are always still present in today's backup ZIP.
    from retention import run_retention
    run_retention()
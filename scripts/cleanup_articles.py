import sqlite3
import re
import shutil
import os

def main():
    db_path = "magti_portal.db"
    backup_path = "magti_portal.db.pre_cleanup"
    
    # 1. Backup First
    if os.path.exists(db_path):
        print(f"Creating database backup at {backup_path}...")
        shutil.copyfile(db_path, backup_path)
    else:
        print(f"Error: {db_path} not found.")
        return
        
    # 2. Database Connection
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # Get database tables
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
    tables = [row[0] for row in cursor.fetchall()]
    print(f"Found tables in database: {tables}")
    
    # Define target tables
    target_tables = []
    if "articles" in tables:
        target_tables.append("articles")
    if "news" in tables:
        target_tables.append("news")
        
    if not target_tables:
        print("No target tables found (expected 'articles' or 'news').")
        conn.close()
        return

    # Regular expression for extracting link text from anchor tags
    link_pattern = re.compile(r'<a\s+[^>]*>(.*?)</a>', re.DOTALL | re.IGNORECASE)
    
    # Google Sites footer tokens to remove
    footer_tokens = ["Page updated", "Google Sites", "Report abuse"]
    
    # Compile regexes to match the exact footer tokens along with surrounding whitespace/newlines
    token_patterns = [
        re.compile(rf"\s*{re.escape(token)}\s*", re.IGNORECASE)
        for token in footer_tokens
    ]
    
    for table in target_tables:
        print(f"Processing table: {table}")
        
        # Read all rows
        cursor.execute(f"SELECT id, content FROM {table}")
        rows = cursor.fetchall()
        
        updated_count = 0
        for row_id, content in rows:
            if not content:
                continue
                
            cleaned = content
            
            # Clean broken links
            cleaned = link_pattern.sub(r'\1', cleaned)
            
            # Remove Google Sites footer tokens and surrounding whitespace
            for pattern in token_patterns:
                cleaned = pattern.sub("", cleaned)
                
            # Update row in DB if changes were made
            if cleaned != content:
                cursor.execute(f"UPDATE {table} SET content = ? WHERE id = ?", (cleaned, row_id))
                updated_count += 1
                
        print(f"Successfully cleaned and updated {updated_count} rows in table '{table}'.")
        
    # 4. Save Changes
    conn.commit()
    conn.close()
    print("Cleanup completed successfully.")

if __name__ == "__main__":
    main()

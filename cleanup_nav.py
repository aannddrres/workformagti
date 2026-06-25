import sqlite3
import sys
import shutil
import os
from bs4 import BeautifulSoup

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    
    db_path = "magti_portal.db"
    backup_path = "magti_portal.db.pre_nav"
    
    # 1. Backup DB
    if os.path.exists(db_path):
        print(f"Backing up database to {backup_path}...")
        shutil.copyfile(db_path, backup_path)
    else:
        print(f"Error: {db_path} not found.")
        sys.exit(1)
        
    # 2. Database Connection
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # Fetch all article and news titles to build the reference set
    cursor.execute("SELECT title FROM articles")
    titles = {row[0].strip() for row in cursor.fetchall() if row[0]}
    
    cursor.execute("SELECT title FROM news")
    titles.update({row[0].strip() for row in cursor.fetchall() if row[0]})
    
    print(f"Loaded {len(titles)} titles for navigation list detection reference.")
    
    # 3. Read articles table
    cursor.execute("SELECT id, title, content FROM articles")
    articles = cursor.fetchall()
    
    updated_count = 0
    
    for art_id, title, content in articles:
        if not content:
            continue
            
        soup = BeautifulSoup(content, 'html.parser')
        
        # We target top-level child elements of the parsed tree.
        # Since the scraper wraps content, top-level children might be section elements.
        sections = soup.find_all('section')
        if not sections:
            # If no sections, fall back to top-level children directly
            children = list(soup.children)
        else:
            children = sections
            
        elements_to_remove = []
        has_changes = False
        
        for child in children:
            if hasattr(child, 'name') and child.name is None:
                # NavigableString (loose text) at the root
                continue
                
            text_content = child.get_text().strip() if hasattr(child, 'get_text') else ""
            if not text_content:
                # Empty structural element/spacer, remove it if it is before any content
                elements_to_remove.append(child)
                has_changes = True
                continue
                
            # Get all sub-elements that might contain title texts
            # We look for paragraphs, list items, anchor tags, and span tags.
            sub_elements = child.find_all(['p', 'li', 'a', 'span']) if hasattr(child, 'find_all') else []
            lines = [el.get_text().strip() for el in sub_elements if el.get_text().strip()]
            
            if not lines:
                # If there are no structured sub-elements, fall back to direct stripped strings
                lines = [s.strip() for s in child.stripped_strings if s.strip()]
                
            if not lines:
                # Empty block
                elements_to_remove.append(child)
                has_changes = True
                continue
                
            # Count how many lines match other article/news titles
            other_title_matches = 0
            for line in lines:
                if line in titles and line != title:
                    other_title_matches += 1
                    
            match_ratio = other_title_matches / len(lines) if lines else 0
            
            # If the block has a high density of other page titles, it's a navigation menu section.
            # We require at least 1 match and ratio >= 0.7 to identify it as a menu.
            if other_title_matches >= 1 and match_ratio >= 0.7:
                elements_to_remove.append(child)
                has_changes = True
            else:
                # Stop processing top-level elements immediately once we reach actual content
                break
                
        if has_changes and elements_to_remove:
            # Remove marked elements from the tree
            for el in elements_to_remove:
                el.decompose()
                
            # Convert back to HTML string
            new_content = str(soup)
            
            # Update the database
            cursor.execute("UPDATE articles SET content = ? WHERE id = ?", (new_content, art_id))
            updated_count += 1
            print(f"Updated article ID {art_id} ({title}): Removed {len(elements_to_remove)} structural nav/spacer element(s).")
            
    # 4. Commit and Close
    conn.commit()
    conn.close()
    
    print(f"Cleanup successfully completed. Updated {updated_count} articles.")

if __name__ == "__main__":
    main()

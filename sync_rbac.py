import os
import sys
from sqlalchemy.orm import sessionmaker
from database import engine
import models
import security

def parse_markdown_rbac():
    doc_path = "ინფო_დეველოპერებისთვის.md"
    if not os.path.exists(doc_path):
        print(f"Error: {doc_path} not found.")
        sys.exit(1)

    with open(doc_path, "r", encoding="utf-8") as f:
        content = f.read()

    table_rows = []
    in_table = False
    for line in content.splitlines():
        if "როლი" in line and "წვდომა" in line and "მოქმედებები" in line:
            in_table = True
            continue
        if in_table:
            if line.strip().startswith("|"):
                if "---" in line:
                    continue
                table_rows.append(line)
            else:
                if table_rows: # break if we're done with the table
                    break

    if not table_rows:
        print("Error: Could not locate the RBAC table in the documentation.")
        sys.exit(1)
    
    role_mapping = {}
    for row in table_rows:
        parts = [p.strip() for p in row.split("|")[1:-1]]
        if len(parts) < 3:
            continue
        
        role_name_geo = parts[0]
        actions_geo = parts[2]
        
        # Identify the role key
        if "ოპერატორი" in role_name_geo:
            role_key = security.ROLE_OPERATOR
        elif "მენეჯერი" in role_name_geo or "სუპერვაიზერი" in role_name_geo:
            role_key = security.ROLE_MANAGER
        elif "კონტენტი" in role_name_geo:
            role_key = security.ROLE_CONTENT_ADMIN
        elif "სისტემური" in role_name_geo:
            role_key = security.ROLE_SYSTEM_ADMIN
        else:
            continue

        role_mapping[role_key] = actions_geo

    return role_mapping

def map_permissions(role_mapping):
    all_permissions = [
        security.PERM_ARTICLES_VIEW,
        security.PERM_ARTICLES_EDIT,
        security.PERM_ARTICLES_PUBLISH,
        security.PERM_ARTICLES_ARCHIVE,
        security.PERM_VIDEOS_ARCHIVE,
        security.PERM_USERS_MANAGE,
        security.PERM_COMPLIANCE_ASSIGN,
        security.PERM_REPORTS_EXPORT
    ]

    mapped_perms = {}
    for role, actions in role_mapping.items():
        perms = []
        if role == security.ROLE_SYSTEM_ADMIN:
            perms = all_permissions.copy()
        elif role == security.ROLE_CONTENT_ADMIN:
            perms = [
                security.PERM_ARTICLES_VIEW,
                security.PERM_ARTICLES_EDIT,
                security.PERM_ARTICLES_PUBLISH,
                security.PERM_ARTICLES_ARCHIVE,
                security.PERM_VIDEOS_ARCHIVE
            ]
        elif role == security.ROLE_MANAGER:
            perms = [
                security.PERM_COMPLIANCE_ASSIGN,
                security.PERM_REPORTS_EXPORT
            ]
        elif role == security.ROLE_OPERATOR:
            perms = []
            
        mapped_perms[role] = perms

    return mapped_perms

def main():
    commit = "--commit" in sys.argv
    
    role_actions = parse_markdown_rbac()
    target_permissions = map_permissions(role_actions)
    
    all_permissions = [
        security.PERM_ARTICLES_VIEW,
        security.PERM_ARTICLES_EDIT,
        security.PERM_ARTICLES_PUBLISH,
        security.PERM_ARTICLES_ARCHIVE,
        security.PERM_VIDEOS_ARCHIVE,
        security.PERM_USERS_MANAGE,
        security.PERM_COMPLIANCE_ASSIGN,
        security.PERM_REPORTS_EXPORT
    ]

    print("=" * 90)
    print("RBAC SYNC DRY-RUN MATRIX AUDIT (Role vs Permissions)")
    print("=" * 90)
    print(f"{'Permission':<28} | {'operator':<10} | {'manager':<10} | {'content_admin':<13} | {'admin':<10}")
    print("-" * 90)
    
    for perm in all_permissions:
        row_str = f"{perm:<28} | "
        for r in [security.ROLE_OPERATOR, security.ROLE_MANAGER, security.ROLE_CONTENT_ADMIN, security.ROLE_SYSTEM_ADMIN]:
            has_perm = perm in target_permissions.get(r, [])
            val = "GRANT" if has_perm else "REVOKE"
            row_str += f"{val:<10} | "
        print(row_str[:-3])
    print("=" * 90)

    # Initialize SQLAlchemy
    Session = sessionmaker(bind=engine)
    session = Session()
    
    try:
        users = session.query(models.User).all()
        print("\nProposed Changes to Users:")
        print(f"{'User Email':<25} | {'Role':<15} | {'Permissions Action'}")
        print("-" * 90)
        
        changes_count = 0
        for user in users:
            old_perms = set(user.permissions or [])
            new_perms = set(target_permissions.get(user.role, []))
            
            if old_perms != new_perms:
                granted = new_perms - old_perms
                revoked = old_perms - new_perms
                action_desc = []
                if granted:
                    action_desc.append(f"Grant: {list(granted)}")
                if revoked:
                    action_desc.append(f"Revoke: {list(revoked)}")
                print(f"{user.email:<25} | {user.role:<15} | {', '.join(action_desc)}")
                user.permissions = list(new_perms)
                changes_count += 1
            else:
                print(f"{user.email:<25} | {user.role:<15} | NO CHANGE")
                
        if commit:
            session.commit()
            print(f"\nSuccessfully committed changes for {changes_count} users to the database.")
        else:
            session.rollback()
            print(f"\nDRY RUN: Rollback performed. {changes_count} users would have been updated.")
            print("Run with '--commit' to apply changes.")
            
    except Exception as e:
        session.rollback()
        print(f"Error executing sync: {e}")
        sys.exit(1)
    finally:
        session.close()

if __name__ == "__main__":
    main()

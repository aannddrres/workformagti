import sys
import os

# Ensure the project root is in the Python path
sys.path.append('.')

from sqlalchemy.orm import sessionmaker
from database import engine
import models
import security

def seed_rbac():
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        print("Starting RBAC seeding...")
        
        # 1. Define the 13 permissions
        permissions_list = [
            ("users:manage", "Manage portal users and accounts"),
            ("content:editor", "Create and edit knowledge base articles/news drafts"),
            ("content:publisher", "Publish, schedule and archive knowledge base content"),
            ("compliance:manage", "Assign required reading compliance checklists"),
            ("reports:view_global", "View aggregate portal performance KPIs and department statistics"),
            ("reports:export", "Export standard reading compliance reports (Excel)"),
            ("reports:export_sensitive", "Export detailed audit logs and sensitive employee progress data"),
            ("communication:broadcast", "Send system-wide broadcast banner notifications"),
            ("system:audit", "Access DDL and administrative action logs"),
            ("content:archive", "Archive knowledge base articles and news"),
            ("compliance:assign", "Assign compliance reading requirements to users"),
            ("feedback:resolve", "Resolve or reject crowdsourced feedback reports"),
            ("reports:export_personal_data", "Export sensitive reports containing personal data")
        ]
        
        # 2. Upsert permissions
        db_permissions = {}
        perms_created = 0
        for name, desc in permissions_list:
            perm = db.query(models.Permission).filter(models.Permission.name == name).first()
            if not perm:
                perm = models.Permission(name=name, description=desc)
                db.add(perm)
                perms_created += 1
                print(f"Created permission: {name}")
            else:
                perm.description = desc
                print(f"Permission already exists: {name}")
            db_permissions[name] = perm
            
        db.flush()
        
        # 3. Define the roles and their corresponding permission names
        role_bindings = {
            security.ROLE_SYSTEM_ADMIN: [name for name, _ in permissions_list],
            security.ROLE_CONTENT_ADMIN: [
                "content:editor", "content:publisher", "compliance:manage", 
                "reports:view_global", "reports:export", "communication:broadcast", 
                "system:audit", "content:archive", "compliance:assign", "feedback:resolve"
            ],
            security.ROLE_MANAGER: [
                "compliance:manage", "reports:export",
                "content:archive", "compliance:assign", "feedback:resolve",
                "system:audit"  # own-department-scoped audit view — see migrate.py's ensure_system_audit_permission_seeded
            ],
            security.ROLE_OPERATOR: []
        }
        
        # 4. Upsert roles and bind permissions
        roles_created = 0
        for role_name, perm_names in role_bindings.items():
            role = db.query(models.Role).filter(models.Role.name == role_name).first()
            if not role:
                role = models.Role(name=role_name, description=f"{role_name.capitalize()} role")
                db.add(role)
                roles_created += 1
                print(f"Created role: {role_name}")
            else:
                print(f"Role already exists: {role_name}")
                
            db.flush()
            
            # Clear existing bindings for this role to prevent duplicate associations
            db.query(models.RolePermission).filter(models.RolePermission.role_id == role.id).delete()
            
            # Create new associations
            for p_name in perm_names:
                perm_obj = db_permissions[p_name]
                assoc = models.RolePermission(role_id=role.id, permission_id=perm_obj.id)
                db.add(assoc)
                
            print(f"Bound {len(perm_names)} permissions to role '{role_name}'")
            
        db.commit()
        print("\nSeeding complete!")
        print(f"Summary: Seeded {perms_created} new permissions and {roles_created} new roles.")
        
    except Exception as e:
        print(f"Error seeding database: {e}")
        db.rollback()
        raise e
    finally:
        db.close()

if __name__ == "__main__":
    seed_rbac()

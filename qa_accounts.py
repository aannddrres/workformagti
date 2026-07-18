"""QA / seed test accounts (scripts/seed_portal.py `users` mode, docs/SEED_GUIDE.md).

Lives outside security.py so the core auth/RBAC module doesn't carry QA
fixture data — security.py is auth/RBAC logic, this is test data that
happens to reuse its role constants.

Standing login set independent of the full org seed. Naming: {role}.{dept_code}@magti.ge,
dept_code matches seed_portal.py's ORG_TECH/ORG_INFO/ORG_OFFICE codes (tech/info/office).
"""
from security import ROLE_CONTENT_ADMIN, ROLE_MANAGER, ROLE_OPERATOR, ROLE_SYSTEM_ADMIN

TEST_ACCOUNT_PASSWORD = "Test1234!"

TEST_ACCOUNTS: list[dict] = [
    {"email": "sysadmin@magti.ge", "name": "სისტემური ადმინისტრატორი", "role": ROLE_SYSTEM_ADMIN, "department": "All"},
    {"email": "admin@magti.ge", "name": "პორტალის ადმინი", "role": ROLE_SYSTEM_ADMIN, "department": "All"},
    {"email": "content@magti.ge", "name": "კონტენტის ადმინისტრატორი", "role": ROLE_CONTENT_ADMIN, "department": "All"},
    {"email": "manager@magti.ge", "name": "ტესტ მენეჯერი", "role": ROLE_MANAGER, "department": "All"},
    {"email": "operator@magti.ge", "name": "ტესტ ოპერატორი", "role": ROLE_OPERATOR, "department": "All"},

    {"email": "admin.tech@magti.ge", "name": "ადმინი (ტექნიკური)", "role": ROLE_SYSTEM_ADMIN, "department": "ტექნიკური"},
    {"email": "manager.tech@magti.ge", "name": "მენეჯერი (ტექნიკური)", "role": ROLE_MANAGER, "department": "ტექნიკური — ჯგუფი 01"},
    {"email": "operator.tech@magti.ge", "name": "ოპერატორი (ტექნიკური) 1", "role": ROLE_OPERATOR, "department": "ტექნიკური — ჯგუფი 01"},
    {"email": "operator2.tech@magti.ge", "name": "ოპერატორი (ტექნიკური) 2", "role": ROLE_OPERATOR, "department": "ტექნიკური — ჯგუფი 01"},

    {"email": "admin.info@magti.ge", "name": "ადმინი (საინფორმაციო)", "role": ROLE_SYSTEM_ADMIN, "department": "საინფორმაციო"},
    {"email": "manager.info@magti.ge", "name": "მენეჯერი (საინფორმაციო)", "role": ROLE_MANAGER, "department": "საინფორმაციო — ჯგუფი 01"},
    {"email": "operator.info@magti.ge", "name": "ოპერატორი (საინფორმაციო) 1", "role": ROLE_OPERATOR, "department": "საინფორმაციო — ჯგუფი 01"},
    {"email": "operator2.info@magti.ge", "name": "ოპერატორი (საინფორმაციო) 2", "role": ROLE_OPERATOR, "department": "საინფორმაციო — ჯგუფი 01"},

    {"email": "admin.office@magti.ge", "name": "ადმინი (ოფისი)", "role": ROLE_SYSTEM_ADMIN, "department": "ოფისი"},
    {"email": "manager.office@magti.ge", "name": "მენეჯერი (ოფისი)", "role": ROLE_MANAGER, "department": "ოფისი — ჯგუფი 01"},
    {"email": "operator.office@magti.ge", "name": "ოპერატორი (ოფისი) 1", "role": ROLE_OPERATOR, "department": "ოფისი — ჯგუფი 01"},
    {"email": "operator2.office@magti.ge", "name": "ოპერატორი (ოფისი) 2", "role": ROLE_OPERATOR, "department": "ოფისი — ჯგუფი 01"},

    {"email": "manager2.tech@magti.ge", "name": "მენეჯერი (ტექნიკური) 2", "role": ROLE_MANAGER, "department": "ტექნიკური — ჯგუფი 02"},
    {"email": "operator3.tech@magti.ge", "name": "ოპერატორი (ტექნიკური) 3", "role": ROLE_OPERATOR, "department": "ტექნიკური — ჯგუფი 02"},
    {"email": "operator3.info@magti.ge", "name": "ოპერატორი (საინფორმაციო) 3", "role": ROLE_OPERATOR, "department": "საინფორმაციო — ჯგუფი 02"},
]

import sys

with open('main.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = 'if db.query(models.User).filter(models.User.email == payload.email).first():'
new = 'if db.query(models.User).filter(models.User.email == payload.email.lower()).first():'

if old in content:
    with open('main.py', 'w', encoding='utf-8') as f:
        f.write(content.replace(old, new))
    print('Patched check in main.py')
else:
    print('Check not found')

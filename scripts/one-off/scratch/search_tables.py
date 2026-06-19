import re
import sys
sys.stdout.reconfigure(encoding='utf-8')

with open('base-layout.html', 'r', encoding='utf-8') as f:
    content = f.read()

idx = content.find('id="page-admin"')
if idx == -1:
    print("page-admin not found")
    sys.exit()

for m in re.finditer(r'<table[^>]*>', content[idx:]):
    abs_pos = idx + m.start()
    line_no = content[:abs_pos].count('\n') + 1
    start = max(0, abs_pos - 100)
    end = min(len(content), abs_pos + 250)
    context = content[start:end].replace('\n', ' ')
    print(f"Line {line_no}: {context}")
    print("-" * 50)

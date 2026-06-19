import re
import sys
sys.stdout.reconfigure(encoding='utf-8')

with open('base-layout.html', 'r', encoding='utf-8') as f:
    content = f.read()

for m in re.finditer(r'news-badge|sidebar-news-badge|badge', content, re.IGNORECASE):
    line_no = content[:m.start()].count('\n') + 1
    start = max(0, m.start() - 60)
    end = min(len(content), m.end() + 100)
    context = content[start:end].replace('\n', ' ')
    print(f"Line {line_no}: match '{m.group(0)}': {context}")

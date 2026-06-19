# -*- coding: utf-8 -*-
with open(r'c:\Users\nikaa\OneDrive\Desktop\Magti base\base-layout.html', 'r', encoding='utf-8') as f:
    lines = f.readlines()

results = []
for i, line in enumerate(lines):
    if 'id="page-news"' in line or 'switchMainPage(\'page-news\'' in line or 'renderNews' in line or 'fetchAndRenderNews' in line:
        results.append(f"{i+1}: {line.strip()}")

# Write to file
with open('news_page_analysis.txt', 'w', encoding='utf-8') as out:
    out.write('\n'.join(results))
print("Done")

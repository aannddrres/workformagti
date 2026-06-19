# -*- coding: utf-8 -*-
import os

keywords = ['news', 'department', 'მობილ', 'ტექნიკ', 'საინფორმ', 'target_department', 'Support', 'Informational', 'Technical', 'Mobile']
results = []

path = r'c:\Users\nikaa\OneDrive\Desktop\Magti base'
for f in os.listdir(path):
    if f.endswith('.py'):
        p = os.path.join(path, f)
        try:
            with open(p, 'r', encoding='utf-8') as file:
                for i, line in enumerate(file):
                    found = [kw for kw in keywords if kw in line]
                    if found:
                        results.append(f"{f}:{i+1} ({', '.join(found)}): {line.strip()}")
        except Exception as e:
            results.append(f"Error reading {f}: {str(e)}")

with open('migration_search.txt', 'w', encoding='utf-8') as out:
    out.write('\n'.join(results))
print("Done")

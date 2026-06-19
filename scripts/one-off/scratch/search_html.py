import re
import sys

# Ensure UTF-8 output on console
sys.stdout.reconfigure(encoding='utf-8')

def search_pattern(pattern, context_lines=5):
    with open('base-layout.html', 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    matches = []
    regex = re.compile(pattern, re.IGNORECASE)
    for idx, line in enumerate(lines):
        if regex.search(line):
            matches.append(idx)
            
    if not matches:
        print(f"No matches found for pattern: {pattern}")
        return
        
    print(f"Found {len(matches)} matches:")
    for match_idx in matches:
        print(f"\n--- Match at line {match_idx + 1} ---")
        start = max(0, match_idx - context_lines)
        end = min(len(lines), match_idx + context_lines + 1)
        for i in range(start, end):
            prefix = "--> " if i == match_idx else "    "
            print(f"{prefix}{i+1}: {lines[i]}", end="")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python search_html.py <pattern> [context_lines]")
    else:
        pattern = sys.argv[1]
        context = int(sys.argv[2]) if len(sys.argv) > 2 else 5
        search_pattern(pattern, context)

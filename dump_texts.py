import re, sys
fn = sys.argv[1]
xml = open(fn, encoding='utf-8', errors='replace').read()
texts = re.findall(r'text="([^"]*)"', xml) + re.findall(r"text='([^']*)'", xml)
seen = set()
for t in texts:
    if t.strip() and t not in seen:
        seen.add(t)
        print(repr(t[:150]))
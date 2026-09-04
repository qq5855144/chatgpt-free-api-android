import re, sys
fn = sys.argv[1] if len(sys.argv) > 1 else '/sdcard/Download/ui11.xml'
xml = open(fn, encoding='utf-8', errors='replace').read()
texts = re.findall(r'text="([^"]*)"', xml)
for t in texts:
    if True:
        t2 = t.replace('&#10;', '\n').replace('"', '"').replace('&lt;', '<').replace('&gt;', '>')
        print(t2)
        print('======')
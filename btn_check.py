import re
xml = open('/sdcard/Download/v6.xml', encoding='utf-8', errors='replace').read()
for m in re.finditer(r'<node[^>]*text="([\u2464-\u246b][^"]*)"[^>]*>', xml):
    node = m.group(0)
    t = m.group(1)
    en = re.search(r'enabled="(\w+)"', node)
    cl = re.search(r'clickable="(\w+)"', node)
    bd = re.search(r'bounds="(\[[^"]*\])"', node)
    print(t, '| enabled=', en.group(1) if en else '?', '| clickable=', cl.group(1) if cl else '?', '|', bd.group(1) if bd else '?')
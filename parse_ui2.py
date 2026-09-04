import re, sys
fn = sys.argv[1] if len(sys.argv) > 1 else '/sdcard/Download/v7.xml'
xml = open(fn, encoding='utf-8', errors='replace').read()
# 匹配单引号或双引号的 text 属性
texts = re.findall(r'text=("|\')(.*?)\1', xml)
for q, t in texts:
    if len(t) > 20:
        t2 = t.replace('&#10;', '\n').replace('"', '"').replace('&lt;', '<').replace('&gt;', '>')
        print(t2)
        print('======')
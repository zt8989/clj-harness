import sys, json, collections

path = sys.argv[1]
c = collections.Counter()
b = collections.Counter()
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        n = len(line.encode("utf-8"))
        try:
            row = json.loads(line)
        except Exception:
            c["UNPARSEABLE"] += 1
            b["UNPARSEABLE"] += n
            continue
        t = row.get("type")
        p = row.get("payload") or {}
        if isinstance(p, dict):
            key = f'{t}:{p.get("name") or p.get("type")}'
        else:
            key = f"{t}:?"
        c[key] += 1
        b[key] += n

for k, v in b.most_common(20):
    print(f"{v/1048576:9.2f} MB  {v*100/sum(b.values()):5.1f}%  {c[k]:8d}  {k}")

import json, sys, datetime, os

p = sys.argv[1]
def when(ts):
    return datetime.datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S")

rows = []
with open(p, encoding="utf-8") as f:
    for i, line in enumerate(f):
        try:
            rows.append((i, json.loads(line)))
        except Exception:
            pass

print(f"{p}\n{len(rows)} rows; first ts {when(rows[0][1]['ts'])} last ts {when(rows[-1][1]['ts'])}\n")

def describe(row):
    ts = row.get("ts"); typ = row.get("type"); pl = row.get("payload") or {}
    out = f"{when(ts)}  {typ} "
    if typ == "event":
        out += f"name={pl.get('name')} value={json.dumps(pl.get('value'), ensure_ascii=False)[:120]}"
    elif typ == "message":
        c = pl.get("content")
        text = c if isinstance(c, str) else json.dumps(c, ensure_ascii=False)
        out += f"role={pl.get('role')} source={pl.get('source')} text={text[:110]!r}"
    else:
        out += json.dumps(pl, ensure_ascii=False)[:140]
    return out

print("--- first 12 rows")
for i, r in rows[:12]:
    print(f"  line {i}: {describe(r)}")
print(f"\n--- bind/run boundaries")
for i, r in rows:
    pl = r.get("payload") or {}
    if r.get("type") == "event" and pl.get("name") in ("project/bound", "provider/init", "provider/changed"):
        print(f"  line {i}: {describe(r)}")
print("\n--- the user messages (first 6)")
n = 0
for i, r in rows:
    pl = r.get("payload") or {}
    if r.get("type") == "message" and pl.get("role") == "user":
        print(f"  line {i}: {describe(r)}")
        n += 1
        if n >= 6:
            break
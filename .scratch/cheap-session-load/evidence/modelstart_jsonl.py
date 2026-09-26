import sys, json

path = sys.argv[1]
shown = 0
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        p = row.get("payload") or {}
        if p.get("name") == "model/start":
            print("keys:", sorted(p.get("value", {}).keys()))
            v = p.get("value", {})
            for k, val in v.items():
                s = json.dumps(val, ensure_ascii=False)
                print(f"   {k}: {len(s.encode('utf-8'))} bytes", s[:120].replace("\n", " "))
            shown += 1
            if shown >= 2:
                break

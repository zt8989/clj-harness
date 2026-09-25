import json, os, sys, datetime

p = r"C:\Users\zhouteng\.clj-harness\projects\C__Users_zhouteng_Documents_workspace_lisp-harness\b38aad31-1903-43ed-88b9-c69e59eabb33.jsonl"
want = sys.argv[1:] or None

def when(ts):
    return datetime.datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S")

with open(p, encoding="utf-8") as f:
    for i, line in enumerate(f):
        try:
            row = json.loads(line)
        except Exception:
            continue
        ts = row.get("ts")
        typ = row.get("type")
        pl = row.get("payload") or {}
        role = pl.get("role")
        text = ""
        if role == "user":
            c = pl.get("content")
            text = (c if isinstance(c, str) else json.dumps(c, ensure_ascii=False))[:90]
        elif typ == "event" and (pl.get("type") == "CUSTOM"):
            text = f"[{pl.get('name')}]"
        if role == "user" and ts:
            print(f"{when(ts)}  line {i}  USER  {text}")
        elif want and ts and any(w in str(ts) for w in want):
            print(f"{when(ts)}  line {i}  {typ} {text[:100]}")
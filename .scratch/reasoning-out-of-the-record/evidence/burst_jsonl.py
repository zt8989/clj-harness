import sys, json, collections

path = sys.argv[1]
per_sec = collections.Counter()
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        p = row.get("payload")
        if isinstance(p, dict) and p.get("type") == "REASONING_MESSAGE_CONTENT":
            per_sec[row["ts"] // 1000] += 1

secs = sorted(per_sec)
top = per_sec.most_common(5)
print("busiest seconds (frames/sec):", [v for _, v in top])
vals = sorted(per_sec.values())
n = len(vals)
print("seconds with reasoning:", n)
print("per-second p50", vals[n // 2], "p90", vals[int(n * 0.9)], "p99", vals[int(n * 0.99)], "max", vals[-1])

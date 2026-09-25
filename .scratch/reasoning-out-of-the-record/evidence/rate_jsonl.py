import sys, json

path = sys.argv[1]
first = None
last = None
n = 0
sample = None
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        p = row.get("payload")
        if not isinstance(p, dict) or p.get("type") != "REASONING_MESSAGE_CONTENT":
            continue
        n += 1
        ts = row.get("ts")
        if first is None:
            first = ts
            sample = line.rstrip("\n")
        last = ts

print("frames", n)
print("span seconds", round((last - first) / 1000, 1))
print("frames/sec", round(n / ((last - first) / 1000), 1))
print()
print("one sample line, verbatim:")
print(sample)
print()
print("its bytes", len(sample.encode("utf-8")))

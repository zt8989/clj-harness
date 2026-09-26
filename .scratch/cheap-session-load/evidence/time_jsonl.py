import json, sys, time

path = sys.argv[1]
lines = []
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        lines.append(line.rstrip("\n"))

total = sum(len(l.encode("utf-8")) for l in lines)
print("lines", len(lines), "bytes", total)

# where does the last user message sit, and how big is everything after it?
last_user = None
for i, l in enumerate(lines):
    if '"role":"user"' in l:
        last_user = i
print("last_user_line_index", last_user, "of", len(lines))
tail = lines[last_user + 1:]
print("tail_lines", len(tail), "tail_bytes", sum(len(l.encode("utf-8")) for l in tail))

t0 = time.time()
n = 0
for l in lines:
    json.loads(l)
    n += 1
print("python full json parse seconds", round(time.time() - t0, 2), "rows", n)

t0 = time.time()
k = 0
for l in tail:
    json.loads(l)
    k += 1
print("python tail json parse seconds", round(time.time() - t0, 2), "rows", k)

t0 = time.time()
c = 0
for l in tail:
    if "REASONING_MESSAGE_CONTENT" not in l:
        c += 1
print("python tail substring-scan seconds", round(time.time() - t0, 2), "kept", c)

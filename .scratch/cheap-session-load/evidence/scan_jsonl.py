import json, sys, collections

path = sys.argv[1]
kinds = collections.Counter()
roles = collections.Counter()
names = collections.Counter()
msgs = 0
first_user_seq = None
seq = 0
last_user_seq = None
user_line_bytes = []
total = 0
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        seq += 1
        total += len(line.encode("utf-8"))
        try:
            row = json.loads(line)
        except Exception:
            kinds["UNPARSEABLE"] += 1
            continue
        t = row.get("type")
        kinds[t] += 1
        p = row.get("payload") or {}
        if t == "message":
            msgs += 1
            roles[p.get("role")] += 1
            if p.get("role") == "user":
                if first_user_seq is None:
                    first_user_seq = seq
                last_user_seq = seq
                user_line_bytes.append(len(line.encode("utf-8")))
        else:
            names[p.get("name") or p.get("type")] += 1

print("lines", seq, "bytes", total)
print("kinds", dict(kinds))
print("roles", dict(roles))
print("first_user_line", first_user_seq, "last_user_line", last_user_seq)
print("tail_since_last_user_bytes", sum(user_line_bytes), "n_user", len(user_line_bytes))
print("top events")
for k, v in names.most_common(15):
    print("   ", v, k)

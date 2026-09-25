import sys, json, collections

path = sys.argv[1]
by_kind = collections.Counter()
by_kind_lines = collections.Counter()
role_bytes = collections.Counter()
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        b = len(line.encode("utf-8"))
        if "REASONING" in line:
            by_kind["reasoning"] += b
            by_kind_lines["reasoning"] += 1
        elif "TEXT_MESSAGE_CONTENT" in line:
            by_kind["text"] += b
            by_kind_lines["text"] += 1
        elif "TOOL_CALL" in line:
            by_kind["toolcall"] += b
            by_kind_lines["toolcall"] += 1
        elif '"type":"message"' in line:
            by_kind["message"] += b
            by_kind_lines["message"] += 1
            try:
                role_bytes[json.loads(line)["payload"].get("role")] += b
            except Exception:
                role_bytes["bad"] += b
        else:
            by_kind["other"] += b
            by_kind_lines["other"] += 1

tot = sum(by_kind.values())
for k, v in by_kind.most_common():
    print(f"{k:10s} {v/1048576:8.2f} MB  {v*100/tot:5.1f}%  {by_kind_lines[k]:8d} lines")
print("total", round(tot / 1048576, 2), "MB")
print("message rows by role, MB:", {k: round(v / 1048576, 2) for k, v in role_bytes.items()})

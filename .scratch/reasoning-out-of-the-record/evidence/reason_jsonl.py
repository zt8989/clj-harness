import sys, json, collections

path = sys.argv[1]
kinds = collections.Counter()
line_bytes = collections.Counter()
delta_total = 0
delta_chars_total = 0
per_msg = collections.Counter()
sizes = []
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        p = row.get("payload")
        if not isinstance(p, dict):
            continue
        t = p.get("type") or ""
        if not t.startswith("REASONING"):
            continue
        b = len(line.encode("utf-8"))
        kinds[t] += 1
        line_bytes[t] += b
        if t == "REASONING_MESSAGE_CONTENT":
            d = p.get("delta") or ""
            delta_total += len(d.encode("utf-8"))
            delta_chars_total += len(d)
            sizes.append(len(d))
            per_msg[p.get("messageId")] += 1

for k, v in kinds.most_common():
    print(f"   {k:26s} {v:8d}   {line_bytes[k]/1048576:7.2f} MB")
tot = sum(line_bytes.values())
cont = line_bytes["REASONING_MESSAGE_CONTENT"]
print(f"reasoning total            {sum(kinds.values()):8d}   {tot/1048576:7.2f} MB")
print(f"   delta text              {delta_total/1048576:7.2f} MB ({delta_total*100/cont:.1f}% of CONTENT lines)")
print(f"   frame overhead          {(cont-delta_total)/1048576:7.2f} MB")
print("reasoning messages (distinct messageId):", len(per_msg))
sizes.sort()
n = len(sizes)
print("delta chars/frame: min", sizes[0], "p50", sizes[n//2], "p90", sizes[int(n*0.9)],
      "max", sizes[-1], "mean", round(sum(sizes)/n, 1))
print("text chars", delta_chars_total,
      "| bytes/frame overhead avg", round((cont-delta_total)/n, 1))

import sys, json, collections

path = sys.argv[1]
runs = 0
sysrows = 0
sysrow_bytes = 0
tools_bytes = 0
tools_sizes = []
ms_rows = 0
ms_bytes = 0
ms_tools = 0
prov_init = 0
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        row = json.loads(line)
        b = len(line.encode("utf-8"))
        p = row.get("payload")
        if not isinstance(p, dict):
            continue
        nm = p.get("name")
        if nm == "provider/init":
            prov_init += 1
        # message rows: payload is the provider message
        if row.get("type") == "message" and p.get("role") == "system":
            sysrows += 1
            sysrow_bytes += b
        if nm == "model/start":
            ms_rows += 1
            ms_bytes += b
            t = p.get("value", {}).get("tools")
            if t is not None:
                ms_tools += 1
                tools_bytes += len(json.dumps(t, ensure_ascii=False).encode("utf-8"))
                tools_sizes.append(len(t))

print("provider/init rows        ", prov_init)
print("system message rows       ", sysrows, f"({sysrow_bytes/1048576:.2f} MB incl. any envelope tools)")
print("model/start rows          ", ms_rows, f"({ms_bytes/1048576:.2f} MB)")
print("   carrying a tools array ", ms_tools, f"({tools_bytes/1048576:.2f} MB of tool tables)")
if tools_sizes:
    print("   tools per table: first", tools_sizes[0], "last", tools_sizes[-1], "distinct", len(set(tools_sizes)))

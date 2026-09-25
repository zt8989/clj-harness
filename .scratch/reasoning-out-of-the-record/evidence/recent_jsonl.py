import sys, json, os, glob

base = sys.argv[1]
files = sorted(glob.glob(os.path.join(base, "**", "*.jsonl"), recursive=True),
               key=os.path.getmtime, reverse=True)[:6]
for path in files:
    ms = 0
    ms_tools = 0
    ms_bytes = 0
    sysrows = 0
    sys_tools = 0
    sys_tools_bytes = 0
    sig = 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            try:
                row = json.loads(line)
            except Exception:
                continue
            p = row.get("payload")
            if not isinstance(p, dict):
                continue
            if p.get("name") == "model/start":
                ms += 1
                ms_bytes += len(line.encode("utf-8"))
                v = p.get("value", {})
                if v.get("tools") is not None:
                    ms_tools += 1
                if v.get("tools-names-hash") is not None:
                    sig += 1
            if row.get("type") == "message" and p.get("role") == "system":
                sysrows += 1
                # envelope lives beside the payload in the row
                env = row.get("source") and True
                tools = (row.get("envelope") or {}).get("tools") if isinstance(row.get("envelope"), dict) else None
    print(os.path.basename(path)[:20], f"{os.path.getsize(path)/1048576:6.1f} MB",
          f"| model/start {ms:5d} ({ms_bytes/1048576:5.2f} MB) with-table {ms_tools:4d} with-signature {sig:5d}",
          f"| system rows {sysrows}")

#!/bin/bash
set -e
OUT=/home/mahin/arivo-monitor-server/data/storage-health.json
TMP=$(mktemp)
/usr/sbin/smartctl -j -H /dev/sdb > "$TMP" || true
python3 - "$TMP" "$OUT" <<'PY'
import json,sys,datetime,os,subprocess
src,out=sys.argv[1:]
try:d=json.load(open(src))
except Exception:d={}
def cmd(*a):
    try:return subprocess.check_output(a,text=True).strip()
    except Exception:return None
r={
    "checked_at":datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "available":bool(d),
    "passed":d.get("smart_status",{}).get("passed"),
    "model":cmd("lsblk","-ndo","MODEL","/dev/sdb"),
    "serial":cmd("lsblk","-ndo","SERIAL","/dev/sdb"),
    "size":cmd("lsblk","-ndo","SIZE","/dev/sdb"),
    "device":"/dev/sdb"
}
tmp=out+".tmp"
open(tmp,"w").write(json.dumps(r,indent=2))
os.replace(tmp,out)
PY
chown mahin:mahin "$OUT"
rm -f "$TMP"

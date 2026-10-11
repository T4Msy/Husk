#!/bin/bash
# Linux port of build_android_system.sh + build_java_runtime.sh, for CI: from the Lineage image the Android guest boots
# (releases/lineage-v2), make build/android-system (fonts, CA certificates) and build/java-runtime (libcore, ICU, tzdata,
# conscrypt, Husk's Java framework, framework-res), which the app's "Stage guest image" step bundles.
#
#   scripts/ci/build_runtime_linux.sh <vda.qcow2|vda.raw>
#
# Needs qemu-img, debugfs (e2fsprogs), python3, a JDK (javac/jar/java) and R8 (HUSK_D8=r8.jar, 8.5+).
set -euo pipefail

HUSK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMG="${1:?usage: build_runtime_linux.sh <vda.qcow2|vda.raw>}"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"

RAW="$IMG"
case "$IMG" in *.qcow2) echo "==> converting the image"; qemu-img convert -O raw "$IMG" "$WORK/vda.raw"; RAW="$WORK/vda.raw" ;; esac

echo "==> unpacking system from the super partition"
python3 -I - "$RAW" "$WORK/system.img" <<'EOF'
import struct, sys
raw, out = sys.argv[1], sys.argv[2]
f = open(raw, 'rb')
f.seek(512); h = f.read(92); assert h[:8] == b'EFI PART', 'no GPT'
lba, n, sz = struct.unpack_from('<QII', h, 72)
f.seek(lba * 512); start = None
for i in range(n):
    e = f.read(sz)
    if e[56:128].decode('utf-16le').rstrip('\0') == 'super': start = struct.unpack_from('<Q', e, 32)[0]
assert start, 'no super partition'
base = start * 512
f.seek(base + 12288); h = f.read(256)
magic, _, _, hsize = struct.unpack_from('<IHHI', h, 0)
assert magic == 0x414C5030, 'no logical partition metadata'
o = 12 + 32; tsize = struct.unpack_from('<I', h, o)[0]; o += 4 + 32
descs = [struct.unpack_from('<III', h, o + 12 * i) for i in range(4)]
f.seek(base + 12288 + hsize); T = f.read(tsize)
(po, pn, ps), (eo, en, es) = descs[0], descs[1]
ext = [struct.unpack_from('<QIQI', T, eo + es * i) for i in range(en)]
for i in range(pn):
    e = T[po + ps * i:po + ps * (i + 1)]
    name = e[:36].split(b'\0')[0].decode(); _, fi, ne, _ = struct.unpack_from('<IIII', e, 36)
    if name != 'system': continue
    with open(out, 'wb') as w:
        for j in range(fi, fi + ne):
            nsec, _, data, _ = ext[j]
            f.seek(base + data * 512); left = nsec * 512
            while left:
                b = f.read(min(left, 64 << 20)); w.write(b); left -= len(b)
EOF

SYSIMG="$WORK/system.img"
dbg() { debugfs -R "$1" "$2" >/dev/null 2>&1 || true; }
# The system image is system-as-root: /system lives at /system inside it.
SP=/system
dbg "stat $SP/framework" "$SYSIMG"
if ! debugfs -R "ls $SP/apex" "$SYSIMG" 2>/dev/null | grep -q apex; then SP=""; fi
echo "==> system root at '${SP:-/}'"

echo "==> APEX payloads"
APEX="$WORK/apex"; mkdir -p "$APEX"
for name in com.android.art com.android.i18n com.android.tzdata com.android.conscrypt; do
    for ext in capex apex; do dbg "dump $SP/apex/$name.$ext $WORK/$name.$ext" "$SYSIMG"; [ -s "$WORK/$name.$ext" ] && break; done
    python3 -I - "$WORK" "$name" <<'EOF'
import sys, zipfile, os
w, n = sys.argv[1], sys.argv[2]
p = next(p for p in (f'{w}/{n}.capex', f'{w}/{n}.apex') if os.path.exists(p) and os.path.getsize(p))
z = zipfile.ZipFile(p)
if 'original_apex' in z.namelist(): z = zipfile.ZipFile(z.open('original_apex'))
open(f'{w}/{n}.img', 'wb').write(z.read('apex_payload.img'))
EOF
    mkdir -p "$APEX/$name"
    for d in javalib lib64 etc; do debugfs -R "rdump /$d $APEX/$name" "$WORK/$name.img" >/dev/null 2>&1 || true; done
    echo "    $name: $(find "$APEX/$name" -type f | wc -l) files"
done

echo "==> android-system (fonts, fonts.xml, certificates)"
OUTS="$HUSK_ROOT/build/android-system"
rm -rf "$OUTS" "$WORK/dump"; mkdir -p "$OUTS/etc" "$OUTS/fonts" "$OUTS/cacerts" "$WORK/dump"
dbg "rdump $SP/fonts $WORK/dump" "$SYSIMG"
find "$WORK/dump/fonts" -type f ! -name '*CJK*' -size +100c -exec cp {} "$OUTS/fonts/" \;
for x in fonts.xml font_fallback.xml; do dbg "dump $SP/etc/$x $OUTS/etc/$x" "$SYSIMG"; done
cp "$APEX/com.android.conscrypt/"cacerts/* "$OUTS/cacerts/" 2>/dev/null || {
    debugfs -R "rdump /cacerts $WORK" "$WORK/com.android.conscrypt.img" >/dev/null 2>&1; cp "$WORK/cacerts/"* "$OUTS/cacerts/"; }
echo "    $(ls "$OUTS/fonts" | wc -l) fonts, $(ls "$OUTS/cacerts" | wc -l) certificates"

echo "==> java-runtime"
dbg "dump $SP/framework/org.apache.http.legacy.jar $WORK/org.apache.http.legacy.jar" "$SYSIMG"
dbg "dump $SP/framework/framework-res.apk $WORK/framework-res.apk" "$SYSIMG"
OUT="$HUSK_ROOT/build/java-runtime"
rm -rf "$OUT"; mkdir -p "$OUT"
copy() { mkdir -p "$OUT/$(dirname "$1")"; cp "$APEX/$1" "$OUT/$1"; }
for j in core-oj core-libart okhttp bouncycastle apache-xml; do copy com.android.art/javalib/$j.jar; done
for l in libandroidio libbase libc++ libexpat libjavacore libnativehelper libopenjdk; do copy com.android.art/lib64/$l.so; done
copy com.android.i18n/javalib/core-icu4j.jar
for l in libicu libicui18n libicuuc libicu_jni libandroidicu; do [ -f "$APEX/com.android.i18n/lib64/$l.so" ] && copy com.android.i18n/lib64/$l.so; done
mkdir -p "$OUT/com.android.i18n/etc"; cp -R "$APEX/com.android.i18n/etc/icu" "$OUT/com.android.i18n/etc/"
mkdir -p "$OUT/com.android.tzdata"; cp -R "$APEX/com.android.tzdata/etc" "$OUT/com.android.tzdata/"
copy com.android.conscrypt/javalib/conscrypt.jar
for l in libjavacrypto libcrypto libssl; do copy com.android.conscrypt/lib64/$l.so; done
"$HUSK_ROOT/src/java-framework/build.sh" "$OUT/husk-framework.dex"
cp "$WORK/org.apache.http.legacy.jar" "$OUT/org.apache.http.legacy.jar"
python3 "$HUSK_ROOT/tools/strip_framework_res.py" "$WORK/framework-res.apk" "$OUT/framework-res.apk"
du -sh "$OUTS" "$OUT"

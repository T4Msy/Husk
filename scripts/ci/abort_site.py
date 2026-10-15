#!/usr/bin/env python3
"""Where a guest library called abort(): the functions on the stack at that moment, disassembled with the strings they load and
the JNI functions they call. Husk's crash report gives one address as "<exported symbol>+<offset>" and the other guest frames as
raw return addresses; with the library at hand this turns them into code to read.

usage: abort_site.py <lib.so> <symbol> <offset> <abort return address> [<frame return address> ...]
"""
import re
import shutil
import subprocess
import sys

from elftools.elf.elffile import ELFFile

JNI = ["reserved0", "reserved1", "reserved2", "reserved3", "GetVersion", "DefineClass", "FindClass", "FromReflectedMethod",
       "FromReflectedField", "ToReflectedMethod", "GetSuperclass", "IsAssignableFrom", "ToReflectedField", "Throw", "ThrowNew",
       "ExceptionOccurred", "ExceptionDescribe", "ExceptionClear", "FatalError", "PushLocalFrame", "PopLocalFrame", "NewGlobalRef",
       "DeleteGlobalRef", "DeleteLocalRef", "IsSameObject", "NewLocalRef", "EnsureLocalCapacity", "AllocObject", "NewObject",
       "NewObjectV", "NewObjectA", "GetObjectClass", "IsInstanceOf", "GetMethodID"]
TYPES = ["Object", "Boolean", "Byte", "Char", "Short", "Int", "Long", "Float", "Double"]
for kind in ("Call%sMethod", "CallNonvirtual%sMethod"):
    for t in TYPES + ["Void"]:
        JNI += [kind % t, kind % t + "V", kind % t + "A"]
JNI += ["GetFieldID"] + ["Get%sField" % t for t in TYPES] + ["Set%sField" % t for t in TYPES] + ["GetStaticMethodID"]
for t in TYPES + ["Void"]:
    JNI += ["CallStatic%sMethod" % t, "CallStatic%sMethodV" % t, "CallStatic%sMethodA" % t]
JNI += ["GetStaticFieldID"] + ["GetStatic%sField" % t for t in TYPES] + ["SetStatic%sField" % t for t in TYPES]
JNI += ["NewString", "GetStringLength", "GetStringChars", "ReleaseStringChars", "NewStringUTF", "GetStringUTFLength",
        "GetStringUTFChars", "ReleaseStringUTFChars", "GetArrayLength", "NewObjectArray", "GetObjectArrayElement",
        "SetObjectArrayElement"]
P = TYPES[1:]
for kind in ("New%sArray", "Get%sArrayElements", "Release%sArrayElements", "Get%sArrayRegion", "Set%sArrayRegion"):
    JNI += [kind % t for t in P]
JNI += ["RegisterNatives", "UnregisterNatives", "MonitorEnter", "MonitorExit", "GetJavaVM", "GetStringRegion", "GetStringUTFRegion",
        "GetPrimitiveArrayCritical", "ReleasePrimitiveArrayCritical", "GetStringCritical", "ReleaseStringCritical", "NewWeakGlobalRef",
        "DeleteWeakGlobalRef", "ExceptionCheck", "NewDirectByteBuffer", "GetDirectBufferAddress", "GetDirectBufferCapacity",
        "GetObjectRefType"]
assert JNI[113] == "GetStaticMethodID" and JNI[228] == "ExceptionCheck", "JNI table out of order"


OBJDUMP = shutil.which("llvm-objdump") or shutil.which("objdump")


def main():
    path, symname, off = sys.argv[1], sys.argv[2], int(sys.argv[3], 0)
    ras = [int(a, 0) for a in sys.argv[4:]]
    f = open(path, "rb")
    elf = ELFFile(f)
    syms = []
    for sec in ("symtab", ".symtab", ".dynsym"):
        s = elf.get_section_by_name(sec)
        if s:
            syms += [(x["st_value"], x["st_size"], x.name) for x in s.iter_symbols() if x["st_value"] and x["st_info"]["type"] == "STT_FUNC"]
    syms.sort()
    target = [v for v, _, n in syms if n == symname]
    if not target:
        sys.exit("no symbol %s in %s" % (symname, path))
    abort_ra = target[0] + off
    delta = abort_ra - ras[0]
    print("%s = %#x; abort's return address in the library: %#x (load bias %#x)" % (symname, target[0], abort_ra, -delta & (2**64 - 1)))

    # function starts: the unwind tables know every function, exported or not
    starts = set(v for v, _, _ in syms)
    try:
        for e in elf.get_dwarf_info().EH_CFI_entries():
            if hasattr(e, "header") and "initial_location" in e.header:
                starts.add(e.header["initial_location"])
    except Exception as ex:  # noqa: BLE001 - the tables are a nicety
        print("(unwind tables not read: %s)" % ex)
    starts = sorted(starts)
    names = {v: n for v, _, n in syms}

    def section_bytes_at(addr, n):
        for seg in elf.iter_segments():
            if seg["p_type"] == "PT_LOAD" and seg["p_vaddr"] <= addr < seg["p_vaddr"] + seg["p_filesz"]:
                f.seek(seg["p_offset"] + addr - seg["p_vaddr"])
                return f.read(n)
        return b""

    def cstr(addr):
        b = section_bytes_at(addr, 160)
        s = b.split(b"\0")[0]
        if len(s) >= 2 and all(32 <= c < 127 for c in s):
            return s.decode()
        return None

    for i, ra in enumerate(ras):
        a = ra + delta
        st = max([s for s in starts if s <= a] or [a - 0x400])
        if a - st > 0x3000:
            st = a - 0x3000
        nm = names.get(st, "sub_%x" % st)
        print("\n==== frame %d: return address %#x = %s+%#x" % (i + 5, a, nm, a - st))
        out = subprocess.run([OBJDUMP, "-d", "--no-show-raw-insn", "--start-address=%#x" % st, "--stop-address=%#x" % (a + 8), path],
                             capture_output=True, text=True).stdout
        regs = {}
        for line in out.splitlines():
            m = re.match(r"\s*([0-9a-f]+):\s+(\S+)\s*(.*)", line)
            if not m:
                continue
            pc, op, args = int(m.group(1), 16), m.group(2), m.group(3)
            note = ""
            r = re.match(r"(x\d+), (?:0x)?([0-9a-f]+)", args)
            if op == "adrp" and r:
                regs[r.group(1)] = int(r.group(2), 16)
            r = re.match(r"(x\d+), (x\d+), #(0x[0-9a-f]+|\d+)", args)
            if op == "add" and r and r.group(2) in regs:
                ad = regs[r.group(2)] + int(r.group(3), 0)
                s = cstr(ad)
                note = ' "%s"' % s if s else " -> %#x" % ad
            r = re.match(r"(x\d+), (?:0x)?([0-9a-f]+)", args)
            if op == "adr" and r:
                s = cstr(int(r.group(2), 16))
                if s:
                    note = ' "%s"' % s
            r = re.match(r"x\d+, \[x\d+, #(0x[0-9a-f]+)\]", args)
            if op == "ldr" and r:
                k = int(r.group(1), 16)
                if k % 8 == 0 and 4 <= k // 8 < len(JNI) and k >= 0x20:
                    note = "  (JNIEnv %s?)" % JNI[k // 8]
            print("%8x: %-6s %s%s%s" % (pc, op, args, note, "   <== return address" if pc + 4 == a else ""))


if __name__ == "__main__":
    main()

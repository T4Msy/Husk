#!/usr/bin/env python3
"""The official WhatsApp APK as whatsapp.com served it at a given time, from the Internet Archive's copies of
https://www.whatsapp.com/android/current/WhatsApp.apk. Each distinct copy in the window is fetched until one has the wanted
version prefix; its version and signing certificate are printed so the copy can be checked to be WhatsApp's own.

usage: old_whatsapp.py <yyyymm from> <yyyymm to> <version prefix> <out.apk>
"""
import hashlib
import json
import sys
import urllib.request

from androguard.core.apk import APK

URL = "whatsapp.com/android/current/WhatsApp.apk"


def get(url, path=None):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Husk CI; abort-site analysis)"})
    with urllib.request.urlopen(req, timeout=300) as r:
        data = r.read()
    if path:
        open(path, "wb").write(data)
    return data


def main():
    lo, hi, want, out = sys.argv[1:5]
    cdx = "https://web.archive.org/cdx/search/cdx?url=%s&from=%s&to=%s&output=json&fl=timestamp,statuscode,digest,length&filter=statuscode:200&collapse=digest" % (URL, lo, hi)
    rows = json.loads(get(cdx) or b"[]")[1:]
    print("%d distinct copies between %s and %s" % (len(rows), lo, hi))
    best = None
    for ts, _, digest, length in rows:
        try:
            get("https://web.archive.org/web/%sid_/https://www.%s" % (ts, URL), out)
            a = APK(out)
            v = a.get_androidversion_name()
            certs = [hashlib.sha256(c).hexdigest() for c in a.get_certificates_der_v2() or []] or \
                    [hashlib.sha256(c.dump()).hexdigest() for c in a.get_certificates()]
            print("%s  %s  %s  version %s  package %s  cert sha256 %s" % (ts, digest, length, v, a.get_package(), ",".join(certs)))
            if a.get_package() == "com.whatsapp" and v.startswith(want):
                best = (ts, v)
                break
        except Exception as ex:  # noqa: BLE001 - a broken copy is skipped
            print("%s: %s" % (ts, ex))
    if not best:
        sys.exit("no copy with version %s" % want)
    print("using the copy of %s, version %s" % best)


if __name__ == "__main__":
    main()

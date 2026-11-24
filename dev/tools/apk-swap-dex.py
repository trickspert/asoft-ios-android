"""apk-swap-dex.py IN.apk DEX_DIR OUT.apk: IN.apk with its classes*.dex replaced by DEX_DIR's and its old signature
left out, and its baseline profile (assets/dexopt, made for the old dex). Every other entry is copied as it was (its bytes and its compression); stored entries are aligned as
zipalign does: 16 KiB for native libraries (page size), 4 bytes for the rest (resources.arsc must be)."""
import os
import sys
import zipfile

src, dexdir, dst = sys.argv[1:4]
SIG = ('META-INF/MANIFEST.MF',)


def is_sig(name):
    if name in SIG:
        return True
    if name.startswith('META-INF/') and name.count('/') == 1:
        return name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC'))
    return False


def align_for(name):
    return 16384 if name.endswith('.so') else 4


def write(zout, info, data):
    """@info's entry with @data, its local header padded so that a stored entry's data starts aligned."""
    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    zi.create_system = info.create_system
    if zi.compress_type == zipfile.ZIP_STORED:
        a = align_for(zi.filename)
        start = zout.fp.tell() + 30 + len(zi.filename.encode('utf-8'))
        pad = (-start) % a
        if 0 < pad < 4:          # an extra field is at least 4 bytes (id, length)
            pad += a
        zi.extra = (b'\x35\xd9' + (pad - 4).to_bytes(2, 'little') + b'\0' * (pad - 4)) if pad else b''
    zout.writestr(zi, data)


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    kept = 0
    # the new dex files are packed as the official classes.dex was (AGP stores them, aligned, from minSdk 28 on)
    dex_method = next((i.compress_type for i in zin.infolist() if i.filename == 'classes.dex'), zipfile.ZIP_DEFLATED)
    for info in zin.infolist():
        n = info.filename
        # (assets/dexopt: the baseline profile AGP made for the old dex's methods; the new dex goes without one)
        if is_sig(n) or (n.startswith('classes') and n.endswith('.dex') and '/' not in n) or n.startswith('assets/dexopt/'):
            continue
        write(zout, info, zin.read(n))
        kept += 1
    dexes = sorted(f for f in os.listdir(dexdir) if f.endswith('.dex'))
    for f in dexes:
        zi = zipfile.ZipInfo(f, date_time=(2026, 11, 24, 0, 0, 0))
        zi.compress_type = dex_method
        write(zout, zi, open(os.path.join(dexdir, f), 'rb').read())
    print('apk: %d entries kept, %d dex files new: %s' % (kept, len(dexes), ', '.join(dexes)))

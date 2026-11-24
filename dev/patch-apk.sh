#!/bin/bash
# patch-apk.sh OFFICIAL.apk OUT.apk KEYSTORE.p12: the official APK with this checkout's Java code in place of its own.
# Everything else (iOS 26's QEMU and engine libraries, Turnip, the helper VM, the setup's scripts, the resources and the
# manifest) stays byte for byte the official one's: only classes*.dex change, and the signature (a release key is not
# in the repository, so OUT.apk is signed with KEYSTORE.p12, made here when missing; keep it to sign the next one with,
# or Android refuses it as an update).
#   BT=the SDK's build-tools folder (d8, aapt2, dexdump, zipalign, apksigner; 36), ANDROID_JAR=platforms/android-36's
#   android.jar (compileSdk 36), CONSCRYPT=org.conscrypt:conscrypt-android:2.5.3's classes.jar (from its .aar)
# Checks before it packs: the code compiles against the APK's own resource ids, and the dex holds every class the
# official one has (a missing one would be a crash on the phone).
set -euo pipefail
in=$(realpath "$1") out=$(realpath -m "$2") ks=$(realpath -m "$3")
bt=${BT:?BT: the build-tools folder} aj=${ANDROID_JAR:?ANDROID_JAR} cc=${CONSCRYPT:?CONSCRYPT}
here=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$(dirname "$out")" "$(dirname "$ks")"
w=$(mktemp -d)
trap 'rm -rf "$w"' EXIT
mkdir -p "$w/gen/com/asoft/ios" "$w/classes" "$w/dex" "$w/old"
# the resource ids the official resources.arsc gives (the code inlines them)
"$bt/aapt2" dump resources "$in" > "$w/res.txt"
python3 -I - "$w/res.txt" "$w/gen/com/asoft/ios/R.java" <<'PY'
import re, sys
types = {}
for l in open(sys.argv[1]):
    m = re.match(r'\s*resource (0x[0-9a-f]{8}) ([\w.]+)/([\w.]+)', l)
    if m:
        types.setdefault(m.group(2), {})[m.group(3).replace('.', '_')] = m.group(1)
out = ['package com.asoft.ios;', 'public final class R {']
for t, ns in sorted(types.items()):
    out.append('  public static final class %s {' % t)
    out += ['    public static final int %s = %s;' % (n, v) for n, v in sorted(ns.items())]
    out.append('  }')
out.append('}')
open(sys.argv[2], 'w').write('\n'.join(out) + '\n')
print('R: ' + ', '.join('%s %d' % (t, len(ns)) for t, ns in sorted(types.items())))
PY
# the libraries' own R classes (org.conscrypt.R: AGP makes one per library, empty here), as the official dex has them
list() { for d in "$1"/classes*.dex; do "$bt/dexdump" "$d" | sed -n "s/^  Class descriptor  : '\(.*\)'$/\1/p"; done | sort -u; }
unzip -q -o "$in" 'classes*.dex' -d "$w/old"
list "$w/old" > "$w/old.txt"
for r in $(grep -E '^L.*/R;$' "$w/old.txt" | grep -v '^Lcom/asoft/ios/R;$'); do
    pkg=$(echo "$r" | sed 's|^L||; s|/R;$||')
    mkdir -p "$w/gen/$pkg"
    echo "package ${pkg//\//.}; public final class R { private R() {} }" > "$w/gen/$pkg/R.java"
    echo "R: $pkg's (empty, as the official one)"
done
javac -nowarn --release 17 -encoding UTF-8 -cp "$aj:$cc" -d "$w/classes" \
    "$here"/app/src/main/java/com/asoft/ios/*.java $(find "$w/gen" -name R.java)
( cd "$w/classes" && jar cf "$w/app.jar" . )
minsdk=$("$bt/aapt2" dump badging "$in" | sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p")
"$bt/d8" --release --min-api "${minsdk:-29}" --lib "$aj" --output "$w/dex" "$w/app.jar" "$cc"
# every class of the official dex must be in the new one
list "$w/dex" > "$w/new.txt"
missing=$(comm -23 "$w/old.txt" "$w/new.txt")
if [ -n "$missing" ]; then
    echo "the new dex lacks classes the official one has:"; echo "$missing" | head -20; exit 1
fi
echo "classes: official $(wc -l < "$w/old.txt"), new $(wc -l < "$w/new.txt")"
python3 -I "$here/dev/tools/apk-swap-dex.py" "$in" "$w/dex" "$w/unsigned.apk"
if [ ! -f "$ks" ]; then
    keytool -genkeypair -keystore "$ks" -storetype PKCS12 -storepass asoftfix -keypass asoftfix -alias asoftfix \
        -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=asoft fix build" > /dev/null
    echo "new signing key: $ks (password asoftfix)"
fi
"$bt/zipalign" -c -P 16 4 "$w/unsigned.apk"
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:asoftfix --key-pass pass:asoftfix --min-sdk-version "${minsdk:-29}" \
    --out "$out" "$w/unsigned.apk"
"$bt/apksigner" verify --min-sdk-version "${minsdk:-29}" "$out"
echo "signed and verified: $out ($(du -h "$out" | cut -f1))"

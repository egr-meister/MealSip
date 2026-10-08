#!/usr/bin/env bash
# Verifies one signed release pass (APK + AAB).
# Usage: verify-release.sh <label> <apk> <aab> <out-dir>
# Env:   BUILD_TOOLS_DIR, BUNDLETOOL_JAR, EXPECTED_CERT_SHA256 (uppercase hex, no colons)
# Never prints passwords; signing material is not touched here.
set -euo pipefail

LABEL="$1"; APK="$2"; AAB="$3"; OUT="$4"
BT="${BUILD_TOOLS_DIR:?}"
mkdir -p "$OUT"
fail() { echo "::error::[$LABEL] $*"; exit 1; }

test -s "$APK" || fail "APK missing: $APK"
test -s "$AAB" || fail "AAB missing: $AAB"

echo "== [$LABEL] APK signature (apksigner verify --print-certs)"
"$BT/apksigner" verify --verbose --print-certs "$APK" | tee "$OUT/apk-signature.txt"
grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" "$OUT/apk-signature.txt" || fail "APK v2 signature not verified"
if grep -q "CN=Android Debug" "$OUT/apk-signature.txt"; then fail "APK is signed with the debug certificate"; fi
APK_SHA=$(grep -m1 "certificate SHA-256 digest" "$OUT/apk-signature.txt" | awk -F': ' '{print toupper($2)}' | tr -d ': ')
test -n "$APK_SHA" || fail "Could not read APK signer digest"

echo "== [$LABEL] AAB integrity and signer (jarsigner)"
# No -strict: bundletool AABs always trigger the harmless JDK 17+ notice
# "signed in JarFile but is not signed in JarInputStream", which -strict turns into a failure.
jarsigner -verify -verbose -certs "$AAB" > "$OUT/aab-verify.txt" 2>&1 || { tail -40 "$OUT/aab-verify.txt"; fail "jarsigner failed"; }
grep -q "jar verified" "$OUT/aab-verify.txt" || { tail -40 "$OUT/aab-verify.txt"; fail "AAB signature not verified"; }
if grep -qE "jar is unsigned|unsigned entries|digest error|SecurityException" "$OUT/aab-verify.txt"; then
  tail -40 "$OUT/aab-verify.txt"; fail "AAB has unsigned or tampered entries"
fi
if grep -q "CN=Android Debug" "$OUT/aab-verify.txt"; then fail "AAB is signed with the debug certificate"; fi
AAB_SHA=$(keytool -printcert -jarfile "$AAB" | grep -m1 "SHA256:" | sed 's/.*SHA256: //' | tr -d ': ' | tr 'a-f' 'A-F')
echo "APK signer SHA-256: $APK_SHA"
echo "AAB signer SHA-256: $AAB_SHA"
echo "Expected  SHA-256: ${EXPECTED_CERT_SHA256:-<not provided>}"
[ "$APK_SHA" = "$AAB_SHA" ] || fail "APK and AAB are signed by different certificates"
if [ -n "${EXPECTED_CERT_SHA256:-}" ]; then
  [ "$APK_SHA" = "$EXPECTED_CERT_SHA256" ] || fail "Signer is not the MealSip upload key"
fi
# A self-signed upload certificate is valid for Google Play; it is NOT rejected here.

echo "== [$LABEL] Permissions allowlist (packaged APK, AAB manifest, merged manifest)"
ALLOWED=$'android.permission.POST_NOTIFICATIONS\nandroid.permission.RECEIVE_BOOT_COMPLETED'
check_list() {
  local src="$1" list="$2" bad=0
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    if ! printf '%s\n' "$ALLOWED" | grep -qx "$p"; then echo "::error::[$LABEL] $src: permission not in allowlist: $p"; bad=1; fi
  done <<< "$list"
  return $bad
}
"$BT/aapt2" dump permissions "$APK" | tee "$OUT/packaged-permissions.txt"
APK_PERMS=$(grep -oE "uses-permission: name='[^']+'" "$OUT/packaged-permissions.txt" | sed "s/uses-permission: name='//;s/'//" || true)
java -jar "${BUNDLETOOL_JAR:?}" dump manifest --bundle "$AAB" > "$OUT/aab-manifest.xml"
AAB_PERMS=$(grep -oE 'uses-permission[^>]*android:name="[^"]+"' "$OUT/aab-manifest.xml" | sed 's/.*android:name="//;s/"//' || true)
MERGED=$(find app/build/intermediates -path "*release*" -name AndroidManifest.xml | grep -i merged_manifest | head -1 || true)
MERGED_PERMS=""
if [ -n "$MERGED" ]; then
  MERGED_PERMS=$(grep -oE 'uses-permission[^>]*android:name="[^"]+"' "$MERGED" | sed 's/.*android:name="//;s/"//' | sort -u || true)
else
  fail "Merged release manifest not found"
fi
FAILED=0
check_list "APK" "$APK_PERMS" || FAILED=1
check_list "AAB" "$AAB_PERMS" || FAILED=1
check_list "Merged manifest" "$MERGED_PERMS" || FAILED=1
grep -q 'android:allowBackup="false"' "$OUT/aab-manifest.xml" || { echo "::error::[$LABEL] allowBackup is not false"; FAILED=1; }
grep -q 'android:debuggable="true"' "$OUT/aab-manifest.xml" && { echo "::error::[$LABEL] debuggable release"; FAILED=1; }
[ "$FAILED" = 0 ] || fail "Permission / manifest check failed"
{
  echo "APK: $(echo $APK_PERMS)"
  echo "AAB: $(echo $AAB_PERMS)"
  echo "Merged: $(echo $MERGED_PERMS)"
} > "$OUT/permissions-summary.txt"
cat "$OUT/permissions-summary.txt"

echo "== [$LABEL] Native libraries / 16 KB page size"
SO_APK=$(unzip -Z1 "$APK" | grep -E '\.so$' || true)
SO_AAB=$(unzip -Z1 "$AAB" | grep -E '\.so$' || true)
{
  echo "## 16 KB page-size findings ($LABEL)"
  if [ -z "$SO_APK$SO_AAB" ]; then
    echo "- No native .so libraries in the release APK or AAB (including transitive dependencies)."
    echo "- ELF segment alignment does not apply. APK zip alignment verified with zipalign -c -P 16."
  else
    echo "- Native libraries found:"; printf '  - %s\n' $SO_APK $SO_AAB
  fi
} > "$OUT/native-libs.md"
"$BT/zipalign" -c -P 16 -v 4 "$APK" > "$OUT/zipalign.txt" 2>&1 || { tail -40 "$OUT/zipalign.txt"; fail "zipalign 16 KB check failed"; }
if [ -n "$SO_APK$SO_AAB" ]; then
  rm -rf "$OUT/so" && mkdir -p "$OUT/so"
  unzip -q -o "$APK" 'lib/*' -d "$OUT/so" || true
  unzip -q -o "$AAB" 'base/lib/*' -d "$OUT/so" || true
  while IFS= read -r f; do
    ALIGN=$(objdump -p "$f" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')
    echo "- $f LOAD align: $ALIGN" >> "$OUT/native-libs.md"
    echo "$ALIGN" | grep -qE '2\*\*(1[4-9]|[2-9][0-9])' || fail "$f is not 16 KB aligned"
  done < <(find "$OUT/so" -name '*.so')
  echo "- NOTE: runtime testing in a 16 KB environment is still required before claiming runtime compatibility." >> "$OUT/native-libs.md"
fi
cat "$OUT/native-libs.md"

{
  echo "## Release artifacts ($LABEL)"
  echo "- APK: $(stat -c %s "$APK") bytes ($(du -h "$APK" | cut -f1))"
  echo "- AAB: $(stat -c %s "$AAB") bytes ($(du -h "$AAB" | cut -f1))"
  echo "- Signer SHA-256: $APK_SHA"
} > "$OUT/sizes.md"
cat "$OUT/sizes.md"
cp "$APK" "$AAB" "$OUT/"
echo "[$LABEL] all checks passed"

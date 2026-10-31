#!/usr/bin/env bash
# Checks a built Google Play bundle of SunApp (no.stormberry.sunapp.play) before it is uploaded.
#
#   tools/check-play-bundle.sh <bundle.aab> <versionName> <versionCode> [upload-cert-sha256]
#
# Fails (exit 1) unless the bundle is the alarm-free Play edition:
#   - package no.stormberry.sunapp.play, the given versionName and versionCode
#   - NO uses-permission and NO permission declaration in the bundle manifest
#   - NO alarm component (activity, service, receiver, provider) in the bundle manifest
#   - the only activity is MainActivity
#   - NO alarm class in the dex (R8 deleted it with the code behind Edition.ALARMS)
#   - signed by exactly one upload key: its certificate is NOT the sovereign release certificate,
#     and equals the given SHA-256 when one is given (hex, with or without colons)
# Read only: it writes nothing but a temporary directory it removes. Needs java (JAVA_HOME or PATH),
# unzip and the bundletool jar (BUNDLETOOL, default ~/Android/tools/bundletool-all-1.18.3.jar).
set -euo pipefail

[ $# -ge 3 ] && [ $# -le 4 ] || { echo "usage: $0 <bundle.aab> <versionName> <versionCode> [upload-cert-sha256]" >&2; exit 2; }
AAB="$1" NAME="$2" CODE="$3" CERT="${4:-}"
BUNDLETOOL="${BUNDLETOOL:-$HOME/Android/tools/bundletool-all-1.18.3.jar}"
RELEASE_CERT=f968ba08240a5781d35e36db2b1d51c153ba28123eb54c16b50f282dc8d5af4c   # the sovereign release key
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"; KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
fail=0
ok()  { printf 'ok    %s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*" >&2; fail=1; }

[ -f "$AAB" ] || { echo "no such bundle: $AAB" >&2; exit 2; }
[ -f "$BUNDLETOOL" ] || { echo "no bundletool jar at $BUNDLETOOL" >&2; exit 2; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT

"$JAVA" -jar "$BUNDLETOOL" dump manifest --bundle="$AAB" > "$T/manifest.xml"

grep -q 'package="no.stormberry.sunapp.play"' "$T/manifest.xml" && ok "package no.stormberry.sunapp.play" || bad "package is not no.stormberry.sunapp.play"
grep -q "android:versionName=\"$NAME\"" "$T/manifest.xml" && ok "versionName $NAME" || bad "versionName is not $NAME"
grep -q "android:versionCode=\"$CODE\"" "$T/manifest.xml" && ok "versionCode $CODE" || bad "versionCode is not $CODE"

if grep -E '<(uses-permission|uses-permission-sdk-23|permission)\b' "$T/manifest.xml" > "$T/perms.txt"; then
  bad "the bundle manifest declares permissions:"; cat "$T/perms.txt" >&2
else
  ok "no uses-permission and no permission declaration in the bundle manifest"
fi

grep -oE '<(activity|activity-alias|service|receiver|provider)[^>]*android:name="[^"]*"' "$T/manifest.xml" \
  | sed -E 's/.*android:name="([^"]*)"/\1/' | sort > "$T/components.txt" || true
if grep -i 'alarm' "$T/components.txt" > "$T/alarm-components.txt"; then
  bad "alarm components in the bundle manifest:"; cat "$T/alarm-components.txt" >&2
else
  ok "no alarm component in the bundle manifest"
fi
acts="$(grep -oE '<activity[^>]*android:name="[^"]*"' "$T/manifest.xml" | sed -E 's/.*android:name="([^"]*)"/\1/' | tr '\n' ' ')"
[ "$acts" = "no.stormberry.sunapp.MainActivity " ] && ok "the only activity is MainActivity" || bad "activities are: $acts"
printf '      components: %s\n' "$(paste -sd' ' "$T/components.txt")"

unzip -q -o "$AAB" 'base/dex/*.dex' -d "$T/dex"
# grep reads the dex files itself. The earlier 'cat ... | grep -q' failed open under pipefail: on a
# match grep exits at once, cat dies of SIGPIPE, the pipeline returns 141 and the check said ok.
ls "$T"/dex/base/dex/*.dex >/dev/null || bad "no dex in the bundle"
if grep -a -q 'no/stormberry/sunapp/alarm/' "$T"/dex/base/dex/*.dex; then
  bad "the dex still holds classes from no/stormberry/sunapp/alarm/"
else
  ok "no class from no/stormberry/sunapp/alarm/ in the dex"
fi

SHA="$("$KEYTOOL" -printcert -jarfile "$AAB" 2>/dev/null | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')"
if [ -z "$SHA" ]; then
  bad "the bundle carries no signature"
else
  signers="$(unzip -Z1 "$AAB" | grep -cE '^META-INF/[^/]+\.(RSA|EC|DSA)$' || true)"
  printf '      signing certificate SHA-256: %s (signers: %s)\n' "$SHA" "$signers"
  [ "$signers" = 1 ] && ok "exactly one signer" || bad "$signers signers"
  [ "$SHA" != "$RELEASE_CERT" ] && ok "not signed with the sovereign release key" || bad "SIGNED WITH THE SOVEREIGN RELEASE KEY: never upload this"
  if [ -n "$CERT" ]; then
    want="$(printf '%s' "$CERT" | tr -d ':' | tr 'A-F' 'a-f')"
    [ "$SHA" = "$want" ] && ok "signed with the expected upload certificate" || bad "certificate is not the expected $want"
  fi
fi

[ "$fail" = 0 ] && { echo "CHECK PASSED"; exit 0; } || { echo "CHECK FAILED" >&2; exit 1; }

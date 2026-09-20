#!/usr/bin/env bash
set -euo pipefail
for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD EXPECTED_SIGNER_SHA256; do
  test -n "${!name:-}" || { echo "::error::Missing signing configuration: $name"; exit 1; }
done
key_path="$RUNNER_TEMP/rootlesszachdsp-test.jks"
umask 077
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$key_path"
actual="$(keytool -exportcert -keystore "$key_path" -storepass:env KEYSTORE_PASSWORD -alias "$KEY_ALIAS" | sha256sum | awk '{print $1}')"
expected="$(printf '%s' "$EXPECTED_SIGNER_SHA256" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"
test "$actual" = "$expected" || { echo '::error::Signing certificate mismatch'; exit 1; }
echo "ROOTLESS_TEST_KEYSTORE_PATH=$key_path" >> "$GITHUB_ENV"

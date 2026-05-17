#!/bin/sh
TILES_DIR="${XYZ_BASETILEDIRECTORY:-/tmp/tiles}"
VECTOR_DIR="$TILES_DIR/vector"
mkdir -p "$VECTOR_DIR"

CUSTOM_TRUSTSTORE="/tmp/custom-cacerts"
DEFAULT_CACERTS="${JAVA_HOME}/lib/security/cacerts"
# Allow overrides via environment variables
CUSTOM_TRUST_P12="${CUSTOM_TRUST_P12:-/app/certs/truststore.p12}"
CUSTOM_TRUST_P12_PASSWORD="${CUSTOM_TRUST_P12_PASSWORD:-changeit}"

# Only create merged truststore if custom truststore exists
if [ -f "${CUSTOM_TRUST_P12}" ]; then
  echo "Custom truststore found at ${CUSTOM_P12}"
  echo "Creating merged JVM truststore..."

  # Copy default JVM truststore
  cp "${DEFAULT_CACERTS}" "${CUSTOM_TRUSTSTORE}"

  # Merge custom PKCS12 truststore into copied cacerts
  keytool -importkeystore \
    -noprompt \
    -srckeystore "${CUSTOM_TRUST_P12}" \
    -srcstoretype PKCS12 \
    -srcstorepass "${CUSTOM_TRUST_P12_PASSWORD}" \
    -destkeystore "${CUSTOM_TRUSTSTORE}"
    -destkeystore "${CUSTOM_TRUSTSTORE}"

  # Configure JVM to use merged truststore
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} \
-Djavax.net.ssl.trustStore=${CUSTOM_TRUSTSTORE} \
-Djavax.net.ssl.trustStorePassword=changeit"

  echo "Merged truststore configured."
else
  echo "No custom truststore found at ${CUSTOM_TRUST_P12}"
  echo "Using default JVM truststore."
fi

exec java ${JAVA_TOOL_OPTIONS} -jar /app/xyz-tile-cache.jar

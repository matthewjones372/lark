#!/usr/bin/env bash
# Makes the certificates lark-actor-remote's TLS tests use: two CAs, and a key store per node holding its key and a
# certificate one of them signed, with the node's name as a DNS subject alternative name. Valid for a century, so the
# tests need no clock. Run from this directory; it replaces what is here.
set -euo pipefail
pass=lark-test
days=36500
rm -f ./*.p12 ./*.pem ./*.csr

ca() {
    keytool -genkeypair -alias "$1" -dname "CN=$1" -keyalg EC -groupname secp256r1 -validity $days \
        -ext bc:c -keystore "$1.p12" -storetype PKCS12 -storepass $pass
    keytool -exportcert -rfc -alias "$1" -keystore "$1.p12" -storepass $pass -file "$1.pem"
    # The store a node trusts: the CA's certificate alone.
    keytool -importcert -noprompt -alias "$1" -file "$1.pem" -keystore "trusts-$1.p12" -storetype PKCS12 \
        -storepass $pass
}

node() {
    local name=$1 signer=$2
    keytool -genkeypair -alias node -dname "CN=$name" -keyalg EC -groupname secp256r1 -validity $days \
        -keystore "$name.p12" -storetype PKCS12 -storepass $pass
    keytool -certreq -alias node -keystore "$name.p12" -storepass $pass -file "$name.csr"
    keytool -gencert -alias "$signer" -keystore "$signer.p12" -storepass $pass -infile "$name.csr" \
        -outfile "$name.pem" -rfc -validity $days -ext "san=dns:$name" -ext ku:c=digitalSignature \
        -ext eku=serverAuth,clientAuth
    keytool -importcert -noprompt -alias "$signer" -file "$signer.pem" -keystore "$name.p12" -storepass $pass
    keytool -importcert -noprompt -alias node -file "$name.pem" -keystore "$name.p12" -storepass $pass
    rm "$name.csr" "$name.pem"
}

ca cluster-ca
ca other-ca
for name in n1 n2 n3; do node $name cluster-ca; done
node n4 other-ca
rm ./*.pem cluster-ca.p12 other-ca.p12

package io.github.matthewjones372.lark.actor.remote

import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

private const val TLS_1_3 = "TLSv1.3"

/**
 * How a node's connections are secured (spec 0073): TLS 1.3 both ways, each side presenting the certificate in
 * [context]'s keys and requiring one its trust store signed. A peer with no certificate, or one signed by another,
 * is refused before a frame crosses. Each connection is made from [context] afresh, so a context whose keys the
 * service rotates serves the new certificate from the next connection on.
 */
class Tls(val context: SSLContext) {

    internal fun listening(): ServerSocket =
        (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = arrayOf(TLS_1_3)
            needClientAuth = true
        }

    /** [connected], to [peer], as the client side of a TLS connection whose handshake is done. */
    internal fun over(connected: Socket, peer: Node): SSLSocket =
        (context.socketFactory.createSocket(connected, peer.host, peer.port, true) as SSLSocket).apply {
            enabledProtocols = arrayOf(TLS_1_3)
            useClientMode = true
            startHandshake()
        }

    companion object {
        /**
         * TLS from key stores: [keys] holds this node's private key and its certificate chain under [password],
         * and [trusted] the certificates of the CAs whose nodes it lets in, usually the cluster's one.
         */
        fun mutual(keys: KeyStore, password: CharArray, trusted: KeyStore): Tls {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keys, password) }.keyManagers
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trusted) }.trustManagers
            return Tls(SSLContext.getInstance(TLS_1_3).apply { init(keyManagers, trustManagers, null) })
        }
    }
}

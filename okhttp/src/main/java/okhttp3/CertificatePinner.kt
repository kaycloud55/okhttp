/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.toCanonicalHost
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * 约束哪些证书是受信任的。 钉住证书防止对证书颁发机构的攻击。 它还可以防止通过应用程序用户已知或未知的中间人证书权限进行连接。
 * 此类目前固定证书的主题公共密钥信息，如 Adam Langley 的 Weblog 所述。
 * 引脚可以是 HPKP 证书中的 base64 SHA-256哈希，也可以是 Chromium 静态证书中的 SHA-1 base64哈希。
 *
 * ## 设置证书
 *
 *
 * 固定主机最简单的方法是在连接失败时断开网络请求，打开固定并读取预期的配置。 一定要在一个可信的网络上做到这一点，
 * 而且不要使用[Charles]或[Fiddler]这样的中间人工具。
 *
 * For example, to pin `https://publicobject.com`, start with a broken configuration:
 *
 * ```
 * String hostname = "publicobject.com";
 * CertificatePinner certificatePinner = new CertificatePinner.Builder()
 *     .add(hostname, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") //这里明确标记了证书
 *     .build();
 * OkHttpClient client = OkHttpClient.Builder()
 *     .certificatePinner(certificatePinner)
 *     .build();
 *
 * Request request = new Request.Builder()
 *     .url("https://" + hostname)
 *     .build();
 * client.newCall(request).execute();
 * ```
 *
 * As expected, this fails with a certificate pinning exception:
 *
 * ```
 * javax.net.ssl.SSLPeerUnverifiedException: Certificate pinning failure!
 * Peer certificate chain:
 *     sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=: CN=publicobject.com, OU=PositiveSSL
 *     sha256/klO23nT2ehFDXCfx3eHTDRESMz3asj1muO+4aIdjiuY=: CN=COMODO RSA Secure Server CA
 *     sha256/grX4Ta9HpZx6tSHkmCrvpApTQGo67CYDnvprLg5yRME=: CN=COMODO RSA Certification Authority
 *     sha256/lCppFqbkrlJ3EcVFAkeip0+44VaoJUymbnOaEUk7tEU=: CN=AddTrust External CA Root
 * Pinned certificates for publicobject.com:
 *     sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
 *   at okhttp3.CertificatePinner.check(CertificatePinner.java)
 *   at okhttp3.Connection.upgradeToTls(Connection.java)
 *   at okhttp3.Connection.connect(Connection.java)
 *   at okhttp3.Connection.connectAndSetOwner(Connection.java)
 * ```
 *
 * 接下来，将异常中的公钥散列粘贴到 certificate pinner 的配置中:
 *
 * ```
 * CertificatePinner certificatePinner = new CertificatePinner.Builder()
 *     .add("publicobject.com", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
 *     .add("publicobject.com", "sha256/klO23nT2ehFDXCfx3eHTDRESMz3asj1muO+4aIdjiuY=")
 *     .add("publicobject.com", "sha256/grX4Ta9HpZx6tSHkmCrvpApTQGo67CYDnvprLg5yRME=")
 *     .add("publicobject.com", "sha256/lCppFqbkrlJ3EcVFAkeip0+44VaoJUymbnOaEUk7tEU=")
 *     .build();
 * ```
 *
 * ## Domain Patterns
 *
 * Pinning操作是根据主机名和/匹配符来进行匹配的. 如果要同时对`publicobject.com` 和
 * `www.publicobject.com` 进行固定操作，就要对两个hostname都进行配置. 或者使用特定的匹配模式来操作：
 *
 *  * **Full domain name**: you may pin an exact domain name like `www.publicobject.com`. It won't
 *    match additional prefixes (`us-west.www.publicobject.com`) or suffixes (`publicobject.com`).
 *
 *  * **Any number of subdomains**: Use two asterisks to like `**.publicobject.com` to match any
 *    number of prefixes (`us-west.www.publicobject.com`, `www.publicobject.com`) including no
 *    prefix at all (`publicobject.com`). For most applications this is the best way to configure
 *    certificate pinning.
 *
 *  * **Exactly one subdomain**: Use a single asterisk like `*.publicobject.com` to match exactly
 *    one prefix (`www.publicobject.com`, `api.publicobject.com`). Be careful with this approach as
 *    no pinning will be enforced if additional prefixes are present, or if no prefixes are present.
 *
 * Note that any other form is unsupported. You may not use asterisks in any position other than
 * the leftmost label.
 *
 * If multiple patterns match a hostname, any match is sufficient. For example, suppose pin A
 * applies to `*.publicobject.com` and pin B applies to `api.publicobject.com`. Handshakes for
 * `api.publicobject.com` are valid if either A's or B's certificate is in the chain.
 *
 * ## Warning: 固定证书是危险的!
 *
 * 固定证书限制了服务端更新其TLS证书的能力. 一旦固定了证书, 将增加额外的操作复杂性，并限制了在证书颁发机构之间进行迁移的能力.
 * 如果没有服务器的TLS管理员的批准，请不要固定证书。
 *
 * ### 关于自签名证书的注意事项
 *
 * [CertificatePinner] 不能用来固定自签名证书，如果这个证书[javax.net.ssl.TrustManager]信任的话.
 *
 * See also [OWASP: Certificate and Public Key Pinning][owasp].
 *
 * [charles]: http://charlesproxy.com
 * [fiddler]: http://fiddlertool.com
 * [langley]: http://goo.gl/AIx3e5
 * [owasp]: https://www.owasp.org/index.php/Certificate_and_Public_Key_Pinning
 * [rfc_7469]: http://tools.ietf.org/html/rfc7469
 * [static_certificates]: http://goo.gl/XDh6je
 */
@Suppress("NAME_SHADOWING")
class CertificatePinner internal constructor(
        private val pins: Set<Pin>,
        internal val certificateChainCleaner: CertificateChainCleaner?
) {
    /**
     * Confirms that at least one of the certificates pinned for `hostname` is in `peerCertificates`.
     * Does nothing if there are no certificates pinned for `hostname`. OkHttp calls this after a
     * successful TLS handshake, but before the connection is used.
     *
     * @throws SSLPeerUnverifiedException if `peerCertificates` don't match the certificates pinned
     *     for `hostname`.
     */
    @Throws(SSLPeerUnverifiedException::class)
    fun check(hostname: String, peerCertificates: List<Certificate>) {
        return check(hostname) {
            (certificateChainCleaner?.clean(peerCertificates, hostname) ?: peerCertificates)
                    .map { it as X509Certificate }
        }
    }

    internal fun check(hostname: String, cleanedPeerCertificatesFn: () -> List<X509Certificate>) {
        val pins = findMatchingPins(hostname)
        if (pins.isEmpty()) return

        val peerCertificates = cleanedPeerCertificatesFn()

        for (peerCertificate in peerCertificates) {
            // Lazily compute the hashes for each certificate.
            var sha1: ByteString? = null
            var sha256: ByteString? = null

            for (pin in pins) {
                when (pin.hashAlgorithm) {
                    "sha256/" -> {
                        if (sha256 == null) sha256 = peerCertificate.toSha256ByteString()
                        if (pin.hash == sha256) return // Success!
                    }
                    "sha1/" -> {
                        if (sha1 == null) sha1 = peerCertificate.toSha1ByteString()
                        if (pin.hash == sha1) return // Success!
                    }
                    else -> throw AssertionError("unsupported hashAlgorithm: ${pin.hashAlgorithm}")
                }
            }
        }

        // If we couldn't find a matching pin, format a nice exception.
        val message = buildString {
            append("Certificate pinning failure!")
            append("\n  Peer certificate chain:")
            for (element in peerCertificates) {
                append("\n    ")
                append(pin(element))
                append(": ")
                append(element.subjectDN.name)
            }
            append("\n  Pinned certificates for ")
            append(hostname)
            append(":")
            for (pin in pins) {
                append("\n    ")
                append(pin)
            }
        }
        throw SSLPeerUnverifiedException(message)
    }

    @Deprecated(
            "replaced with {@link #check(String, List)}.",
            ReplaceWith("check(hostname, peerCertificates.toList())")
    )
    @Throws(SSLPeerUnverifiedException::class)
    fun check(hostname: String, vararg peerCertificates: Certificate) {
        check(hostname, peerCertificates.toList())
    }

    /**
     * Returns list of matching certificates' pins for the hostname. Returns an empty list if the
     * hostname does not have pinned certificates.
     */
    internal fun findMatchingPins(hostname: String): List<Pin> {
        var result: List<Pin> = emptyList()
        for (pin in pins) {
            if (pin.matches(hostname)) {
                if (result.isEmpty()) result = mutableListOf()
                (result as MutableList<Pin>).add(pin)
            }
        }
        return result
    }

    /** Returns a certificate pinner that uses `certificateChainCleaner`. */
    internal fun withCertificateChainCleaner(
            certificateChainCleaner: CertificateChainCleaner?
    ): CertificatePinner {
        return if (this.certificateChainCleaner == certificateChainCleaner) {
            this
        } else {
            CertificatePinner(pins, certificateChainCleaner)
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is CertificatePinner &&
                other.pins == pins &&
                other.certificateChainCleaner == certificateChainCleaner
    }

    override fun hashCode(): Int {
        var result = 37
        result = 41 * result + pins.hashCode()
        result = 41 * result + certificateChainCleaner.hashCode()
        return result
    }

    internal data class Pin(
            /** A hostname like `example.com` or a pattern like `*.example.com` (canonical form). */
            private val pattern: String,
            /** Either `sha1/` or `sha256/`. */
            val hashAlgorithm: String,
            /** The hash of the pinned certificate using [hashAlgorithm]. */
            val hash: ByteString
    ) {
        fun matches(hostname: String): Boolean {
            return when {
                pattern.startsWith("**.") -> {
                    // With ** empty prefixes match so exclude the dot from regionMatches().
                    val suffixLength = pattern.length - 3
                    val prefixLength = hostname.length - suffixLength
                    hostname.regionMatches(hostname.length - suffixLength, pattern, 3, suffixLength) &&
                            (prefixLength == 0 || hostname[prefixLength - 1] == '.')
                }
                pattern.startsWith("*.") -> {
                    // With * there must be a prefix so include the dot in regionMatches().
                    val suffixLength = pattern.length - 1
                    val prefixLength = hostname.length - suffixLength
                    hostname.regionMatches(hostname.length - suffixLength, pattern, 1, suffixLength) &&
                            hostname.lastIndexOf('.', prefixLength - 1) == -1
                }
                else -> hostname == pattern
            }
        }

        override fun toString(): String = hashAlgorithm + hash.base64()
    }

    /** Builds a configured certificate pinner. */
    class Builder {
        private val pins = mutableListOf<Pin>()

        /**
         * Pins certificates for `pattern`.
         *
         * @param pattern lower-case host name or wildcard pattern such as `*.example.com`.
         * @param pins SHA-256 or SHA-1 hashes. Each pin is a hash of a certificate's Subject Public Key
         *     Info, base64-encoded and prefixed with either `sha256/` or `sha1/`.
         */
        fun add(pattern: String, vararg pins: String) = apply {
            for (pin in pins) {
                this.pins.add(newPin(pattern, pin))
            }
        }

        fun build(): CertificatePinner = CertificatePinner(pins.toSet(), null)
    }

    companion object {
        @JvmField
        val DEFAULT = Builder().build()

        /**
         * Returns the SHA-256 of `certificate`'s public key.
         *
         * In OkHttp 3.1.2 and earlier, this returned a SHA-1 hash of the public key. Both types are
         * supported, but SHA-256 is preferred.
         */
        @JvmStatic
        fun pin(certificate: Certificate): String {
            require(certificate is X509Certificate) { "Certificate pinning requires X509 certificates" }
            return "sha256/${certificate.toSha256ByteString().base64()}"
        }

        internal fun X509Certificate.toSha1ByteString(): ByteString =
                publicKey.encoded.toByteString().sha1()

        internal fun X509Certificate.toSha256ByteString(): ByteString =
                publicKey.encoded.toByteString().sha256()

        internal fun newPin(pattern: String, pin: String): Pin {
            require((pattern.startsWith("*.") && pattern.indexOf("*", 1) == -1) ||
                    (pattern.startsWith("**.") && pattern.indexOf("*", 2) == -1) ||
                    pattern.indexOf("*") == -1) {
                "Unexpected pattern: $pattern"
            }
            val canonicalPattern =
                    pattern.toCanonicalHost() ?: throw IllegalArgumentException("Invalid pattern: $pattern")

            return when {
                pin.startsWith("sha1/") -> {
                    val hash = pin.substring("sha1/".length).decodeBase64()!!
                    Pin(canonicalPattern, "sha1/", hash)
                }
                pin.startsWith("sha256/") -> {
                    val hash = pin.substring("sha256/".length).decodeBase64()!!
                    Pin(canonicalPattern, "sha256/", hash)
                }
                else -> throw IllegalArgumentException("pins must start with 'sha256/' or 'sha1/': $pin")
            }
        }
    }
}

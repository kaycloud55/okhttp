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

/**
 * [TLS cipher suites][iana_tls_parameters].
 *
 * **Not all cipher suites are supported on all platforms.** As newer cipher suites are created (for
 * stronger privacy, better performance, etc.) they will be adopted by the platform and then exposed
 * here. Cipher suites that are not available on either Android (through API level 24) or Java
 * (through JDK 9) are omitted for brevity.
 *
 * See [Android SSLEngine][sslengine] which lists the cipher suites supported by Android.
 *
 * See [JDK Providers][oracle_providers] which lists the cipher suites supported by Oracle.
 *
 * See [NativeCrypto.java][conscrypt_providers] which lists the cipher suites supported by
 * Conscrypt.
 *
 * [iana_tls_parameters]: https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml
 * [sslengine]: https://developer.android.com/reference/javax/net/ssl/SSLEngine.html
 * [oracle_providers]: https://docs.oracle.com/javase/10/security/oracle-providers.htm
 * [conscrypt_providers]: https://github.com/google/conscrypt/blob/master/common/src/main/java/org/conscrypt/NativeCrypto.java
 */
class CipherSuite private constructor(
    /**
     * Returns the Java name of this cipher suite. For some older cipher suites the Java name has the
     * prefix `SSL_`, causing the Java name to be different from the instance name which is always
     * prefixed `TLS_`. For example, `TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName()` is
     * `"SSL_RSA_EXPORT_WITH_RC4_40_MD5"`.
     */
    @get:JvmName("javaName") val javaName: String
) {
    @JvmName("-deprecated_javaName")
    @Deprecated(
        message = "moved to val",
        replaceWith = ReplaceWith(expression = "javaName"),
        level = DeprecationLevel.ERROR)
    fun javaName(): String = javaName

    override fun toString(): String = javaName

    companion object {
        /**
         * Compares cipher suites names like "TLS_RSA_WITH_NULL_MD5" and "SSL_RSA_WITH_NULL_MD5",
         * ignoring the "TLS_" or "SSL_" prefix which is not consistent across platforms. In particular
         * some IBM JVMs use the "SSL_" prefix everywhere whereas Oracle JVMs mix "TLS_" and "SSL_".
         */
        internal val ORDER_BY_NAME = object : Comparator<String> {
            override fun compare(a: String, b: String): Int {
                var i = 4
                val limit = minOf(a.length, b.length)
                while (i < limit) {
                    val charA = a[i]
                    val charB = b[i]
                    if (charA != charB) return if (charA < charB) -1 else 1
                    i++
                }
                val lengthA = a.length
                val lengthB = b.length
                if (lengthA != lengthB) return if (lengthA < lengthB) -1 else 1
                return 0
            }
        }

        /**
         * Holds interned instances. This needs to be above the init() calls below so that it's
         * initialized by the time those parts of `<clinit>()` run. Guarded by CipherSuite.class.
         */
        private val INSTANCES = mutableMapOf<String, CipherSuite>()

        // Last updated 2016-07-03 using cipher suites from Android 24 and Java 9.

        // @JvmField val TLS_NULL_WITH_NULL_NULL = init("TLS_NULL_WITH_NULL_NULL", 0x0000)
        @JvmField
        val TLS_RSA_WITH_NULL_MD5 = init("SSL_RSA_WITH_NULL_MD5", 0x0001)
        @JvmField
        val TLS_RSA_WITH_NULL_SHA = init("SSL_RSA_WITH_NULL_SHA", 0x0002)
        @JvmField
        val TLS_RSA_EXPORT_WITH_RC4_40_MD5 = init("SSL_RSA_EXPORT_WITH_RC4_40_MD5", 0x0003)
        @JvmField
        val TLS_RSA_WITH_RC4_128_MD5 = init("SSL_RSA_WITH_RC4_128_MD5", 0x0004)
        @JvmField
        val TLS_RSA_WITH_RC4_128_SHA = init("SSL_RSA_WITH_RC4_128_SHA", 0x0005)
        // @JvmField val TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5 = init("SSL_RSA_EXPORT_WITH_RC2_CBC_40_MD5", 0x0006)
        // @JvmField val TLS_RSA_WITH_IDEA_CBC_SHA = init("TLS_RSA_WITH_IDEA_CBC_SHA", 0x0007)
        @JvmField
        val TLS_RSA_EXPORT_WITH_DES40_CBC_SHA = init("SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x0008)
        @JvmField
        val TLS_RSA_WITH_DES_CBC_SHA = init("SSL_RSA_WITH_DES_CBC_SHA", 0x0009)
        @JvmField
        val TLS_RSA_WITH_3DES_EDE_CBC_SHA = init("SSL_RSA_WITH_3DES_EDE_CBC_SHA", 0x000a)
        // @JvmField val TLS_DH_DSS_EXPORT_WITH_DES40_CBC_SHA = init("SSL_DH_DSS_EXPORT_WITH_DES40_CBC_SHA", 0x000b)
        // @JvmField val TLS_DH_DSS_WITH_DES_CBC_SHA = init("TLS_DH_DSS_WITH_DES_CBC_SHA", 0x000c)
        // @JvmField val TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA = init("TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA", 0x000d)
        // @JvmField val TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA = init("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x000e)
        // @JvmField val TLS_DH_RSA_WITH_DES_CBC_SHA = init("TLS_DH_RSA_WITH_DES_CBC_SHA", 0x000f)
        // @JvmField val TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA = init("TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA", 0x0010)
        @JvmField
        val TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA = init("SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA", 0x0011)
        @JvmField
        val TLS_DHE_DSS_WITH_DES_CBC_SHA = init("SSL_DHE_DSS_WITH_DES_CBC_SHA", 0x0012)
        @JvmField
        val TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA = init("SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA", 0x0013)
        @JvmField
        val TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA = init("SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x0014)
        @JvmField
        val TLS_DHE_RSA_WITH_DES_CBC_SHA = init("SSL_DHE_RSA_WITH_DES_CBC_SHA", 0x0015)
        @JvmField
        val TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA = init("SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA", 0x0016)
        @JvmField
        val TLS_DH_anon_EXPORT_WITH_RC4_40_MD5 = init("SSL_DH_anon_EXPORT_WITH_RC4_40_MD5", 0x0017)
        @JvmField
        val TLS_DH_anon_WITH_RC4_128_MD5 = init("SSL_DH_anon_WITH_RC4_128_MD5", 0x0018)
        @JvmField
        val TLS_DH_anon_EXPORT_WITH_DES40_CBC_SHA = init("SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA", 0x0019)
        @JvmField
        val TLS_DH_anon_WITH_DES_CBC_SHA = init("SSL_DH_anon_WITH_DES_CBC_SHA", 0x001a)
        @JvmField
        val TLS_DH_anon_WITH_3DES_EDE_CBC_SHA = init("SSL_DH_anon_WITH_3DES_EDE_CBC_SHA", 0x001b)
        @JvmField
        val TLS_KRB5_WITH_DES_CBC_SHA = init("TLS_KRB5_WITH_DES_CBC_SHA", 0x001e)
        @JvmField
        val TLS_KRB5_WITH_3DES_EDE_CBC_SHA = init("TLS_KRB5_WITH_3DES_EDE_CBC_SHA", 0x001f)
        @JvmField
        val TLS_KRB5_WITH_RC4_128_SHA = init("TLS_KRB5_WITH_RC4_128_SHA", 0x0020)
        // @JvmField val TLS_KRB5_WITH_IDEA_CBC_SHA = init("TLS_KRB5_WITH_IDEA_CBC_SHA", 0x0021)
        @JvmField
        val TLS_KRB5_WITH_DES_CBC_MD5 = init("TLS_KRB5_WITH_DES_CBC_MD5", 0x0022)
        @JvmField
        val TLS_KRB5_WITH_3DES_EDE_CBC_MD5 = init("TLS_KRB5_WITH_3DES_EDE_CBC_MD5", 0x0023)
        @JvmField
        val TLS_KRB5_WITH_RC4_128_MD5 = init("TLS_KRB5_WITH_RC4_128_MD5", 0x0024)
        // @JvmField val TLS_KRB5_WITH_IDEA_CBC_MD5 = init("TLS_KRB5_WITH_IDEA_CBC_MD5", 0x0025)
        @JvmField
        val TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA = init("TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA", 0x0026)
        // @JvmField val TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA = init("TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA", 0x0027)
        @JvmField
        val TLS_KRB5_EXPORT_WITH_RC4_40_SHA = init("TLS_KRB5_EXPORT_WITH_RC4_40_SHA", 0x0028)
        @JvmField
        val TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5 = init("TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5", 0x0029)
        // @JvmField val TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5 = init("TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5", 0x002a)
        @JvmField
        val TLS_KRB5_EXPORT_WITH_RC4_40_MD5 = init("TLS_KRB5_EXPORT_WITH_RC4_40_MD5", 0x002b)
        // @JvmField val TLS_PSK_WITH_NULL_SHA = init("TLS_PSK_WITH_NULL_SHA", 0x002c)
        // @JvmField val TLS_DHE_PSK_WITH_NULL_SHA = init("TLS_DHE_PSK_WITH_NULL_SHA", 0x002d)
        // @JvmField val TLS_RSA_PSK_WITH_NULL_SHA = init("TLS_RSA_PSK_WITH_NULL_SHA", 0x002e)
        @JvmField
        val TLS_RSA_WITH_AES_128_CBC_SHA = init("TLS_RSA_WITH_AES_128_CBC_SHA", 0x002f)
        // @JvmField val TLS_DH_DSS_WITH_AES_128_CBC_SHA = init("TLS_DH_DSS_WITH_AES_128_CBC_SHA", 0x0030)
        // @JvmField val TLS_DH_RSA_WITH_AES_128_CBC_SHA = init("TLS_DH_RSA_WITH_AES_128_CBC_SHA", 0x0031)
        @JvmField
        val TLS_DHE_DSS_WITH_AES_128_CBC_SHA = init("TLS_DHE_DSS_WITH_AES_128_CBC_SHA", 0x0032)
        @JvmField
        val TLS_DHE_RSA_WITH_AES_128_CBC_SHA = init("TLS_DHE_RSA_WITH_AES_128_CBC_SHA", 0x0033)
        @JvmField
        val TLS_DH_anon_WITH_AES_128_CBC_SHA = init("TLS_DH_anon_WITH_AES_128_CBC_SHA", 0x0034)
        @JvmField
        val TLS_RSA_WITH_AES_256_CBC_SHA = init("TLS_RSA_WITH_AES_256_CBC_SHA", 0x0035)
        // @JvmField val TLS_DH_DSS_WITH_AES_256_CBC_SHA = init("TLS_DH_DSS_WITH_AES_256_CBC_SHA", 0x0036)
        // @JvmField val TLS_DH_RSA_WITH_AES_256_CBC_SHA = init("TLS_DH_RSA_WITH_AES_256_CBC_SHA", 0x0037)
        @JvmField
        val TLS_DHE_DSS_WITH_AES_256_CBC_SHA = init("TLS_DHE_DSS_WITH_AES_256_CBC_SHA", 0x0038)
        @JvmField
        val TLS_DHE_RSA_WITH_AES_256_CBC_SHA = init("TLS_DHE_RSA_WITH_AES_256_CBC_SHA", 0x0039)
        @JvmField
        val TLS_DH_anon_WITH_AES_256_CBC_SHA = init("TLS_DH_anon_WITH_AES_256_CBC_SHA", 0x003a)
        @JvmField
        val TLS_RSA_WITH_NULL_SHA256 = init("TLS_RSA_WITH_NULL_SHA256", 0x003b)
        @JvmField
        val TLS_RSA_WITH_AES_128_CBC_SHA256 = init("TLS_RSA_WITH_AES_128_CBC_SHA256", 0x003c)
        @JvmField
        val TLS_RSA_WITH_AES_256_CBC_SHA256 = init("TLS_RSA_WITH_AES_256_CBC_SHA256", 0x003d)
        // @JvmField val TLS_DH_DSS_WITH_AES_128_CBC_SHA256 = init("TLS_DH_DSS_WITH_AES_128_CBC_SHA256", 0x003e)
        // @JvmField val TLS_DH_RSA_WITH_AES_128_CBC_SHA256 = init("TLS_DH_RSA_WITH_AES_128_CBC_SHA256", 0x003f)
        @JvmField
        val TLS_DHE_DSS_WITH_AES_128_CBC_SHA256 = init("TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", 0x0040)
        @JvmField
        val TLS_RSA_WITH_CAMELLIA_128_CBC_SHA = init("TLS_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0041)
        // @JvmField val TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA = init("TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA", 0x0042)
        // @JvmField val TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA = init("TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0043)
        @JvmField
        val TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA = init("TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA", 0x0044)
        @JvmField
        val TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA = init("TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0045)
        // @JvmField val TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA = init("TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA", 0x0046)
        @JvmField
        val TLS_DHE_RSA_WITH_AES_128_CBC_SHA256 = init("TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", 0x0067)
        // @JvmField val TLS_DH_DSS_WITH_AES_256_CBC_SHA256 = init("TLS_DH_DSS_WITH_AES_256_CBC_SHA256", 0x0068)
        // @JvmField val TLS_DH_RSA_WITH_AES_256_CBC_SHA256 = init("TLS_DH_RSA_WITH_AES_256_CBC_SHA256", 0x0069)
        @JvmField
        val TLS_DHE_DSS_WITH_AES_256_CBC_SHA256 = init("TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", 0x006a)
        @JvmField
        val TLS_DHE_RSA_WITH_AES_256_CBC_SHA256 = init("TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", 0x006b)
        @JvmField
        val TLS_DH_anon_WITH_AES_128_CBC_SHA256 = init("TLS_DH_anon_WITH_AES_128_CBC_SHA256", 0x006c)
        @JvmField
        val TLS_DH_anon_WITH_AES_256_CBC_SHA256 = init("TLS_DH_anon_WITH_AES_256_CBC_SHA256", 0x006d)
        @JvmField
        val TLS_RSA_WITH_CAMELLIA_256_CBC_SHA = init("TLS_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0084)
        // @JvmField val TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA = init("TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA", 0x0085)
        // @JvmField val TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA = init("TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0086)
        @JvmField
        val TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA = init("TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA", 0x0087)
        @JvmField
        val TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA = init("TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0088)
        // @JvmField val TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA = init("TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA", 0x0089)
        @JvmField
        val TLS_PSK_WITH_RC4_128_SHA = init("TLS_PSK_WITH_RC4_128_SHA", 0x008a)
        @JvmField
        val TLS_PSK_WITH_3DES_EDE_CBC_SHA = init("TLS_PSK_WITH_3DES_EDE_CBC_SHA", 0x008b)
        @JvmField
        val TLS_PSK_WITH_AES_128_CBC_SHA = init("TLS_PSK_WITH_AES_128_CBC_SHA", 0x008c)
        @JvmField
        val TLS_PSK_WITH_AES_256_CBC_SHA = init("TLS_PSK_WITH_AES_256_CBC_SHA", 0x008d)
        // @JvmField val TLS_DHE_PSK_WITH_RC4_128_SHA = init("TLS_DHE_PSK_WITH_RC4_128_SHA", 0x008e)
        // @JvmField val TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA = init("TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA", 0x008f)
        // @JvmField val TLS_DHE_PSK_WITH_AES_128_CBC_SHA = init("TLS_DHE_PSK_WITH_AES_128_CBC_SHA", 0x0090)
        // @JvmField val TLS_DHE_PSK_WITH_AES_256_CBC_SHA = init("TLS_DHE_PSK_WITH_AES_256_CBC_SHA", 0x0091)
        // @JvmField val TLS_RSA_PSK_WITH_RC4_128_SHA = init("TLS_RSA_PSK_WITH_RC4_128_SHA", 0x0092)
        // @JvmField val TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA = init("TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA", 0x0093)
        // @JvmField val TLS_RSA_PSK_WITH_AES_128_CBC_SHA = init("TLS_RSA_PSK_WITH_AES_128_CBC_SHA", 0x0094)
        // @JvmField val TLS_RSA_PSK_WITH_AES_256_CBC_SHA = init("TLS_RSA_PSK_WITH_AES_256_CBC_SHA", 0x0095)
        @JvmField
        val TLS_RSA_WITH_SEED_CBC_SHA = init("TLS_RSA_WITH_SEED_CBC_SHA", 0x0096)
        // @JvmField val TLS_DH_DSS_WITH_SEED_CBC_SHA = init("TLS_DH_DSS_WITH_SEED_CBC_SHA", 0x0097)
        // @JvmField val TLS_DH_RSA_WITH_SEED_CBC_SHA = init("TLS_DH_RSA_WITH_SEED_CBC_SHA", 0x0098)
        // @JvmField val TLS_DHE_DSS_WITH_SEED_CBC_SHA = init("TLS_DHE_DSS_WITH_SEED_CBC_SHA", 0x0099)
        // @JvmField val TLS_DHE_RSA_WITH_SEED_CBC_SHA = init("TLS_DHE_RSA_WITH_SEED_CBC_SHA", 0x009a)
        // @JvmField val TLS_DH_anon_WITH_SEED_CBC_SHA = init("TLS_DH_anon_WITH_SEED_CBC_SHA", 0x009b)
        @JvmField
        val TLS_RSA_WITH_AES_128_GCM_SHA256 = init("TLS_RSA_WITH_AES_128_GCM_SHA256", 0x009c)
        @JvmField
        val TLS_RSA_WITH_AES_256_GCM_SHA384 = init("TLS_RSA_WITH_AES_256_GCM_SHA384", 0x009d)
        @JvmField
        val TLS_DHE_RSA_WITH_AES_128_GCM_SHA256 = init("TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", 0x009e)
        @JvmField
        val TLS_DHE_RSA_WITH_AES_256_GCM_SHA384 = init("TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", 0x009f)
        // @JvmField val TLS_DH_RSA_WITH_AES_128_GCM_SHA256 = init("TLS_DH_RSA_WITH_AES_128_GCM_SHA256", 0x00a0)
        // @JvmField val TLS_DH_RSA_WITH_AES_256_GCM_SHA384 = init("TLS_DH_RSA_WITH_AES_256_GCM_SHA384", 0x00a1)
        @JvmField
        val TLS_DHE_DSS_WITH_AES_128_GCM_SHA256 = init("TLS_DHE_DSS_WITH_AES_128_GCM_SHA256", 0x00a2)
        @JvmField
        val TLS_DHE_DSS_WITH_AES_256_GCM_SHA384 = init("TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", 0x00a3)
        // @JvmField val TLS_DH_DSS_WITH_AES_128_GCM_SHA256 = init("TLS_DH_DSS_WITH_AES_128_GCM_SHA256", 0x00a4)
        // @JvmField val TLS_DH_DSS_WITH_AES_256_GCM_SHA384 = init("TLS_DH_DSS_WITH_AES_256_GCM_SHA384", 0x00a5)
        @JvmField
        val TLS_DH_anon_WITH_AES_128_GCM_SHA256 = init("TLS_DH_anon_WITH_AES_128_GCM_SHA256", 0x00a6)
        @JvmField
        val TLS_DH_anon_WITH_AES_256_GCM_SHA384 = init("TLS_DH_anon_WITH_AES_256_GCM_SHA384", 0x00a7)
        // @JvmField val TLS_PSK_WITH_AES_128_GCM_SHA256 = init("TLS_PSK_WITH_AES_128_GCM_SHA256", 0x00a8)
        // @JvmField val TLS_PSK_WITH_AES_256_GCM_SHA384 = init("TLS_PSK_WITH_AES_256_GCM_SHA384", 0x00a9)
        // @JvmField val TLS_DHE_PSK_WITH_AES_128_GCM_SHA256 = init("TLS_DHE_PSK_WITH_AES_128_GCM_SHA256", 0x00aa)
        // @JvmField val TLS_DHE_PSK_WITH_AES_256_GCM_SHA384 = init("TLS_DHE_PSK_WITH_AES_256_GCM_SHA384", 0x00ab)
        // @JvmField val TLS_RSA_PSK_WITH_AES_128_GCM_SHA256 = init("TLS_RSA_PSK_WITH_AES_128_GCM_SHA256", 0x00ac)
        // @JvmField val TLS_RSA_PSK_WITH_AES_256_GCM_SHA384 = init("TLS_RSA_PSK_WITH_AES_256_GCM_SHA384", 0x00ad)
        // @JvmField val TLS_PSK_WITH_AES_128_CBC_SHA256 = init("TLS_PSK_WITH_AES_128_CBC_SHA256", 0x00ae)
        // @JvmField val TLS_PSK_WITH_AES_256_CBC_SHA384 = init("TLS_PSK_WITH_AES_256_CBC_SHA384", 0x00af)
        // @JvmField val TLS_PSK_WITH_NULL_SHA256 = init("TLS_PSK_WITH_NULL_SHA256", 0x00b0)
        // @JvmField val TLS_PSK_WITH_NULL_SHA384 = init("TLS_PSK_WITH_NULL_SHA384", 0x00b1)
        // @JvmField val TLS_DHE_PSK_WITH_AES_128_CBC_SHA256 = init("TLS_DHE_PSK_WITH_AES_128_CBC_SHA256", 0x00b2)
        // @JvmField val TLS_DHE_PSK_WITH_AES_256_CBC_SHA384 = init("TLS_DHE_PSK_WITH_AES_256_CBC_SHA384", 0x00b3)
        // @JvmField val TLS_DHE_PSK_WITH_NULL_SHA256 = init("TLS_DHE_PSK_WITH_NULL_SHA256", 0x00b4)
        // @JvmField val TLS_DHE_PSK_WITH_NULL_SHA384 = init("TLS_DHE_PSK_WITH_NULL_SHA384", 0x00b5)
        // @JvmField val TLS_RSA_PSK_WITH_AES_128_CBC_SHA256 = init("TLS_RSA_PSK_WITH_AES_128_CBC_SHA256", 0x00b6)
        // @JvmField val TLS_RSA_PSK_WITH_AES_256_CBC_SHA384 = init("TLS_RSA_PSK_WITH_AES_256_CBC_SHA384", 0x00b7)
        // @JvmField val TLS_RSA_PSK_WITH_NULL_SHA256 = init("TLS_RSA_PSK_WITH_NULL_SHA256", 0x00b8)
        // @JvmField val TLS_RSA_PSK_WITH_NULL_SHA384 = init("TLS_RSA_PSK_WITH_NULL_SHA384", 0x00b9)
        // @JvmField val TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00ba)
        // @JvmField val TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256", 0x00bb)
        // @JvmField val TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00bc)
        // @JvmField val TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256", 0x00bd)
        // @JvmField val TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00be)
        // @JvmField val TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256", 0x00bf)
        // @JvmField val TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256 = init("TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c0)
        // @JvmField val TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256 = init("TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256", 0x00c1)
        // @JvmField val TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256 = init("TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c2)
        // @JvmField val TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256 = init("TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256", 0x00c3)
        // @JvmField val TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256 = init("TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c4)
        // @JvmField val TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256 = init("TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256", 0x00c5)
        @JvmField
        val TLS_EMPTY_RENEGOTIATION_INFO_SCSV = init("TLS_EMPTY_RENEGOTIATION_INFO_SCSV", 0x00ff)
        @JvmField
        val TLS_FALLBACK_SCSV = init("TLS_FALLBACK_SCSV", 0x5600)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_NULL_SHA = init("TLS_ECDH_ECDSA_WITH_NULL_SHA", 0xc001)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_RC4_128_SHA = init("TLS_ECDH_ECDSA_WITH_RC4_128_SHA", 0xc002)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA = init("TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA", 0xc003)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA = init("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA", 0xc004)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA = init("TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA", 0xc005)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_NULL_SHA = init("TLS_ECDHE_ECDSA_WITH_NULL_SHA", 0xc006)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_RC4_128_SHA = init("TLS_ECDHE_ECDSA_WITH_RC4_128_SHA", 0xc007)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA = init("TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA", 0xc008)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA = init("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", 0xc009)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA = init("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", 0xc00a)
        @JvmField
        val TLS_ECDH_RSA_WITH_NULL_SHA = init("TLS_ECDH_RSA_WITH_NULL_SHA", 0xc00b)
        @JvmField
        val TLS_ECDH_RSA_WITH_RC4_128_SHA = init("TLS_ECDH_RSA_WITH_RC4_128_SHA", 0xc00c)
        @JvmField
        val TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA = init("TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA", 0xc00d)
        @JvmField
        val TLS_ECDH_RSA_WITH_AES_128_CBC_SHA = init("TLS_ECDH_RSA_WITH_AES_128_CBC_SHA", 0xc00e)
        @JvmField
        val TLS_ECDH_RSA_WITH_AES_256_CBC_SHA = init("TLS_ECDH_RSA_WITH_AES_256_CBC_SHA", 0xc00f)
        @JvmField
        val TLS_ECDHE_RSA_WITH_NULL_SHA = init("TLS_ECDHE_RSA_WITH_NULL_SHA", 0xc010)
        @JvmField
        val TLS_ECDHE_RSA_WITH_RC4_128_SHA = init("TLS_ECDHE_RSA_WITH_RC4_128_SHA", 0xc011)
        @JvmField
        val TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA = init("TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", 0xc012)
        @JvmField
        val TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA = init("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", 0xc013)
        @JvmField
        val TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA = init("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", 0xc014)
        @JvmField
        val TLS_ECDH_anon_WITH_NULL_SHA = init("TLS_ECDH_anon_WITH_NULL_SHA", 0xc015)
        @JvmField
        val TLS_ECDH_anon_WITH_RC4_128_SHA = init("TLS_ECDH_anon_WITH_RC4_128_SHA", 0xc016)
        @JvmField
        val TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA = init("TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA", 0xc017)
        @JvmField
        val TLS_ECDH_anon_WITH_AES_128_CBC_SHA = init("TLS_ECDH_anon_WITH_AES_128_CBC_SHA", 0xc018)
        @JvmField
        val TLS_ECDH_anon_WITH_AES_256_CBC_SHA = init("TLS_ECDH_anon_WITH_AES_256_CBC_SHA", 0xc019)
        // @JvmField val TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA = init("TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA", 0xc01a)
        // @JvmField val TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA = init("TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA", 0xc01b)
        // @JvmField val TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA = init("TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA", 0xc01c)
        // @JvmField val TLS_SRP_SHA_WITH_AES_128_CBC_SHA = init("TLS_SRP_SHA_WITH_AES_128_CBC_SHA", 0xc01d)
        // @JvmField val TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA = init("TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA", 0xc01e)
        // @JvmField val TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA = init("TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA", 0xc01f)
        // @JvmField val TLS_SRP_SHA_WITH_AES_256_CBC_SHA = init("TLS_SRP_SHA_WITH_AES_256_CBC_SHA", 0xc020)
        // @JvmField val TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA = init("TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA", 0xc021)
        // @JvmField val TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA = init("TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA", 0xc022)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256 = init("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", 0xc023)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384 = init("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", 0xc024)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256 = init("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256", 0xc025)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384 = init("TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384", 0xc026)
        @JvmField
        val TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256 = init("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", 0xc027)
        @JvmField
        val TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384 = init("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", 0xc028)
        @JvmField
        val TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256 = init("TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256", 0xc029)
        @JvmField
        val TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384 = init("TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384", 0xc02a)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 = init("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", 0xc02b)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 = init("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", 0xc02c)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256 = init("TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256", 0xc02d)
        @JvmField
        val TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384 = init("TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384", 0xc02e)
        @JvmField
        val TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 = init("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", 0xc02f)
        @JvmField
        val TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 = init("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", 0xc030)
        @JvmField
        val TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256 = init("TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256", 0xc031)
        @JvmField
        val TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384 = init("TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384", 0xc032)
        // @JvmField val TLS_ECDHE_PSK_WITH_RC4_128_SHA = init("TLS_ECDHE_PSK_WITH_RC4_128_SHA", 0xc033)
        // @JvmField val TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA = init("TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA", 0xc034)
        @JvmField
        val TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA = init("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA", 0xc035)
        @JvmField
        val TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA = init("TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA", 0xc036)
        // @JvmField val TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256 = init("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256", 0xc037)
        // @JvmField val TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384 = init("TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384", 0xc038)
        // @JvmField val TLS_ECDHE_PSK_WITH_NULL_SHA = init("TLS_ECDHE_PSK_WITH_NULL_SHA", 0xc039)
        // @JvmField val TLS_ECDHE_PSK_WITH_NULL_SHA256 = init("TLS_ECDHE_PSK_WITH_NULL_SHA256", 0xc03a)
        // @JvmField val TLS_ECDHE_PSK_WITH_NULL_SHA384 = init("TLS_ECDHE_PSK_WITH_NULL_SHA384", 0xc03b)
        // @JvmField val TLS_RSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_RSA_WITH_ARIA_128_CBC_SHA256", 0xc03c)
        // @JvmField val TLS_RSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_RSA_WITH_ARIA_256_CBC_SHA384", 0xc03d)
        // @JvmField val TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256 = init("TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256", 0xc03e)
        // @JvmField val TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384 = init("TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384", 0xc03f)
        // @JvmField val TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256", 0xc040)
        // @JvmField val TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384", 0xc041)
        // @JvmField val TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256 = init("TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256", 0xc042)
        // @JvmField val TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384 = init("TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384", 0xc043)
        // @JvmField val TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256", 0xc044)
        // @JvmField val TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384", 0xc045)
        // @JvmField val TLS_DH_anon_WITH_ARIA_128_CBC_SHA256 = init("TLS_DH_anon_WITH_ARIA_128_CBC_SHA256", 0xc046)
        // @JvmField val TLS_DH_anon_WITH_ARIA_256_CBC_SHA384 = init("TLS_DH_anon_WITH_ARIA_256_CBC_SHA384", 0xc047)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256", 0xc048)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384", 0xc049)
        // @JvmField val TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256", 0xc04a)
        // @JvmField val TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384", 0xc04b)
        // @JvmField val TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256", 0xc04c)
        // @JvmField val TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384", 0xc04d)
        // @JvmField val TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256 = init("TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256", 0xc04e)
        // @JvmField val TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384 = init("TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384", 0xc04f)
        // @JvmField val TLS_RSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_RSA_WITH_ARIA_128_GCM_SHA256", 0xc050)
        // @JvmField val TLS_RSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_RSA_WITH_ARIA_256_GCM_SHA384", 0xc051)
        // @JvmField val TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256", 0xc052)
        // @JvmField val TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384", 0xc053)
        // @JvmField val TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256", 0xc054)
        // @JvmField val TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384", 0xc055)
        // @JvmField val TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256 = init("TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256", 0xc056)
        // @JvmField val TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384 = init("TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384", 0xc057)
        // @JvmField val TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256 = init("TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256", 0xc058)
        // @JvmField val TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384 = init("TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384", 0xc059)
        // @JvmField val TLS_DH_anon_WITH_ARIA_128_GCM_SHA256 = init("TLS_DH_anon_WITH_ARIA_128_GCM_SHA256", 0xc05a)
        // @JvmField val TLS_DH_anon_WITH_ARIA_256_GCM_SHA384 = init("TLS_DH_anon_WITH_ARIA_256_GCM_SHA384", 0xc05b)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256", 0xc05c)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384", 0xc05d)
        // @JvmField val TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256", 0xc05e)
        // @JvmField val TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384", 0xc05f)
        // @JvmField val TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256", 0xc060)
        // @JvmField val TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384", 0xc061)
        // @JvmField val TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256 = init("TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256", 0xc062)
        // @JvmField val TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384 = init("TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384", 0xc063)
        // @JvmField val TLS_PSK_WITH_ARIA_128_CBC_SHA256 = init("TLS_PSK_WITH_ARIA_128_CBC_SHA256", 0xc064)
        // @JvmField val TLS_PSK_WITH_ARIA_256_CBC_SHA384 = init("TLS_PSK_WITH_ARIA_256_CBC_SHA384", 0xc065)
        // @JvmField val TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256 = init("TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256", 0xc066)
        // @JvmField val TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384 = init("TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384", 0xc067)
        // @JvmField val TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256 = init("TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256", 0xc068)
        // @JvmField val TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384 = init("TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384", 0xc069)
        // @JvmField val TLS_PSK_WITH_ARIA_128_GCM_SHA256 = init("TLS_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06a)
        // @JvmField val TLS_PSK_WITH_ARIA_256_GCM_SHA384 = init("TLS_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06b)
        // @JvmField val TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256 = init("TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06c)
        // @JvmField val TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384 = init("TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06d)
        // @JvmField val TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256 = init("TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06e)
        // @JvmField val TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384 = init("TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06f)
        // @JvmField val TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256 = init("TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256", 0xc070)
        // @JvmField val TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384 = init("TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384", 0xc071)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc072)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc073)
        // @JvmField val TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc074)
        // @JvmField val TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc075)
        // @JvmField val TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc076)
        // @JvmField val TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc077)
        // @JvmField val TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc078)
        // @JvmField val TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc079)
        // @JvmField val TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07a)
        // @JvmField val TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07b)
        // @JvmField val TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07c)
        // @JvmField val TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07d)
        // @JvmField val TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07e)
        // @JvmField val TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07f)
        // @JvmField val TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256", 0xc080)
        // @JvmField val TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384", 0xc081)
        // @JvmField val TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256", 0xc082)
        // @JvmField val TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384", 0xc083)
        // @JvmField val TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256", 0xc084)
        // @JvmField val TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384", 0xc085)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc086)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc087)
        // @JvmField val TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc088)
        // @JvmField val TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc089)
        // @JvmField val TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc08a)
        // @JvmField val TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc08b)
        // @JvmField val TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc08c)
        // @JvmField val TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc08d)
        // @JvmField val TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc08e)
        // @JvmField val TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc08f)
        // @JvmField val TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc090)
        // @JvmField val TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc091)
        // @JvmField val TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256 = init("TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc092)
        // @JvmField val TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384 = init("TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc093)
        // @JvmField val TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc094)
        // @JvmField val TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc095)
        // @JvmField val TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc096)
        // @JvmField val TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc097)
        // @JvmField val TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc098)
        // @JvmField val TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc099)
        // @JvmField val TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256 = init("TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc09a)
        // @JvmField val TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384 = init("TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc09b)
        // @JvmField val TLS_RSA_WITH_AES_128_CCM = init("TLS_RSA_WITH_AES_128_CCM", 0xc09c)
        // @JvmField val TLS_RSA_WITH_AES_256_CCM = init("TLS_RSA_WITH_AES_256_CCM", 0xc09d)
        // @JvmField val TLS_DHE_RSA_WITH_AES_128_CCM = init("TLS_DHE_RSA_WITH_AES_128_CCM", 0xc09e)
        // @JvmField val TLS_DHE_RSA_WITH_AES_256_CCM = init("TLS_DHE_RSA_WITH_AES_256_CCM", 0xc09f)
        // @JvmField val TLS_RSA_WITH_AES_128_CCM_8 = init("TLS_RSA_WITH_AES_128_CCM_8", 0xc0a0)
        // @JvmField val TLS_RSA_WITH_AES_256_CCM_8 = init("TLS_RSA_WITH_AES_256_CCM_8", 0xc0a1)
        // @JvmField val TLS_DHE_RSA_WITH_AES_128_CCM_8 = init("TLS_DHE_RSA_WITH_AES_128_CCM_8", 0xc0a2)
        // @JvmField val TLS_DHE_RSA_WITH_AES_256_CCM_8 = init("TLS_DHE_RSA_WITH_AES_256_CCM_8", 0xc0a3)
        // @JvmField val TLS_PSK_WITH_AES_128_CCM = init("TLS_PSK_WITH_AES_128_CCM", 0xc0a4)
        // @JvmField val TLS_PSK_WITH_AES_256_CCM = init("TLS_PSK_WITH_AES_256_CCM", 0xc0a5)
        // @JvmField val TLS_DHE_PSK_WITH_AES_128_CCM = init("TLS_DHE_PSK_WITH_AES_128_CCM", 0xc0a6)
        // @JvmField val TLS_DHE_PSK_WITH_AES_256_CCM = init("TLS_DHE_PSK_WITH_AES_256_CCM", 0xc0a7)
        // @JvmField val TLS_PSK_WITH_AES_128_CCM_8 = init("TLS_PSK_WITH_AES_128_CCM_8", 0xc0a8)
        // @JvmField val TLS_PSK_WITH_AES_256_CCM_8 = init("TLS_PSK_WITH_AES_256_CCM_8", 0xc0a9)
        // @JvmField val TLS_PSK_DHE_WITH_AES_128_CCM_8 = init("TLS_PSK_DHE_WITH_AES_128_CCM_8", 0xc0aa)
        // @JvmField val TLS_PSK_DHE_WITH_AES_256_CCM_8 = init("TLS_PSK_DHE_WITH_AES_256_CCM_8", 0xc0ab)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_AES_128_CCM = init("TLS_ECDHE_ECDSA_WITH_AES_128_CCM", 0xc0ac)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_AES_256_CCM = init("TLS_ECDHE_ECDSA_WITH_AES_256_CCM", 0xc0ad)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8 = init("TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8", 0xc0ae)
        // @JvmField val TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8 = init("TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8", 0xc0af)
        @JvmField
        val TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 = init("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", 0xcca8)
        @JvmField
        val TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 =
            init("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", 0xcca9)
        @JvmField
        val TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256 = init("TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256", 0xccaa)
        // @JvmField val TLS_PSK_WITH_CHACHA20_POLY1305_SHA256 = init("TLS_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccab)
        @JvmField
        val TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256 = init("TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccac)
        // @JvmField val TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256 = init("TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccad)
        // @JvmField val TLS_RSA_PSK_WITH_CHACHA20_POLY1305_SHA256 = init("TLS_RSA_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccae)

        // TLS 1.3 https://tools.ietf.org/html/rfc8446
        @JvmField
        val TLS_AES_128_GCM_SHA256 = init("TLS_AES_128_GCM_SHA256", 0x1301)
        @JvmField
        val TLS_AES_256_GCM_SHA384 = init("TLS_AES_256_GCM_SHA384", 0x1302)
        @JvmField
        val TLS_CHACHA20_POLY1305_SHA256 = init("TLS_CHACHA20_POLY1305_SHA256", 0x1303)
        @JvmField
        val TLS_AES_128_CCM_SHA256 = init("TLS_AES_128_CCM_SHA256", 0x1304)
        @JvmField
        val TLS_AES_128_CCM_8_SHA256 = init("TLS_AES_128_CCM_8_SHA256", 0x1305)

        /**
         * @param javaName the name used by Java APIs for this cipher suite. Different than the IANA
         *     name for older cipher suites because the prefix is `SSL_` instead of `TLS_`.
         */
        @JvmStatic
        @Synchronized
        fun forJavaName(javaName: String): CipherSuite {
            var result: CipherSuite? = INSTANCES[javaName]
            if (result == null) {
                result = INSTANCES[secondaryName(javaName)]

                if (result == null) {
                    result = CipherSuite(javaName)
                }

                // Add the new cipher suite, or a confirmed alias.
                INSTANCES[javaName] = result
            }
            return result
        }

        private fun secondaryName(javaName: String): String {
            return when {
                javaName.startsWith("TLS_") -> "SSL_" + javaName.substring(4)
                javaName.startsWith("SSL_") -> "TLS_" + javaName.substring(4)
                else -> javaName
            }
        }

        /**
         * @param javaName the name used by Java APIs for this cipher suite. Different than the IANA
         *     name for older cipher suites because the prefix is `SSL_` instead of `TLS_`.
         * @param value the integer identifier for this cipher suite. (Documentation only.)
         */
        private fun init(javaName: String, value: Int): CipherSuite {
            val suite = CipherSuite(javaName)
            INSTANCES[javaName] = suite
            return suite
        }
    }
}

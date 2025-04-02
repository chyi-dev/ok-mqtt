package com.ok.mqtt

import java.security.KeyStore
import java.security.cert.Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * SSL 工具类
 * @author Leyi
 * @date 2025/4/1 11:11
 */
object SSLUtils {
    fun createSocketFactory(
        keyStore: KeyStore,
        clientCertAlias: String,
        keyPassword: String? = null,
        caCert: Certificate? = null
    ): SSLSocketFactory {
        // 1. 初始化 KeyManagerFactory
        val kmf = KeyManagerFactory.getInstance("X509").apply {
            init(keyStore, keyPassword?.toCharArray())
        }

        // 2. 初始化 TrustManagerFactory
        val tmf = if (caCert != null) {
            val trustStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("ca", caCert)
            }
            TrustManagerFactory.getInstance("X509").apply {
                init(trustStore)
            }
        } else {
            // 使用系统默认信任库
            TrustManagerFactory.getDefaultAlgorithm().let {
                TrustManagerFactory.getInstance(it).apply {
                    init(null as KeyStore?)
                }
            }
        }

        // 3. 创建 SSLContext
        val sslContext = SSLContext.getInstance("TLSv1.2").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }

        return sslContext.socketFactory
    }
}
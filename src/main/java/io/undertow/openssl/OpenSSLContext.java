/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.undertow.openssl;


import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.undertow.openssl.OpenSSLLogger.ROOT_LOGGER;

public class OpenSSLContext {

    private static final String defaultProtocol = "TLS";

    private final SSLHostConfig sslHostConfig;
    private final SSLHostConfigCertificate certificate;
    private OpenSSLServerSessionContext sessionContext;

    private List<String> ciphers = new ArrayList<>();

    public List<String> getCiphers() {
        return ciphers;
    }

    private String enabledProtocol;

    public String getEnabledProtocol() {
        return enabledProtocol;
    }

    public void setEnabledProtocol(String protocol) {
        enabledProtocol = (protocol == null) ? defaultProtocol : protocol;
    }

    protected final long ctx;

    @SuppressWarnings("unused")
    private volatile int aprPoolDestroyed;
    private static final AtomicIntegerFieldUpdater<OpenSSLContext> DESTROY_UPDATER
            = AtomicIntegerFieldUpdater.newUpdater(OpenSSLContext.class, "aprPoolDestroyed");
    static final CertificateFactory X509_CERT_FACTORY;
    private boolean initialized = false;

    static {
        try {
            X509_CERT_FACTORY = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException(e);
        }
    }

    public OpenSSLContext(SSLHostConfig sslHostConfig, SSLHostConfigCertificate certificate)
            throws SSLException {
        this.sslHostConfig = sslHostConfig;
        this.certificate = certificate;
        boolean success = false;
        try {
            if (SSLHostConfig.adjustRelativePath(certificate.getCertificateFile()) == null) {
                // This is required
                throw ROOT_LOGGER.certificateRequired();
            }

            // SSL protocol
            int value = SSL.SSL_PROTOCOL_NONE;
            if (sslHostConfig.getProtocols().size() == 0) {
                value = SSL.SSL_PROTOCOL_ALL;
            } else {
                for (String protocol : sslHostConfig.getProtocols()) {
                    if (SSL.SSL_PROTO_SSLv2Hello.equalsIgnoreCase(protocol)) {
                        // NO-OP. OpenSSL always supports SSLv2Hello
                    } else if (SSL.SSL_PROTO_SSLv2.equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_SSLV2;
                    } else if (SSL.SSL_PROTO_SSLv3.equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_SSLV3;
                    } else if (SSL.SSL_PROTO_TLSv1.equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_TLSV1;
                    } else if (SSL.SSL_PROTO_TLSv1_1.equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_TLSV1_1;
                    } else if (SSL.SSL_PROTO_TLSv1_2.equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_TLSV1_2;
                    } else if (SSL.SSL_PROTO_ALL.equalsIgnoreCase(protocol)) {
                        value |= SSL.SSL_PROTOCOL_ALL;
                    } else {
                        // Protocol not recognized, fail to start as it is safer than
                        // continuing with the default which might enable more than the
                        // is required
                        throw ROOT_LOGGER.invalidSSLProtocol(protocol);
                    }
                }
            }

            // Create SSL Context
            try {
                ctx = SSLContext.make(value, SSL.SSL_MODE_SERVER);
            } catch (Exception e) {
                // If the sslEngine is disabled on the AprLifecycleListener
                // there will be an Exception here but there is no way to check
                // the AprLifecycleListener settings from here
                throw ROOT_LOGGER.failedToMakeSSLContext(e);
            }
            success = true;
        } catch(Exception e) {
            throw ROOT_LOGGER.failedToInitialiseSSLContext(e);
        }
    }

    /**
     * Setup the SSL_CTX
     *
     * @param kms Must contain a KeyManager of the type
     * {@code OpenSSLKeyManager}
     * @param tms
     * @param sr Is not used for this implementation.
     */
    public synchronized void init(KeyManager[] kms, TrustManager[] tms, SecureRandom sr) {
        if (initialized) {
            ROOT_LOGGER.initCalledMultipleTimes();
            return;
        }
        try {
            boolean legacyRenegSupported = false;
            try {
                legacyRenegSupported = SSL.hasOp(SSL.SSL_OP_ALLOW_UNSAFE_LEGACY_RENEGOTIATION);
                if (legacyRenegSupported)
                    if (sslHostConfig.getInsecureRenegotiation()) {
                        SSLContext.setOptions(ctx, SSL.SSL_OP_ALLOW_UNSAFE_LEGACY_RENEGOTIATION);
                    } else {
                        SSLContext.clearOptions(ctx, SSL.SSL_OP_ALLOW_UNSAFE_LEGACY_RENEGOTIATION);
                    }
            } catch (UnsatisfiedLinkError e) {
                // Ignore
            }
            if (!legacyRenegSupported) {
                // OpenSSL does not support unsafe legacy renegotiation.
                ROOT_LOGGER.debug("Your version of OpenSSL does not support legacy renegotiation");
            }
            // Use server's preference order for ciphers (rather than
            // client's)
            boolean orderCiphersSupported = false;
            try {
                orderCiphersSupported = SSL.hasOp(SSL.SSL_OP_CIPHER_SERVER_PREFERENCE);
                if (orderCiphersSupported) {
                    if (sslHostConfig.getHonorCipherOrder()) {
                        SSLContext.setOptions(ctx, SSL.SSL_OP_CIPHER_SERVER_PREFERENCE);
                    } else {
                        SSLContext.clearOptions(ctx, SSL.SSL_OP_CIPHER_SERVER_PREFERENCE);
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                // Ignore
            }
            if (!orderCiphersSupported) {
                // OpenSSL does not support ciphers ordering.
                ROOT_LOGGER.noHonorCipherOrder();
            }

            // Disable compression if requested
            boolean disableCompressionSupported = false;
            try {
                disableCompressionSupported = SSL.hasOp(SSL.SSL_OP_NO_COMPRESSION);
                if (disableCompressionSupported) {
                    if (sslHostConfig.getDisableCompression()) {
                        SSLContext.setOptions(ctx, SSL.SSL_OP_NO_COMPRESSION);
                    } else {
                        SSLContext.clearOptions(ctx, SSL.SSL_OP_NO_COMPRESSION);
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                // Ignore
            }
            if (!disableCompressionSupported) {
                ROOT_LOGGER.noDisableCompression();
            }

            // Disable TLS Session Tickets (RFC4507) to protect perfect forward secrecy
            boolean disableSessionTicketsSupported = false;
            try {
                disableSessionTicketsSupported = SSL.hasOp(SSL.SSL_OP_NO_TICKET);
                if (disableSessionTicketsSupported) {
                    if (sslHostConfig.getDisableSessionTickets()) {
                        SSLContext.setOptions(ctx, SSL.SSL_OP_NO_TICKET);
                    } else {
                        SSLContext.clearOptions(ctx, SSL.SSL_OP_NO_TICKET);
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                // Ignore
            }
            if (!disableSessionTicketsSupported) {
                // OpenSSL is too old to support TLS Session Tickets.
                ROOT_LOGGER.noDisableSessionTickets();
            }

            // Set session cache size, if specified
            if (sslHostConfig.getSessionCacheSize() > 0) {
                SSLContext.setSessionCacheSize(ctx, sslHostConfig.getSessionCacheSize());
            } else {
                // Get the default session cache size using SSLContext.setSessionCacheSize()
                long sessionCacheSize = SSLContext.setSessionCacheSize(ctx, 20480);
                // Revert the session cache size to the default value.
                SSLContext.setSessionCacheSize(ctx, sessionCacheSize);
            }

            // Set session timeout, if specified
            if (sslHostConfig.getSessionTimeout() > 0) {
                SSLContext.setSessionCacheTimeout(ctx, sslHostConfig.getSessionTimeout());
            } else {
                // Get the default session timeout using SSLContext.setSessionCacheTimeout()
                long sessionTimeout = SSLContext.setSessionCacheTimeout(ctx, 300);
                // Revert the session timeout to the default value.
                SSLContext.setSessionCacheTimeout(ctx, sessionTimeout);
            }

            // List the ciphers that the client is permitted to negotiate
            String ciphers = sslHostConfig.getCiphers();
            if (!("ALL".equals(ciphers)) && ciphers.indexOf(":") == -1) {
                StringTokenizer tok = new StringTokenizer(ciphers, ",");
                this.ciphers = new ArrayList<>();
                while (tok.hasMoreTokens()) {
                    String token = tok.nextToken().trim();
                    if (!"".equals(token)) {
                        this.ciphers.add(token);
                    }
                }
                ciphers = CipherSuiteConverter.toOpenSsl(ciphers);
            } else {
                this.ciphers = OpenSSLCipherConfigurationParser.parseExpression(ciphers);
            }
            SSLContext.setCipherSuite(ctx, ciphers);
            // Load Server key and certificate
            SSLContext.setCertificate(ctx,
                    SSLHostConfig.adjustRelativePath(certificate.getCertificateFile()),
                    SSLHostConfig.adjustRelativePath(certificate.getCertificateKeyFile()),
                    certificate.getCertificateKeyPassword(), SSL.SSL_AIDX_RSA);
            // Support Client Certificates
            SSLContext.setCACertificate(ctx,
                    SSLHostConfig.adjustRelativePath(sslHostConfig.getCaCertificateFile()),
                    SSLHostConfig.adjustRelativePath(sslHostConfig.getCaCertificatePath()));
            // Set revocation
            SSLContext.setCARevocation(ctx,
                    SSLHostConfig.adjustRelativePath(
                            sslHostConfig.getCertificateRevocationListFile()),
                    SSLHostConfig.adjustRelativePath(
                            sslHostConfig.getCertificateRevocationListPath()));
            // Client certificate verification
            int value = 0;
            switch (sslHostConfig.getCertificateVerification()) {
            case NONE:
                value = SSL.SSL_CVERIFY_NONE;
                break;
            case OPTIONAL:
                value = SSL.SSL_CVERIFY_OPTIONAL;
                break;
            case OPTIONAL_NO_CA:
                value = SSL.SSL_CVERIFY_OPTIONAL_NO_CA;
                break;
            case REQUIRED:
                value = SSL.SSL_CVERIFY_REQUIRE;
                break;
            }
            SSLContext.setVerify(ctx, value, sslHostConfig.getCertificateVerificationDepth());

            if (tms != null) {
                final X509TrustManager manager = chooseTrustManager(tms);
                SSLContext.setCertVerifyCallback(ctx, new CertificateVerifier() {
                    @Override
                    public boolean verify(long ssl, byte[][] chain, String auth) {
                        X509Certificate[] peerCerts = certificates(chain);
                        try {
                            manager.checkClientTrusted(peerCerts, auth);
                            return true;
                        } catch (Exception e) {
                            ROOT_LOGGER.debug("Certificate verification failed", e);
                        }
                        return false;
                    }
                });
            }
            String[] protos = new OpenSSLProtocols(enabledProtocol).getProtocols();
            SSLContext.setNpnProtos(ctx, protos, SSL.SSL_SELECTOR_FAILURE_CHOOSE_MY_LAST_PROTOCOL);

            sessionContext = new OpenSSLServerSessionContext(ctx);
            initialized = true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static OpenSSLKeyManager chooseKeyManager(KeyManager[] managers) throws Exception {
        for (KeyManager manager : managers) {
            if (manager instanceof OpenSSLKeyManager) {
                return (OpenSSLKeyManager) manager;
            }
        }
        throw ROOT_LOGGER.keyManagerMissing();
    }

    static X509TrustManager chooseTrustManager(TrustManager[] managers) {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) {
                return (X509TrustManager) m;
            }
        }
        throw ROOT_LOGGER.trustManagerMissing();
    }

    private static X509Certificate[] certificates(byte[][] chain) {
        X509Certificate[] peerCerts = new X509Certificate[chain.length];
        for (int i = 0; i < peerCerts.length; i++) {
            peerCerts[i] = new OpenSslX509Certificate(chain[i]);
        }
        return peerCerts;
    }

    public SSLSessionContext getServerSessionContext() {
        return sessionContext;
    }

    public SSLEngine createSSLEngine() {
        return new OpenSSLEngine(ctx, defaultProtocol, false, sessionContext);
    }

    public SSLServerSocketFactory getServerSocketFactory() {
        throw new UnsupportedOperationException();
    }

    public SSLParameters getSupportedSSLParameters() {
        throw new UnsupportedOperationException();
    }

    /**
     * Generates a key specification for an (encrypted) private key.
     *
     * @param password characters, if {@code null} or empty an unencrypted key
     * is assumed
     * @param key bytes of the DER encoded private key
     *
     * @return a key specification
     *
     * @throws IOException if parsing {@code key} fails
     * @throws NoSuchAlgorithmException if the algorithm used to encrypt
     * {@code key} is unknown
     * @throws NoSuchPaddingException if the padding scheme specified in the
     * decryption algorithm is unknown
     * @throws InvalidKeySpecException if the decryption key based on
     * {@code password} cannot be generated
     * @throws InvalidKeyException if the decryption key based on
     * {@code password} cannot be used to decrypt {@code key}
     * @throws InvalidAlgorithmParameterException if decryption algorithm
     * parameters are somehow faulty
     */
    protected static PKCS8EncodedKeySpec generateKeySpec(char[] password, byte[] key)
            throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException,
            InvalidKeyException, InvalidAlgorithmParameterException {

        if (password == null || password.length == 0) {
            return new PKCS8EncodedKeySpec(key);
        }

        EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(key);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
        PBEKeySpec pbeKeySpec = new PBEKeySpec(password);
        SecretKey pbeKey = keyFactory.generateSecret(pbeKeySpec);

        Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
        cipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedPrivateKeyInfo.getAlgParameters());

        return encryptedPrivateKeyInfo.getKeySpec(cipher);
    }

    @Override
    protected final void finalize() throws Throwable {
        super.finalize();
        synchronized (OpenSSLContext.class) {
            if (ctx != 0) {
                SSLContext.free(ctx);
            }
        }
    }
}
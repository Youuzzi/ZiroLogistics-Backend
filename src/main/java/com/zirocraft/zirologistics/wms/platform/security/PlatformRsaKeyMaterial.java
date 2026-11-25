package com.zirocraft.zirologistics.wms.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Independent platform signing material; never reads tenant WMS JWT keys. */
record PlatformRsaKeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    static PlatformRsaKeyMaterial fromPem(String publicPem, String privatePem) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(decode(publicPem, "PUBLIC KEY")));
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(decode(privatePem, "PRIVATE KEY")));
            if (publicKey.getModulus().bitLength() < 2048 || privateKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("Platform JWT RSA keys must be at least 2048 bits");
            }
            byte[] challenge = "zirologistics-platform-jwt-key-pair-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(challenge);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("Platform JWT public and private keys do not match");
            }
            return new PlatformRsaKeyMaterial(publicKey, privateKey);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Platform JWT RSA key material is invalid", exception);
        }
    }

    private static byte[] decode(String pem, String label) {
        if (pem == null || pem.isBlank()) throw new IllegalArgumentException("Platform JWT " + label + " is required");
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        String normalized = pem.strip();
        if (!normalized.startsWith(begin) || !normalized.endsWith(end)) {
            throw new IllegalArgumentException("Platform JWT key must use PEM " + label + " format");
        }
        String body = normalized.substring(begin.length(), normalized.length() - end.length()).replaceAll("\\s", "");
        if (body.isBlank()) throw new IllegalArgumentException("Platform JWT " + label + " is empty");
        return Base64.getDecoder().decode(body);
    }
}

package com.zirocraft.zirologistics.wms.security.config;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

record WmsRsaKeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {

    static WmsRsaKeyMaterial fromPem(String publicKeyPem, String privateKeyPem) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(decodePem(publicKeyPem, "PUBLIC KEY")));
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privateKeyPem, "PRIVATE KEY")));

            if (publicKey.getModulus().bitLength() < 2048
                    || privateKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("WMS JWT RSA keys must be at least 2048 bits");
            }

            verifyKeyPair(publicKey, privateKey);
            return new WmsRsaKeyMaterial(publicKey, privateKey);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("WMS JWT RSA key material is invalid", exception);
        }
    }

    private static byte[] decodePem(String pem, String label) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("WMS JWT " + label + " is required");
        }
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        String normalized = pem.strip();
        if (!normalized.startsWith(begin) || !normalized.endsWith(end)) {
            throw new IllegalArgumentException("WMS JWT key must use PEM " + label + " format");
        }
        String body = normalized.substring(begin.length(), normalized.length() - end.length())
                .replaceAll("\\s", "");
        if (body.isBlank()) {
            throw new IllegalArgumentException("WMS JWT " + label + " is empty");
        }
        return Base64.getDecoder().decode(body);
    }

    private static void verifyKeyPair(RSAPublicKey publicKey, RSAPrivateKey privateKey) throws Exception {
        byte[] challenge = "zirologistics-wms-jwt-key-pair-check".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException("WMS JWT public and private keys do not match");
        }
    }
}

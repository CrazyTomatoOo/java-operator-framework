package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Utilities for reading PEM-encoded certificates and private keys.
 */
public final class PemCertificateUtils {
    private static final String RSA_ALGORITHM = "RSA";

    private PemCertificateUtils() {
    }

    /**
     * Reads X.509 certificates from a PEM or DER file.
     *
     * @param path the certificate file
     * @return the certificates in the file
     * @throws IOException if the file cannot be read or does not contain valid certificates
     */
    public static List<X509Certificate> readCertificates(Path path) throws IOException {
        return readCertificates(Files.readAllBytes(path));
    }

    /**
     * Reads X.509 certificates from PEM or DER bytes.
     *
     * @param bytes the certificate bytes
     * @return the certificates in the bytes
     * @throws IOException if the bytes do not contain valid certificates
     */
    public static List<X509Certificate> readCertificates(byte[] bytes) throws IOException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                Collection<? extends Certificate> certificates = factory.generateCertificates(input);
                List<X509Certificate> result = new ArrayList<>();
                for (Certificate certificate : certificates) {
                    result.add((X509Certificate) certificate);
                }
                return result;
            }
        } catch (CertificateException | ClassCastException exception) {
            throw new IOException("Failed to read X.509 certificates", exception);
        }
    }

    /**
     * Reads an RSA private key from a PEM file.
     *
     * @param path the private key file
     * @return the private key
     * @throws IOException if the file cannot be read or contains an unsupported or invalid key
     */
    public static PrivateKey readPrivateKey(Path path) throws IOException {
        return readPrivateKey(Files.readAllBytes(path));
    }

    /**
     * Reads an RSA private key from PKCS#8 or PKCS#1 PEM bytes.
     *
     * @param bytes the private key bytes
     * @return the private key
     * @throws IOException if the key is encrypted or invalid
     */
    public static PrivateKey readPrivateKey(byte[] bytes) throws IOException {
        String pem = new String(bytes, StandardCharsets.US_ASCII);
        rejectEncryptedPrivateKey(pem);
        try {
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                return readPkcs1PrivateKey(pem);
            }
            byte[] der = decodePem(pem, "PRIVATE KEY");
            return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to read RSA private key", exception);
        }
    }

    private static PrivateKey readPkcs1PrivateKey(String pem) throws IOException, GeneralSecurityException {
        DerReader reader = new DerReader(decodePem(pem, "RSA PRIVATE KEY"));
        reader.readSequence();
        reader.readInteger();
        BigInteger modulus = reader.readInteger();
        BigInteger publicExponent = reader.readInteger();
        BigInteger privateExponent = reader.readInteger();
        BigInteger primeP = reader.readInteger();
        BigInteger primeQ = reader.readInteger();
        BigInteger primeExponentP = reader.readInteger();
        BigInteger primeExponentQ = reader.readInteger();
        BigInteger crtCoefficient = reader.readInteger();
        RSAPrivateCrtKeySpec keySpec = new RSAPrivateCrtKeySpec(modulus, publicExponent, privateExponent, primeP,
                primeQ, primeExponentP, primeExponentQ, crtCoefficient);
        return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(keySpec);
    }

    private static void rejectEncryptedPrivateKey(String pem) throws IOException {
        if (pem.contains("ENCRYPTED") || pem.contains("Proc-Type") || pem.contains("DEK-Info")) {
            throw new IOException("Encrypted private keys are not supported");
        }
    }

    private static byte[] decodePem(String pem, String label) throws IOException {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int beginIndex = pem.indexOf(begin);
        int endIndex = pem.indexOf(end, beginIndex + begin.length());
        if (beginIndex < 0 || endIndex < 0) {
            throw new IOException("PEM block not found: " + label);
        }
        String base64 = pem.substring(beginIndex + begin.length(), endIndex).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid base64 in PEM block: " + label, exception);
        }
    }

    private static final class DerReader {
        private final ByteArrayInputStream input;

        private DerReader(byte[] bytes) {
            this.input = new ByteArrayInputStream(bytes);
        }

        private void readSequence() throws IOException {
            if (input.read() != 0x30) {
                throw new IOException("Expected ASN.1 sequence");
            }
            readLength();
        }

        private BigInteger readInteger() throws IOException {
            if (input.read() != 0x02) {
                throw new IOException("Expected ASN.1 integer");
            }
            byte[] value = input.readNBytes(readLength());
            return new BigInteger(value);
        }

        private int readLength() throws IOException {
            int first = input.read();
            if (first < 0) {
                throw new IOException("Unexpected end of DER data");
            }
            if ((first & 0x80) == 0) {
                return first;
            }
            int count = first & 0x7F;
            if (count == 0 || count > 4) {
                throw new IOException("Unsupported DER length");
            }
            int length = 0;
            for (int i = 0; i < count; i++) {
                int next = input.read();
                if (next < 0) {
                    throw new IOException("Unexpected end of DER length");
                }
                length = (length << 8) | next;
            }
            return length;
        }
    }
}

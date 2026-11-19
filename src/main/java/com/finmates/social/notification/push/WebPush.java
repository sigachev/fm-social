package com.finmates.social.notification.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push without a library: payload encryption (RFC 8291, aes128gcm per RFC 8188) and VAPID
 * (RFC 8292, ES256 JWT). Pure JDK crypto on P-256.
 */
public final class WebPush {

    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    static final ECParameterSpec P256 = p256();

    private WebPush() {}

    public static byte[] b64(String s) {
        return B64D.decode(s.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String b64(byte[] b) {
        return B64U.encodeToString(b);
    }

    // ── keys ─────────────────────────────────────────────────────────────────

    public static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) throw new InvalidKeyException("expected a 65-byte uncompressed P-256 point");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    public static PrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    public static byte[] uncompressed(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 4;
        put32(k.getW().getAffineX(), out, 1);
        put32(k.getW().getAffineY(), out, 33);
        return out;
    }

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    // ── encryption (RFC 8291) ────────────────────────────────────────────────

    /** Encrypts {@code payload} for a subscription's {@code p256dh} and {@code auth}. Returns the request body. */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        return encrypt(payload, uaPublic, authSecret, newKeyPair(), random(16));
    }

    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt) throws GeneralSecurityException {
        byte[] asPublic = uncompressed((ECPublicKey) as.getPublic());
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(publicKey(uaPublic), true);
        byte[] shared = ka.generateSecret();

        byte[] prkKey = hmac(authSecret, shared);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1});
        byte[] ikm = Arrays.copyOf(hmac(prkKey, keyInfo), 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(concat(payload, new byte[]{2}));   // 0x02: last (only) record

        return concat(salt, ByteBuffer.allocate(4).putInt(4096).array(), new byte[]{65}, asPublic, ct);
    }

    // ── VAPID (RFC 8292) ─────────────────────────────────────────────────────

    /** The Authorization header value for a push service endpoint. */
    public static String vapid(String endpoint, String subject, PrivateKey key, byte[] publicKey, long nowSeconds) throws GeneralSecurityException {
        URI u = URI.create(endpoint);
        String aud = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + aud + "\",\"exp\":" + (nowSeconds + 12 * 3600) + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String jwt = header + "." + claims + "." + b64(derToRaw(s.sign()));
        return "vapid t=" + jwt + ", k=" + b64(publicKey);
    }

    /** DER ECDSA signature -> 64-byte r||s (JOSE). */
    static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7f;
        int rLen = der[i + 1];
        byte[] r = Arrays.copyOfRange(der, i + 2, i + 2 + rLen);
        i = i + 2 + rLen;
        int sLen = der[i + 1];
        byte[] s = Arrays.copyOfRange(der, i + 2, i + 2 + sLen);
        byte[] out = new byte[64];
        put32(new BigInteger(1, r), out, 0);
        put32(new BigInteger(1, s), out, 32);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static void put32(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, off + 32 - len, len);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

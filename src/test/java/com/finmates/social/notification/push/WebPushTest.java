package com.finmates.social.notification.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WebPushTest {

    /** Decrypts as a browser would (RFC 8291), proving the payload round-trips. */
    @Test
    void encryptsSoTheBrowserCanDecrypt() throws Exception {
        KeyPair ua = WebPush.newKeyPair();
        byte[] uaPublic = WebPush.uncompressed((ECPublicKey) ua.getPublic());
        byte[] auth = WebPush.random(16);
        byte[] msg = "{\"title\":\"ETH is above $3,400\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPush.encrypt(msg, uaPublic, auth);

        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        assertEquals(4096, ByteBuffer.wrap(body, 16, 4).getInt());
        assertEquals(65, body[20]);
        byte[] asPublic = Arrays.copyOfRange(body, 21, 86);
        byte[] ct = Arrays.copyOfRange(body, 86, body.length);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ua.getPrivate());
        ka.doPhase(WebPush.publicKey(asPublic), true);
        byte[] shared = ka.generateSecret();
        byte[] prkKey = WebPush.hmac(auth, shared);
        byte[] ikm = Arrays.copyOf(WebPush.hmac(prkKey, WebPush.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1})), 32);
        byte[] prk = WebPush.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPush.hmac(prk, WebPush.concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(WebPush.hmac(prk, WebPush.concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = c.doFinal(ct);

        assertEquals(2, plain[plain.length - 1]);
        assertArrayEquals(msg, Arrays.copyOf(plain, plain.length - 1));
    }

    @Test
    void vapidTokenVerifiesWithThePublicKey() throws Exception {
        KeyPair k = WebPush.newKeyPair();
        byte[] pub = WebPush.uncompressed((ECPublicKey) k.getPublic());
        String h = WebPush.vapid("https://fcm.googleapis.com/fcm/send/abc", "mailto:admin@finmates.com", k.getPrivate(), pub, 1_800_000_000L);
        assertTrue(h.startsWith("vapid t="));
        String jwt = h.substring(8, h.indexOf(','));
        String[] parts = jwt.split("\\.");
        String claims = new String(WebPush.b64(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\""), claims);
        byte[] raw = WebPush.b64(parts[2]);
        assertEquals(64, raw.length);
        // raw r||s -> DER, then verify
        byte[] der = toDer(raw);
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(k.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(v.verify(der));
        assertTrue(h.endsWith("k=" + WebPush.b64(pub)));
    }

    @Test
    void keysRoundTripFromBase64url() throws Exception {
        KeyPair k = WebPush.newKeyPair();
        byte[] pub = WebPush.uncompressed((ECPublicKey) k.getPublic());
        assertEquals(k.getPublic(), WebPush.publicKey(WebPush.b64(WebPush.b64(pub))));
    }

    private static byte[] toDer(byte[] raw) {
        byte[] r = int2(Arrays.copyOfRange(raw, 0, 32)), s = int2(Arrays.copyOfRange(raw, 32, 64));
        byte[] out = new byte[2 + 2 + r.length + 2 + s.length];
        out[0] = 0x30; out[1] = (byte) (out.length - 2);
        out[2] = 0x02; out[3] = (byte) r.length; System.arraycopy(r, 0, out, 4, r.length);
        int i = 4 + r.length;
        out[i] = 0x02; out[i + 1] = (byte) s.length; System.arraycopy(s, 0, out, i + 2, s.length);
        return out;
    }

    private static byte[] int2(byte[] v) {
        int i = 0;
        while (i < v.length - 1 && v[i] == 0) i++;
        byte[] t = Arrays.copyOfRange(v, i, v.length);
        if ((t[0] & 0x80) != 0) { byte[] p = new byte[t.length + 1]; System.arraycopy(t, 0, p, 1, t.length); return p; }
        return t;
    }
}

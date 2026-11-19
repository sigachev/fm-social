import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Makes a VAPID key pair for browser notifications. Run once:
 *
 *   java scripts/VapidKeys.java
 *
 * Prints WEBPUSH_PUBLIC_KEY and WEBPUSH_PRIVATE_KEY (base64url). Store both in the fm-social
 * Kubernetes secret "fm-webpush" (see k8s/deployment.yaml). The private key never goes in git.
 * Changing the pair later makes every browser subscribe again.
 */
public class VapidKeys {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        var kp = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] p = new byte[65];
        p[0] = 4;
        copy(pub.getW().getAffineX().toByteArray(), p, 1);
        copy(pub.getW().getAffineY().toByteArray(), p, 33);
        byte[] d = new byte[32];
        copy(((ECPrivateKey) kp.getPrivate()).getS().toByteArray(), d, 0);
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        System.out.println("WEBPUSH_PUBLIC_KEY=" + e.encodeToString(p));
        System.out.println("WEBPUSH_PRIVATE_KEY=" + e.encodeToString(d));
        System.out.println();
        System.out.println("kubectl -n dev create secret generic fm-webpush \\");
        System.out.println("  --from-literal=public-key=<WEBPUSH_PUBLIC_KEY> --from-literal=private-key=<WEBPUSH_PRIVATE_KEY>");
    }

    private static void copy(byte[] v, byte[] out, int off) {
        int len = Math.min(v.length, 32);
        System.arraycopy(v, v.length - len, out, off + 32 - len, len);
    }
}

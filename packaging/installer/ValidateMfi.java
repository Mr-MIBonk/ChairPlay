import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

/** Source-launched with JDK 17 during packaging only. Mirrors LocalMfiAuthenticationClient's input checks;
 * proves key consistency, not Apple trust. Never prints input bytes or parser exception details. */
class ValidateMfi {
    private static byte[] read(Path directory, String name) throws Exception {
        try (var input = Files.newInputStream(directory.resolve(name))) {
            var bytes = input.readNBytes(16385);
            if (bytes.length == 0 || bytes.length > 16384) throw new IllegalArgumentException();
            return bytes;
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException();
            var directory = Path.of(args[0]);
            var encoded = read(directory, "identity.pk8");
            java.security.PrivateKey key;
            try {
                key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(
                new java.io.ByteArrayInputStream(read(directory, "certificate.p7b")));
            if (certificates.size() != 1) throw new IllegalArgumentException();
            var publicKey = (ECPublicKey) certificates.iterator().next().getPublicKey();
            if (!publicKey.getParams().getOrder().toString(16).equals(
                    "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")) {
                throw new IllegalArgumentException();
            }
            var challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            var signer = Signature.getInstance("NONEwithECDSA");
            signer.initSign(key);
            signer.update(challenge);
            var signature = signer.sign();
            signer.initVerify(publicKey);
            signer.update(challenge);
            if (!signer.verify(signature)) throw new IllegalArgumentException();
            System.out.println("MFi packaging input validated (key consistency only).");
        } catch (Exception error) {
            System.err.println("Invalid MFi input: expected a matching P-256 PKCS#8 key and one X.509 certificate, each 1–16384 bytes.");
            System.exit(1);
        }
    }
}

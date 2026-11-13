package net.ethug.tscatemfdh.core;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Passcode-based encryption. Everything stored on Drive is AES-256-GCM encrypted, so the Drive link alone is useless. */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITER = 210_000;
    private static final int CHUNK = 1 << 20;

    private Crypto() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static SecretKey deriveKey(String passcode, byte[] salt) throws Exception {
        return derive(passcode, salt, "");
    }

    /** Key-wrapping KDF: constant prefix so an empty passcode ("no passcode") is still valid. */
    public static SecretKey deriveWrapKey(String passcode, byte[] salt) throws Exception {
        return derive(passcode, salt, "tscatemfdh-wrap:");
    }

    private static SecretKey derive(String passcode, byte[] salt, String prefix) throws Exception {
        PBEKeySpec spec = new PBEKeySpec((prefix + passcode).toCharArray(), salt, ITER, 256);
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    /** Small blobs (lease, meta check). Output = iv || ciphertext. */
    public static byte[] seal(SecretKey key, byte[] plain) throws Exception {
        byte[] iv = randomBytes(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    public static byte[] open(SecretKey key, byte[] sealed) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    /** Large files: split in 1 MiB chunks, each independently sealed with a counter-derived IV (prefix random per file). */
    public static void encryptFile(SecretKey key, Path in, Path out) throws Exception {
        byte[] prefix = randomBytes(8);
        try (InputStream is = new BufferedInputStream(new FileInputStream(in.toFile()));
             DataOutputStream os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out.toFile())))) {
            os.write(prefix);
            byte[] buf = new byte[CHUNK];
            int counter = 0;
            int n;
            while ((n = is.readNBytes(buf, 0, CHUNK)) > 0) {
                Cipher c = chunkCipher(key, prefix, counter++, Cipher.ENCRYPT_MODE);
                byte[] ct = c.doFinal(buf, 0, n);
                os.writeInt(ct.length);
                os.write(ct);
            }
            os.writeInt(0); // end marker: truncation is detected
        }
    }

    public static void decryptFile(SecretKey key, Path in, Path out) throws Exception {
        try (DataInputStream is = new DataInputStream(new BufferedInputStream(new FileInputStream(in.toFile())));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out.toFile()))) {
            byte[] prefix = new byte[8];
            is.readFully(prefix);
            int counter = 0;
            while (true) {
                int len = is.readInt();
                if (len == 0) break;
                if (len < 0 || len > CHUNK + 64) throw new IOException("corrupt chunk");
                byte[] ct = new byte[len];
                is.readFully(ct);
                Cipher c = chunkCipher(key, prefix, counter++, Cipher.DECRYPT_MODE);
                os.write(c.doFinal(ct));
            }
        }
    }

    private static Cipher chunkCipher(SecretKey key, byte[] prefix, int counter, int mode) throws Exception {
        byte[] iv = new byte[12];
        System.arraycopy(prefix, 0, iv, 0, 8);
        iv[8] = (byte) (counter >>> 24);
        iv[9] = (byte) (counter >>> 16);
        iv[10] = (byte) (counter >>> 8);
        iv[11] = (byte) counter;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, iv));
        return c;
    }
}

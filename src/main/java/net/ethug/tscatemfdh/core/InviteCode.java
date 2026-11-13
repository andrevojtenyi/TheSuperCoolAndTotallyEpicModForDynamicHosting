package net.ethug.tscatemfdh.core;

import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The shareable "link". Holds a drive.file-scoped refresh token (only reaches files this mod created),
 * and the folder id. The passcode is NOT in here: it is shared separately and encrypts all world data.
 */
public record InviteCode(String clientId, String clientSecret, String refreshToken, String folderId, String name) {
    private static final String PREFIX = "TSC1.";
    private static final Gson GSON = new Gson();

    public String encode() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static InviteCode decode(String s) {
        try {
            s = s.trim();
            if (!s.startsWith(PREFIX)) throw new IllegalArgumentException("Not a shared-world invite");
            byte[] raw = Base64.getUrlDecoder().decode(s.substring(PREFIX.length()));
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                InviteCode c = GSON.fromJson(new String(gz.readAllBytes(), StandardCharsets.UTF_8), InviteCode.class);
                if (c == null || c.folderId == null || c.refreshToken == null) throw new IllegalArgumentException("Invite is incomplete");
                return c;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invite code is invalid", e);
        }
    }
}

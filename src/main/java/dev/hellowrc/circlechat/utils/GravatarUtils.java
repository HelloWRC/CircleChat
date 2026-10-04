package dev.hellowrc.circlechat.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class GravatarUtils {
    public static final int SmallAvatarSize = 64;
    public static final int DefaultAvatarSize = 128;
    public static final int LargeAvatarSize = 512;

    public static String getAvatarUrl(String email) {
        return getAvatarUrl(email, DefaultAvatarSize);
    }

    public static String getAvatarUrl(String email, Integer size) {
        String normalizedEmail = email.trim().toLowerCase();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    normalizedEmail.getBytes(StandardCharsets.UTF_8)
            );

            String hashString = HexFormat.of().formatHex(hash);

            return "https://gravatar.com/avatar/" + hashString + "?s=" + size.toString() + "&d=identicon";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not supported", e);
        }
    }
}
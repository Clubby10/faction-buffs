package com.lucasdunn.paradisebuffs;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class BuffItemAuthenticator {
    private static final String LEGACY_MARKER_ROOT = ChatColor.BLACK + "PB:";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String HIDDEN_MARKER_ROOT = hide(LEGACY_MARKER_ROOT);

    private final byte[] signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public BuffItemAuthenticator(byte[] signingKey) {
        this.signingKey = signingKey.clone();
    }

    public String createMarker(String buffId, String kind, Material material) {
        byte[] nonceBytes = new byte[12];
        secureRandom.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String marker = markerPrefix(buffId, kind) + nonce + ":"
                + sign(buffId, kind, material, nonce);
        return hide(marker);
    }

    public boolean hasValidMarker(ItemStack item, String buffId, String kind) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return false;
        }
        String prefix = markerPrefix(buffId, kind);
        for (String line : meta.getLore()) {
            for (String marker : markersIn(line)) {
                if (!marker.startsWith(prefix)) {
                    continue;
                }
                String payload = marker.substring(prefix.length());
                int separator = payload.indexOf(':');
                if (separator != 16 || payload.indexOf(':', separator + 1) != -1) {
                    continue;
                }
                String nonce = payload.substring(0, separator);
                String suppliedSignature = payload.substring(separator + 1);
                if (suppliedSignature.length() != 22) {
                    continue;
                }
                String expectedSignature = sign(buffId, kind, item.getType(), nonce);
                if (MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.US_ASCII),
                        suppliedSignature.getBytes(StandardCharsets.US_ASCII))) {
                    hideLegacyMarkers(item);
                    return true;
                }
            }
        }
        return false;
    }

    /** Appends a signature to visible lore without creating an extra tooltip line. */
    public void attachMarker(List<String> lore, String marker) {
        if (lore.isEmpty()) {
            lore.add(marker);
            return;
        }
        int last = lore.size() - 1;
        lore.set(last, lore.get(last) + marker);
    }

    /** Converts old visible black marker lines into hidden suffixes. */
    public List<String> normalizeLore(List<String> lore) {
        List<String> normalized = new ArrayList<String>();
        for (String line : lore) {
            if (line != null && line.startsWith(LEGACY_MARKER_ROOT)) {
                attachMarker(normalized, hide(line));
            } else {
                normalized.add(line);
            }
        }
        return normalized;
    }

    public void hideLegacyMarkers(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return;
        }
        List<String> original = meta.getLore();
        List<String> normalized = normalizeLore(original);
        if (!original.equals(normalized)) {
            meta.setLore(normalized);
            item.setItemMeta(meta);
        }
    }

    private String markerPrefix(String buffId, String kind) {
        return LEGACY_MARKER_ROOT + buffId + ":" + kind + ":";
    }

    private List<String> markersIn(String line) {
        List<String> markers = new ArrayList<String>();
        if (line == null) {
            return markers;
        }
        if (line.startsWith(LEGACY_MARKER_ROOT)) {
            markers.add(line);
        }
        int start = line.indexOf(HIDDEN_MARKER_ROOT);
        while (start >= 0) {
            int next = line.indexOf(HIDDEN_MARKER_ROOT,
                    start + HIDDEN_MARKER_ROOT.length());
            String decoded = reveal(line.substring(start,
                    next < 0 ? line.length() : next));
            if (decoded != null) {
                markers.add(decoded);
            }
            start = next;
        }
        return markers;
    }

    private static String hide(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder hidden = new StringBuilder(bytes.length * 4);
        for (byte valueByte : bytes) {
            int unsigned = valueByte & 0xFF;
            hidden.append(ChatColor.COLOR_CHAR).append(HEX[unsigned >>> 4]);
            hidden.append(ChatColor.COLOR_CHAR).append(HEX[unsigned & 0x0F]);
        }
        return hidden.toString();
    }

    private static String reveal(String hidden) {
        if (hidden.length() % 4 != 0) {
            return null;
        }
        byte[] bytes = new byte[hidden.length() / 4];
        for (int index = 0; index < bytes.length; index++) {
            int offset = index * 4;
            if (hidden.charAt(offset) != ChatColor.COLOR_CHAR
                    || hidden.charAt(offset + 2) != ChatColor.COLOR_CHAR) {
                return null;
            }
            int high = Character.digit(hidden.charAt(offset + 1), 16);
            int low = Character.digit(hidden.charAt(offset + 3), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String sign(String buffId, String kind, Material material, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String input = buffId + "|" + kind + "|" + material.name() + "|" + nonce;
            byte[] fullSignature = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Arrays.copyOf(fullSignature, 16));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}

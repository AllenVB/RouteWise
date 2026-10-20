package com.routewise.routing.matrix;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * Canonical fingerprint of a node set — the cache key.
 *
 * <p><b>This definition is shared with the Python service</b>
 * ({@code app/matrix/builder.py:node_set_hash}) and the two must agree exactly,
 * or the backend would never find a cached matrix it just stored. Both sides
 * have a test pinning the hash of {@code [1,2,3]}.
 *
 * <p>Definition: sorted, de-duplicated ids, joined with commas, SHA-256 of the
 * UTF-8 bytes, lowercase hex.
 */
public final class NodeSetHash {

    private NodeSetHash() {}

    public static String of(Collection<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("düğüm listesi boş");
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : new TreeSet<>(nodeIds)) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(id.longValue());
        }
        return sha256(sb.toString());
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);
        }
    }
}

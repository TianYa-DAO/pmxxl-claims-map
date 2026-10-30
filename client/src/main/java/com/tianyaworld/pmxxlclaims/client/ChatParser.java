package com.tianyaworld.pmxxlclaims.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatParser {

    private static final Pattern NAME = Pattern.compile("(?:\u9886\u5730|Land|Residence)\\s*[:：]\\s*(.+)$");
    private static final Pattern COORD = Pattern.compile("X\\s*[:：]\\s*(-?\\d+).{0,12}Y\\s*[:：]\\s*(-?\\d+).{0,12}Z\\s*[:：]\\s*(-?\\d+)");
    private static final Pattern OWNER = Pattern.compile("(?:\u6240\u6709\u8005|Owner)\\s*[:：]\\s*(.+)$");
    private static final Pattern COORD_LIKE = Pattern.compile("^[-\\d\\s:,XY Z]+$");

    private static String pendingName;
    private static String pendingOwner;
    private static String pendingWorld;
    private static final List<int[]> pendingCoords = new ArrayList<>();
    private static long lastLineAt;

    private ChatParser() {
    }

    public static synchronized void accept(String raw, String world) {
        if (raw == null || raw.length() > 512) {
            return;
        }
        String line = raw.replaceAll("\u00a7.", "").trim();
        if (line.isEmpty()) {
            return;
        }
        Matcher nameMatcher = NAME.matcher(line);
        if (nameMatcher.find()) {
            String candidate = nameMatcher.group(1).trim();
            if (!candidate.isEmpty() && candidate.length() < 64 && !looksLikeCoordinates(candidate)) {
                flush();
                pendingName = candidate;
                pendingOwner = "";
                pendingWorld = world;
                pendingCoords.clear();
                lastLineAt = System.currentTimeMillis();
                return;
            }
        }
        Matcher coordMatcher = COORD.matcher(line);
        boolean found = false;
        while (coordMatcher.find()) {
            int[] triple = parseTriple(coordMatcher);
            if (triple == null) {
                break;
            }
            if (pendingName == null) {
                found = false;
                break;
            }
            pendingCoords.add(triple);
            found = true;
        }
        if (found) {
            lastLineAt = System.currentTimeMillis();
            flushIfComplete();
            return;
        }
        Matcher ownerMatcher = OWNER.matcher(line);
        if (ownerMatcher.find() && pendingName != null) {
            pendingOwner = ownerMatcher.group(1).trim();
            lastLineAt = System.currentTimeMillis();
        }
    }

    private static int[] parseTriple(Matcher matcher) {
        try {
            long x = Long.parseLong(matcher.group(1));
            long y = Long.parseLong(matcher.group(2));
            long z = Long.parseLong(matcher.group(3));
            if (Math.abs(x) > 30000000L || Math.abs(y) > 32767L || Math.abs(z) > 30000000L) {
                return null;
            }
            return new int[]{(int) x, (int) y, (int) z};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean looksLikeCoordinates(String value) {
        return COORD_LIKE.matcher(value).matches() && value.chars().anyMatch(Character::isDigit);
    }

    private static void flushIfComplete() {
        if (pendingName == null || pendingCoords.size() < 2 || pendingCoords.size() % 2 != 0) {
            return;
        }
        List<ClaimStore.Box> boxes = new ArrayList<>();
        for (int i = 0; i + 1 < pendingCoords.size(); i += 2) {
            int[] first = pendingCoords.get(i);
            int[] second = pendingCoords.get(i + 1);
            boxes.add(new ClaimStore.Box(
                    Math.min(first[0], second[0]), Math.min(first[2], second[2]),
                    Math.max(first[0], second[0]), Math.max(first[2], second[2])));
        }
        ClaimStore.put(pendingName, pendingWorld == null ? "" : pendingWorld, pendingOwner, boxes);
        pendingCoords.clear();
    }

    public static synchronized void flush() {
        if (pendingName != null) {
            flushIfComplete();
        }
        pendingName = null;
        pendingOwner = null;
        pendingWorld = null;
        pendingCoords.clear();
    }

    public static synchronized void tick() {
        if (pendingName != null && System.currentTimeMillis() - lastLineAt > 1500L) {
            flush();
            ClaimStore.saveIfDirty();
        }
    }
}

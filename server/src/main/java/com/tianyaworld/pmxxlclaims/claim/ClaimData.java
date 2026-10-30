package com.tianyaworld.pmxxlclaims.claim;

import java.util.List;

public final class ClaimData {

    public static final int FLAG_SUBZONE = 1;

    private ClaimData() {
    }

    public record Area(int minX, int minZ, int maxX, int maxZ) {
    }

    public record Claim(String name, String owner, int color, int flags, List<Area> areas) {
    }
}

package dev.somnia.skyblock;

final class ProgressMath {
    static final int CRAFT_COST = 160;
    static final int CRAFT_COLLECTION = 100;
    static final int BATCH_CAP = 64;

    /**
     * Cumulative skill XP to reach each level (index = level), from the SkyBlock wiki skill tables
     * (Farming / Mining / Combat / Foraging / Fishing / Enchanting / Carpentry share it). Levels 26-60 are kept for later;
     * v1 caps every skill at {@link #V1_CAP}.
     */
    private static final long[] LEVELS = {
            0L, 50L, 175L, 375L, 675L, 1175L, 1925L, 2925L, 4425L, 6425L,
            9925L, 14925L, 22425L, 32425L, 47425L, 67425L, 97425L, 147425L, 222425L, 322425L,
            522425L, 822425L, 1222425L, 1722425L, 2322425L, 3022425L, 3822425L, 4722425L, 5722425L, 6822425L,
            8022425L, 9322425L, 10722425L, 12222425L, 13822425L, 15522425L, 17322425L, 19222425L, 21222425L, 23322425L,
            25522425L, 27822425L, 30222425L, 32722425L, 35322425L, 38072425L, 40972425L, 44072425L, 47472425L, 51172425L,
            55172425L, 59472425L, 64072425L, 68972425L, 74172425L, 79672425L, 85472425L, 91572425L, 97972425L, 104672425L,
            111672425L
    };

    /** v1 level cap for all seven skills. */
    static final int V1_CAP = 25;

    private static final int[] TIERS = {
            0, 50, 100, 250, 500, 1000, 2500
    };

    private ProgressMath() {
    }

    static int level(long xp) {
        int level = 0;
        for (int i = 1; i <= V1_CAP; i++) {
            if (xp >= LEVELS[i]) {
                level = i;
            }
        }
        return level;
    }

    static long levelStart(int level) {
        if (level < 0) {
            return 0;
        }
        if (level >= LEVELS.length) {
            return LEVELS[LEVELS.length - 1];
        }
        return LEVELS[level];
    }

    static long nextLevelXp(int level) {
        int next = level + 1;
        if (next >= LEVELS.length) {
            return LEVELS[LEVELS.length - 1];
        }
        return LEVELS[next];
    }

    /** Coin reward for reaching a skill level (wiki skill reward tables). */
    static long coinsFor(int level) {
        if (level <= 0) {
            return 0;
        }
        long[] coins = {0, 100, 250, 500, 750, 1000, 2000, 3000, 4000, 5000, 7500, 10000, 15000, 20000, 25000, 30000,
                40000, 50000, 65000, 80000, 100000, 125000, 150000, 175000, 200000, 225000, 250000, 275000, 300000,
                325000, 350000, 375000, 400000, 425000, 450000, 475000, 500000, 550000, 600000, 650000, 700000,
                750000, 800000, 850000, 900000};
        return level < coins.length ? coins[level] : 1_000_000L;
    }

    /** SkyBlock XP for reaching a skill level: 5 for 1-10, 10 for 11-25, 20 for 26-50, 30 for 51-60 (wiki SkyBlock Levels). */
    static int sbXpFor(int level) {
        if (level <= 0) return 0;
        if (level <= 10) return 5;
        if (level <= 25) return 10;
        if (level <= 50) return 20;
        return 30;
    }

    static int tier(long collected) {
        int tier = 0;
        for (int i = 1; i < TIERS.length; i++) {
            if (collected >= TIERS[i]) {
                tier = i;
            }
        }
        return tier;
    }

    static String roman(int tier) {
        return switch (tier) {
            case 0 -> "—";
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "V+";
        };
    }

    static int maxLevel() {
        return V1_CAP;
    }

    static int[] tiers() {
        return TIERS.clone();
    }

    /** Roman numerals for skill levels (1..60). */
    static String numeral(int n) {
        if (n <= 0) {
            return "0";
        }
        int[] v = {50, 40, 10, 9, 5, 4, 1};
        String[] r = {"L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                out.append(r[i]);
                n -= v[i];
            }
        }
        return out.toString();
    }
}

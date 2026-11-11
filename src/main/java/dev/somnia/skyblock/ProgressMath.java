package dev.somnia.skyblock;

final class ProgressMath {
    static final int CRAFT_COST = 160;
    static final int CRAFT_COLLECTION = 100;
    static final int BATCH_CAP = 64;

    private static final int[] LEVELS = {
            0, 50, 125, 250, 450, 800, 1400, 2300, 3600, 5500,
            8000, 11000, 15000, 20000, 27000, 36000, 48000, 63000, 82000, 105000,
            135000, 175000, 225000, 290000, 370000, 470000
    };

    private static final int[] TIERS = {
            0, 50, 100, 250, 500, 1000, 2500
    };

    private ProgressMath() {
    }

    static int level(long xp) {
        int level = 0;
        for (int i = 1; i < LEVELS.length; i++) {
            if (xp >= LEVELS[i]) {
                level = i;
            }
        }
        return level;
    }

    static int levelStart(int level) {
        if (level < 0) {
            return 0;
        }
        if (level >= LEVELS.length) {
            return LEVELS[LEVELS.length - 1];
        }
        return LEVELS[level];
    }

    static int nextLevelXp(int level) {
        int next = level + 1;
        if (next >= LEVELS.length) {
            return LEVELS[LEVELS.length - 1];
        }
        return LEVELS[next];
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
        return LEVELS.length - 1;
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

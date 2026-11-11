package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Villager;

final class HubWorld {
    static final int PORTAL_X = -1;
    static final int PORTAL_Z = 14;
    static final int TABLE_X = 12;
    static final int TABLE_Z = 5;
    static final int FURNACE_X = 14;
    static final int FURNACE_Z = 5;
    static final int GATE_Z = -98;
    static final int COAL_Z0 = -46;
    static final int COAL_Z1 = -66;
    static final int GOLD_Z0 = -80;
    static final int GOLD_Z1 = -92;
    static final int GOLD_BLOCK_X = -16;
    static final int GOLD_BLOCK_Z = -4;

    private static final int Y = IslandManager.GROUND_Y;
    private static int placed;

    private HubWorld() {
    }

    static int paste(World world) {
        placed = 0;
        for (int x = -96; x <= 86; x++) {
            for (int z = -150; z <= 64; z++) {
                if (land(x, z)) {
                    column(world, x, z);
                }
            }
        }
        paths(world);
        well(world, 5, -12);
        pond(world);
        house(world, -28, -20, -16, -9, -16, -12, BlockFace.EAST, Material.STONE_BRICKS, Material.DARK_OAK_LOG, Material.STONE_BRICK_STAIRS, true, false);
        for (int x = -26; x <= -18; x++) {
            put(world, x, Y + 1, -12, Material.RED_CARPET);
        }
        hang(world, -22, -14);
        hang(world, -24, -16);
        house(world, -28, -6, -16, 2, -16, -2, BlockFace.EAST, Material.STONE_BRICKS, Material.DARK_OAK_LOG, Material.DARK_OAK_STAIRS, true, true);
        for (int z = -5; z <= 1; z++) {
            if (z == -4) {
                continue;
            }
            put(world, -26, Y + 1, z, Material.BOOKSHELF);
            put(world, -26, Y + 2, z, Material.BOOKSHELF);
        }
        hang(world, -22, -2);
        house(world, 10, 3, 16, 8, 13, 8, BlockFace.SOUTH, Material.STONE_BRICKS, Material.SPRUCE_LOG, Material.SPRUCE_STAIRS, true, false);
        put(world, TABLE_X, Y + 1, TABLE_Z, Material.CRAFTING_TABLE);
        Block furnace = world.getBlockAt(FURNACE_X, Y + 1, FURNACE_Z);
        furnace.setType(Material.FURNACE, false);
        if (furnace.getBlockData() instanceof Directional facing) {
            facing.setFacing(BlockFace.SOUTH);
            furnace.setBlockData(facing, false);
        }
        placed++;
        hang(world, 13, 5);
        stalls(world);
        windmill(world);
        wheat(world);
        house(world, 46, 5, 50, 9, 48, 9, BlockFace.SOUTH, Material.OAK_PLANKS, Material.OAK_LOG, Material.OAK_STAIRS, true, false);
        put(world, 47, Y + 1, 6, Material.HAY_BLOCK);
        put(world, 47, Y + 1, 7, Material.HAY_BLOCK);
        put(world, 49, Y + 1, 7, Material.COMPOSTER);
        house(world, -12, 18, -6, 23, -6, 20, BlockFace.EAST, Material.OAK_PLANKS, Material.OAK_LOG, Material.OAK_STAIRS, true, false);
        house(world, 22, -2, 28, 4, 22, 1, BlockFace.WEST, Material.OAK_PLANKS, Material.OAK_LOG, Material.OAK_STAIRS, true, false);
        graveyard(world);
        tower(world);
        ruins(world);
        colosseum(world);
        camp(world);
        lamps(world);
        scatter(world);
        mouth(world);
        arch(world);
        dress(world);
        beams(world);
        gate(world);
        torches(world);
        portal(world, PORTAL_X, PORTAL_Z);
        signs(world);
        person(world, -5, -34, "Lazy Miner", Villager.Profession.NITWIT);
        person(world, 13, 10, "Blacksmith", Villager.Profession.TOOLSMITH);
        Block barrel = world.getBlockAt(-6, Y + 1, -33);
        barrel.setType(Material.BARREL, false);
        if (barrel.getBlockData() instanceof Directional facing) {
            facing.setFacing(BlockFace.SOUTH);
            barrel.setBlockData(facing, false);
        }
        placed++;
        put(world, -7, Y + 1, -34, Material.LANTERN);
        grandeur(world);
        return sealed(world) ? placed : -1;
    }

    private static void column(World world, int x, int z) {
        int h = relief(x, z);
        int rim = rim(x, z);
        boolean cliff = cliff(x, z, h);
        int surface = Y + h;
        int drop = rim <= 1 ? 5 : rim == 2 ? 7 : 9 + Math.min(11, h / 3);
        int bottom = Y - drop;
        boolean walk = x >= -1 && x <= 1 && z <= -43 && z >= -97;
        boolean wall = (x == -2 || x == 2) && z <= -43 && z >= GATE_Z;
        Material top = top(x, z, h, rim, cliff);
        for (int y = bottom; y <= surface; y++) {
            if (walk && y >= Y + 1 && y <= Y + 3) {
                continue;
            }
            Material type;
            if (walk && y >= Y + 4) {
                type = Material.STONE;
            } else if (walk && y == Y) {
                type = Material.COBBLESTONE;
            } else if (wall && y >= Y && y <= Y + 4) {
                type = Material.STONE;
            } else if (y == surface) {
                type = top;
            } else if (y == surface - 1 && soft(top)) {
                type = Material.DIRT;
            } else {
                type = rock(x, y, z);
            }
            put(world, x, y, z, type);
        }
        if (walk) {
            for (int y = Y + 1; y <= Y + 3; y++) {
                Block open = world.getBlockAt(x, y, z);
                if (!open.getType().isAir()) {
                    open.setType(Material.AIR, false);
                }
            }
        }
        if (walk && surface < Y + 6) {
            for (int y = Y + 4; y <= Y + 6; y++) {
                put(world, x, y, z, Material.STONE);
            }
        }
        if (rim == 1 && hash(x, z) % 7 == 0) {
            drip(world, x, bottom - 1, z, true);
        }
        if (rim == 1) {
            lip(world, x, z, surface);
        }
    }

    private static void lip(World world, int x, int z, int surface) {
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] neighbor : neighbors) {
            int nx = x + neighbor[0];
            int nz = z + neighbor[1];
            if (land(nx, nz)) {
                continue;
            }
            int roll = hash(nx, nz);
            Material crust = roll % 3 == 0 ? Material.COBBLESTONE : Material.DIRT;
            put(world, nx, surface - 1, nz, crust);
            put(world, nx, surface - 2, nz, Material.STONE);
            if (roll % 3 == 0) {
                Material cap = roll % 2 == 0 ? Material.ANDESITE : Material.COARSE_DIRT;
                put(world, nx, surface, nz, cap);
            }
            int hang = 1 + roll % 4;
            for (int i = 1; i <= hang; i++) {
                drip(world, nx, surface - 2 - i, nz, i == hang);
            }
        }
    }

    private static void drip(World world, int x, int y, int z, boolean tip) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.POINTED_DRIPSTONE, false);
        if (block.getBlockData() instanceof PointedDripstone drip) {
            drip.setVerticalDirection(BlockFace.DOWN);
            drip.setThickness(tip ? PointedDripstone.Thickness.TIP : PointedDripstone.Thickness.BASE);
            block.setBlockData(drip, false);
        }
        placed++;
    }

    private static boolean soft(Material type) {
        return type == Material.GRASS_BLOCK || type == Material.PODZOL || type == Material.COARSE_DIRT || type == Material.SNOW_BLOCK;
    }

    private static Material top(int x, int z, int h, int rim, boolean cliff) {
        if (h >= 42 && !cliff) {
            return Material.SNOW_BLOCK;
        }
        if (h >= 10 || cliff || (rim == 1 && hash(x, z) % 3 == 0)) {
            int n = hash(x, z) % 7;
            if (n == 0) {
                return Material.ANDESITE;
            }
            if (n == 1) {
                return Material.DIORITE;
            }
            if (n == 2) {
                return Material.COBBLESTONE;
            }
            if (n == 3) {
                return Material.GRAVEL;
            }
            return Material.STONE;
        }
        if (x <= -20 && h > 0 && hash(x, z) % 4 == 0) {
            return Material.PODZOL;
        }
        if (rim <= 2 && hash(x, z) % 5 == 0) {
            return Material.COARSE_DIRT;
        }
        if (hash(x, z) % 43 == 0) {
            return Material.COARSE_DIRT;
        }
        return Material.GRASS_BLOCK;
    }

    private static Material rock(int x, int y, int z) {
        int n = hash(x + y * 31, z) % 11;
        if (n == 0) {
            return Material.ANDESITE;
        }
        if (n == 1) {
            return Material.DIORITE;
        }
        if (n == 2) {
            return Material.COBBLESTONE;
        }
        if (n == 3) {
            return Material.MOSSY_COBBLESTONE;
        }
        return Material.STONE;
    }

    private static void paths(World world) {
        for (int z = -44; z <= 8; z++) {
            for (int x = -1; x <= 1; x++) {
                put(world, x, Y, z, brick(x, z));
            }
        }
        for (int z = 9; z <= 13; z++) {
            for (int x = -1; x <= 1; x++) {
                put(world, x, Y, z, brick(x, z));
            }
        }
        for (int z = 15; z <= 30; z++) {
            for (int x = -1; x <= 1; x++) {
                put(world, x, Y, z, brick(x, z));
            }
        }
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    continue;
                }
                put(world, x, Y, z, brick(x, z));
            }
        }
        stair(world, -4, Y + 1, -4, Material.OAK_STAIRS, BlockFace.NORTH);
        stair(world, 4, Y + 1, -4, Material.OAK_STAIRS, BlockFace.NORTH);
        stair(world, -4, Y + 1, 4, Material.OAK_STAIRS, BlockFace.SOUTH);
        stair(world, 4, Y + 1, 4, Material.OAK_STAIRS, BlockFace.SOUTH);
        for (int x = -16; x <= 12; x++) {
            for (int z = -13; z <= -11; z++) {
                if (x >= 4 && x <= 6) {
                    continue;
                }
                put(world, x, Y, z, brick(x, z));
            }
        }
        for (int x = -15; x <= -2; x++) {
            put(world, x, Y, -2, brick(x, -2));
        }
        for (int x = 16; x <= 22; x++) {
            put(world, x, Y, 2, Material.DIRT_PATH);
        }
        for (int x = -8; x >= -43; x--) {
            int z = 6 + ((-8 - x) * 6) / 36;
            int h = relief(x, z);
            put(world, x, Y + h, z, Material.DIRT_PATH);
            put(world, x, Y + h, z + 1, Material.DIRT_PATH);
        }
    }

    private static Material brick(int x, int z) {
        if (x == 0 && z == 0) {
            // intact() checks the spawn block, so it stays plain.
            return Material.STONE_BRICKS;
        }
        int n = hash(x, z) % 11;
        if (n == 0) {
            return Material.CRACKED_STONE_BRICKS;
        }
        if (n == 1) {
            return Material.MOSSY_STONE_BRICKS;
        }
        return Material.STONE_BRICKS;
    }

    private static void well(World world, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                put(world, x + dx, Y, z + dz, Material.STONE_BRICKS);
                if (dx == 0 || dz == 0) {
                    link(world, x + dx, Y + 1, z + dz, Material.COBBLESTONE_WALL);
                } else {
                    link(world, x + dx, Y + 1, z + dz, Material.OAK_FENCE);
                    link(world, x + dx, Y + 2, z + dz, Material.OAK_FENCE);
                    link(world, x + dx, Y + 3, z + dz, Material.OAK_FENCE);
                }
            }
        }
        stair(world, x - 1, Y + 4, z - 1, Material.SPRUCE_STAIRS, BlockFace.NORTH);
        stair(world, x, Y + 4, z - 1, Material.SPRUCE_STAIRS, BlockFace.NORTH);
        stair(world, x + 1, Y + 4, z - 1, Material.SPRUCE_STAIRS, BlockFace.NORTH);
        stair(world, x - 1, Y + 4, z + 1, Material.SPRUCE_STAIRS, BlockFace.SOUTH);
        stair(world, x, Y + 4, z + 1, Material.SPRUCE_STAIRS, BlockFace.SOUTH);
        stair(world, x + 1, Y + 4, z + 1, Material.SPRUCE_STAIRS, BlockFace.SOUTH);
        stair(world, x - 1, Y + 4, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
        stair(world, x + 1, Y + 4, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
        link(world, x, Y + 4, z, Material.SPRUCE_FENCE);
        put(world, x, Y + 3, z, Material.IRON_CHAIN);
        Block lantern = world.getBlockAt(x, Y + 2, z);
        lantern.setType(Material.LANTERN, false);
        if (lantern.getBlockData() instanceof Lantern hanging) {
            hanging.setHanging(true);
            lantern.setBlockData(hanging, false);
        }
        placed++;
        water(world, x, z);
    }

    private static void link(World world, int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == type) {
            return;
        }
        block.setType(type, true);
        placed++;
    }

    private static void pond(World world) {
        for (int x = 7; x <= 15; x++) {
            for (int z = 17; z <= 23; z++) {
                boolean inner = x >= 9 && x <= 12 && z >= 19 && z <= 21;
                if (inner) {
                    put(world, x, Y - 1, z, Material.DIRT);
                    water(world, x, z);
                    if (hash(x, z) % 3 == 0) {
                        put(world, x, Y + 1, z, Material.LILY_PAD);
                    }
                } else {
                    put(world, x, Y, z, Material.GRASS_BLOCK);
                }
            }
        }
        cane(world, 8, 20);
        cane(world, 13, 18);
    }

    private static void wheat(World world) {
        for (int x = 36; x <= 44; x++) {
            for (int z = 4; z <= 10; z++) {
                boolean edge = x == 36 || x == 44 || z == 4 || z == 10;
                if (edge) {
                    if (x == 40 && z == 10) {
                        gate(world, x, z, BlockFace.SOUTH, Material.OAK_FENCE_GATE);
                    } else {
                        link(world, x, Y + 1, z, Material.OAK_FENCE);
                    }
                    continue;
                }
                if (x == 40 && z == 7) {
                    water(world, x, z);
                    continue;
                }
                Block soil = world.getBlockAt(x, Y, z);
                soil.setType(Material.FARMLAND, false);
                placed++;
                Block crop = world.getBlockAt(x, Y + 1, z);
                crop.setType(Material.WHEAT, false);
                if (crop.getBlockData() instanceof org.bukkit.block.data.Ageable age) {
                    age.setAge(age.getMaximumAge());
                    crop.setBlockData(age, false);
                }
            }
        }
    }

    private static void stalls(World world) {
        stall(world, 11, -19, 4, Material.RED_WOOL, Material.WHITE_WOOL, Material.OAK_PLANKS);
        stall(world, 17, -17, 3, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.SPRUCE_PLANKS);
        stall(world, 22, -19, 5, Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL, Material.DARK_OAK_PLANKS);
    }

    private static void stall(World world, int x, int z, int width, Material cloth, Material stripe, Material counter) {
        int post = 3 + (width % 2);
        for (int dx = 0; dx < width; dx++) {
            boolean edge = dx == 0 || dx == width - 1;
            if (edge) {
                for (int y = 1; y <= post; y++) {
                    link(world, x + dx, Y + y, z, Material.OAK_FENCE);
                    link(world, x + dx, Y + y, z + 2, Material.OAK_FENCE);
                }
            }
            put(world, x + dx, Y + 1, z + 1, counter);
            Material wool = (dx % 2 == 0) ? cloth : stripe;
            put(world, x + dx, Y + post + 1, z, wool);
            put(world, x + dx, Y + post + 1, z + 1, (dx % 2 == 0) ? stripe : cloth);
            put(world, x + dx, Y + post + 1, z + 2, wool);
            stair(world, x + dx, Y + post + 1, z + 3, Material.OAK_STAIRS, BlockFace.SOUTH);
        }
        Block barrel = world.getBlockAt(x + 1, Y + 2, z + 1);
        barrel.setType(Material.BARREL, false);
        if (barrel.getBlockData() instanceof Directional facing) {
            facing.setFacing(BlockFace.SOUTH);
            barrel.setBlockData(facing, false);
        }
        placed++;
    }

    private static void windmill(World world) {
        int x = 56;
        int z = -10;
        int baseH = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                baseH = Math.max(baseH, relief(x + dx, z + dz));
            }
        }
        int base = Y + baseH;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int floor = Y + relief(x + dx, z + dz);
                for (int y = floor; y <= base; y++) {
                    put(world, x + dx, y, z + dz, y == base ? Material.COBBLESTONE : Material.DIRT);
                }
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    for (int y = base + 1; y < base; y++) {
                        put(world, x + dx, y, z + dz, Material.STONE_BRICKS);
                    }
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                for (int y = base + 1; y <= base + 4; y++) {
                    if (!edge) {
                        world.getBlockAt(x + dx, y, z + dz).setType(Material.AIR, false);
                        continue;
                    }
                    if (corner) {
                        put(world, x + dx, y, z + dz, Material.OAK_LOG);
                    } else if (y == base + 3) {
                        pane(world, x + dx, y, z + dz, Math.abs(dx) == 2 ? BlockFace.EAST : BlockFace.NORTH);
                    } else {
                        put(world, x + dx, y, z + dz, Material.OAK_PLANKS);
                    }
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean edge = dx == -1 || dx == 1 || dz == -1 || dz == 1;
                boolean corner = (dx == -1 || dx == 1) && (dz == -1 || dz == 1);
                for (int y = base + 5; y <= base + 12; y++) {
                    if (!edge) {
                        world.getBlockAt(x + dx, y, z + dz).setType(Material.AIR, false);
                        continue;
                    }
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        continue;
                    }
                    put(world, x + dx, y, z + dz, corner ? Material.OAK_LOG : Material.WHITE_WOOL);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockFace face = dx == -1 ? BlockFace.WEST : dx == 1 ? BlockFace.EAST : dz == -1 ? BlockFace.NORTH : BlockFace.SOUTH;
                if (dx == 0 && dz == 0) {
                    put(world, x, base + 13, z, Material.OAK_PLANKS);
                } else if (dx == 0 || dz == 0) {
                    stair(world, x + dx, base + 13, z + dz, Material.SPRUCE_STAIRS, face);
                }
            }
        }
        link(world, x, base + 14, z, Material.OAK_FENCE);
        int hub = base + 9;
        int sailX = x - 2;
        put(world, sailX, hub, z, Material.OAK_LOG);
        for (int d = 1; d <= 5; d++) {
            put(world, sailX, hub + d, z + d, Material.WHITE_WOOL);
            put(world, sailX, hub + d, z - d, Material.WHITE_WOOL);
            put(world, sailX, hub - d, z + d, Material.WHITE_WOOL);
            put(world, sailX, hub - d, z - d, Material.WHITE_WOOL);
            put(world, sailX - 1, hub + d, z + d, Material.WHITE_WOOL);
            put(world, sailX - 1, hub - d, z - d, Material.WHITE_WOOL);
        }
        doorAt(world, x - 2, base + 1, z, BlockFace.WEST, Material.OAK_DOOR);
        for (int y = base + 1; y <= base + 4; y++) {
            Block rung = world.getBlockAt(x - 1, y, z);
            rung.setType(Material.LADDER, false);
            if (rung.getBlockData() instanceof Directional facing) {
                facing.setFacing(BlockFace.EAST);
                rung.setBlockData(facing, false);
            }
            placed++;
        }
    }

    private static void graveyard(World world) {
        for (int x = -56; x <= -44; x++) {
            for (int z = 8; z <= 18; z++) {
                boolean edge = x == -56 || x == -44 || z == 8 || z == 18;
                if (!edge) {
                    if (hash(x, z) % 3 == 0) {
                        put(world, x, Y, z, Material.COARSE_DIRT);
                    }
                    continue;
                }
                if (x == -44 && z == 13) {
                    gate(world, x, z, BlockFace.EAST, Material.DARK_OAK_FENCE_GATE);
                } else {
                    link(world, x, Y + 1, z, Material.DARK_OAK_FENCE);
                }
            }
        }
        for (int y = Y + 1; y <= Y + 3; y++) {
            put(world, -44, y, 12, Material.STONE_BRICKS);
            put(world, -44, y, 14, Material.STONE_BRICKS);
        }
        stair(world, -44, Y + 4, 13, Material.STONE_BRICK_STAIRS, BlockFace.EAST);
        tomb(world, -54, 11);
        tomb(world, -50, 11);
        tomb(world, -54, 15);
        tomb(world, -50, 15);
        for (int y = Y + 1; y <= Y + 6; y++) {
            put(world, -52, y, 13, Material.DARK_OAK_LOG);
        }
        log(world, -51, Y + 4, 13, Material.DARK_OAK_LOG, Axis.X);
        log(world, -53, Y + 5, 13, Material.DARK_OAK_LOG, Axis.X);
        put(world, -52, Y + 7, 13, Material.COBWEB);
        put(world, -55, Y + 1, 9, Material.SOUL_TORCH);
        put(world, -45, Y + 1, 9, Material.SOUL_TORCH);
        put(world, -55, Y + 1, 17, Material.SOUL_TORCH);
        put(world, -45, Y + 1, 17, Material.SOUL_TORCH);
    }

    private static void tomb(World world, int x, int z) {
        put(world, x, Y + 1, z, Material.MOSSY_COBBLESTONE);
        put(world, x + 1, Y + 1, z, Material.MOSSY_COBBLESTONE);
        stair(world, x, Y + 2, z, Material.MOSSY_COBBLESTONE_STAIRS, BlockFace.WEST);
        stair(world, x + 1, Y + 2, z, Material.MOSSY_COBBLESTONE_STAIRS, BlockFace.EAST);
    }

    private static void tower(World world) {
        for (int x = -70; x <= -66; x++) {
            for (int z = -8; z <= -4; z++) {
                boolean wall = x == -70 || x == -66 || z == -8 || z == -4;
                boolean corner = (x == -70 || x == -66) && (z == -8 || z == -4);
                for (int y = Y + 1; y <= Y + 11; y++) {
                    if (!wall) {
                        if (y == Y + 1) {
                            put(world, x, y, z, Material.SPRUCE_PLANKS);
                        }
                        continue;
                    }
                    if (corner) {
                        put(world, x, y, z, Material.DARK_OAK_LOG);
                    } else if (y == Y + 4 || y == Y + 7 || y == Y + 10) {
                        pane(world, x, y, z, (x == -70 || x == -66) ? BlockFace.EAST : BlockFace.NORTH);
                    } else {
                        put(world, x, y, z, Material.DARK_OAK_PLANKS);
                    }
                }
            }
        }
        int cx = -68;
        int cz = -6;
        for (int layer = 0; layer <= 5; layer++) {
            int radius = layer >= 4 ? 0 : layer >= 2 ? 1 : 2;
            int y = Y + 12 + layer;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean edge = Math.abs(dx) == radius || Math.abs(dz) == radius;
                    if (radius == 0) {
                        put(world, cx, y, cz, Material.DARK_OAK_LOG);
                        continue;
                    }
                    if (edge) {
                        BlockFace face = Math.abs(dx) >= Math.abs(dz)
                                ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
                                : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                        stair(world, cx + dx, y, cz + dz, Material.DARK_OAK_STAIRS, face);
                    } else {
                        put(world, cx + dx, y, cz + dz, Material.DARK_OAK_PLANKS);
                    }
                }
            }
        }
        link(world, cx, Y + 18, cz, Material.DARK_OAK_FENCE);
        door(world, -66, -6, BlockFace.EAST, Material.DARK_OAK_DOOR);
        put(world, -68, Y + 2, -6, Material.SOUL_TORCH);
    }

    private static void ruins(World world) {
        for (int x = -22; x <= -16; x++) {
            broken(world, x, 22);
            if (hash(x, 25) % 2 == 0) {
                broken(world, x, 25);
            }
        }
    }

    private static void broken(World world, int x, int z) {
        if (hash(x, z) % 4 == 0) {
            return;
        }
        Material type = hash(x, z) % 2 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
        put(world, x, Y + 1, z, type);
        if (hash(x, z) % 3 != 0) {
            put(world, x, Y + 2, z, type);
        }
        if (hash(x, z) % 5 == 0) {
            put(world, x, Y + 3, z, type);
            stair(world, x, Y + 4, z, Material.STONE_BRICK_STAIRS, BlockFace.NORTH);
        }
    }

    private static void colosseum(World world) {
        int cz = 42;
        int outer = 13;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int dist = dx * dx + dz * dz;
                if (dist > outer * outer) {
                    continue;
                }
                int x = dx;
                int z = cz + dz;
                boolean opening = Math.abs(dx) <= 2 && dz <= 0;
                if (dist <= 36) {
                    put(world, x, Y, z, Material.SAND);
                    continue;
                }
                put(world, x, Y, z, brick(x, z));
                if (opening) {
                    continue;
                }
                int ring = (int) Math.round(Math.sqrt(dist));
                BlockFace inward = Math.abs(dx) >= Math.abs(dz)
                        ? (dx > 0 ? BlockFace.WEST : BlockFace.EAST)
                        : (dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH);
                if (ring <= 8) {
                    stair(world, x, Y + 1, z, Material.STONE_BRICK_STAIRS, inward);
                } else if (ring <= 10) {
                    put(world, x, Y + 1, z, brick(x, z + 1));
                    stair(world, x, Y + 2, z, Material.STONE_BRICK_STAIRS, inward);
                } else if (ring <= 12) {
                    put(world, x, Y + 1, z, brick(x, z + 2));
                    put(world, x, Y + 2, z, brick(x, z + 3));
                    stair(world, x, Y + 3, z, Material.STONE_BRICK_STAIRS, inward);
                } else {
                    for (int y = Y + 1; y <= Y + 6; y++) {
                        if (y == Y + 4 && hash(x, z) % 4 == 0) {
                            continue;
                        }
                        put(world, x, y, z, brick(x, z + y));
                    }
                    stair(world, x, Y + 7, z, Material.STONE_BRICK_STAIRS, inward);
                }
            }
        }
    }

    private static void camp(World world) {
        Block fire = world.getBlockAt(-6, Y + 1, -5);
        fire.setType(Material.CAMPFIRE, false);
        if (fire.getBlockData() instanceof Campfire campfire) {
            campfire.setLit(true);
            campfire.setFacing(BlockFace.SOUTH);
            fire.setBlockData(campfire, false);
        }
        placed++;
        stair(world, -7, Y + 1, -5, Material.OAK_STAIRS, BlockFace.EAST);
        stair(world, -5, Y + 1, -5, Material.OAK_STAIRS, BlockFace.WEST);
        stair(world, -6, Y + 1, -6, Material.OAK_STAIRS, BlockFace.SOUTH);
        stair(world, -6, Y + 1, -4, Material.OAK_STAIRS, BlockFace.NORTH);
    }

    private static void lamps(World world) {
        int[] zs = {6, 2, -6, -16, -22, -28};
        for (int z : zs) {
            lamp(world, 4, z);
            lamp(world, -4, z);
        }
    }

    private static void lamp(World world, int x, int z) {
        if (!land(x, z) || relief(x, z) != 0 || roofed(x, z)) {
            return;
        }
        put(world, x, Y + 1, z, Material.OAK_FENCE);
        Block block = world.getBlockAt(x, Y + 2, z);
        block.setType(Material.LANTERN, false);
        if (block.getBlockData() instanceof Lantern lantern) {
            lantern.setHanging(false);
            block.setBlockData(lantern, false);
        }
        placed++;
    }

    private static void scatter(World world) {
        for (int x = -90; x <= 80; x++) {
            for (int z = -140; z <= 60; z++) {
                if (!land(x, z) || roofed(x, z)) {
                    continue;
                }
                int h = relief(x, z);
                if (Math.abs(x) <= 5 && z <= -26 && z >= -110) {
                    continue;
                }
                Material ground = world.getBlockAt(x, Y + h, z).getType();
                boolean forest = x <= -18 || (x <= -8 && z >= 0);
                if (h >= 5 && h <= 36 && hash(x, z) % 9 == 0 && solidGround(ground)) {
                    spruce(world, x, z, h);
                    continue;
                }
                if (forest && h <= 8 && soil(ground) && hash(x, z) % 7 == 0) {
                    if (x <= -28 && hash(x, z) % 5 == 0) {
                        darkPair(world, x, z);
                    } else {
                        oak(world, x, z, false);
                    }
                    continue;
                }
                if (rim(x, z) == 1 && h < 14 && hash(x, z) % 6 == 0) {
                    boulder(world, x, z, h);
                }
            }
        }
        oak(world, 8, 6, false);
        oak(world, -10, 8, false);
        oak(world, -14, -16, true);
        oak(world, 20, 8, false);
        oak(world, -36, 2, false);
        oak(world, 30, 18, false);
        oak(world, -18, 26, false);
        bush(world, 6, -16);
        bush(world, -8, 12);
        fallen(world, -30, 16);
        flora(world);
    }

    private static boolean solidGround(Material type) {
        return type == Material.STONE || type == Material.ANDESITE || type == Material.DIORITE
                || type == Material.COBBLESTONE || type == Material.GRAVEL || type == Material.SNOW_BLOCK
                || soil(type) || type == Material.DIRT;
    }

    private static void boulder(World world, int x, int z, int h) {
        int base = Y + h;
        put(world, x, base + 1, z, Material.COBBLESTONE);
        if (hash(x, z) % 2 == 0) {
            put(world, x + 1, base + 1, z, Material.ANDESITE);
            put(world, x, base + 2, z, Material.MOSSY_COBBLESTONE);
        }
    }

    private static void flora(World world) {
        for (int x = -90; x <= 80; x++) {
            for (int z = -140; z <= 60; z++) {
                int h = relief(x, z);
                if (!land(x, z) || h > 8 || roofed(x, z)) {
                    continue;
                }
                int y = Y + h;
                Material ground = world.getBlockAt(x, y, z).getType();
                if (ground != Material.GRASS_BLOCK && ground != Material.PODZOL) {
                    continue;
                }
                if (!world.getBlockAt(x, y + 1, z).getType().isAir()) {
                    continue;
                }
                int roll = hash(x, z) % 20;
                if (roll > 12) {
                    continue;
                }
                Material plant = Material.SHORT_GRASS;
                if (roll == 0) {
                    plant = Material.FERN;
                } else if (roll == 1 && hash(x + 3, z) % 3 == 0) {
                    plant = Material.POPPY;
                } else if (roll == 2 && hash(x, z + 5) % 3 == 0) {
                    plant = Material.DANDELION;
                } else if (roll == 3 && hash(x + 1, z) % 4 == 0) {
                    plant = Material.AZURE_BLUET;
                } else if (roll == 4 && hash(x, z + 1) % 4 == 0) {
                    plant = Material.WHITE_TULIP;
                } else if (x <= -30 && h > 1 && hash(x, z) % 19 == 0) {
                    plant = Material.BROWN_MUSHROOM;
                }
                put(world, x, y + 1, z, plant);
            }
        }
    }

    private static boolean soil(Material type) {
        return type == Material.GRASS_BLOCK || type == Material.PODZOL || type == Material.COARSE_DIRT;
    }

    private static void oak(World world, int x, int z, boolean dark) {
        if (roofed(x, z) || !land(x, z)) {
            return;
        }
        int h = relief(x, z);
        if (h > 10) {
            return;
        }
        int base = Y + h;
        Material ground = world.getBlockAt(x, base, z).getType();
        if (!soil(ground) && ground != Material.DIRT) {
            return;
        }
        boolean giant = !dark && h <= 6 && hash(x, z) % 13 == 0;
        int radius = giant ? 4 : 2 + (hash(x, z) % 2);
        int trunk = giant ? 7 : 5 + (hash(x, z) % 3);
        Material log = dark ? Material.DARK_OAK_LOG : Material.OAK_LOG;
        Material leaf = dark ? Material.DARK_OAK_LEAVES : Material.OAK_LEAVES;
        for (int i = 1; i <= trunk; i++) {
            put(world, x, base + i, z, log);
        }
        int branch = hash(x, z) % 4;
        int bx = branch == 0 ? 1 : branch == 1 ? -1 : 0;
        int bz = branch == 2 ? 1 : branch == 3 ? -1 : 0;
        int by = base + trunk - 2;
        log(world, x + bx, by, z + bz, log, bx != 0 ? Axis.X : Axis.Z);
        if (giant) {
            log(world, x + bx * 2, by, z + bz * 2, log, bx != 0 ? Axis.X : Axis.Z);
        }
        int crown = base + trunk;
        for (int dy = -radius + 1; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int reach = dx * dx + dy * dy + dz * dz;
                    if (reach > radius * radius) {
                        continue;
                    }
                    if (reach > (radius - 1) * (radius - 1) && hash(x + dx, z + dz + dy) % 3 == 0) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    leaves(world, x + dx, crown + dy, z + dz, leaf);
                }
            }
        }
    }

    private static void darkPair(World world, int x, int z) {
        int h = relief(x, z);
        if (h > 8) {
            return;
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (roofed(x + dx, z + dz) || !land(x + dx, z + dz)) {
                    return;
                }
                if (Math.abs(relief(x + dx, z + dz) - h) > 1) {
                    return;
                }
                Material ground = world.getBlockAt(x + dx, Y + h, z + dz).getType();
                if (!soil(ground)) {
                    return;
                }
            }
        }
        int base = Y + h;
        int trunk = 6 + hash(x, z) % 3;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int i = 1; i <= trunk; i++) {
                    put(world, x + dx, base + i, z + dz, Material.DARK_OAK_LOG);
                }
            }
        }
        int radius = 4;
        int crown = base + trunk;
        for (int dy = -2; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius + 1; dx++) {
                for (int dz = -radius; dz <= radius + 1; dz++) {
                    double ddx = dx - 0.5;
                    double ddz = dz - 0.5;
                    if (ddx * ddx + dy * dy + ddz * ddz > radius * radius) {
                        continue;
                    }
                    if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1 && dy <= 0) {
                        continue;
                    }
                    leaves(world, x + dx, crown + dy, z + dz, Material.DARK_OAK_LEAVES);
                }
            }
        }
    }

    private static void spruce(World world, int x, int z, int h) {
        if (Math.abs(x) <= 2 && z <= -40 && z >= -100) {
            return;
        }
        int base = Y + h;
        int trunk = 7 + hash(x, z) % 5;
        if (h >= 18) {
            trunk += 2;
        }
        for (int i = 1; i <= trunk; i++) {
            put(world, x, base + i, z, Material.SPRUCE_LOG);
        }
        for (int layer = 2; layer <= trunk; layer++) {
            int radius = Math.max(1, (trunk - layer) / 2);
            if (radius > 4) {
                radius = 4;
            }
            int y = base + layer;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && hash(x + dx, z + dz) % 2 == 0) {
                        continue;
                    }
                    leaves(world, x + dx, y, z + dz, Material.SPRUCE_LEAVES);
                }
            }
        }
        leaves(world, x, base + trunk + 1, z, Material.SPRUCE_LEAVES);
        leaves(world, x, base + trunk + 2, z, Material.SPRUCE_LEAVES);
    }

    private static void leaves(World world, int x, int y, int z, Material type) {
        if (roofed(x, z)) {
            return;
        }
        if (Math.abs(x) <= 1 && z <= -43 && z >= -97 && y >= Y && y <= Y + 4) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (!block.getType().isAir()) {
            return;
        }
        block.setType(type, false);
        if (block.getBlockData() instanceof Leaves data) {
            data.setPersistent(true);
            block.setBlockData(data, false);
        }
        placed++;
    }

    private static void bush(World world, int x, int z) {
        int h = relief(x, z);
        if (roofed(x, z) || h > 8 || world.getBlockAt(x, Y + h, z).getType() != Material.GRASS_BLOCK) {
            return;
        }
        leaves(world, x, Y + h + 1, z, Material.OAK_LEAVES);
    }

    private static void fallen(World world, int x, int z) {
        int h = relief(x, z);
        if (roofed(x, z) || !soil(world.getBlockAt(x, Y + h, z).getType())) {
            return;
        }
        Block block = world.getBlockAt(x, Y + h + 1, z);
        block.setType(Material.OAK_LOG, false);
        if (block.getBlockData() instanceof Orientable axis) {
            axis.setAxis(Axis.X);
            block.setBlockData(axis, false);
        }
        placed++;
        Block next = world.getBlockAt(x + 1, Y + h + 1, z);
        next.setType(Material.OAK_LOG, false);
        if (next.getBlockData() instanceof Orientable axis) {
            axis.setAxis(Axis.X);
            next.setBlockData(axis, false);
        }
        placed++;
    }

    private static void mouth(World world) {
        for (int z = -42; z <= -41; z++) {
            for (int x = -3; x <= 3; x++) {
                if (Math.abs(x) <= 1) {
                    put(world, x, Y, z, Material.COBBLESTONE);
                    for (int y = Y + 1; y <= Y + 4; y++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                } else {
                    for (int y = Y + 1; y <= Y + 5; y++) {
                        put(world, x, y, z, Material.DARK_OAK_LOG);
                    }
                }
            }
            for (int x = -3; x <= 3; x++) {
                log(world, x, Y + 5, z, Material.DARK_OAK_LOG, Axis.X);
                log(world, x, Y + 6, z, Material.DARK_OAK_LOG, Axis.X);
            }
        }
        stair(world, -3, Y + 7, -42, Material.DARK_OAK_STAIRS, BlockFace.WEST);
        stair(world, 3, Y + 7, -42, Material.DARK_OAK_STAIRS, BlockFace.EAST);
    }

    private static void arch(World world) {
        for (int x = -3; x <= 3; x++) {
            if (Math.abs(x) >= 2) {
                for (int y = Y + 1; y <= Y + 5; y++) {
                    put(world, x, y, -34, Material.STONE_BRICKS);
                }
                put(world, x, Y + 6, -34, Material.STONE_BRICKS);
            } else {
                for (int y = Y + 1; y <= Y + 4; y++) {
                    world.getBlockAt(x, y, -34).setType(Material.AIR, false);
                }
                put(world, x, Y + 5, -34, Material.STONE_BRICKS);
                put(world, x, Y + 6, -34, Material.STONE_BRICKS);
            }
        }
        stair(world, -3, Y + 7, -34, Material.STONE_BRICK_STAIRS, BlockFace.WEST);
        stair(world, 3, Y + 7, -34, Material.STONE_BRICK_STAIRS, BlockFace.EAST);
        wallTorch(world, -2, Y + 3, -33, BlockFace.SOUTH);
        wallTorch(world, 2, Y + 3, -33, BlockFace.SOUTH);
        wallSign(world, -2, Y + 2, -33, BlockFace.SOUTH, "Coal Mine");
    }

    private static void dress(World world) {
        for (int z = -42; z <= -30; z++) {
            brickFace(world, 4, z, 6);
            brickFace(world, 5, z, 6);
            if (z < -36 || z > -32) {
                brickFace(world, -4, z, 6);
                brickFace(world, -5, z, 6);
            } else {
                brickFace(world, -9, z, 5);
            }
            if (z != -34 && z != -42 && z != -41) {
                parapet(world, 3, z);
                if (z < -36) {
                    parapet(world, -3, z);
                }
            }
        }
    }

    private static void parapet(World world, int x, int z) {
        if (!land(x, z)) {
            return;
        }
        put(world, x, Y + 1, z, Material.STONE_BRICKS);
        link(world, x, Y + 2, z, Material.STONE_BRICK_WALL);
    }

    private static void brickFace(World world, int x, int z, int built) {
        if (!land(x, z)) {
            return;
        }
        int h = relief(x, z);
        int top = Math.max(3, Math.min(h, built));
        for (int y = Y + 1; y <= Y + top; y++) {
            int n = hash(x, z + y) % 7;
            Material type = n == 0 ? Material.MOSSY_STONE_BRICKS : n == 1 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
            put(world, x, y, z, type);
        }
    }

    private static void beams(World world) {
        for (int z = -52; z >= -92; z -= 8) {
            if (z == COAL_Z0) {
                continue;
            }
            for (int x = -2; x <= 2; x++) {
                log(world, x, Y + 4, z, Material.OAK_LOG, Axis.X);
            }
        }
    }

    private static void gate(World world) {
        for (int x = -1; x <= 1; x++) {
            for (int y = Y + 1; y <= Y + 3; y++) {
                put(world, x, y, GATE_Z, Material.OBSIDIAN);
            }
        }
        wallSign(world, 0, Y + 2, GATE_Z + 1, BlockFace.SOUTH, "Deep Caverns", "Sealed");
    }

    private static void torches(World world) {
        for (int z = -48; z >= -92; z -= 4) {
            wallTorch(world, -1, Y + 3, z, BlockFace.EAST);
        }
    }

    private static void signs(World world) {
        wallSign(world, PORTAL_X + 4, Y + 1, PORTAL_Z, BlockFace.EAST, "Island");
        wallSign(world, -15, Y + 3, -12, BlockFace.EAST, "Auction House", "STT escrow");
        wallSign(world, -15, Y + 3, -2, BlockFace.EAST, "Bank", "Relic Pool");
        postSign(world, 16, Y + 1, -10, BlockFace.SOUTH, "Bazaar", "STT market");
        postSign(world, 11, Y + 1, 9, BlockFace.SOUTH, "Furnace");
        wallSign(world, -1, Y + 2, -72, BlockFace.EAST, "Gold Mine");
    }

    private static void house(World world, int x0, int z0, int x1, int z1, int doorX, int doorZ, BlockFace face, Material wall, Material pillar, Material roof, boolean windows, boolean gold) {
        boolean large = Math.max(x1 - x0, z1 - z0) >= 11;
        int wallTop = large ? Y + 6 : Y + 5;
        int riseMax = large ? 5 : 3;
        int mid = (x0 + x1) / 2;
        int span = Math.max(mid - x0, x1 - mid);
        Material floor = wall == Material.OAK_PLANKS ? Material.COBBLESTONE : Material.STONE_BRICKS;
        Material gable = roof == Material.STONE_BRICK_STAIRS ? Material.STONE_BRICKS
                : roof == Material.DARK_OAK_STAIRS ? Material.DARK_OAK_PLANKS
                : roof == Material.OAK_STAIRS ? Material.OAK_PLANKS : Material.SPRUCE_PLANKS;
        Material beam = roof == Material.OAK_STAIRS ? Material.OAK_LOG : Material.DARK_OAK_LOG;
        Material doorType = pillar == Material.DARK_OAK_LOG ? Material.DARK_OAK_DOOR
                : pillar == Material.SPRUCE_LOG ? Material.SPRUCE_DOOR : Material.OAK_DOOR;
        for (int x = x0; x <= x1; x++) {
            int rise = riseAt(x, mid, span, riseMax);
            int roofY = wallTop + rise;
            for (int z = z0; z <= z1; z++) {
                boolean edgeX = x == x0 || x == x1;
                boolean edgeZ = z == z0 || z == z1;
                boolean side = edgeX || edgeZ;
                boolean corner = edgeX && edgeZ;
                put(world, x, Y, z, floor);
                for (int y = Y + 1; y <= wallTop; y++) {
                    if (!side) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                        continue;
                    }
                    int index = edgeZ ? (x - x0) : (z - z0);
                    if (corner || y == wallTop) {
                        put(world, x, y, z, pillar);
                    } else if (windows && (y == Y + 2 || y == Y + 3) && index % 4 == 2) {
                        pane(world, x, y, z, edgeZ ? BlockFace.NORTH : BlockFace.EAST);
                    } else if (wall == Material.STONE_BRICKS && hash(x, z + y * 13) % 17 == 0) {
                        put(world, x, y, z, Material.CRACKED_STONE_BRICKS);
                    } else if (wall == Material.STONE_BRICKS && hash(x, z + y * 13) % 19 == 0) {
                        put(world, x, y, z, Material.MOSSY_STONE_BRICKS);
                    } else {
                        put(world, x, y, z, wall);
                    }
                }
                if (!side) {
                    for (int y = wallTop + 1; y < roofY; y++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
                if (edgeZ) {
                    for (int y = wallTop + 1; y < roofY; y++) {
                        put(world, x, y, z, gable);
                    }
                }
                if (x == mid) {
                    log(world, x, roofY, z, beam, Axis.Z);
                } else {
                    stair(world, x, roofY, z, roof, x < mid ? BlockFace.WEST : BlockFace.EAST);
                }
            }
        }
        boolean skirtNorth = z0 != -6;
        boolean skirtSouth = z0 != -20;
        if (skirtNorth) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                int sample = Math.max(x0, Math.min(x1, x));
                stair(world, x, wallTop + riseAt(sample, mid, span, riseMax), z0 - 1, roof, BlockFace.NORTH);
            }
        }
        if (skirtSouth) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                int sample = Math.max(x0, Math.min(x1, x));
                stair(world, x, wallTop + riseAt(sample, mid, span, riseMax), z1 + 1, roof, BlockFace.SOUTH);
            }
        }
        for (int z = z0; z <= z1; z++) {
            stair(world, x0 - 1, wallTop + riseAt(x0, mid, span, riseMax), z, roof, BlockFace.WEST);
            stair(world, x1 + 1, wallTop + riseAt(x1, mid, span, riseMax), z, roof, BlockFace.EAST);
        }
        door(world, doorX, doorZ, face, doorType);
        int chimneyX = face == BlockFace.EAST ? x0 + 1 : face == BlockFace.WEST ? x1 - 1 : x1 - 1;
        int chimneyZ = face == BlockFace.SOUTH ? z0 + 1 : face == BlockFace.NORTH ? z1 - 1 : z0 + 2;
        if (chimneyX == doorX && chimneyZ == doorZ) {
            chimneyX = x0 + 1;
            chimneyZ = z1 - 1;
        }
        if ((chimneyX == TABLE_X && chimneyZ == TABLE_Z) || (chimneyX == FURNACE_X && chimneyZ == FURNACE_Z)) {
            chimneyX = x0 + 1;
            chimneyZ = z0 + 1;
        }
        int chimneyTop = wallTop + riseMax + 2;
        for (int y = Y + 1; y < chimneyTop; y++) {
            put(world, chimneyX, y, chimneyZ, Material.BRICKS);
        }
        put(world, chimneyX, chimneyTop, chimneyZ, Material.STONE_BRICKS);
        int lanternZ = (z0 + z1) / 2;
        if (lanternZ != chimneyZ || mid != chimneyX) {
            hangAt(world, mid, wallTop + riseMax - 1, lanternZ);
        }
        if (gold) {
            put(world, GOLD_BLOCK_X, Y + 2, GOLD_BLOCK_Z, Material.GOLD_BLOCK);
            put(world, GOLD_BLOCK_X, Y + 2, 0, Material.GOLD_BLOCK);
            put(world, GOLD_BLOCK_X, Y + 3, GOLD_BLOCK_Z, Material.GOLD_BLOCK);
            put(world, GOLD_BLOCK_X, Y + 3, 0, Material.GOLD_BLOCK);
        }
    }

    private static int riseAt(int x, int mid, int span, int riseMax) {
        int dx = Math.abs(x - mid);
        int rise = (int) Math.round(riseMax * (1.0 - dx / (double) (span + 1)));
        if (rise < 0) {
            return 0;
        }
        return Math.min(rise, riseMax);
    }

    private static void hang(World world, int x, int z) {
        for (int y = Y + 16; y >= Y + 3; y--) {
            if (world.getBlockAt(x, y, z).getType().isAir()) {
                continue;
            }
            hangAt(world, x, y - 1, z);
            return;
        }
    }

    private static void hangAt(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (!block.getType().isAir()) {
            return;
        }
        if (world.getBlockAt(x, y + 1, z).getType().isAir()) {
            return;
        }
        block.setType(Material.LANTERN, false);
        if (block.getBlockData() instanceof Lantern lantern) {
            lantern.setHanging(true);
            block.setBlockData(lantern, false);
        }
        placed++;
    }

    private static void portal(World world, int x, int z) {
        for (int dx = 0; dx <= 3; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                boolean frame = dx == 0 || dx == 3 || dy == 0 || dy == 4;
                Block block = world.getBlockAt(x + dx, Y + dy, z);
                if (frame) {
                    block.setType(Material.OBSIDIAN, false);
                    placed++;
                    continue;
                }
                block.setType(Material.NETHER_PORTAL, false);
                if (block.getBlockData() instanceof Orientable orientable) {
                    orientable.setAxis(Axis.X);
                    block.setBlockData(orientable, false);
                }
                placed++;
            }
        }
    }

    private static void door(World world, int x, int z, BlockFace face, Material type) {
        doorAt(world, x, Y + 1, z, face, type);
    }

    private static void doorAt(World world, int x, int y, int z, BlockFace face, Material type) {
        Block bottom = world.getBlockAt(x, y, z);
        Block top = world.getBlockAt(x, y + 1, z);
        bottom.setType(type, false);
        top.setType(type, false);
        if (bottom.getBlockData() instanceof Door lower) {
            lower.setHalf(Bisected.Half.BOTTOM);
            lower.setFacing(face);
            bottom.setBlockData(lower, false);
        }
        if (top.getBlockData() instanceof Door upper) {
            upper.setHalf(Bisected.Half.TOP);
            upper.setFacing(face);
            top.setBlockData(upper, false);
        }
    }

    private static void gate(World world, int x, int z, BlockFace face, Material type) {
        Block block = world.getBlockAt(x, Y + 1, z);
        block.setType(type, false);
        if (block.getBlockData() instanceof Gate gate) {
            gate.setFacing(face);
            block.setBlockData(gate, false);
        }
    }

    private static void stair(World world, int x, int y, int z, Material type, BlockFace face) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(type, false);
        if (block.getBlockData() instanceof Stairs stairs) {
            stairs.setFacing(face);
            stairs.setHalf(Bisected.Half.BOTTOM);
            block.setBlockData(stairs, false);
        }
        placed++;
    }

    private static void water(World world, int x, int z) {
        Block block = world.getBlockAt(x, Y, z);
        block.setType(Material.WATER, false);
        if (block.getBlockData() instanceof Levelled levelled) {
            levelled.setLevel(0);
            block.setBlockData(levelled, false);
        }
    }

    private static void cane(World world, int x, int z) {
        put(world, x, Y, z, Material.DIRT);
        put(world, x, Y + 1, z, Material.SUGAR_CANE);
        put(world, x, Y + 2, z, Material.SUGAR_CANE);
    }

    private static void wallTorch(World world, int x, int y, int z, BlockFace face) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.WALL_TORCH, false);
        if (block.getBlockData() instanceof Directional facing) {
            facing.setFacing(face);
            block.setBlockData(facing, false);
        }
    }

    private static void wallSign(World world, int x, int y, int z, BlockFace face, String... lines) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_WALL_SIGN, false);
        if (block.getBlockData() instanceof Directional facing) {
            facing.setFacing(face);
            block.setBlockData(facing, false);
        }
        writeSign(block, lines);
    }

    private static void postSign(World world, int x, int y, int z, BlockFace face, String... lines) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_SIGN, false);
        if (block.getBlockData() instanceof Rotatable rotation) {
            rotation.setRotation(face);
            block.setBlockData(rotation, false);
        }
        writeSign(block, lines);
    }

    private static void writeSign(Block block, String... lines) {
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }
        // Legacy procedural hub: keep the sign block, never write player-facing text.
        for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
            org.bukkit.block.sign.SignSide face = sign.getSide(side);
            for (int i = 0; i < 4; i++) {
                face.line(i, Component.empty());
            }
        }
        sign.setWaxed(true);
        sign.update(false, false);
    }

    private static void person(World world, int x, int z, String name, Villager.Profession profession) {
        if (world.getBlockAt(x, Y, z).getType().isAir()) {
            put(world, x, Y, z, Material.GRASS_BLOCK);
        }
        for (int y = Y + 1; y <= Y + 2; y++) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
        Villager villager = world.spawn(new org.bukkit.Location(world, x + 0.5, Y + 1, z + 0.5), Villager.class);
        villager.setProfession(profession);
        villager.setVillagerType(Villager.Type.PLAINS);
        villager.setAI(false);
        villager.setAware(false);
        villager.setSilent(true);
        villager.setInvulnerable(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCanPickupItems(false);
        villager.customName(Component.text(name, NamedTextColor.YELLOW));
        villager.setCustomNameVisible(true);
    }

    private static boolean sealed(World world) {
        if (!wet(world, 5, -12) || !wet(world, 40, 7)) {
            return false;
        }
        for (int x = 9; x <= 12; x++) {
            for (int z = 19; z <= 21; z++) {
                if (!wet(world, x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean wet(World world, int x, int z) {
        if (world.getBlockAt(x, Y, z).getType() != Material.WATER) {
            return false;
        }
        if (world.getBlockAt(x, Y - 1, z).getType().isAir()) {
            return false;
        }
        return !world.getBlockAt(x + 1, Y, z).getType().isAir()
                && !world.getBlockAt(x - 1, Y, z).getType().isAir()
                && !world.getBlockAt(x, Y, z + 1).getType().isAir()
                && !world.getBlockAt(x, Y, z - 1).getType().isAir();
    }

    private static boolean roofed(int x, int z) {
        if (x >= -28 && x <= -16 && z >= -20 && z <= -8) {
            return true;
        }
        if (x >= -28 && x <= -16 && z >= -6 && z <= 2) {
            return true;
        }
        if (x >= 9 && x <= 17 && z >= 2 && z <= 11) {
            return true;
        }
        if (x >= 11 && x <= 27 && z >= -22 && z <= -7) {
            return true;
        }
        if (x >= 50 && x <= 66 && z >= -18 && z <= -2) {
            return true;
        }
        if (x >= -14 && x <= -4 && z >= 16 && z <= 25) {
            return true;
        }
        if (x >= 20 && x <= 30 && z >= -4 && z <= 6) {
            return true;
        }
        if (x >= -9 && x <= -4 && z >= -36 && z <= -32) {
            return true;
        }
        if (x >= 35 && x <= 45 && z >= 3 && z <= 11) {
            return true;
        }
        if (x >= 45 && x <= 51 && z >= 4 && z <= 11) {
            return true;
        }
        if (x >= 6 && x <= 16 && z >= 16 && z <= 24) {
            return true;
        }
        if (x >= -58 && x <= -42 && z >= 6 && z <= 20) {
            return true;
        }
        if (Math.abs(x) <= 14 && z >= 28 && z <= 56) {
            return true;
        }
        if (x >= -24 && x <= -14 && z >= 18 && z <= 28) {
            return true;
        }
        if (x >= -72 && x <= -64 && z >= -12 && z <= 2) {
            return true;
        }
        if (Math.abs(x) <= 3 && z >= -44 && z <= 48) {
            return true;
        }
        if (z >= -14 && z <= -10 && x >= -18 && x <= 14) {
            return true;
        }
        if (z >= -3 && z <= -1 && x >= -18 && x <= 2) {
            return true;
        }
        if (x >= 3 && x <= 7 && z >= -14 && z <= -10) {
            return true;
        }
        if (x >= -4 && x <= 4 && z >= 12 && z <= 16) {
            return true;
        }
        if (x >= -8 && x <= -4 && z >= -8 && z <= -2) {
            return true;
        }
        return false;
    }

    private static boolean land(int x, int z) {
        if (!shape(x, z)) {
            return false;
        }
        if (held(x, z)) {
            return true;
        }
        if (shape(x + 1, z) && shape(x - 1, z) && shape(x, z + 1) && shape(x, z - 1)) {
            return true;
        }
        return hash(x, z) % 5 != 0;
    }

    private static boolean held(int x, int z) {
        if (Math.abs(x) <= 40 && z >= -40 && z <= 18) {
            return true;
        }
        if (Math.abs(x) <= 4 && z >= -46 && z <= 56) {
            return true;
        }
        if (x >= 34 && x <= 62 && z >= -16 && z <= 14) {
            return true;
        }
        if (x >= 6 && x <= 18 && z >= 14 && z <= 26) {
            return true;
        }
        if (x >= -60 && x <= -40 && z >= 4 && z <= 22) {
            return true;
        }
        if (Math.abs(x) <= 16 && z >= 26 && z <= 58) {
            return true;
        }
        if (x >= -74 && x <= -62 && z >= -12 && z <= 2) {
            return true;
        }
        return false;
    }

    private static boolean shape(int x, int z) {
        if (disk(x, z, 0, -6, 42, 26)) {
            return true;
        }
        if (disk(x, z, 0, 34, 28, 22)) {
            return true;
        }
        if (disk(x, z, 40, -2, 36, 24)) {
            return true;
        }
        if (disk(x, z, -42, 8, 40, 28)) {
            return true;
        }
        if (disk(x, z, 0, -92, 50, 52)) {
            return true;
        }
        if (disk(x, z, -70, -56, 18, 20)) {
            return true;
        }
        if (disk(x, z, -52, -28, 22, 18)) {
            return true;
        }
        if (disk(x, z, -48, -52, 20, 14)) {
            return true;
        }
        return Math.abs(x) <= 14 && z <= -28 && z >= -48;
    }

    private static boolean disk(int x, int z, int cx, int cz, int rx, int rz) {
        long dx = (long) (x - cx) * rz;
        long dz = (long) (z - cz) * rx;
        long reach = (long) rx * rz;
        return dx * dx + dz * dz <= reach * reach;
    }

    private static int relief(int x, int z) {
        int h = dome(x, z, 0, -36, 16, 18, 16, false);
        h = Math.max(h, dome(x, z, 0, -64, 14, 24, 32, false));
        h = Math.max(h, dome(x, z, 2, -102, 16, 18, 52, true));
        h = Math.max(h, dome(x, z, -16, -78, 10, 12, 22, true));
        h = Math.max(h, dome(x, z, 18, -96, 10, 12, 20, true));
        h = Math.max(h, dome(x, z, -66, -52, 12, 12, 24, true));
        h = Math.max(h, dome(x, z, -74, 6, 14, 12, 8, false));
        h = Math.max(h, dome(x, z, -34, -8, 10, 8, 6, false));
        h = Math.max(h, dome(x, z, -68, 28, 12, 10, 7, false));
        h = Math.max(h, dome(x, z, 58, -10, 12, 10, 8, false));
        h = Math.max(h, dome(x, z, 26, 28, 8, 6, 4, false));
        h = Math.max(h, dome(x, z, -24, 32, 8, 6, 5, false));
        if (z <= -20 && z > -50 && Math.abs(x) > 3 && h > 0 && h < 24) {
            h = (h / 3) * 3;
        }
        if (flatPad(x, z)) {
            return 0;
        }
        return h;
    }

    private static boolean flatPad(int x, int z) {
        if (Math.abs(x) <= 2 && z >= -44 && z <= 34) {
            return true;
        }
        if (Math.abs(x) <= 3 && z >= -44 && z <= -28) {
            return true;
        }
        if (x >= -30 && x <= -14 && z >= -22 && z <= 4) {
            return true;
        }
        if (x >= 8 && x <= 30 && z >= -24 && z <= 12) {
            return true;
        }
        if (x >= 34 && x <= 52 && z >= 2 && z <= 12) {
            return true;
        }
        if (x >= -4 && x <= 5 && z >= 11 && z <= 17) {
            return true;
        }
        if (x >= 3 && x <= 7 && z >= -14 && z <= -10) {
            return true;
        }
        if (x >= -14 && x <= -4 && z >= 16 && z <= 25) {
            return true;
        }
        if (x >= -60 && x <= -40 && z >= 4 && z <= 20) {
            return true;
        }
        if (Math.abs(x) <= 14 && z >= 30 && z <= 54) {
            return true;
        }
        if (x >= 6 && x <= 16 && z >= 16 && z <= 24) {
            return true;
        }
        if (x >= -8 && x <= -4 && z >= -8 && z <= -2) {
            return true;
        }
        if (x >= -8 && x <= -4 && z >= -36 && z <= -32) {
            return true;
        }
        if (z >= -14 && z <= -10 && x >= -20 && x <= 16) {
            return true;
        }
        if (z >= -3 && z <= -1 && x >= -18 && x <= 2) {
            return true;
        }
        return false;
    }

    private static void log(World world, int x, int y, int z, Material type, Axis axis) {
        Block block = world.getBlockAt(x, y, z);
        Material before = block.getType();
        block.setType(type, false);
        if (block.getBlockData() instanceof Orientable orientable) {
            orientable.setAxis(axis);
            block.setBlockData(orientable, false);
        }
        if (before != type) {
            placed++;
        }
    }

    private static void pane(World world, int x, int y, int z, BlockFace wall) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.GLASS_PANE, false);
        if (block.getBlockData() instanceof MultipleFacing pane) {
            pane.setFace(BlockFace.NORTH, wall == BlockFace.EAST || wall == BlockFace.WEST);
            pane.setFace(BlockFace.SOUTH, wall == BlockFace.EAST || wall == BlockFace.WEST);
            pane.setFace(BlockFace.EAST, wall == BlockFace.NORTH || wall == BlockFace.SOUTH);
            pane.setFace(BlockFace.WEST, wall == BlockFace.NORTH || wall == BlockFace.SOUTH);
            block.setBlockData(pane, false);
        }
        placed++;
    }

    private static int dome(int x, int z, int cx, int cz, int rx, int rz, int peak, boolean sharp) {
        double nx = (x - cx) / (double) rx;
        double nz = (z - cz) / (double) rz;
        double distance = nx * nx + nz * nz;
        if (distance >= 1.0) {
            return 0;
        }
        double t = 1.0 - Math.sqrt(distance);
        if (sharp) {
            t *= t;
        }
        return (int) Math.round(peak * t);
    }

    private static boolean cliff(int x, int z, int h) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || !land(x + dx, z + dz)) {
                    continue;
                }
                if (relief(x + dx, z + dz) <= h - 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int rim(int x, int z) {
        for (int radius = 1; radius <= 8; radius++) {
            if (!land(x + radius, z) || !land(x - radius, z) || !land(x, z + radius) || !land(x, z - radius)
                    || !land(x + radius, z + radius) || !land(x - radius, z - radius)
                    || !land(x + radius, z - radius) || !land(x - radius, z + radius)) {
                return radius;
            }
        }
        return 8;
    }

    private static void put(World world, int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == type) {
            return;
        }
        block.setType(type, false);
        if (!type.isAir()) {
            placed++;
        }
    }

    private static int hash(int x, int z) {
        int n = x * 73428767 ^ z * 912271;
        n ^= n >>> 13;
        n *= 1274126177;
        n ^= n >>> 16;
        return n & 0x7fffffff;
    }
    // ===== layout 4: landmarks, light and colour =====
    private static void grandeur(World world) {
        // Lamp posts down both roads and along the market street.
        int[] road = {10, 18, 22, 26, -10, -19, -25};
        for (int z : road) {
            post(world, 4, z, Material.LANTERN);
            post(world, -4, z, Material.LANTERN);
        }
        for (int x = -14; x <= 10; x += 6) {
            if (x == -2) {
                continue;
            }
            post(world, x, -15, Material.LANTERN);
            post(world, x, -9, Material.LANTERN);
        }
        // Plaza flag poles: gold and Somnia blue.
        flag(world, -7, -7, Material.YELLOW_WOOL, Material.WHITE_WOOL, -1);
        flag(world, 7, -7, Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL, 1);
        flag(world, -7, 7, Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL, -1);
        flag(world, 7, 7, Material.YELLOW_WOOL, Material.WHITE_WOOL, 1);
        // Grand arch between the plaza and the market street.
        gateway(world, -8);
        // Lanterns at the doors.
        post(world, -15, -14, Material.LANTERN);
        post(world, -15, -10, Material.LANTERN);
        post(world, -15, -4, Material.LANTERN);
        post(world, -15, 0, Material.LANTERN);
        post(world, 11, 10, Material.LANTERN);
        post(world, 15, 10, Material.LANTERN);
        // Bazaar awnings get hanging lanterns.
        int[][] stalls = {{11, -19, 4}, {17, -17, 3}, {22, -19, 5}};
        for (int[] stall : stalls) {
            int post = 3 + (stall[2] % 2);
            hangAt(world, stall[0], Y + post, stall[1] + 3);
            hangAt(world, stall[0] + stall[2] - 1, Y + post, stall[1] + 3);
        }
        colosseumCrown(world);
        // Beacons: a gold beam on the summit and a red beam out of the Colosseum floor.
        beacon(world, 2, -102, Material.YELLOW_STAINED_GLASS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                put(world, dx, Y - 2, 42 + dz, Material.IRON_BLOCK);
            }
        }
        put(world, 0, Y - 1, 42, Material.BEACON);
        put(world, 0, Y, 42, Material.RED_STAINED_GLASS);
        put(world, -68, Y + 19, -6, Material.END_ROD);
    }

    private static void post(World world, int x, int z, Material light) {
        if (!land(x, z) || relief(x, z) != 0 || world.getBlockAt(x, Y, z).getType().isAir()) {
            return;
        }
        for (int y = Y + 1; y <= Y + 4; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return;
            }
        }
        put(world, x, Y + 1, z, Material.STONE_BRICK_WALL);
        link(world, x, Y + 2, z, Material.SPRUCE_FENCE);
        link(world, x, Y + 3, z, Material.SPRUCE_FENCE);
        Block block = world.getBlockAt(x, Y + 4, z);
        block.setType(light, false);
        if (block.getBlockData() instanceof Lantern lantern) {
            lantern.setHanging(false);
            block.setBlockData(lantern, false);
        }
        placed++;
    }

    private static void flag(World world, int x, int z, Material cloth, Material stripe, int dir) {
        if (!land(x, z) || world.getBlockAt(x, Y, z).getType().isAir()) {
            return;
        }
        for (int y = Y + 1; y <= Y + 8; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return;
            }
        }
        put(world, x, Y + 1, z, Material.CHISELED_STONE_BRICKS);
        for (int y = Y + 2; y <= Y + 8; y++) {
            link(world, x, y, z, Material.DARK_OAK_FENCE);
        }
        put(world, x, Y + 9, z, Material.GOLD_BLOCK);
        for (int i = 1; i <= 3; i++) {
            int fx = x + dir * i;
            if (!world.getBlockAt(fx, Y + 7, z).getType().isAir() || !world.getBlockAt(fx, Y + 8, z).getType().isAir()) {
                break;
            }
            put(world, fx, Y + 8, z, i % 2 == 0 ? stripe : cloth);
            put(world, fx, Y + 7, z, i % 2 == 0 ? cloth : stripe);
        }
    }

    private static void gateway(World world, int z) {
        for (int x = -3; x <= 3; x += 6) {
            for (int y = Y + 1; y <= Y + 6; y++) {
                if (!world.getBlockAt(x, y, z).getType().isAir()) {
                    return;
                }
            }
        }
        for (int x = -3; x <= 3; x += 6) {
            put(world, x, Y + 1, z, Material.CHISELED_STONE_BRICKS);
            for (int y = Y + 2; y <= Y + 5; y++) {
                put(world, x, y, z, brick(x, z + y));
            }
        }
        for (int x = -4; x <= 4; x++) {
            if (Math.abs(x) == 4) {
                stair(world, x, Y + 6, z, Material.STONE_BRICK_STAIRS, x < 0 ? BlockFace.EAST : BlockFace.WEST);
                continue;
            }
            put(world, x, Y + 6, z, Math.abs(x) <= 1 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS);
            put(world, x, Y + 7, z, Math.abs(x) == 3 ? Material.STONE_BRICK_SLAB : Material.STONE_BRICKS);
        }
        put(world, 0, Y + 8, z, Material.GOLD_BLOCK);
        stair(world, -1, Y + 8, z, Material.STONE_BRICK_STAIRS, BlockFace.EAST);
        stair(world, 1, Y + 8, z, Material.STONE_BRICK_STAIRS, BlockFace.WEST);
        hangAt(world, -2, Y + 5, z);
        hangAt(world, 2, Y + 5, z);
        hangAt(world, 0, Y + 5, z);
    }

    private static void colosseumCrown(World world) {
        int cz = 42;
        int[][] spots = {{13, 0}, {-13, 0}, {0, 13}, {9, 9}, {-9, 9}, {9, -9}, {-9, -9}, {12, 5}, {-12, 5}, {5, 12}, {-5, 12}};
        for (int[] spot : spots) {
            int x = spot[0];
            int z = cz + spot[1];
            int top = -1;
            for (int y = Y + 9; y >= Y + 5; y--) {
                if (!world.getBlockAt(x, y, z).getType().isAir()) {
                    top = y;
                    break;
                }
            }
            if (top < 0) {
                continue;
            }
            put(world, x, top + 1, z, Material.CHISELED_STONE_BRICKS);
            Block fire = world.getBlockAt(x, top + 2, z);
            fire.setType(Material.CAMPFIRE, false);
            if (fire.getBlockData() instanceof Campfire campfire) {
                campfire.setLit(true);
                campfire.setSignalFire(false);
                fire.setBlockData(campfire, false);
            }
            placed++;
        }
        // North gate lintel over the opening (|dx| <= 2 at z = 29).
        int z = cz - 13;
        for (int x = -3; x <= 3; x++) {
            put(world, x, Y + 7, z, Math.abs(x) <= 1 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS);
        }
        put(world, 0, Y + 8, z, Material.GOLD_BLOCK);
        hangAt(world, -2, Y + 6, z);
        hangAt(world, 2, Y + 6, z);
    }

    private static void beacon(World world, int x, int z, Material glass) {
        int top = -1;
        for (int y = Y + 80; y >= Y; y--) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                top = y;
                break;
            }
        }
        if (top < 0) {
            return;
        }
        int base = top + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = base - 1; y >= base - 12; y--) {
                    if (!world.getBlockAt(x + dx, y, z + dz).getType().isAir()) {
                        break;
                    }
                    put(world, x + dx, y, z + dz, Material.POLISHED_ANDESITE);
                }
                put(world, x + dx, base, z + dz, Material.IRON_BLOCK);
            }
        }
        put(world, x, base + 1, z, Material.BEACON);
        put(world, x, base + 2, z, glass);
    }

}

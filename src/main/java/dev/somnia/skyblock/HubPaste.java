package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Reads the hub build file (plugins/SomniaSkyblock/hub/hubN.sbh) and places it, at once or spread over ticks.
 * The file is made on the build box: an original zoned island (terrain, districts, paths) plus licensed
 * SchemsFactory pieces (see docs/HUB.md). It is a gzip stream of a block palette, chunk-ordered run-length
 * block sections, sign texts, resource spots (ore, wheat, logs) and a META json block with zones and spawn points.
 * The file stays on the server; it must not be shared or re-uploaded.
 */
final class HubPaste {
    record Ore(int x, int y, int z, Material type) {
    }

    static final class Result {
        long placed;
        int badStates;
        String meta = "";
        final List<Ore> ores = new ArrayList<>();
        /** Every sign position in the build file (the build's signs are blank decoration). */
        final List<int[]> signs = new ArrayList<>();
    }

    private HubPaste() {
    }

    static Result read(World world, File file, boolean place, Logger log) throws IOException {
        Result result = new Result();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 1 << 16), 1 << 16))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!"SBH1".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a hub build file: " + file);
            }
            int size = in.readInt();
            BlockData[] palette = new BlockData[size];
            for (int i = 0; i < size; i++) {
                String name = text(in);
                if (place && i > 0) {
                    palette[i] = parse(name);
                    if (palette[i] == null) {
                        result.badStates++;
                        log.warning("Hub block skipped (unknown on this version): " + name);
                    }
                }
            }
            int sections = in.readInt();
            for (int s = 0; s < sections; s++) {
                int ox = in.readInt();
                int oy = in.readInt();
                int oz = in.readInt();
                in.readInt();
                in.readInt();
                in.readInt();
                int chunks = in.readInt();
                for (int c = 0; c < chunks; c++) {
                    int x0 = in.readInt();
                    int y0 = in.readInt();
                    int z0 = in.readInt();
                    int w = in.readInt();
                    int h = in.readInt();
                    int l = in.readInt();
                    byte[] runs = new byte[in.readInt()];
                    in.readFully(runs);
                    if (!place) {
                        continue;
                    }
                    int[] cursor = {0};
                    int count = varint(runs, cursor);
                    int p = 0;
                    int layer = w * l;
                    for (int r = 0; r < count; r++) {
                        int length = varint(runs, cursor);
                        int index = varint(runs, cursor);
                        if (index == 0 || palette[index] == null) {
                            p += length;
                            continue;
                        }
                        BlockData data = palette[index];
                        for (int k = 0; k < length; k++, p++) {
                            int y = p / layer;
                            int rest = p - y * layer;
                            int z = rest / w;
                            int x = rest - z * w;
                            world.getBlockAt(ox + x0 + x, oy + y0 + y, oz + z0 + z).setBlockData(data, false);
                            result.placed++;
                        }
                    }
                }
                if (place) {
                    log.info("Hub section " + (s + 1) + "/" + sections + " placed (" + result.placed + " blocks so far).");
                }
            }
            int signs = in.readInt();
            for (int i = 0; i < signs; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                String[] lines = new String[4];
                for (int k = 0; k < 4; k++) {
                    lines[k] = text(in);
                }
                result.signs.add(new int[]{x, y, z});
                if (place) {
                    sign(world.getBlockAt(x, y, z), lines);
                }
            }
            int ores = in.readInt();
            for (int i = 0; i < ores; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                char kind = (char) in.readByte();
                Material type = switch (kind) {
                    case 'G' -> Material.GOLD_ORE;
                    case 'L' -> Material.LAPIS_ORE;
                    case 'R' -> Material.REDSTONE_ORE;
                    case 'D' -> Material.DIAMOND_ORE;
                    case 'I' -> Material.IRON_ORE;
                    case 'W' -> null; // hub11 META: "W" = wood/log regrow; logs are found by block type now
                    case 'O' -> Material.OAK_LOG;
                    case 'B' -> Material.BIRCH_LOG;
                    case 'K' -> Material.DARK_OAK_LOG;
                    case 'C' -> Material.COAL_ORE;
                    default -> null;
                };
                result.ores.add(new Ore(x, y, z, type));
            }
            byte[] tag = new byte[4];
            try {
                in.readFully(tag);
            } catch (java.io.EOFException none) {
                return result;
            }
            if ("META".equals(new String(tag, StandardCharsets.US_ASCII))) {
                byte[] json = new byte[in.readInt()];
                in.readFully(json);
                result.meta = new String(json, StandardCharsets.UTF_8);
            }
        }
        return result;
    }

    /** One chunk of the build file: its origin, size and run-length encoded palette indices. */
    private record Piece(int bx, int by, int bz, int w, int l, byte[] runs) {
    }

    /**
     * The whole build file in memory (still run-length encoded), placed a budget of blocks at a time
     * so a paste can be spread over many server ticks.
     */
    static final class Plan {
        final Result result = new Result();
        private BlockData[] palette;
        private final List<Piece> pieces = new ArrayList<>();
        private final List<int[]> signAt = new ArrayList<>();
        private final List<String[]> signText = new ArrayList<>();
        /** World positions of the signs written by the paste (for relabelling). */
        final List<int[]> placedSigns = new ArrayList<>();
        long total;
        private int piece;
        private int[] cursor;
        private int runsLeft = -1;
        private int runLeft;
        private int runIndex;
        private int p;
        /** Offset added to every block and sign (island templates are stored relative to the island origin). */
        int dx, dy, dz;

        /** Largest |x| or |z| any block of the file reaches (before the offset). */
        int horizontalExtent() {
            int most = 0;
            for (Piece c : pieces) {
                most = Math.max(most, Math.max(Math.abs(c.bx()), Math.abs(c.bx() + c.w() - 1)));
                most = Math.max(most, Math.max(Math.abs(c.bz()), Math.abs(c.bz() + c.l() - 1)));
            }
            return most;
        }

        /** Places up to budget blocks (or until the deadline). Returns true when everything is placed. */
        boolean step(World world, long budget, long deadlineNanos) {
            long done = 0;
            while (piece < pieces.size()) {
                Piece c = pieces.get(piece);
                if (runsLeft < 0) {
                    cursor = new int[]{0};
                    runsLeft = varint(c.runs(), cursor);
                    runLeft = 0;
                    p = 0;
                }
                int layer = c.w() * c.l();
                while (runLeft > 0 || runsLeft > 0) {
                    if (runLeft == 0) {
                        runLeft = varint(c.runs(), cursor);
                        runIndex = varint(c.runs(), cursor);
                        runsLeft--;
                        if (runIndex == 0 || palette[runIndex] == null) {
                            p += runLeft;
                            runLeft = 0;
                            continue;
                        }
                    }
                    BlockData data = palette[runIndex];
                    while (runLeft > 0) {
                        int y = p / layer;
                        int rest = p - y * layer;
                        int z = rest / c.w();
                        int x = rest - z * c.w();
                        world.getBlockAt(dx + c.bx() + x, dy + c.by() + y, dz + c.bz() + z).setBlockData(data, false);
                        p++;
                        runLeft--;
                        result.placed++;
                        if (++done >= budget || ((done & 1023) == 0 && System.nanoTime() > deadlineNanos)) {
                            return false;
                        }
                    }
                }
                piece++;
                runsLeft = -1;
            }
            for (int i = 0; i < signAt.size(); i++) {
                int[] at = signAt.get(i);
                sign(world.getBlockAt(dx + at[0], dy + at[1], dz + at[2]), signText.get(i));
                placedSigns.add(new int[]{dx + at[0], dy + at[1], dz + at[2]});
            }
            signAt.clear();
            signText.clear();
            return true;
        }

        double progress() {
            return total == 0 ? 1 : Math.min(1, result.placed / (double) total);
        }
    }

    /** Reads the build file for a paste spread over ticks (see {@link Plan#step}). */
    static Plan plan(File file, Logger log) throws IOException {
        Plan plan = new Plan();
        Result result = plan.result;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 1 << 16), 1 << 16))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!"SBH1".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a hub build file: " + file);
            }
            int size = in.readInt();
            plan.palette = new BlockData[size];
            for (int i = 0; i < size; i++) {
                String name = text(in);
                if (i > 0) {
                    plan.palette[i] = parse(name);
                    if (plan.palette[i] == null) {
                        result.badStates++;
                        log.warning("Hub block skipped (unknown on this version): " + name);
                    }
                }
            }
            int sections = in.readInt();
            for (int s = 0; s < sections; s++) {
                int ox = in.readInt();
                int oy = in.readInt();
                int oz = in.readInt();
                in.readInt();
                in.readInt();
                in.readInt();
                int chunks = in.readInt();
                for (int c = 0; c < chunks; c++) {
                    int x0 = in.readInt();
                    int y0 = in.readInt();
                    int z0 = in.readInt();
                    int w = in.readInt();
                    int h = in.readInt();
                    int l = in.readInt();
                    byte[] runs = new byte[in.readInt()];
                    in.readFully(runs);
                    plan.pieces.add(new Piece(ox + x0, oy + y0, oz + z0, w, l, runs));
                    int[] cur = {0};
                    int count = varint(runs, cur);
                    for (int r = 0; r < count; r++) {
                        int length = varint(runs, cur);
                        int index = varint(runs, cur);
                        if (index != 0 && plan.palette[index] != null) {
                            plan.total += length;
                        }
                    }
                }
            }
            int signs = in.readInt();
            for (int i = 0; i < signs; i++) {
                plan.signAt.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
                String[] lines = new String[4];
                for (int k = 0; k < 4; k++) {
                    lines[k] = text(in);
                }
                plan.signText.add(lines);
            }
            int ores = in.readInt();
            for (int i = 0; i < ores; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                result.ores.add(new Ore(x, y, z, oreType((char) in.readByte())));
            }
            byte[] tag = new byte[4];
            try {
                in.readFully(tag);
            } catch (java.io.EOFException none) {
                return plan;
            }
            if ("META".equals(new String(tag, StandardCharsets.US_ASCII))) {
                byte[] json = new byte[in.readInt()];
                in.readFully(json);
                result.meta = new String(json, StandardCharsets.UTF_8);
            }
        }
        return plan;
    }

    private static Material oreType(char kind) {
        return switch (kind) {
            case 'G' -> Material.GOLD_ORE;
                    case 'L' -> Material.LAPIS_ORE;
                    case 'R' -> Material.REDSTONE_ORE;
                    case 'D' -> Material.DIAMOND_ORE;
            case 'I' -> Material.IRON_ORE;
            case 'W' -> null;
            case 'O' -> Material.OAK_LOG;
            case 'B' -> Material.BIRCH_LOG;
            case 'K' -> Material.DARK_OAK_LOG;
            case 'C' -> Material.COAL_ORE;
            default -> null;
        };
    }

    private static void sign(Block block, String[] lines) {
        // Signs never talk to players: build-file sign texts are not written (the sign blocks stay blank).
        if (block != null) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        for (int k = 0; k < 4; k++) {
            sign.getSide(Side.FRONT).line(k, Component.text(lines[k]));
        }
        sign.setWaxed(true);
        sign.update(true, false);
    }

    private static BlockData parse(String name) {
        try {
            return Bukkit.createBlockData(name);
        } catch (IllegalArgumentException wrongState) {
            int cut = name.indexOf('[');
            if (cut < 0) {
                return null;
            }
            try {
                return Bukkit.createBlockData(name.substring(0, cut));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    private static String text(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int varint(byte[] data, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = data[cursor[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }
}

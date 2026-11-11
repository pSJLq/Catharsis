package dev.somnia.skyblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Optional starter-island template: plugins/SomniaSkyblock/island/starter.sbh (same SBH1 format as the hub
 * build file). Block coordinates and every META position are relative to the island origin, where
 * (0, 0, 0) is world (islandX, GROUND_Y, islandZ). See /workspace/hubbuild/ISLAND_SPEC.md.
 */
final class IslandTemplate {
    /** Islands are protected within ±RADIUS of their origin, so a template may not reach further. */
    static final int MAX_SIZE = IslandManager.RADIUS;

    final File file;
    /** spawn: x, y, z, yaw, pitch (relative). */
    double[] spawn = {0.5, 1, 0.5, 0, 0};
    /** Guide NPC: x, y, z, yaw (relative), or null for the automatic spot near the spawn. */
    double[] npc;
    /** Portal centre (relative), only used for the "Travel to" hologram; the portal blocks are in the file. */
    int[] portal;
    String portalDest = "hub";
    int[] minion;
    int[] chest;
    /** Portal frame box (relative x0,y0,z0,x1,y1,z1); blocks inside cannot be broken. */
    int[] portalFrame;
    int size;
    /** First-visit chat tips for template islands (META "tips"); empty = a short generic set. */
    java.util.List<String> tips = new java.util.ArrayList<>();

    private IslandTemplate(File file) {
        this.file = file;
    }

    /** Reads and validates the template, or returns null (with a log line) when it is absent or unusable. */
    static IslandTemplate load(File file, Logger log) {
        if (!file.isFile()) {
            return null;
        }
        try {
            HubPaste.Plan plan = HubPaste.plan(file, log);
            IslandTemplate t = new IslandTemplate(file);
            String meta = plan.result.meta;
            JsonObject root = meta == null || meta.isBlank() ? new JsonObject() : JsonParser.parseString(meta).getAsJsonObject();
            if (root.has("spawn")) {
                JsonObject s = root.getAsJsonObject("spawn");
                t.spawn = new double[]{s.get("x").getAsDouble(), s.get("y").getAsDouble(), s.get("z").getAsDouble(),
                        s.has("yaw") ? s.get("yaw").getAsDouble() : 0, s.has("pitch") ? s.get("pitch").getAsDouble() : 0};
            }
            if (root.has("npc")) {
                JsonArray a = root.getAsJsonArray("npc");
                t.npc = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.size() > 3 ? a.get(3).getAsDouble() : 0};
            }
            t.portal = ints(root.get("portal"));
            if (root.has("portal_dest")) {
                t.portalDest = root.get("portal_dest").getAsString();
            }
            t.minion = ints(root.get("minion"));
            if (root.has("tips")) {
                for (JsonElement tip : root.getAsJsonArray("tips")) {
                    t.tips.add(tip.getAsString());
                }
            }
            t.chest = ints(root.get("chest"));
            t.portalFrame = ints(root.get("portal_frame"));
            if (t.portalFrame != null && t.portalFrame.length != 6) {
                log.warning("Island template portal_frame needs 6 numbers; ignored.");
                t.portalFrame = null;
            }
            int extent = plan.horizontalExtent();
            t.size = root.has("size") ? root.get("size").getAsInt() : extent;
            if (t.size > MAX_SIZE || extent > MAX_SIZE) {
                log.warning("Island template " + file.getName() + " reaches " + Math.max(extent, t.size) + " blocks from the origin; the limit is "
                        + MAX_SIZE + ". Using the built-in island generator.");
                return null;
            }
            if (extent > t.size) {
                log.info("Island template blocks reach " + extent + " from the origin (META size " + t.size + "); protection radius " + MAX_SIZE + " covers it, using " + extent + ".");
                t.size = extent;
            }
            log.info("Island template " + file.getName() + ": " + plan.total + " blocks, size " + t.size + ", spawn "
                    + t.spawn[0] + "," + t.spawn[1] + "," + t.spawn[2] + ", portal to " + t.portalDest
                    + (t.portalFrame == null ? "" : ", portal frame " + java.util.Arrays.toString(t.portalFrame) + " protected") + ".");
            return t;
        } catch (IOException | RuntimeException e) {
            log.warning("Island template " + file + " could not be read (" + e.getMessage() + "); using the built-in island generator.");
            return null;
        }
    }

    /** Places the template with its origin at (ox, oy, oz). Returns the number of blocks placed. */
    long paste(World world, int ox, int oy, int oz, Logger log) throws IOException {
        HubPaste.Plan plan = HubPaste.plan(file, log);
        plan.dx = ox;
        plan.dy = oy;
        plan.dz = oz;
        plan.step(world, Long.MAX_VALUE, Long.MAX_VALUE);
        return plan.result.placed;
    }

    private static int[] ints(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out.length < 3 ? null : out;
    }
}

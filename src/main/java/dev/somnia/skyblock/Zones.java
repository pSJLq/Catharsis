package dev.somnia.skyblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Named districts of the hub island, read from the META block of the hub build file.
 * A coarse grid (one letter per cell) tells which district a column belongs to; boxes such as the
 * Hollow Crypts under the mine override the grid. Mobs spawn only at the listed spawn points.
 */
final class Zones {
    record District(String id, String name, boolean combat, int cx, int cz, int r) {
    }

    record Area(String id, String name, boolean combat, int x0, int y0, int z0, int x1, int y1, int z1) {
        boolean contains(Location at) {
            return at.getBlockX() >= x0 && at.getBlockX() <= x1 && at.getBlockY() >= y0 - 1 && at.getBlockY() <= y1
                    && at.getBlockZ() >= z0 && at.getBlockZ() <= z1;
        }
    }

    /** One mob spawn point: weighted types, a cap of live mobs, a respawn delay and a time-of-day rule. */
    record Point(int index, String zone, int x, int y, int z, List<Mobs.Type> types, int[] weights, int max, int respawnSeconds, String time) {
        boolean activeAt(long worldTime) {
            boolean night = worldTime % 24000 >= 13000 && worldTime % 24000 < 23000;
            return switch (time) {
                case "night" -> night;
                case "day" -> !night;
                default -> true;
            };
        }
    }

    /**
     * A hub portal: nether-portal fill whose centre is at x,y,z and which sends players to dest. Layout 10+ also
     * gives the sheet box (min/max), its axis and the fill rows [x0,x1,y0,y1,z0,z1]; label_at is the sign spot.
     */
    record Portal(String id, int x, int y, int z, String dest, String label, int[] min, int[] max, String axis, List<int[]> rows, double[] labelAt) {
        /** True when the block is in the sheet box or right next to it. */
        boolean contains(Location at) {
            if (min == null || max == null) {
                return false;
            }
            int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
            return bx >= min[0] - 1 && bx <= max[0] + 1 && by >= min[1] - 1 && by <= max[1] + 1
                    && bz >= min[2] - 1 && bz <= max[2] + 1;
        }
    }

    /** A META region box: kind combat (mob zone), safe / interior / event (never mobs), resource, pvp. */
    record Region(String id, String kind, String district, String name, int[] min, int[] max) {
        boolean contains(Location at) {
            int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
            return bx >= min[0] && bx <= max[0] && by >= min[1] - 1 && by <= max[1] && bz >= min[2] && bz <= max[2];
        }

        long volume() {
            return (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
        }
    }

    private final List<Region> regions = new ArrayList<>();

    private final List<Portal> portals = new ArrayList<>();
    private final java.util.Map<String, int[]> warps = new java.util.HashMap<>();
    private final List<District> districts = new ArrayList<>();
    private final List<Area> areas = new ArrayList<>();
    private final List<Point> points = new ArrayList<>();
    private String[] grid = new String[0];
    private int originX;
    private int originZ;
    private int cell = 4;

    static Zones parse(String json, java.util.logging.Logger log) {
        Zones zones = new Zones();
        if (json == null || json.isEmpty()) {
            return zones;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("origin") && root.has("grid")) {
            JsonArray origin = root.getAsJsonArray("origin");
            zones.originX = origin.get(0).getAsInt();
            zones.originZ = origin.get(1).getAsInt();
            zones.cell = root.has("cell") ? root.get("cell").getAsInt() : 4;
            JsonArray rows = root.getAsJsonArray("grid");
            zones.grid = new String[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                zones.grid[i] = rows.get(i).getAsString();
            }
        }
        JsonObject ascii = root.has("names_ascii") ? root.getAsJsonObject("names_ascii") : new JsonObject();
        JsonObject names = root.has("names") ? root.getAsJsonObject("names") : new JsonObject();
        for (JsonElement element : root.getAsJsonArray("districts")) {
            JsonObject d = element.getAsJsonObject();
            zones.districts.add(new District(d.get("id").getAsString(), display(d, ascii, names),
                    combat(d), d.has("cx") ? d.get("cx").getAsInt() : 0, d.has("cz") ? d.get("cz").getAsInt() : 0, d.has("r") ? d.get("r").getAsInt() : 0));
            if (d.has("letter")) {
                zones.letters.put(d.get("letter").getAsString().charAt(0), zones.districts.size() - 1);
            }
        }
        for (JsonElement element : root.has("areas") ? root.getAsJsonArray("areas") : new JsonArray()) {
            JsonObject a = element.getAsJsonObject();
            JsonArray box = a.getAsJsonArray("box");
            zones.areas.add(new Area(a.get("id").getAsString(), display(a, ascii, names), combat(a),
                    box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt(), box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt()));
        }
        int index = 0;
        for (JsonElement element : root.has("spawns") ? root.getAsJsonArray("spawns") : new JsonArray()) {
            JsonObject s = element.getAsJsonObject();
            List<Mobs.Type> types = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            for (String part : s.get("types").getAsString().split(",")) {
                String[] kv = part.split(":");
                try {
                    types.add(Mobs.Type.valueOf(kv[0].trim()));
                    weights.add(kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1);
                } catch (IllegalArgumentException unknown) {
                    log.warning("Hub spawn point uses an unknown mob type: " + kv[0]);
                }
            }
            if (types.isEmpty()) {
                continue;
            }
            int[] w = weights.stream().mapToInt(Integer::intValue).toArray();
            zones.points.add(new Point(index++, s.get("zone").getAsString(), s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt(),
                    List.copyOf(types), w, s.get("max").getAsInt(), s.get("respawn").getAsInt(), s.get("time").getAsString()));
        }
        if (root.has("portals")) {
            for (JsonElement element : root.getAsJsonArray("portals")) {
                JsonObject p = element.getAsJsonObject();
                String label = p.has("label_ascii") ? p.get("label_ascii").getAsString() : p.has("label") ? p.get("label").getAsString() : p.get("dest").getAsString();
                int[] min = ints(p, "min");
                int[] max = ints(p, "max");
                List<int[]> rows = new ArrayList<>();
                if (p.has("rows") && p.get("rows").isJsonArray()) {
                    for (JsonElement r : p.getAsJsonArray("rows")) {
                        JsonArray a = r.getAsJsonArray();
                        rows.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt()});
                    }
                }
                double[] labelAt = null;
                if (p.has("label_at") && p.get("label_at").isJsonArray()) {
                    JsonArray a = p.getAsJsonArray("label_at");
                    labelAt = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
                }
                zones.portals.add(new Portal(p.has("id") ? p.get("id").getAsString() : "portal_" + p.get("dest").getAsString(),
                        p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt(),
                        p.get("dest").getAsString(), label, min, max, p.has("axis") ? p.get("axis").getAsString() : "x", rows, labelAt));
            }
        }
        if (root.has("regions") && root.get("regions").isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> e : root.getAsJsonObject("regions").entrySet()) {
                JsonObject r = e.getValue().getAsJsonObject();
                int[] min = ints(r, "min");
                int[] max = ints(r, "max");
                if (min == null || max == null) {
                    continue;
                }
                zones.regions.add(new Region(e.getKey(), r.has("kind") ? r.get("kind").getAsString() : "",
                        r.has("district") ? r.get("district").getAsString() : null,
                        r.has("name_ascii") ? r.get("name_ascii").getAsString() : null, min, max));
            }
        }
        if (root.has("warps")) {
            for (java.util.Map.Entry<String, JsonElement> e : root.getAsJsonObject("warps").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                zones.warps.put(e.getKey(), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            }
        }
        return zones;
    }

    private static int[] ints(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        JsonArray a = o.getAsJsonArray(key);
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    List<Region> regions() {
        return regions;
    }

    /** Smallest META region containing the location (optionally of one kind), or null. */
    Region regionAt(Location at, String kind) {
        Region best = null;
        for (Region r : regions) {
            if ((kind == null || kind.equals(r.kind())) && r.contains(at) && (best == null || r.volume() < best.volume())) {
                best = r;
            }
        }
        return best;
    }

    /** Safe, interior and event regions never get mobs. */
    boolean safeAt(Location at) {
        for (Region r : regions) {
            if (("safe".equals(r.kind()) || "interior".equals(r.kind()) || "event".equals(r.kind())) && r.contains(at)) {
                return true;
            }
        }
        return false;
    }

    /** The portal whose sheet box holds this block (layout 10+), else the nearest within 6 blocks. */
    Portal portalAt(Location at) {
        for (Portal p : portals) {
            if (p.contains(at)) {
                return p;
            }
        }
        return portalNear(at);
    }

    private static final java.util.Map<String, String> FALLBACK = java.util.Map.ofEntries(
            java.util.Map.entry("plaza", "Lumenhold"), java.util.Map.entry("colosseum", "Embercrown Colosseum"),
            java.util.Map.entry("barrows", "Dusk Thicket"), java.util.Map.entry("crypts", "Gullet Cave"),
            java.util.Map.entry("ruins", "Greyfang Citadel"), java.util.Map.entry("mountain", "Mount Veyra"),
            java.util.Map.entry("spire", "Stargazer Tower"), java.util.Map.entry("mine", "Darkvein Mine"),
            java.util.Map.entry("forest", "Elderglade"), java.util.Map.entry("farm", "Goldmeadow"),
            java.util.Map.entry("lake", "Mirror Lake"), java.util.Map.entry("wharf", "Quiet Harbor"),
            java.util.Map.entry("relics", "Hall of Relics"));

    static String fallbackName(String id) {
        return FALLBACK.getOrDefault(id, id);
    }

    /** True when a district or area with this id is defined. */
    boolean has(String id) {
        return districts.stream().anyMatch(d -> d.id().equals(id)) || areas.stream().anyMatch(a -> a.id().equals(id));
    }

    /** Display name of a district/area id (META first, then the built-in fallback). */
    String name(String id) {
        for (District d : districts) {
            if (d.id().equals(id)) {
                return d.name();
            }
        }
        for (Area a : areas) {
            if (a.id().equals(id)) {
                return a.name();
            }
        }
        return fallbackName(id);
    }

    private static boolean combat(JsonObject entry) {
        return entry.has("kind") && "combat".equals(entry.get("kind").getAsString());
    }

    /** Grid letter -> district index, when META gives each district an explicit "letter". */
    private final java.util.Map<Character, Integer> letters = new java.util.HashMap<>();

    /**
     * In-game display name: names_ascii[id], then the entry's name_ascii, then name / names[id]
     * (the builder's Russian names), then the built-in English default.
     */
    private static String display(JsonObject entry, JsonObject ascii, JsonObject names) {
        String id = entry.get("id").getAsString();
        if (ascii.has(id) && !ascii.get(id).getAsString().isBlank()) {
            return ascii.get(id).getAsString();
        }
        if (entry.has("name_ascii") && !entry.get("name_ascii").getAsString().isBlank()) {
            return entry.get("name_ascii").getAsString();
        }
        if (entry.has("name") && !entry.get("name").getAsString().isBlank()) {
            return entry.get("name").getAsString();
        }
        if (names.has(id) && !names.get(id).getAsString().isBlank()) {
            return names.get(id).getAsString();
        }
        return fallbackName(id);
    }

    List<Portal> portals() {
        return portals;
    }

    int[] warp(String id) {
        return warps.get(id);
    }

    /** Nearest portal within 6 blocks of a location, or null. */
    Portal portalNear(Location at) {
        Portal best = null;
        double bd = 36;
        for (Portal p : portals) {
            double dx = at.getX() - (p.x() + 0.5);
            double dy = at.getY() - (p.y() + 2);
            double dz = at.getZ() - (p.z() + 0.5);
            double d = dx * dx + dy * dy * 0.25 + dz * dz;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    List<Point> points() {
        return points;
    }

    List<District> districts() {
        return districts;
    }

    District district(String id) {
        for (District d : districts) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }

    /** Zone id and display name at a location, or null in the void. Areas (crypts, tunnels) win over the surface grid. */
    String[] at(Location at) {
        for (Area area : areas) {
            if (area.contains(at)) {
                return new String[]{area.id(), area.name(), area.combat() ? "combat" : "peaceful"};
            }
        }
        // META regions: a combat region is a mob zone of its district; safe/interior/event never are.
        Region combatRegion = regionAt(at, "combat");
        if (combatRegion != null && combatRegion.district() != null) {
            return new String[]{combatRegion.district(), combatRegion.name() != null ? combatRegion.name() : name(combatRegion.district()), "combat"};
        }
        Region room = null;
        for (Region r : regions) {
            if (("interior".equals(r.kind()) || "safe".equals(r.kind()) || "event".equals(r.kind())) && r.district() != null
                    && r.contains(at) && (room == null || r.volume() < room.volume())) {
                room = r;
            }
        }
        if (room != null) {
            return new String[]{room.district(), room.name() != null ? room.name() : name(room.district()), "peaceful"};
        }
        if (grid.length == 0) {
            // No grid in META: nearest district whose radius covers the point.
            District best = null;
            double bd = Double.MAX_VALUE;
            for (District d : districts) {
                double dx = at.getX() - d.cx(), dz = at.getZ() - d.cz();
                double dist = dx * dx + dz * dz;
                if (d.r() > 0 && dist <= (double) d.r() * d.r() && dist < bd) {
                    bd = dist;
                    best = d;
                }
            }
            return best == null ? null : new String[]{best.id(), best.name(), best.combat() ? "combat" : "peaceful"};
        }
        int gz = Math.floorDiv(at.getBlockZ() - originZ, cell);
        int gx = Math.floorDiv(at.getBlockX() - originX, cell);
        if (gz < 0 || gz >= grid.length || gx < 0 || gx >= grid[gz].length()) {
            return null;
        }
        char c = grid[gz].charAt(gx);
        int i = letters.isEmpty() ? c - 'a' : letters.getOrDefault(c, -1);
        if (c == '.' || i < 0 || i >= districts.size()) {
            return null;
        }
        District d = districts.get(i);
        return new String[]{d.id(), d.name(), d.combat() ? "combat" : "peaceful"};
    }

    boolean combat(String zoneId) {
        for (Area area : areas) {
            if (area.id().equals(zoneId)) {
                return area.combat();
            }
        }
        District d = district(zoneId);
        return d != null && d.combat();
    }
}

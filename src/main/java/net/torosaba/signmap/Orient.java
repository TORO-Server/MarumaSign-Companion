package net.torosaba.signmap;

/**
 * 看板ブロックの向きの符号。MarumaSign 本体は、立て看板 (rotation 0-15) と壁掛け看板 (facing) の画像だけを描く。
 * <ul>
 *   <li>0..15: 立て看板の rotation (0 が南、1 増えるごとに 22.5 度、西へ回る)</li>
 *   <li>16..19: 壁掛け看板の向き (16 南、17 西、18 北、19 東)</li>
 *   <li>-1: 向きが分からない、または画像を描かない種類 (吊り看板など)</li>
 * </ul>
 */
public final class Orient {

    public static final int NONE = -1;

    private Orient() {
    }

    public static boolean isWall(int orient) {
        return orient >= 16;
    }

    public static boolean valid(int orient) {
        return orient >= 0 && orient < 20;
    }

    /** MarumaSign 本体と同じ「看板の向き」(度) */
    public static float yaw(int orient) {
        return orient >= 16 ? (orient - 16) * 90f : orient * 22.5f;
    }

    /** ブロックの名前と状態 (rotation, facing) から符号を作る。画像を描かない種類なら NONE。状態がなければ初期値 (立て看板は 0) */
    public static int fromState(String blockName, String rotation, String facing) {
        return fromState(blockName, rotation, facing, 0);
    }

    /**
     * @param defaultRotation 立て看板で rotation の指定がないとき (初期値のブロック) の rotation。
     *                        壁掛け看板の facing の初期値は north
     */
    public static int fromState(String blockName, String rotation, String facing, int defaultRotation) {
        if (blockName == null) return NONE;
        String name = blockName;
        final int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        if (name.contains("hanging")) return NONE;
        if (name.endsWith("_wall_sign") || name.equals("wall_sign")) {
            final int f = facingIndex(facing == null ? "north" : facing);
            return f < 0 ? NONE : 16 + f;
        }
        if (name.endsWith("_sign") || name.equals("sign")) {
            if (rotation == null) return defaultRotation;
            try {
                final int r = Integer.parseInt(rotation.trim());
                return r >= 0 && r < 16 ? r : NONE;
            } catch (NumberFormatException e) {
                return NONE;
            }
        }
        return NONE;
    }

    /** 壁掛け看板の向き。Direction.toYRot() の順 (南 0、西 90、北 180、東 270) の番号 */
    public static int facingIndex(String facing) {
        if (facing == null) return -1;
        return switch (facing) {
            case "south" -> 0;
            case "west" -> 1;
            case "north" -> 2;
            case "east" -> 3;
            default -> -1;
        };
    }
}

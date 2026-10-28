package net.torosaba.signmap;

/**
 * 看板の文字列 (MarumaSign の形式)。
 * フォーマット: URL|X|Y|Z|横幅|高さ|Z軸回転|Y軸回転|X軸回転
 * MarumaSign 本体の SignSpec と同じ規則で判定する。
 */
public record MarumaSpec(String url, float[] values) {

    public static final int PARAMETER_COUNT = 9;
    public static final float MAX_VALUE = 1000f;

    /** MarumaSign の形式なら内容、そうでなければ null */
    public static MarumaSpec parse(String text) {
        if (!looksLikeSign(text)) return null;
        final String[] p = text.split("\\|");
        if (p.length < PARAMETER_COUNT) return null;
        final float[] values = new float[PARAMETER_COUNT - 1];
        for (int i = 0; i < values.length; i++) {
            final float value;
            try {
                value = Float.parseFloat(p[i + 1]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (Float.isNaN(value) || Float.isInfinite(value)) return null;
            values[i] = Math.max(-MAX_VALUE, Math.min(MAX_VALUE, value));
        }
        return new MarumaSpec(p[0], values);
    }

    private static boolean looksLikeSign(String text) {
        if (text == null || text.length() < 2 * PARAMETER_COUNT - 1) return false;
        int count = 0;
        for (int i = 0, len = text.length(); i < len; i++) {
            if (text.charAt(i) == '|' && ++count >= PARAMETER_COUNT - 1) return true;
        }
        return false;
    }
}

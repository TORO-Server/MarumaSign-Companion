package net.torosaba.signmap;

/** リンクの状態。PENDING は未検査、UNKNOWN は判定できない (一時的な失敗など)、BROKEN は確定したリンク切れ */
public enum LinkStatus {
    PENDING, OK, UNKNOWN, BROKEN
}

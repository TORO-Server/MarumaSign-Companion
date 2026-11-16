// 3D 表示のスクリプトのテスト: node src/test/js/marumasign3d.test.js [MarumaSign 本体が出した基準データ(JSON)]
"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const m = require(path.join(__dirname, "../../main/resources/web/marumasign3d.js"));

const limits = {maxSize: 64, maxOffset: 64};
const kindCount = 8;

// ---- URL ----
for (const ok of ["https://example.com/a.png", "https://i.imgur.com///x.png?a=1&b=2", "https://sub.example.co.jp:8443/x"]) {
    assert.ok(m.safeUrl(ok, true), ok);
}
assert.ok(m.safeUrl("http://example.com/a", false));
assert.strictEqual(m.safeUrl("http://example.com/a", true), null, "画像は https のみ");
for (const bad of [
    "javascript:alert(1)", "data:text/html,<script>1</script>", "file:///etc/passwd", "ftp://example.com/a", "//example.com/a",
    "https://user:pw@example.com/a", "https://user@example.com/a", "https://localhost/a", "https://a.localhost/a",
    "https://127.0.0.1/a", "https://192.168.0.1/a", "https://[::1]/a", "https://2130706433/a", "https://printer.local/a",
    "https://router/a", "https://example.com:22/a", "https://example.com:6379/a", "https://example.com/a b", "https://example.com/\"x",
    "https://example.com/<x>", "https://example.com/a\\b", "https://example.com/a`b", "https://example.com/a'b",
    "https://exa mple.com/", "", null, undefined, 5, {}, "https://" + "a".repeat(2100) + ".com/"]) {
    assert.strictEqual(m.safeUrl(bad, false), null, String(bad));
}

// ---- データの 1 件 ----
const good = [10, 64, -20, 4, 0, "https://example.com/a.png", "https://example.com/a.png", 0, 0, 0, 2, 3, 0, 90, 0];
const s = m.parseSign(good, kindCount, limits);
assert.ok(s);
assert.strictEqual(s.image, "https://example.com/a.png");
const reject = (mutate) => { const a = good.slice(); mutate(a); return m.parseSign(a, kindCount, limits); };
assert.strictEqual(reject(a => a[6] = "javascript:alert(1)"), null);
assert.strictEqual(reject(a => a[0] = 1.5), null);
assert.strictEqual(reject(a => a[0] = "1"), null);
assert.strictEqual(reject(a => a[4] = 99), null);
assert.strictEqual(reject(a => a[3] = 20), null);
assert.strictEqual(reject(a => a[9] = NaN), null);
assert.strictEqual(reject(a => a[9] = Infinity), null);
assert.strictEqual(reject(a => a[9] = "0"), null);
assert.strictEqual(m.parseSign(good.slice(0, 14), kindCount, limits), null);
assert.strictEqual(m.parseSign("x", kindCount, limits), null);
assert.strictEqual(m.parseSign(null, kindCount, limits), null);
// 画像の URL が危なければ、画像だけ使わない (ラベルは残す)。向きが分からなければ画像にしない
assert.strictEqual(reject(a => a[5] = "http://example.com/a.png").image, null);
assert.strictEqual(reject(a => a[5] = "https://127.0.0.1/a.png").image, null);
assert.strictEqual(reject(a => a[3] = -1).image, null);
// 大きさと位置のずれには上限がかかる (空を覆うような悪戯を防ぐ)
const huge = reject(a => { a[7] = 1000; a[8] = -1000; a[9] = 999; a[10] = 1000; a[11] = -1000; });
assert.strictEqual(huge.ox, 64); assert.strictEqual(huge.oy, -64); assert.strictEqual(huge.oz, 64);
assert.strictEqual(huge.sx, 64); assert.strictEqual(huge.sy, -64);
assert.strictEqual(reject(a => a[13] = 99999).yaw, 1000);

// ---- 表示用 ----
assert.strictEqual(m.displayHost("https://Example.com:8443/a?b#c"), "example.com");
assert.strictEqual(m.displayHost("not a url"), "");
assert.ok(m.shorten("https://example.com/" + "a".repeat(200), 36).length <= 36);

// ---- 幾何: 基本のケース ----
{
    // 回転 0 の立て看板、1x1 の正方形: 看板の南側から見える。ブロックの中心 (x 0..1, y 0..1, z ≈ 0.4975)
    const sign = {ox: 0, oy: 0, oz: 0, sx: 1, sy: 1, roll: 0, yaw: 0, pitch: 0, orient: 0};
    const mesh = m.buildMesh(sign, 100, 100);
    const p = mesh.positions;
    const xs = [], ys = [];
    for (let i = 0; i < 4; i++) { xs.push(p[i * 3]); ys.push(p[i * 3 + 1]); assert.ok(Math.abs(p[i * 3 + 2] - 0.4975) < 1e-6); }
    assert.ok(Math.abs(Math.min(...xs)) < 1e-6 && Math.abs(Math.max(...xs) - 1) < 1e-6);
    assert.ok(Math.abs(Math.min(...ys)) < 1e-6 && Math.abs(Math.max(...ys) - 1) < 1e-6);
    // 壁掛け (南向き): 北側の壁に付く
    const wall = m.buildMesh({...sign, orient: 16}, 100, 100);
    assert.ok(Math.abs(wall.positions[2] - (0.5 - 0.4375 - 0.0025)) < 1e-6, String(wall.positions[2]));
    // 縦横比: 200x100 は横が 2 倍 (長い辺が指定の大きさ)
    const wide = m.buildMesh(sign, 200, 100).positions;
    const wx = [wide[0], wide[3], wide[6], wide[9]];
    assert.ok(Math.abs(Math.max(...wx) - Math.min(...wx) - 1) < 1e-6);
}

// ---- MarumaSign 本体 (SignImage.java) との突き合わせ ----
const ref = process.argv[2] || path.join(__dirname, "reference-mesh.json");
// ---- 看板の板を消す ----
{
    // タイルの位置 (-30, 0, -2014)。看板はブロック (-28, 69, -2010) = タイルの中では (2, 69, 4)
    const cells = new Set([m.blockKey(-28, 69, -2010)]);
    const tri = (...p) => p;
    const positions = new Float32Array([
        // 看板の板 (ブロックの中): 消す
        ...tri(2.1, 69.5, 4.875, 2.9, 69.5, 4.875, 2.9, 69.9, 4.875),
        // 地面の上面 (ブロックの下の境界 y=69 に乗る): 残す
        ...tri(2, 69, 4, 3, 69, 4, 3, 69, 5),
        // 隣のブロックの側面 (x=2 の境界に乗る): 残す
        ...tri(2, 69, 4, 2, 70, 4, 2, 70, 5),
        // 看板でないブロックの中: 残す
        ...tri(5.1, 69.5, 4.5, 5.9, 69.5, 4.5, 5.9, 69.9, 4.5),
        // ブロックからはみ出す三角形: 残す
        ...tri(1.5, 69.5, 4.5, 2.9, 69.5, 4.5, 2.9, 69.9, 4.5),
    ]);
    const before = Array.from(positions);
    assert.strictEqual(m.hideBoards(positions, -30, 0, -2014, cells), 1);
    // 消した三角形は 1 点に潰れている
    assert.deepStrictEqual(Array.from(positions.slice(0, 9)), [2.1, 69.5, 4.875, 2.1, 69.5, 4.875, 2.1, 69.5, 4.875].map(Math.fround));
    assert.deepStrictEqual(Array.from(positions.slice(9)), before.slice(9), "ほかの三角形はそのまま");
    // 2 回目は何もしない
    assert.strictEqual(m.hideBoards(positions, -30, 0, -2014, cells), 0);
    assert.strictEqual(m.hideBoards(new Float32Array(before), -30, 0, -2014, new Set()), 0, "看板がなければ何もしない");
}

if (ref && fs.existsSync(ref)) {
    const cases = JSON.parse(fs.readFileSync(ref, "utf8"));
    let worst = 0;
    for (const c of cases) {
        const sign = {ox: c.v[0], oy: c.v[1], oz: c.v[2], sx: c.v[3], sy: c.v[4], roll: c.v[5], yaw: c.v[6], pitch: c.v[7], orient: c.orient};
        const out = m.buildMesh(sign, c.w, c.h);
        for (let i = 0; i < 8; i++) {
            const o = i * 8; // x, y, z, u, v, nx, ny, nz
            const exp = [c.mesh[o], c.mesh[o + 1], c.mesh[o + 2], c.mesh[o + 3], c.mesh[o + 4]];
            const got = [out.positions[i * 3], out.positions[i * 3 + 1], out.positions[i * 3 + 2], out.uvs[i * 2], out.uvs[i * 2 + 1]];
            for (let k = 0; k < 5; k++) {
                const d = Math.abs(exp[k] - got[k]);
                worst = Math.max(worst, d);
                assert.ok(d < 2e-4, `case ${JSON.stringify(c.v)} orient ${c.orient} vertex ${i} component ${k}: expected ${exp[k]} got ${got[k]}`);
            }
        }
    }
    console.log(`reference check: ${cases.length} cases match MarumaSign (max difference ${worst.toExponential(2)})`);
} else {
    console.log("reference check skipped (no reference file)");
}
console.log("marumasign3d.js: all checks passed");


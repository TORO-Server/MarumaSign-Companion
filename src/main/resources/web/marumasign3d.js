/*
 * MarumaSignCompanion: MarumaSign の看板の画像を、BlueMap の 3D の空間に置く。
 *
 * 位置・大きさ・回転の計算は、MarumaSign 本体 (SignImage.java) と同じ。
 * 看板データは webroot/marumasign/<地図の id>.json (プラグインが書く)。
 *
 * 安全のための決まり (看板は誰でも書けて、これは公開サイトで動く):
 *  - innerHTML / eval などは使わない。文字は textContent、要素は createElement だけで作る。
 *  - リンクと画像の URL は、https/http (リンクのみ) だけ。ユーザー情報つき・怪しいホスト・怪しいポートは使わない。
 *  - 画像は fetch (CORS・Cookie なし・Referer なし・転送は追わない) で読み、種類と大きさを確かめてから、縮めたテクスチャにする。
 *  - 看板の数値は有限の値に収め、画像の大きさと位置のずれには上限をかける (空を覆うような悪戯を防ぐ)。
 *  - 同時に読む数・描く数・テクスチャの量に上限をかける。
 */
(function () {
    "use strict";

    // ---------------------------------------------------------------- 純粋な関数 (Node でもテストする)

    const MAX_URL_LENGTH = 2000;
    const SAFE_PORTS = new Set(["", "80", "443", "8080", "8443"]);
    const IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/avif"]);
    const MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    const MAX_IMAGE_PIXELS = 100 * 1000 * 1000;
    const FACE_OFFSET = 0.005;
    const WALL_OFFSET = 0.4375 * 2;
    const ORDER = [0, 1, 2, 3, 3, 2, 1, 0];
    const U = [1, 1, 0, 0];
    const V = [1, 0, 0, 1];

    function isFiniteNumber(v) {
        return typeof v === "number" && Number.isFinite(v);
    }

    function clamp(v, lo, hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * http(s) の URL として使ってよければ正規化した文字列、だめなら null。
     * @param httpsOnly {boolean} true なら https のみ
     */
    function safeUrl(value, httpsOnly) {
        if (typeof value !== "string" || value.length === 0 || value.length > MAX_URL_LENGTH) return null;
        for (let i = 0; i < value.length; i++) {
            const c = value.charCodeAt(i);
            if (c <= 0x20 || c === 0x7f || c === 0x22 || c === 0x27 || c === 0x3c || c === 0x3e || c === 0x5c || c === 0x60) return null;
        }
        let u;
        try {
            u = new URL(value);
        } catch (e) {
            return null;
        }
        if (u.protocol !== "https:" && !(u.protocol === "http:" && !httpsOnly)) return null;
        if (u.username || u.password) return null;
        if (!SAFE_PORTS.has(u.port)) return null;
        const host = u.hostname.toLowerCase();
        if (!host || host.indexOf(".") < 0) return null;
        if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")
            || host.endsWith(".lan") || host.endsWith(".home.arpa")) return null;
        // IP アドレスそのもののホストは使わない (サーバー側でも除いているが、念のため)
        if (/^[0-9.]+$/.test(host) || host.indexOf(":") >= 0 || host.startsWith("[")) return null;
        return u.href;
    }

    /** 表示用に短くしたホスト名 (punycode のまま) */
    function displayHost(url) {
        try {
            return new URL(url).hostname;
        } catch (e) {
            return "";
        }
    }

    function shorten(url, max) {
        let s = String(url);
        const i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        if (s.length <= max) return s;
        const head = Math.floor(max * 2 / 3);
        const tail = max - head - 1;
        return s.substring(0, head) + "…" + s.substring(s.length - tail);
    }

    /**
     * 看板データの 1 件を確かめて内部の形にする。だめなら null。
     * 配列: [x, y, z, 向き, 種類, 画像の URL, リンクの URL, 数値 8 つ]
     */
    function parseSign(raw, kindCount, limits) {
        if (!Array.isArray(raw) || raw.length < 15) return null;
        for (let i = 0; i < 5; i++) if (!Number.isInteger(raw[i])) return null;
        if (typeof raw[5] !== "string" || typeof raw[6] !== "string") return null;
        const v = [];
        for (let i = 7; i < 15; i++) {
            if (!isFiniteNumber(raw[i])) return null;
            v.push(clamp(raw[i], -1000, 1000));
        }
        const orient = raw[3];
        const kind = raw[4];
        if (orient < -1 || orient > 19) return null;
        if (kind < 0 || kind >= kindCount) return null;
        const link = safeUrl(raw[6], false);
        if (!link) return null;
        const image = raw[5] === "" || orient < 0 ? null : safeUrl(raw[5], true);
        return {
            x: raw[0], y: raw[1], z: raw[2],
            orient: orient, kind: kind, image: image, link: link,
            // 位置のずれ、横幅、高さ、Z/Y/X 回転。大きさとずれには上限をかける
            ox: clamp(v[0], -limits.maxOffset, limits.maxOffset),
            oy: clamp(v[1], -limits.maxOffset, limits.maxOffset),
            oz: clamp(v[2], -limits.maxOffset, limits.maxOffset),
            sx: clamp(v[3], -limits.maxSize, limits.maxSize),
            sy: clamp(v[4], -limits.maxSize, limits.maxSize),
            roll: v[5], yaw: v[6], pitch: v[7]
        };
    }

    function isWall(orient) {
        return orient >= 16;
    }

    function signYaw(orient) {
        return orient >= 16 ? (orient - 16) * 90 : orient * 22.5;
    }

    /** MarumaSign 本体の SignImage.rotate() */
    function rotatePoint(sign, x, y, z) {
        let a = sign.pitch * Math.PI / 180;
        let sin = Math.sin(a), cos = Math.cos(a);
        let ty = y * cos - z * sin;
        let tz = y * sin + z * cos;
        y = ty;
        z = tz;

        a = -sign.yaw * Math.PI / 180;
        sin = Math.sin(a);
        cos = Math.cos(a);
        let tx = x * cos + z * sin;
        tz = -x * sin + z * cos;
        x = tx;
        z = tz;

        a = -sign.roll * Math.PI / 180;
        sin = Math.sin(a);
        cos = Math.cos(a);
        tx = x * cos - y * sin;
        ty = x * sin + y * cos;
        x = tx;
        y = ty;

        return [x + sign.ox * 2, y + sign.oy * 2, z + sign.oz * 2];
    }

    /**
     * 看板の画像の頂点 (MarumaSign 本体の SignImage.buildMesh() と同じ。表 4 頂点、裏 4 頂点)。
     * ブロックの角を原点とした座標と UV を返す。
     * @param imgW,imgH 画像の大きさ (縦横比だけ使う)
     */
    function buildMesh(sign, imgW, imgH) {
        let max = Math.max(imgW, imgH);
        if (!(max > 0)) max = 1;
        const vx = imgW / max * sign.sx;
        const vy = imgH / max * sign.sy;
        const base = [
            rotatePoint(sign, -vx, -vy, 0),
            rotatePoint(sign, -vx, +vy, 0),
            rotatePoint(sign, +vx, +vy, 0),
            rotatePoint(sign, +vx, -vy, 0)
        ];
        const wall = isWall(sign.orient);
        const moveZ = wall ? WALL_OFFSET : 0;
        const angle = (-signYaw(sign.orient) - 180) * Math.PI / 180;
        const sin = Math.sin(angle), cos = Math.cos(angle);
        const positions = new Float32Array(8 * 3);
        const uvs = new Float32Array(8 * 2);
        for (let i = 0; i < 8; i++) {
            const v = ORDER[i];
            const front = i < 4;
            const x = base[v][0];
            const y = base[v][1];
            const z = base[v][2] + moveZ + (front ? FACE_OFFSET : -FACE_OFFSET);
            positions[i * 3] = (x * cos + z * sin) * 0.5 + 0.5;
            positions[i * 3 + 1] = y * 0.5 + 0.5;
            positions[i * 3 + 2] = (-x * sin + z * cos) * 0.5 + 0.5;
            uvs[i * 2] = U[v];
            uvs[i * 2 + 1] = V[v];
        }
        return {positions: positions, uvs: uvs};
    }

    /** 看板から見える距離 (MarumaSign 本体の viewDistance と同じ式。上限は設定) */
    function viewDistance(sign, cap) {
        let maxSq = 0;
        const half = [[-sign.sx, -sign.sy], [-sign.sx, sign.sy], [sign.sx, sign.sy], [sign.sx, -sign.sy]];
        for (const p of half) {
            const q = rotatePoint(sign, p[0], p[1], 0);
            maxSq = Math.max(maxSq, q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
        }
        return Math.min(cap, Math.min(512, 64 + Math.sqrt(maxSq) * 2));
    }

    const EXPORTS = {
        safeUrl: safeUrl, parseSign: parseSign, buildMesh: buildMesh, rotatePoint: rotatePoint, viewDistance: viewDistance,
        displayHost: displayHost, shorten: shorten, signYaw: signYaw, isWall: isWall, clamp: clamp,
        IMAGE_TYPES: IMAGE_TYPES, MAX_IMAGE_BYTES: MAX_IMAGE_BYTES, MAX_IMAGE_PIXELS: MAX_IMAGE_PIXELS
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = EXPORTS;
        return;
    }
    if (typeof window === "undefined" || typeof document === "undefined") return;

    // ---------------------------------------------------------------- ブラウザー側

    if (window.marumasign3d) return;
    if (/[?&]marumasign3d=off\b/.test(window.location.search)) return;

    const POLL_MILLIS = 60 * 1000;
    const UPDATE_MILLIS = 400;
    const CELL = 32;
    const LABEL_DISTANCE = 96;
    const MAX_CONCURRENT_LOADS = 3;
    const FETCH_TIMEOUT_MILLIS = 20 * 1000;
    const TEXTURE_PIXEL_BUDGET = 48 * 1024 * 1024;

    let T = null;            // BlueMap.Three
    let app = null;          // window.bluemap
    let group = null;        // 3D オブジェクトを入れる入れ物 (mapViewer.markers の子)
    let settings = null;     // データファイルの設定
    let kinds = [];
    let signs = [];
    let grid = new Map();
    let mapId = null;
    let lastText = null;
    let lastCamera = null;
    let dirty = true;
    let enabled = true;
    const active = new Map();   // 看板 → {mesh, label}
    const textures = new Map(); // URL → {state, tex, pixels, last, aspect}
    const loadQueue = [];
    let loading = 0;
    let infoBox = null;

    function redraw() {
        try {
            if (app && app.mapViewer && app.mapViewer.redraw) app.mapViewer.redraw();
        } catch (e) { /* 描き直しを頼めなくても動く */ }
    }

    // ---- データ

    function dataUrl(id) {
        return "marumasign/" + encodeURIComponent(id) + ".json";
    }

    function loadData() {
        const map = app && app.mapViewer && app.mapViewer.map;
        if (!map || !map.data || typeof map.data.id !== "string") return Promise.resolve();
        const id = map.data.id;
        return fetch(dataUrl(id), {cache: "no-cache", credentials: "same-origin"})
            .then(function (res) {
                if (!res.ok) throw new Error("HTTP " + res.status);
                return res.text();
            })
            .then(function (text) {
                if (id === mapId && text === lastText) return;
                const json = JSON.parse(text);
                applyData(id, json);
                lastText = text;
            })
            .catch(function () {
                if (id !== mapId) clearSigns();
            });
    }

    function num(v, def, lo, hi) {
        return isFiniteNumber(v) ? clamp(v, lo, hi) : def;
    }

    function applyData(id, json) {
        if (!json || json.format !== 1 || !Array.isArray(json.signs) || !Array.isArray(json.kinds)) throw new Error("bad data");
        const limits = {
            maxSize: num(json.maxSize, 64, 1, 1000),
            maxOffset: num(json.maxOffset, 64, 1, 1000)
        };
        settings = {
            drawDistance: num(json.drawDistance, 192, 16, 512),
            maxActive: num(json.maxActive, 200, 10, 1000),
            textureSize: num(json.textureSize, 1024, 64, 4096),
            limits: limits
        };
        kinds = json.kinds.map(function (k) {
            return Array.isArray(k) ? [String(k[0]).slice(0, 8), String(k[1]).slice(0, 40)] : ["", ""];
        });
        const parsed = [];
        for (const raw of json.signs) {
            const s = parseSign(raw, kinds.length, limits);
            if (s) parsed.push(s);
        }
        clearSigns();
        mapId = id;
        signs = parsed;
        grid = new Map();
        for (const s of signs) {
            s.cap = viewDistance(s, settings.drawDistance);
            const key = cellKey(Math.floor(s.x / CELL), Math.floor(s.z / CELL));
            let list = grid.get(key);
            if (!list) grid.set(key, list = []);
            list.push(s);
        }
        dirty = true;
    }

    function cellKey(cx, cz) {
        return cx + "," + cz;
    }

    function clearSigns() {
        for (const s of Array.from(active.keys())) deactivate(s);
        signs = [];
        grid = new Map();
        mapId = null;
        lastText = null;
        redraw();
    }

    // ---- 画像の読み込み (fetch で読み、種類と大きさを確かめて、縮めたテクスチャにする)

    function readLimited(res, controller) {
        const length = Number(res.headers.get("content-length"));
        if (length > MAX_IMAGE_BYTES) return Promise.reject(new Error("too large"));
        if (!res.body || !res.body.getReader) return Promise.reject(new Error("no stream"));
        const reader = res.body.getReader();
        const chunks = [];
        let total = 0;
        const pump = function () {
            return reader.read().then(function (r) {
                if (r.done) return chunks;
                total += r.value.length;
                if (total > MAX_IMAGE_BYTES) {
                    controller.abort();
                    throw new Error("too large");
                }
                chunks.push(r.value);
                return pump();
            });
        };
        return pump();
    }

    function fetchTexture(url, size) {
        const controller = new AbortController();
        const timer = setTimeout(function () { controller.abort(); }, FETCH_TIMEOUT_MILLIS);
        return fetch(url, {
            mode: "cors", credentials: "omit", referrerPolicy: "no-referrer", redirect: "error",
            cache: "force-cache", signal: controller.signal
        }).then(function (res) {
            if (!res.ok) throw new Error("HTTP " + res.status);
            const type = (res.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
            if (!IMAGE_TYPES.has(type)) throw new Error("not an image");
            return readLimited(res, controller).then(function (chunks) {
                return createImageBitmap(new Blob(chunks, {type: type}));
            });
        }).then(function (bmp) {
            try {
                const w = bmp.width, h = bmp.height;
                if (!(w > 0 && h > 0) || w * h > MAX_IMAGE_PIXELS) throw new Error("bad size");
                const scale = Math.min(1, size / Math.max(w, h));
                const cw = Math.max(1, Math.round(w * scale)), ch = Math.max(1, Math.round(h * scale));
                const canvas = document.createElement("canvas");
                canvas.width = cw;
                canvas.height = ch;
                const ctx = canvas.getContext("2d");
                ctx.drawImage(bmp, 0, 0, cw, ch);
                return {canvas: canvas, width: w, height: h, pixels: cw * ch};
            } finally {
                if (bmp.close) bmp.close();
            }
        }).finally(function () {
            clearTimeout(timer);
        });
    }

    function requestTexture(url, then) {
        let t = textures.get(url);
        if (!t) {
            t = {state: "new", tex: null, pixels: 0, last: Date.now(), aspect: null, waiting: []};
            textures.set(url, t);
            loadQueue.push(url);
            pumpQueue();
        }
        t.last = Date.now();
        if (t.state === "ok" || t.state === "fail") {
            then(t);
        } else {
            t.waiting.push(then);
        }
    }

    function pumpQueue() {
        while (loading < MAX_CONCURRENT_LOADS && loadQueue.length > 0) {
            // 新しく頼まれたものから読む (カメラの近くのものが後から頼まれるため)
            const url = loadQueue.pop();
            const t = textures.get(url);
            if (!t || t.state !== "new") continue;
            t.state = "loading";
            loading++;
            fetchTexture(url, settings ? settings.textureSize : 1024).then(function (r) {
                const tex = new T.CanvasTexture(r.canvas);
                tex.flipY = false;
                if (T.SRGBColorSpace) tex.colorSpace = T.SRGBColorSpace;
                tex.generateMipmaps = true;
                tex.minFilter = T.LinearMipmapLinearFilter;
                tex.magFilter = T.LinearFilter;
                tex.wrapS = tex.wrapT = T.ClampToEdgeWrapping;
                t.tex = tex;
                t.aspect = [r.width, r.height];
                t.pixels = r.pixels;
                t.state = "ok";
            }).catch(function () {
                t.state = "fail";
            }).finally(function () {
                loading--;
                const waiting = t.waiting;
                t.waiting = [];
                for (const f of waiting) {
                    try { f(t); } catch (e) { /* 1 件の失敗で他を止めない */ }
                }
                evictTextures();
                pumpQueue();
            });
        }
    }

    function evictTextures() {
        let total = 0;
        for (const t of textures.values()) total += t.pixels;
        if (total <= TEXTURE_PIXEL_BUDGET) return;
        const inUse = new Set();
        for (const s of active.keys()) if (s.image) inUse.add(s.image);
        const candidates = [];
        for (const [url, t] of textures) if (t.state === "ok" && !inUse.has(url)) candidates.push([url, t]);
        candidates.sort(function (a, b) { return a[1].last - b[1].last; });
        for (const c of candidates) {
            if (total <= TEXTURE_PIXEL_BUDGET) break;
            total -= c[1].pixels;
            c[1].tex.dispose();
            textures.delete(c[0]);
        }
    }

    // ---- 3D の画像と、画像にできない看板のラベル

    function addMesh(entry, s, t) {
        if (entry.mesh || active.get(s) !== entry || !t.tex) return;
        const m = buildMesh(s, t.aspect[0], t.aspect[1]);
        const geometry = new T.BufferGeometry();
        geometry.setAttribute("position", new T.BufferAttribute(m.positions, 3));
        geometry.setAttribute("uv", new T.BufferAttribute(m.uvs, 2));
        // 表: 0,1,2,3 裏: 4,5,6,7 (MarumaSign 本体と同じ頂点の順。裏向きの面は描かない)
        geometry.setIndex([0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7]);
        geometry.computeBoundingSphere();
        const material = new T.MeshBasicMaterial({map: t.tex, side: T.FrontSide, transparent: true, alphaTest: 0.02});
        const mesh = new T.Mesh(geometry, material);
        mesh.position.set(s.x, s.y, s.z);
        mesh.frustumCulled = true;
        mesh.onClick = function () {
            showInfo(s);
            return true;
        };
        entry.mesh = mesh;
        group.add(mesh);
        removeLabel(entry);
        redraw();
    }

    function makeLabel(s) {
        const kind = kinds[s.kind] || ["", ""];
        const box = document.createElement("div");
        box.style.cssText = "background:rgba(30,30,30,.85);color:#fff;padding:3px 7px;border-radius:4px;font:12px/1.3 sans-serif;"
            + "white-space:nowrap;box-shadow:0 1px 4px rgba(0,0,0,.6);pointer-events:auto;cursor:pointer";
        const title = document.createElement("div");
        title.textContent = (kind[0] + " " + kind[1]).trim();
        const sub = document.createElement("div");
        sub.style.cssText = "font-size:10px;opacity:.8";
        sub.textContent = shorten(s.link, 36);
        box.appendChild(title);
        box.appendChild(sub);
        box.addEventListener("click", function (ev) {
            ev.stopPropagation();
            showInfo(s);
        });
        return box;
    }

    function addLabel(entry, s) {
        if (entry.label || entry.mesh || !app || !T) return;
        const CSS2D = window.BlueMap && window.BlueMap.CSS2DObject;
        if (typeof CSS2D !== "function") return;
        // CSS2DObject は、親のある要素を包む作りなので、入れ物に入れてから渡す
        const holder = document.createElement("div");
        const el = makeLabel(s);
        holder.appendChild(el);
        const obj = new CSS2D(el);
        obj.position.set(s.x + 0.5, s.y + 0.5, s.z + 0.5);
        entry.label = obj;
        group.add(obj);
    }

    function removeLabel(entry) {
        if (entry.label) {
            group.remove(entry.label);
            entry.label = null;
        }
    }

    function activate(s, dist) {
        const entry = {mesh: null, label: null};
        active.set(s, entry);
        if (s.image) {
            requestTexture(s.image, function (t) {
                if (t.state === "ok") addMesh(entry, s, t);
                else if (active.get(s) === entry && dist < LABEL_DISTANCE) addLabel(entry, s);
            });
        } else if (dist < LABEL_DISTANCE) {
            addLabel(entry, s);
        }
    }

    function deactivate(s) {
        const entry = active.get(s);
        if (!entry) return;
        active.delete(s);
        if (entry.mesh) {
            group.remove(entry.mesh);
            entry.mesh.geometry.dispose();
            entry.mesh.material.dispose();
        }
        removeLabel(entry);
    }

    // ---- クリックしたときの案内 (自動では開かない。開く前にホスト名を見せる)

    function showInfo(s) {
        closeInfo();
        const link = safeUrl(s.link, false);
        if (!link) return;
        const kind = kinds[s.kind] || ["", ""];
        const box = document.createElement("div");
        box.style.cssText = "position:fixed;left:50%;bottom:24px;transform:translateX(-50%);z-index:2147483000;max-width:min(92vw,520px);"
            + "background:rgba(25,25,28,.96);color:#fff;border-radius:8px;padding:10px 14px;font:13px/1.4 sans-serif;"
            + "box-shadow:0 4px 16px rgba(0,0,0,.6)";
        const head = document.createElement("div");
        head.style.cssText = "font-weight:bold;margin-bottom:4px";
        head.textContent = (kind[0] + " " + kind[1]).trim() + " (" + s.x + ", " + s.y + ", " + s.z + ")";
        const host = document.createElement("div");
        host.style.cssText = "opacity:.85;word-break:break-all";
        host.textContent = displayHost(link);
        const path = document.createElement("div");
        path.style.cssText = "opacity:.6;font-size:11px;word-break:break-all;margin-bottom:8px";
        path.textContent = shorten(link, 90);
        const open = document.createElement("a");
        open.textContent = "開く / Open";
        open.href = link;
        open.target = "_blank";
        open.rel = "noopener noreferrer nofollow";
        open.style.cssText = "color:#8cf;margin-right:16px";
        const close = document.createElement("a");
        close.textContent = "閉じる / Close";
        close.href = "#";
        close.style.cssText = "color:#ccc";
        close.addEventListener("click", function (ev) {
            ev.preventDefault();
            closeInfo();
        });
        box.appendChild(head);
        box.appendChild(host);
        box.appendChild(path);
        box.appendChild(open);
        box.appendChild(close);
        document.body.appendChild(box);
        infoBox = box;
    }

    function closeInfo() {
        if (infoBox && infoBox.parentNode) infoBox.parentNode.removeChild(infoBox);
        infoBox = null;
    }

    // ---- カメラの近くの看板だけを出す

    function update() {
        if (!enabled || !group || !app || !app.mapViewer || !settings) return;
        const cam = app.mapViewer.camera;
        if (!cam || !cam.position) return;
        const p = cam.position;
        if (!isFiniteNumber(p.x) || !isFiniteNumber(p.y) || !isFiniteNumber(p.z)) return;
        if (!dirty && lastCamera && Math.abs(lastCamera[0] - p.x) < 2 && Math.abs(lastCamera[1] - p.y) < 2 && Math.abs(lastCamera[2] - p.z) < 2) return;
        lastCamera = [p.x, p.y, p.z];
        dirty = false;

        const reach = Math.min(512, settings.drawDistance);
        const minX = Math.floor((p.x - reach) / CELL), maxX = Math.floor((p.x + reach) / CELL);
        const minZ = Math.floor((p.z - reach) / CELL), maxZ = Math.floor((p.z + reach) / CELL);
        const near = [];
        if ((maxX - minX + 1) * (maxZ - minZ + 1) > 4096) return;
        for (let cx = minX; cx <= maxX; cx++) {
            for (let cz = minZ; cz <= maxZ; cz++) {
                const list = grid.get(cellKey(cx, cz));
                if (!list) continue;
                for (const s of list) {
                    const dx = s.x + 0.5 - p.x, dy = s.y + 0.5 - p.y, dz = s.z + 0.5 - p.z;
                    const d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d <= s.cap) near.push([d, s]);
                }
            }
        }
        near.sort(function (a, b) { return a[0] - b[0]; });
        if (near.length > settings.maxActive) near.length = settings.maxActive;

        const keep = new Set();
        for (const n of near) keep.add(n[1]);
        for (const s of Array.from(active.keys())) if (!keep.has(s)) deactivate(s);
        // 近いものから先に読み込みを頼む (読み込みは新しく頼んだものから始まるので、遠いものを先に頼む)
        for (let i = near.length - 1; i >= 0; i--) {
            const s = near[i][1];
            if (!active.has(s)) activate(s, near[i][0]);
        }
        redraw();
    }

    // ---- 起動

    function attach() {
        if (!app || !app.mapViewer || !app.mapViewer.markers) return false;
        T = window.BlueMap && window.BlueMap.Three;
        if (!T || !T.Group) return false;
        if (!group) {
            group = new T.Group();
            group.name = "marumasign3d";
        }
        if (group.parent !== app.mapViewer.markers) app.mapViewer.markers.add(group);
        return true;
    }

    function start() {
        app = window.bluemap;
        if (!app || !app.mapViewer || !app.events) {
            setTimeout(start, 500);
            return;
        }
        if (!attach()) {
            setTimeout(start, 500);
            return;
        }
        app.events.addEventListener("bluemapMapChanged", function () {
            attach();
            clearSigns();
            loadData();
        });
        document.addEventListener("keydown", function (ev) {
            if (ev.key === "Escape") closeInfo();
        });
        setInterval(function () { loadData(); }, POLL_MILLIS);
        setInterval(update, UPDATE_MILLIS);
        loadData();
    }

    window.marumasign3d = {
        enable: function () { enabled = true; if (group) group.visible = true; dirty = true; redraw(); },
        disable: function () { enabled = false; if (group) group.visible = false; redraw(); },
        reload: function () { lastText = null; return loadData(); },
        stats: function () {
            return {signs: signs.length, active: active.size, textures: textures.size, loading: loading, queued: loadQueue.length};
        }
    };

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
    else start();
})();

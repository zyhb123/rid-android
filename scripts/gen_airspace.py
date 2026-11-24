#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
海南民用运输机场净空保护区 GeoJSON 生成器（C 方案）
口径：以跑道方位定向，沿跑道方向（纵向）机场中心两端各 20km、
      垂直跑道方向（横向）两侧各 10km → 40km × 20km 矩形。
依据：《民用机场管理条例》第49条（净空保护区内禁止升放升空物体）+
      民航/地方净空保护规划通行划设口径（跑道端外20km、中心线两侧10km）。
数据：机场坐标与跑道端点取自 OurAirports 公开库（WGS84）。
注意：这是依据通行口径生成的【参考】净空圈，精确官方净空区图以机场所在地
      政府/民航地区管理局发布为准，形状可能不规则。
"""
import csv, json, math, sys, datetime

# 数据元信息（云端 JSON 与内置 JS 共用，保证两份永不分叉）
SCHEMA = "rid-airspace/v1"
SOURCE = ("民航《民用机场管理条例》第49条净空保护区禁飞升空物体 + 通行划设口径"
          "(沿跑道方向40km×垂直方向20km)；机场坐标/跑道方位取自 OurAirports 公开库(WGS84)")
DISCLAIMER = ("本数据为依据公开法规与通行口径生成的【参考】净空圈，非 UOM 官方矢量边界；"
              "实际可飞性以 UOM 平台及当地民航/政府发布的净空保护图为准，飞行前须在 UOM 报批。")

# 机场内部标识 → 中文名（仅民用运输机场；军用机场不纳入公开 App）
AIRPORTS = {
    "ZJHK": "海口美兰国际机场",
    "ZJSY": "三亚凤凰国际机场",
    "ZJQH": "琼海博鳌机场",
}
LON_EXT = 20000.0   # 纵向半长（沿跑道），米
LAT_EXT = 10000.0   # 横向半宽（垂直跑道），米
M_PER_DEG_LAT = 111320.0

def bearing(lat1, lon1, lat2, lon2):
    """从点1到点2的方位角（度，正北=0，顺时针）"""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360

def rect_for(lat0, lon0, theta_deg):
    """以(lat0,lon0)为中心、方位 theta 的 40×20km 矩形四角（WGS84 lon,lat）"""
    theta = math.radians(theta_deg)
    coslat = math.cos(math.radians(lat0))
    # 本地米坐标：x=东, y=北；u=沿跑道, v=垂直跑道
    ux, uy = math.sin(theta), math.cos(theta)
    vx, vy = math.cos(theta), -math.sin(theta)
    corners = []
    for su in (+1, -1):
        for sv in (+1, -1):
            x = su * LON_EXT * ux + sv * LAT_EXT * vx
            y = su * LON_EXT * uy + sv * LAT_EXT * vy
            lon = lon0 + x / (M_PER_DEG_LAT * coslat)
            lat = lat0 + y / M_PER_DEG_LAT
            corners.append([round(lon, 6), round(lat, 6)])
    # 排序成闭合环（按相对中心方位角排序，保证多边形不自交）
    def ang(p):
        x = (p[0]-lon0) * M_PER_DEG_LAT * coslat
        y = (p[1]-lat0) * M_PER_DEG_LAT
        return math.atan2(y, x)
    corners.sort(key=ang, reverse=True)
    corners.append(corners[0])  # 闭合
    return corners

def main(airports_csv, runways_csv, out_dir):
    import os
    os.makedirs(out_dir, exist_ok=True)
    ap = {r["ident"]: r for r in csv.DictReader(open(airports_csv)) if r["ident"] in AIRPORTS}
    rw = list(csv.DictReader(open(runways_csv)))
    features = []
    for ident, cn in AIRPORTS.items():
        a = ap[ident]
        lat0, lon0 = float(a["latitude_deg"]), float(a["longitude_deg"])
        # 收集该机场非关闭跑道的方位
        heads = []
        for w in rw:
            if w["airport_ident"] != ident or w["closed"] == "1":
                continue
            try:
                lelat, lelon = float(w["le_latitude_deg"]), float(w["le_longitude_deg"])
                helat, helon = float(w["he_latitude_deg"]), float(w["he_longitude_deg"])
                heads.append((bearing(lelat, lelon, helat, helon), w["le_ident"]+"/"+w["he_ident"]))
            except (ValueError, KeyError):
                continue
        if not heads:
            print("⚠️ %s 无可用跑道方位，跳过" % cn, file=sys.stderr); continue
        # 平行跑道（方位差<2°）净空圈重合，去重只保留一条，避免冗余 feature
        uniq = []
        for theta, rw_name in heads:
            if all(abs(theta-u[0]) > 2 for u in uniq):
                uniq.append((theta, rw_name))
        # 每条（去重后的）跑道一个矩形
        for theta, rw_name in uniq:
            ring = rect_for(lat0, lon0, theta)
            features.append({
                "type": "Feature",
                "geometry": {"type": "Polygon", "coordinates": [ring]},
                "properties": {
                    "zoneType": "restricted",
                    "name": "%s 净空保护区（参考）" % cn,
                    "level": "管制空域·无人机飞行须报批",
                    "desc": DISCLAIMER,
                    "source": SOURCE,
                    "runway": rw_name,
                    "bearingDeg": round(theta, 1),
                    "note": "参考圈，官方净空图以政府/民航地区管理局发布为准",
                },
            })
            print("  %-12s 跑道%-7s 方位%6.1f° → 矩形已生成" % (cn, rw_name, theta))
    today = datetime.date.today().isoformat()
    fc = {"type": "FeatureCollection", "features": features}
    # ① 云端热更版（带 schema/updatedAt/source 元数据，AirspaceManager 约定格式）
    cloud = dict(fc)
    cloud.update({
        "schema": SCHEMA, "updatedAt": today, "source": SOURCE,
        "_说明": DISCLAIMER,
    })
    cloud_path = os.path.join(out_dir, "airspace.json")
    json.dump(cloud, open(cloud_path, "w"), ensure_ascii=False, indent=2)
    # ② APK 内置版（window.RID_AIRSPACE 包装，老 WebView file:// 下可直读）
    js_path = os.path.join(out_dir, "rid_airspace.js")
    header = ("/*\n * rid_airspace.js —— 海南民用运输机场净空保护区（参考）\n"
              " * 坐标系 WGS84，渲染时 rid_app.html 的 w2g() 转 GCJ-02。\n"
              " * 由 scripts/gen_airspace.py 生成，勿手改；与 release-assets/airspace.json 同源。\n"
              " * " + DISCLAIMER + "\n * 生成日期 " + today + "\n */\n")
    with open(js_path, "w") as f:
        f.write(header)
        f.write("window.RID_AIRSPACE = ")
        f.write(json.dumps(fc, ensure_ascii=False, indent=2))
        f.write(";\n")
    print("✅ %d 个净空区 → %s" % (len(features), cloud_path))
    print("✅ 内置同源 → %s" % js_path)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])

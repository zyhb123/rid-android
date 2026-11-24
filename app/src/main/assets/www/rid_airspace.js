/*
 * rid_airspace.js —— 空域图层数据包（禁飞区 / 管控区）
 * ------------------------------------------------------------------
 * 加载方式：rid_app.html 用 <script src="rid_airspace.js"> 注入，
 *           老 WebView 在 file:// 下也能稳定读取（避开 XHR 跨域限制）。
 * 坐标系  ：源数据一律 WGS84；渲染时由 rid_app.html 的 w2g() 转 GCJ-02，
 *           与高德底图、无人机/飞手 marker 对齐。
 * 字段    ：window.RID_AIRSPACE = FeatureCollection
 *   properties.zoneType: 'no_fly'(禁飞区,红) | 'restricted'(管控区,橙)
 *   properties.name / level / desc / source
 * 🔴 数据源红线：边界必须以 UOM（民航局无人驾驶航空器综合管理平台）发布为准。
 *   当前内置为【示例占位】，禁止用于实际飞行判断；权威数据到位后整体替换本文件。
 * 热更    ：云端可下发同名结构覆盖 window.RID_AIRSPACE（预留，后续接 OTA/配置接口）。
 */
window.RID_AIRSPACE = {
  "_meta": {
    "schema": "rid-airspace/v1",
    "crs": "WGS84",
    "source": "示例数据-禁止用于实际飞行-待UOM权威源替换",
    "updatedAt": "2026-11-24",
    "note": "zoneType: no_fly=禁飞区(红) / restricted=管控区(橙)。WGS84 源，渲染经 w2g() 转 GCJ-02。"
  },
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": {
        "name": "示例禁飞区 A（待权威数据替换）",
        "zoneType": "no_fly",
        "level": "示例",
        "desc": "禁飞区：禁止一切无人机飞行。此为界面演示占位，边界非真实，严禁据此飞行。",
        "source": "示例"
      },
      "geometry": {
        "type": "Polygon",
        "coordinates": [[
          [110.30, 19.72],
          [110.40, 19.72],
          [110.40, 19.80],
          [110.30, 19.80],
          [110.30, 19.72]
        ]]
      }
    },
    {
      "type": "Feature",
      "properties": {
        "name": "示例管控区 B（待权威数据替换）",
        "zoneType": "restricted",
        "level": "示例",
        "desc": "管控区：限高/需报备，按 UOM 公布条件飞行。此为界面演示占位，边界非真实。",
        "source": "示例"
      },
      "geometry": {
        "type": "Polygon",
        "coordinates": [[
          [110.42, 19.62],
          [110.54, 19.62],
          [110.54, 19.70],
          [110.42, 19.70],
          [110.42, 19.62]
        ]]
      }
    }
  ]
};


# release-assets — 发布资产（随 GitHub Release 上传）

## airspace.json（空域数据热更）

APP 启动后自动从 `https://github.com/zyhb123/rid-android/releases/latest/download/airspace.json`
拉取本文件，成功则替换 APK 内置示例空域，失败/离线则静默回退内置数据，不影响主监测功能。

### 更新空域的操作步骤
1. 从 UOM 平台（https://uom.caac.gov.cn/）查询海南真实禁飞区/管控区边界；
2. 按 `airspace.json` 的结构整理（可让 AI 协助把截图/经纬度转成 GeoJSON）；
3. 改好本文件，随版本一起（或单独）发布到 GitHub Release，**资产文件名必须保持 `airspace.json`**；
4. 用户下次打开 APP 即自动生效，无需重装。

### 字段约定
| 字段 | 含义 |
|---|---|
| `schema` | 固定 `rid-airspace/v1` |
| `updatedAt` | 数据更新日期（APP 图例显示） |
| `source` | 数据来源（APP 图例显示），应标明 UOM |
| `features[].properties.zoneType` | `no_fly`=禁飞区(红) / `restricted`=管控区(橙) |
| `features[].properties.name/level/desc/source` | 弹窗展示文本 |
| `geometry.coordinates` | GeoJSON Polygon，**WGS-84 坐标，[经度,纬度]**，首尾点闭合 |

### 红线
- 边界必须以 UOM 权威发布为准，**禁止臆造/估算飞行限制区**；
- APP 内置的 `app/src/main/assets/www/rid_airspace.js` 仅为离线兜底示例，已三处标注"禁止用于实际飞行"。

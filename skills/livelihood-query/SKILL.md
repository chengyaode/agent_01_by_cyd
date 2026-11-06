---
name: livelihood-query
description: 民生查询功能。当用户询问天气(气温/下雨/要不要带伞/未来几天天气)或快递物流(单号轨迹/包裹到哪了/发货签收状态)时使用,调用 query_weather 与 query_express 两个工具给出准确数据。
---

# 民生查询功能

## 何时使用
- 天气类:用户提到"天气"、"气温"、"下雨/下雪"、"要不要带伞/穿衣"、"未来几天天气"等。
- 快递类:用户提到"快递"、"物流"、"包裹"、"单号"、"到哪了"、"发货/签收"等。

## 步骤
1. **天气查询**:调用 `query_weather(city, forecast_days)`。
   - city 用中文城市名即可(如"北京"、"深圳"),不需要用户提供的行政区划代码。
   - 用户只问"现在多少度"时 forecast_days 填 0;问"这几天/未来几天"时填 3(免费版上限 3 天)。
   - 用户没说什么城市、且上下文里也推不出来时,先追问城市,不要瞎猜。
2. **快递查询**:调用 `query_express(waybill_no, carrier, phone)`(ShowAPI 新版接口 64-19)。
   - waybill_no 必填:向用户要快递单号。
   - carrier 一般留空(默认 auto 自动识别);auto 识别失败或用户明确说了公司时填字母简称:
     shunfeng 顺丰 / yuantong 圆通 / zhongtong 中通 / shentong 申通 / yunda 韵达 / jitujiuye 极兔 / jd 京东 / ems / youzhengguonei 邮政。
   - 顺丰、跨越、中通、壹米滴答、信丰等公司需要**收件人手机号后 4 位**:如果返回提示需要 phone 或识别到是这类单号,先追问用户手机号后四位再调用。
3. 把工具返回的数据整理成简洁中文回答:天气给出当前状况+穿衣/带伞建议;快递给出最新一条轨迹+状态,轨迹多时只概述最近几条。

## 约束
- 数据必须来自工具返回,不要凭记忆编造天气或快递轨迹。
- 工具返回"未配置"提示时,如实告诉用户:需要在 agent.properties 里配置
  `agent.qweather.key`(和风天气,https://dev.qweather.com 免费注册)或
  `agent.showapi.appkey`(万维易源新版接口只需 appKey,https://www.showapi.com),不要反复重试调用。
- 快递涉及隐私:只查用户本人提供的单号,不要主动猜测或遍历单号。

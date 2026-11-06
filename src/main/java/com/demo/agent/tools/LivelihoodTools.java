package com.demo.agent.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Component;

import com.demo.agent.config.LivelihoodProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 民生查询工具:天气(和风天气免费版)+ 快递单号轨迹(万维易源 ShowAPI)。
 *
 * <p>密钥经 Spring 注入 {@link LivelihoodProperties}(agent.qweather.key /
 * agent.showapi.appkey),未配置时工具返回明确的提示而不是抛异常,
 * 便于模型向用户解释"需要先在 agent.properties 配置密钥"。
 *
 * <p>快递查询使用 ShowAPI 新版接口(64-19):只需 appKey 直调,无需 appsecret/签名。
 */
@Component
public class LivelihoodTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** ShowAPI(万维易源)新版网关:快递查询(接口 64-19),appKey 直调免签名 */
    private static final String SHOWAPI_EXPRESS_URL = "https://route.showapi.com/64-19";

    private final LivelihoodProperties props;

    public LivelihoodTools(LivelihoodProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 天气
    // ------------------------------------------------------------------

    @Tool(
            name = "query_weather",
            description =
                    "天气查询。用户提到 天气/气温/下雨/下雪/要不要带伞/未来几天天气 时必须直接调用本工具,"
                            + "不要调用 read_document 或 list_context_files。"
                            + "city 用中文城市名(如 北京/深圳);forecast_days 取 0-3,0 表示只看当前实况。"
                            + "返回当前实况和未来几天预报。",
            readOnly = true,
            concurrencySafe = true)
    public String queryWeather(
            @ToolParam(name = "city", description = "城市名,如: 北京 / 上海 / 深圳(中文或拼音)")
                    String city,
            @ToolParam(
                            name = "forecast_days",
                            required = false,
                            description = "附带未来几天的预报,整数 0-3;填 0 表示只要当前实况,不填默认 3")
                    Integer forecastDays) {
        String qweatherKey = props.qweatherKey();
        String base = props.qweatherBase();
        if (city == null || city.isBlank()) {
            return "请提供城市名,例如: query_weather(city=\"北京\")";
        }
        if (qweatherKey.isEmpty()) {
            return "天气查询未配置:请在 agent.properties 中设置 agent.qweather.key"
                    + "(在 https://dev.qweather.com 免费注册获取)。";
        }
        int days = forecastDays == null ? 3 : Math.max(0, Math.min(forecastDays, 3));
        try {
            // 1) 城市名 -> LocationID(取第一个匹配)
            JsonNode geo = getJson(base + "/geo/v2/city/lookup?location="
                    + enc(city.trim()) + "&key=" + enc(qweatherKey));
            if (!"200".equals(geo.path("code").asText())) {
                return "和风天气城市查询失败,code=" + geo.path("code").asText();
            }
            JsonNode loc = geo.path("location").path(0);
            if (loc.isMissingNode()) {
                return "未找到城市: " + city;
            }
            String locId = loc.path("id").asText();
            String locName = loc.path("name").asText()
                    + (loc.path("adm1").isMissingNode() ? "" : "(" + loc.path("adm1").asText() + ")");

            StringBuilder out = new StringBuilder();
            // 2) 实况
            JsonNode now = getJson(base + "/v7/weather/now?location=" + locId
                    + "&key=" + enc(qweatherKey));
            JsonNode n = now.path("now");
            out.append(locName).append(" 当前: ")
                    .append(n.path("text").asText()).append(" ")
                    .append(n.path("temp").asText()).append("℃(体感 ")
                    .append(n.path("feelsLike").asText()).append("℃), 湿度 ")
                    .append(n.path("humidity").asText()).append("%, ")
                    .append(n.path("windDir").asText()).append(" ")
                    .append(n.path("windSpeed").asText()).append("km/h");
            if (!n.path("obsTime").isMissingNode()) {
                out.append("(观测于 ").append(n.path("obsTime").asText()).append(")");
            }
            // 3) 未来预报(免费版最多 3 天)
            if (days > 0) {
                JsonNode fc = getJson(base + "/v7/weather/3d?location=" + locId
                        + "&key=" + enc(qweatherKey));
                JsonNode daily = fc.path("daily");
                if (daily.isArray() && !daily.isEmpty()) {
                    out.append("\n未来预报:");
                    int limit = Math.min(days, daily.size());
                    for (int i = 0; i < limit; i++) {
                        JsonNode d = daily.get(i);
                        out.append("\n- ").append(d.path("fxDate").asText()).append(": ")
                                .append(d.path("textDay").asText())
                                .append(", ").append(d.path("tempMin").asText()).append("~")
                                .append(d.path("tempMax").asText()).append("℃, 白天")
                                .append(d.path("windDirDay").asText());
                    }
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "天气查询失败: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // 快递
    // ------------------------------------------------------------------

    @Tool(
            name = "query_express",
            description =
                    "快递物流查询。用户提到 快递/物流/包裹/单号/到哪了/发货/签收 时必须直接调用本工具,"
                            + "不要调用 read_document 或 list_context_files。"
                            + "waybill_no 必填;carrier 默认 auto 自动识别,识别失败再传字母简称"
                            + "(shunfeng/zhongtong/yunda/jd/ems 等);"
                            + "顺丰/跨越/中通/壹米滴答/信丰 需传 phone=收件人手机号后四位。"
                            + "返回最新轨迹与签收状态。",
            readOnly = true,
            concurrencySafe = true)
    public String queryExpress(
            @ToolParam(name = "waybill_no", description = "快递单号,如 SF1234567890 / 78123456789012")
                    String waybillNo,
            @ToolParam(
                            name = "carrier",
                            required = false,
                            description =
                                    "快递公司字母简称,可不填(默认 auto 自动识别)。"
                                            + " auto 识别失败时指定,如: shunfeng顺丰 youzhengguonei邮政"
                                            + " yuantong圆通 zhongtong中通 shentong申通 yunda韵达"
                                            + " jd京东 jitujiuye极兔 ems")
                    String carrier,
            @ToolParam(
                            name = "phone",
                            required = false,
                            description =
                                    "收件人手机号后 4 位;顺丰/跨越/中通/壹米滴答/信丰必填,其它可不填")
                    String phone) {
        String appKey = props.showapiAppKey();
        if (waybillNo == null || waybillNo.isBlank()) {
            return "请提供快递单号,例如: query_express(waybill_no=\"75910000000000\")";
        }
        if (appKey.isEmpty()) {
            return "快递查询未配置:请在 agent.properties 中设置 agent.showapi.appkey"
                    + "(在 https://www.showapi.com 控制台「我的套餐」免费额度页获取 appKey,新版接口无需 appsecret)。";
        }
        try {
            StringJoiner form = new StringJoiner("&");
            form.add("nu=" + enc(waybillNo.trim()));
            form.add("com=" + enc(
                    carrier == null || carrier.isBlank() ? "auto" : carrier.trim().toLowerCase()));
            if (phone != null && !phone.isBlank()) {
                form.add("phone=" + enc(phone.trim()));
            }
            String url = SHOWAPI_EXPRESS_URL + "?appKey=" + enc(appKey);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp =
                    HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode root = MAPPER.readTree(decodeBody(resp.body()));
            int gatewayCode = root.path("showapi_res_code").asInt(-1);
            if (gatewayCode != 0) {
                return "快递网关返回错误 res_code=" + gatewayCode + ": "
                        + root.path("showapi_res_error").asText();
            }
            JsonNode body = root.path("showapi_res_body");
            int ret = body.path("ret_code").asInt(0);
            String apiMsg = body.path("msg").asText("");
            if (!body.path("flag").asBoolean(true) || ret != 0) {
                String hint = switch (ret) {
                    case 1 -> "需要补充收/寄件手机号后四位(phone 参数),或单号/参数格式有误。";
                    case 2 -> "查无此单号(可能太新未揽收或单号有误)。";
                    case 6 -> "无法自动识别快递公司,请在 carrier 参数指定字母简称"
                            + "(如 shunfeng / zhongtong / youzhengguonei)。";
                    case 7 -> "单号与手机号后四位不匹配,请核对 phone 参数"
                            + "(顺丰/跨越/中通/壹米滴答/信丰需收件人手机号后四位)。";
                    default -> "";
                };
                return "快递查询失败 ret_code=" + ret
                        + (hint.isEmpty() ? "" : ": " + hint)
                        + (apiMsg.isEmpty() ? "" : "(接口提示: " + apiMsg + ")");
            }
            StringBuilder out = new StringBuilder();
            out.append("单号 ").append(body.path("mailNo").asText(waybillNo.trim()));
            String expName = body.path("expTextName").asText("");
            if (!expName.isEmpty()) {
                out.append(", 快递公司: ").append(expName);
            }
            out.append(", 状态: ").append(statusLabel(body.path("status").asText()));
            JsonNode list = body.path("data");
            if (list.isArray() && !list.isEmpty()) {
                out.append("\n轨迹(新→旧):");
                int shown = Math.min(list.size(), 8);
                for (int i = 0; i < shown; i++) {
                    JsonNode t = list.get(i);
                    out.append("\n- ").append(t.path("time").asText())
                            .append(" ").append(t.path("context").asText());
                }
                if (list.size() > shown) {
                    out.append("\n...(共 ").append(list.size()).append(" 条)");
                }
            } else {
                out.append("\n暂无轨迹信息(单号可能有误、太新未揽收,或需要补充 phone 手机号后四位)。");
            }
            return out.toString();
        } catch (Exception e) {
            return "快递查询失败: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 64-19 接口 status 编码 -> 中文标签 */
    private static String statusLabel(String status) {
        return switch (status) {
            case "1" -> "暂无记录";
            case "2" -> "在途中";
            case "3" -> "派送中";
            case "4" -> "已签收";
            case "5" -> "拒签";
            case "6" -> "疑难";
            case "7" -> "无效";
            case "8" -> "超时";
            case "9" -> "签收失败";
            case "10" -> "退回";
            default -> "未知(" + status + ")";
        };
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return MAPPER.readTree(decodeBody(resp.body()));
    }

    /** 和风天气等接口即使未声明 Accept-Encoding 也返回 gzip,按魔数自动解压 */
    private static String decodeBody(byte[] body) throws IOException {
        if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

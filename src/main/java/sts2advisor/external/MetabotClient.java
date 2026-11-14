package sts2advisor.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 【このファイルの役割】
 * 外部サイト「MetaBot.GG」（https://metabot.gg）の公開MCPサーバーから、カードの統計を取ってくる係です。
 *
 * ・利用規約（https://metabot.gg/en/legal/terms-of-service）で、公開MCPサーバーのデータは
 *   使ってよいと書かれています。条件は次のとおりです。
 *     - 出典として、データごとの metabot.gg のページへのリンクを表示すること
 *       （そのため、返ってきたリンク（URL）も必ず保存します）
 *     - 常識的な頻度でアクセスすること（目安は1分に約60回まで）
 * ・MCP は、AI などのプログラムがデータを問い合わせるための決まった手順です。
 *   中身は「JSON-RPC」という形式の文字を送り、答えを受け取るだけです。
 */
public class MetabotClient {

    /** 取得元としてデータベースに記録する名前 */
    public static final String SOURCE_NAME = "MetaBot.GG (https://metabot.gg)";

    /** 公開MCPサーバーの場所 */
    private static final String MCP_URL = "https://metabot.gg/api/mcp";

    /** インターネットに接続して通信するための Java 標準の道具 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** 問い合わせごとに付ける番号（JSON-RPC の決まりで、問い合わせと答えを対応させるために使う） */
    private int requestId = 0;

    /**
     * カード1枚の統計を、英語のカード名で問い合わせます。
     *
     * @param nameEn 英語のカード名（例："Barricade"）
     * @return 答えの中身（JSON）。"title"（見つかったカード名）、"stats"（ティア・勝率・ピック率）、
     *         "cta"（metabot.gg のページへのリンク）などが入っている
     * @throws IOException          通信に失敗した、またはサーバーがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonObject lookupCard(String nameEn) throws IOException, InterruptedException {
        // 送る内容：「lookup_game_entity」という道具を、スレスパ2・このカード名で使いたい、という依頼
        JsonObject arguments = new JsonObject();
        arguments.addProperty("game", "slay-the-spire-2");
        arguments.addProperty("name", nameEn);
        arguments.addProperty("lang", "en");

        JsonObject params = new JsonObject();
        params.addProperty("name", "lookup_game_entity");
        params.add("arguments", arguments);

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", ++requestId);
        request.addProperty("method", "tools/call");
        request.add("params", params);

        JsonObject response = post(request.toString());
        if (response.has("error")) {
            throw new IOException("MetaBot.GG がエラーを返しました: " + response.get("error"));
        }
        // 答えの中の "structuredContent" に、整理された統計が入っている
        return response.getAsJsonObject("result").getAsJsonObject("structuredContent");
    }

    /**
     * 答えの "stats"（[{"label":"Win rate","value":"31.4%"}, ...]）から、指定した名前の値を取り出します。
     *
     * @param content lookupCard の答え
     * @param label   取り出したい値の名前（"Tier"、"Win rate"、"Pick rate" など）
     * @return 値の文字列（例："31.4%"）。なければ null
     */
    public static String statValue(JsonObject content, String label) {
        JsonArray stats = content.getAsJsonArray("stats");
        if (stats == null) {
            return null;
        }
        for (JsonElement element : stats) {
            JsonObject stat = element.getAsJsonObject();
            if (label.equals(stat.get("label").getAsString())) {
                return stat.get("value").getAsString();
            }
        }
        return null;
    }

    /**
     * "31.4%" のような文字を 31.4 という数字に直します。
     *
     * @param percent パーセントの文字（例："31.4%"）。null でもよい
     * @return 数字。直せなければ null
     */
    public static Double parsePercent(String percent) {
        if (percent == null) {
            return null;
        }
        try {
            return Double.parseDouble(percent.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 一時的なエラーのとき、やり直す最大の回数 */
    private static final int MAX_RETRIES = 3;

    /**
     * MCPサーバーに問い合わせを送り、答えを受け取ります。
     * 次のような一時的なエラーのときは、少し待ってから最大3回までやり直します。
     *   ・429（回数制限）… サーバーが指定した秒数（"Retry-After"）だけ待つ。指定がなければ60秒
     *   ・500番台（502 など、サーバー側の一時的な不調）… 30秒、60秒、90秒と、だんだん長く待つ
     *
     * @param body 送る内容（JSON-RPC の文字列）
     * @return サーバーからの答え（JSON）
     * @throws IOException          通信に失敗した、またはやり直してもエラーが続いたとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    private JsonObject post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MCP_URL))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "sts2-advisor/0.1 (personal use)")
                .header("Content-Type", "application/json")
                // MCPサーバーは、答えを「イベントストリーム」という形式で返してくることがある
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                return parseBody(response.body());
            }

            // 一時的なエラーかどうか（429 か 500番台なら一時的とみなす）
            boolean temporary = status == 429 || status >= 500;
            if (!temporary || attempt >= MAX_RETRIES) {
                throw new IOException("MetaBot.GG がエラーを返しました（HTTPステータス " + status + "）");
            }

            long waitSeconds;
            if (status == 429) {
                waitSeconds = response.headers().firstValue("Retry-After").map(Long::parseLong).orElse(60L);
            } else {
                waitSeconds = 30L * (attempt + 1);
            }
            System.out.println("MetaBot.GG が一時的なエラー（" + status + "）を返したので、"
                    + waitSeconds + " 秒待ってやり直します");
            Thread.sleep(waitSeconds * 1000);
        }
    }

    /**
     * サーバーの答えを JSON として読みます。
     * イベントストリーム形式（"data: {...}" という行が並ぶ形）のときは、"data: " の後ろの JSON を取り出します。
     *
     * @param body サーバーから返ってきた文字列
     * @return 答えの JSON
     * @throws IOException 答えの中に JSON が見つからなかったとき
     */
    private static JsonObject parseBody(String body) throws IOException {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            // ふつうの JSON で返ってきた
            return JsonParser.parseString(trimmed).getAsJsonObject();
        }
        for (String line : trimmed.split("\n")) {
            if (line.startsWith("data:")) {
                return JsonParser.parseString(line.substring("data:".length()).trim()).getAsJsonObject();
            }
        }
        throw new IOException("MetaBot.GG の答えを読めませんでした");
    }
}

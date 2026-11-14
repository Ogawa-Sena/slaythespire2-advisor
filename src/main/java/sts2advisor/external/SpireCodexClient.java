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
 * 外部サイト「Spire Codex」（https://spire-codex.com）の公開APIからデータを取ってくる係です。
 *
 * ・利用規約（https://spire-codex.com/terms）で、公開APIは無料で使ってよいと書かれています。
 * ・ただし回数制限があります（登録なしで、1つのAPIにつき1分間に15回まで）。
 *   このクラスは1回の取り込みで数回しか呼ばないので、制限には引っかかりません。
 * ・API以外のページを自動で読み取ることは規約で禁止されているので、やりません。
 */
public class SpireCodexClient {

    /** 取得元としてデータベースに記録する名前 */
    public static final String SOURCE_NAME = "Spire Codex (https://spire-codex.com)";

    /** APIのURLの先頭部分 */
    private static final String BASE_URL = "https://spire-codex.com";

    /** インターネットに接続して通信するための Java 標準の道具 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * 全カードのデータを、指定した言語で取ってきます。
     *
     * @param lang 言語コード。日本語は "jpn"、英語は "eng"
     * @return カードのデータの一覧（JSON の配列。1要素がカード1枚）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonArray fetchCards(String lang) throws IOException, InterruptedException {
        String body = get("/api/cards?lang=" + lang);
        return JsonParser.parseString(body).getAsJsonArray();
    }

    /**
     * バージョンを指定して、カードデータを取ってきます（2026-11-13 追加。パッチが出たばかりのとき用）。
     * 出たばかりのバージョン（ベータ扱い）は、英語のデータしか無いことがある（v0.113.0 で確認）。
     *
     * @param lang    言語（"jpn" か "eng"。そのバージョンに無い言語は、英語で返ってくることがある）
     * @param version バージョン（例："v0.113.0"）
     * @return カードの一覧（JSON の配列）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonArray fetchCards(String lang, String version) throws IOException, InterruptedException {
        String body = get("/api/cards?lang=" + lang + "&version=" + encode(version));
        return JsonParser.parseString(body).getAsJsonArray();
    }

    /**
     * カードの統計（勝率・ピック率など）を、1つの条件について取ってきます。
     *
     * @param bracket   集計の条件。":" でつないで組み合わせる
     *                  （例："solo:a10:v0.111.0" ＝ ソロ・A10・バージョン v0.111.0 のラン）
     * @param character キャラ（例："IRONCLAD"）
     * @return 統計のデータ（JSON。"rows" の中にカードごとの統計が入っている）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonObject fetchCardMetrics(String bracket, String character) throws IOException, InterruptedException {
        String body = get("/api/runs/metrics/cards?bracket=" + bracket + "&character=" + character);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /**
     * 全レリック、または全ポーションのデータを、指定した言語で取ってきます。
     *
     * @param kind 種類。"relics"＝レリック、"potions"＝ポーション
     * @param lang 言語コード。日本語は "jpn"、英語は "eng"
     * @return データの一覧（JSON の配列。1要素が1個分。レリックにはショップの価格 merchant_price も入っている）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonArray fetchItems(String kind, String lang) throws IOException, InterruptedException {
        String body = get("/api/" + kind + "?lang=" + lang);
        return JsonParser.parseString(body).getAsJsonArray();
    }

    /**
     * レリック・ポーションなどの統計（勝率・lift など）を、1つの条件について取ってきます。
     * カードの統計（fetchCardMetrics）と同じAPIで、種類だけが違います。
     *
     * @param kind      種類。"relics"＝レリック、"potions"＝ポーション
     * @param bracket   集計の条件（例："solo:a10:v0.111.0"）
     * @param character キャラ（例："IRONCLAD"）
     * @return 統計のデータ（JSON。"rows" の中に1個ごとの統計が入っている）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonObject fetchItemMetrics(String kind, String bracket, String character)
            throws IOException, InterruptedException {
        String body = get("/api/runs/metrics/" + kind + "?bracket=" + bracket + "&character=" + character);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /**
     * ショップの統計を取ってきます。ショップに並んだカード・レリック・ポーションごとに、
     * 並んだ回数・買われた回数・買った人の勝率・lift（勝率がどれだけ上がったか）が入っています。
     * キャラ別には分かれていません。
     *
     * @param bracket 集計の条件（例："solo:v0.111.0"）
     * @return 統計のデータ（JSON。"rows" の中に1品ごとの統計が入っている）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonObject fetchShopMetrics(String bracket) throws IOException, InterruptedException {
        String body = get("/api/runs/metrics/shops?bracket=" + bracket);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /**
     * 今のデッキに合うカードを教えてもらいます（Spire Codex の「pick-coach」。MVP のステップ11）。
     * デッキとレリックを送ると、いちばん近いアーキタイプ（よくあるデッキの型）と、
     * 候補のカードごとに「近い勝ちデッキの何%がそのカードを持っているか」などが返ってきます。
     * 送るのはカード・レリックの ID だけで、個人の情報は送りません。
     *
     * @param character キャラ（例："REGENT"）
     * @param cards     デッキのカードの ID（同じカードは枚数分並べる）
     * @param relics    レリックの ID
     * @param offer     候補のカードの ID
     * @return 結果（JSON。"target" に近いアーキタイプ、"offers" に候補ごとの結果）
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public JsonObject fetchPickCoach(String character, java.util.List<String> cards, java.util.List<String> relics,
                                     java.util.List<String> offer) throws IOException, InterruptedException {
        String query = "character=" + encode(character)
                + "&cards=" + encode(String.join(",", cards))
                + "&relics=" + encode(String.join(",", relics))
                + "&offer=" + encode(String.join(",", offer))
                + "&lang=jpn";
        // プレイ中に呼ぶので、待つのは短めにする（遅いときは相性なしで表示を続ける）
        return JsonParser.parseString(get("/api/runs/pick-coach?" + query, Duration.ofSeconds(5))).getAsJsonObject();
    }

    /**
     * URL に入れられるよう、文字を変換します（"," などの記号を %2C のような形にする）。
     *
     * @param text 元の文字
     * @return 変換した文字
     */
    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 統計がそろっている、いちばん新しいゲームのバージョンを調べます。
     * 「-beta」「-rc」などが付いた試験版は除きます。
     *
     * @return バージョンの文字列（例："v0.111.0"）。見つからなければ "unknown"
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public String fetchLatestStatVersion() throws IOException, InterruptedException {
        return latestStableVersion("stat_versions");
    }

    /**
     * Spire Codex のカードデータが対応している、いちばん新しいゲームのバージョン（パッチ）を調べます。
     * 「-beta」「-rc」などが付いた試験版は除きます。
     *
     * @return バージョンの文字列（例："v0.111.0"）。見つからなければ "unknown"
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    public String fetchLatestDataVersion() throws IOException, InterruptedException {
        return latestStableVersion("data_versions");
    }

    /**
     * バージョン一覧のAPIから、指定した種類の一覧を読み、いちばん新しい正式版を選びます。
     *
     * @param listName 一覧の名前。"data_versions"＝カードデータがあるバージョン、"stat_versions"＝統計があるバージョン
     * @return バージョンの文字列（例："v0.111.0"）。見つからなければ "unknown"
     * @throws IOException          通信に失敗した、またはサイトがエラーを返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    private String latestStableVersion(String listName) throws IOException, InterruptedException {
        String body = get("/api/runs/versions");
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        JsonArray versions = json.getAsJsonArray(listName);

        String latest = null;
        for (JsonElement element : versions) {
            String version = element.getAsString();
            // 試験版（"-" が付いているもの）は使わない
            if (version.contains("-")) {
                continue;
            }
            if (latest == null || compareVersions(version, latest) > 0) {
                latest = version;
            }
        }
        return latest != null ? latest : "unknown";
    }

    /**
     * 2つのバージョン文字列を比べます（例："v0.111.0" と "v0.99.1" では v0.111.0 の方が新しい）。
     * 文字のまま比べると "0.99" の方が大きいと判定されてしまうため、数字に分けて比べます。
     *
     * @param a 比べるバージョン1（例："v0.111.0"）
     * @param b 比べるバージョン2（例："v0.99.1"）
     * @return a の方が新しければ正の数、b の方が新しければ負の数、同じなら 0
     */
    static int compareVersions(String a, String b) {
        // 先頭の "v" を取り、"." で区切って数字の配列にする
        String[] partsA = a.replaceFirst("^v", "").split("\\.");
        String[] partsB = b.replaceFirst("^v", "").split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            // 桁が足りない方は 0 とみなす（例："0.111" は "0.111.0" と同じ）
            int numA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int numB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (numA != numB) {
                return Integer.compare(numA, numB);
            }
        }
        return 0;
    }

    /**
     * 指定したAPIにアクセスして、返ってきた中身（文字列）を受け取ります。
     *
     * @param path APIのパス（例："/api/cards?lang=jpn"）
     * @return サイトから返ってきた中身（JSON の文字列）
     * @throws IOException          通信に失敗した、またはサイトが「成功（200）」以外を返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    private String get(String path) throws IOException, InterruptedException {
        return get(path, Duration.ofSeconds(60));
    }

    /**
     * 指定したAPIにアクセスして、返ってきた中身（文字列）を受け取ります（待つ時間の上限を指定する版）。
     *
     * @param path    APIのパス
     * @param timeout 返事を待つ時間の上限
     * @return サイトから返ってきた中身（JSON の文字列）
     * @throws IOException          通信に失敗した、時間切れになった、またはサイトが「成功（200）」以外を返したとき
     * @throws InterruptedException 通信の待ち時間中にプログラムが止められたとき
     */
    private String get(String path, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(timeout)
                // どのプログラムからのアクセスかを名乗る（個人情報は入れない）
                .header("User-Agent", "sts2-advisor/0.1 (personal use)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        // 200 は「成功」の意味。それ以外（429＝回数制限、500＝サイト側のエラーなど）は失敗として止める
        if (response.statusCode() != 200) {
            throw new IOException("Spire Codex がエラーを返しました: " + path
                    + "（HTTPステータス " + response.statusCode() + "）");
        }
        return response.body();
    }
}

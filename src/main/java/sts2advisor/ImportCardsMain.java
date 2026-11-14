package sts2advisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sts2advisor.db.CardDatabase;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.model.Card;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【このファイルの役割】
 * カードデータの取り込みを実行する「入口」です（機能要件 F-14 の最初の部分）。
 *
 * 処理の流れ：
 *   1. Spire Codex から、カードが対応しているゲームのバージョンを調べる
 *   2. 全カードのデータを日本語と英語で取ってくる
 *   3. カードIDを目印に、日本語と英語のデータを1枚ずつ組み合わせる
 *   4. 取得元・取得日時・バージョンと一緒に、データベース（data/sts2advisor.db）に保存する
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java
 */
public class ImportCardsMain {

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * プログラムの入口。ここから処理が始まります。
     *
     * @param args 起動時に渡す設定（今は使わない）
     * @throws Exception 通信やデータベースで失敗したとき（内容を表示して止まる）
     */
    public static void main(String[] args) throws Exception {
        SpireCodexClient client = new SpireCodexClient();

        // 1. 対応しているバージョンを調べる
        String patch = client.fetchLatestDataVersion();
        // 取得日時（秒まで。例：2026-11-11T19:30:00+09:00）
        String fetchedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        System.out.println("取得元: " + SpireCodexClient.SOURCE_NAME);
        System.out.println("対応パッチ: " + patch);

        // 2. 日本語と英語のカードデータを取ってくる
        JsonArray cardsJa = client.fetchCards("jpn");
        JsonArray cardsEn = client.fetchCards("eng");
        System.out.println("日本語のカード: " + cardsJa.size() + " 枚 / 英語のカード: " + cardsEn.size() + " 枚");

        // 3. 日本語と英語を組み合わせる
        List<Card> cards = mergeLanguages(cardsJa, cardsEn);

        // 4. データベースに保存する
        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            int saved = db.saveCards(cards, SpireCodexClient.SOURCE_NAME, fetchedAt, patch);
            System.out.println("保存したカード: " + saved + " 枚");
            System.out.println("データベース内のカード: " + db.countCards() + " 枚（" + DB_FILE.toAbsolutePath() + "）");
        }
    }

    /**
     * 日本語のカードデータと英語のカードデータを、カードIDを目印に組み合わせます。
     * 日本語のデータを基準にします（画面の表示が日本語のため）。
     *
     * @param cardsJa 日本語のカードデータの一覧
     * @param cardsEn 英語のカードデータの一覧
     * @return 組み合わせたカードの一覧
     */
    static List<Card> mergeLanguages(JsonArray cardsJa, JsonArray cardsEn) {
        // 英語のデータを「カードID → データ」の形にして、すぐ探せるようにする
        Map<String, JsonObject> enById = new HashMap<>();
        for (JsonElement element : cardsEn) {
            JsonObject en = element.getAsJsonObject();
            enById.put(en.get("id").getAsString(), en);
        }

        List<Card> cards = new ArrayList<>();
        int missingEn = 0;
        for (JsonElement element : cardsJa) {
            JsonObject ja = element.getAsJsonObject();
            String id = ja.get("id").getAsString();
            JsonObject en = enById.get(id);
            if (en == null) {
                // 英語版に同じIDがない（ふつうは起きない）。英語の項目は空のまま保存する
                missingEn++;
            }

            cards.add(new Card(
                    id,
                    text(ja, "name"),
                    en != null ? text(en, "name") : null,
                    integer(ja, "cost"),
                    bool(ja, "is_x_cost"),
                    // 種類とレアリティは、言語によらない英語のキーで保存する（比べやすいように）
                    text(ja, "type_key"),
                    text(ja, "rarity_key"),
                    text(ja, "color"),
                    text(ja, "description"),
                    en != null ? text(en, "description") : null,
                    text(ja, "upgrade_description"),
                    joinedKeywords(ja),
                    ja.toString()));
        }
        if (missingEn > 0) {
            System.out.println("注意: 英語のデータが見つからなかったカード: " + missingEn + " 枚");
        }
        return cards;
    }

    /**
     * JSON から文字の項目を取り出します。項目がない、または中身が空（null）なら null を返します。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前（例："name"）
     * @return 項目の文字列、または null
     */
    static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return (value == null || value.isJsonNull()) ? null : value.getAsString();
    }

    /**
     * JSON から数字の項目を取り出します。項目がない、または中身が空（null）なら null を返します。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前（例："cost"）
     * @return 項目の数字、または null
     */
    static Integer integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return (value == null || value.isJsonNull()) ? null : value.getAsInt();
    }

    /**
     * JSON から true/false の項目を取り出します。項目がない、または空（null）なら false とみなします。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前（例："is_x_cost"）
     * @return 項目が true なら true、それ以外は false
     */
    static boolean bool(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    /**
     * キーワードの一覧（例：["廃棄", "保留"]）を「廃棄,保留」のような1つの文字列にします。
     *
     * @param json カード1枚分の JSON
     * @return つないだ文字列。キーワードがなければ null
     */
    static String joinedKeywords(JsonObject json) {
        JsonElement value = json.get("keywords");
        if (value == null || value.isJsonNull() || value.getAsJsonArray().isEmpty()) {
            return null;
        }
        List<String> words = new ArrayList<>();
        for (JsonElement word : value.getAsJsonArray()) {
            words.add(word.getAsString());
        }
        return String.join(",", words);
    }
}

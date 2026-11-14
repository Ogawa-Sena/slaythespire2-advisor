package sts2advisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sts2advisor.db.ItemDatabase;
import sts2advisor.db.ItemDatabase.Item;
import sts2advisor.db.ItemDatabase.ItemStat;
import sts2advisor.db.ItemDatabase.ShopStat;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.game.GameFiles;
import sts2advisor.game.GamePck;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 【このファイルの役割】
 * レリックとポーションのデータ・統計、ショップの統計を取り込む「入口」です
 * （機能要件 F-05, F-14。MVP のステップ7。ショップの判断で使う）。
 *
 * 処理の流れ：
 *   1. Spire Codex から、レリックとポーションの内容（日本語・英語）を取り込む
 *   2. ゲームファイルの翻訳データと名前を照合する。違えば**ゲームファイルを正とする**（CLAUDE.md の決まり）
 *   3. Spire Codex から、レリックとポーションの統計を取り込む
 *      （ソロ・いちばん新しいパッチ・5キャラ・アセンション区分 "all" と "a10"）
 *   4. Spire Codex から、ショップの統計（品物ごとの買われた割合・lift）を取り込む
 *
 * 統計の呼び出しは 2種類 × 5キャラ × 2区分 ＋ ショップ2回 ＝ 22回です。
 * 回数制限（1つのAPIにつき1分15回）を超えないよう、1回ごとに6秒待ちます（全部で約2分半）。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ImportItemsMain
 */
public class ImportItemsMain {

    /** データベースファイルの場所（カードと同じファイル） */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /** ゲームファイルの取得元として記録する名前 */
    private static final String GAME_SOURCE = "ゲームファイル (SlayTheSpire2.pck)";

    /** 取り込むキャラ（Spire Codex での呼び名） */
    private static final List<String> CHARACTERS =
            List.of("IRONCLAD", "SILENT", "DEFECT", "NECROBINDER", "REGENT");

    /** 取り込むアセンション区分（"all"＝A0〜A10 全部、"a10"＝A10 だけ） */
    private static final List<String> ASCENSION_GROUPS = List.of("all", "a10");

    /**
     * 取り込む種類。左が Spire Codex の呼び名（API と翻訳データのファイル名）、右がデータベースでの呼び名
     */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("relics", "relic");
        KINDS.put("potions", "potion");
    }

    /** 統計のAPIを呼ぶ間に待つ時間（ミリ秒）。6秒ずつ空ければ、1分に10回までにおさまる */
    private static final long WAIT_MILLIS = 6_000;

    /**
     * プログラムの入口。
     *
     * @param args 1つ目にゲームフォルダの場所を渡せる（省略すると Steam の標準の場所）
     * @throws Exception 通信・ゲームファイル・データベースで失敗したとき（内容を表示して止まる）
     */
    public static void main(String[] args) throws Exception {
        SpireCodexClient client = new SpireCodexClient();
        String dataPatch = client.fetchLatestDataVersion();
        String statPatch = client.fetchLatestStatVersion();
        String now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        System.out.println("取得元: " + SpireCodexClient.SOURCE_NAME);
        System.out.println("内容のパッチ: " + dataPatch + "　統計のパッチ: " + statPatch);

        try (ItemDatabase db = new ItemDatabase(DB_FILE)) {
            // 1. 内容を取り込む
            for (Map.Entry<String, String> kind : KINDS.entrySet()) {
                List<Item> items = fetchItems(client, kind.getKey(), kind.getValue());
                db.saveItems(items, SpireCodexClient.SOURCE_NAME, now, dataPatch);
                System.out.println(kind.getKey() + "：内容 " + items.size() + " 個");

                // 2. ゲームファイルと名前を照合する
                syncWithGame(db, items, kind.getKey(), kind.getValue(), now);
            }

            // 3. レリック・ポーションの統計を取り込む
            for (Map.Entry<String, String> kind : KINDS.entrySet()) {
                List<ItemStat> stats = new ArrayList<>();
                for (String character : CHARACTERS) {
                    for (String group : ASCENSION_GROUPS) {
                        Thread.sleep(WAIT_MILLIS);
                        JsonObject json = client.fetchItemMetrics(kind.getKey(),
                                ImportStatsMain.toBracket(group, statPatch), character);
                        stats.addAll(parseItemStats(json, kind.getValue(), character, group, statPatch));
                    }
                }
                db.saveItemStats(stats, SpireCodexClient.SOURCE_NAME, now);
                System.out.println(kind.getKey() + "：統計 " + stats.size() + " 行");
            }

            // 4. ショップの統計を取り込む
            List<ShopStat> shopStats = new ArrayList<>();
            for (String group : ASCENSION_GROUPS) {
                Thread.sleep(WAIT_MILLIS);
                JsonObject json = client.fetchShopMetrics(ImportStatsMain.toBracket(group, statPatch));
                shopStats.addAll(parseShopStats(json, group, statPatch));
            }
            db.saveShopStats(shopStats, SpireCodexClient.SOURCE_NAME, now);
            System.out.println("ショップ：統計 " + shopStats.size() + " 行");
        }
        System.out.println("保存先: " + DB_FILE.toAbsolutePath());
    }

    /**
     * Spire Codex から、レリックかポーションの内容を日本語と英語で取ってきて、1つにまとめます。
     *
     * @param client   Spire Codex の通信係
     * @param apiKind  Spire Codex での呼び名（"relics" か "potions"）
     * @param itemType データベースでの呼び名（"relic" か "potion"）
     * @return 内容の一覧
     * @throws Exception 通信に失敗したとき
     */
    private static List<Item> fetchItems(SpireCodexClient client, String apiKind, String itemType) throws Exception {
        JsonArray ja = client.fetchItems(apiKind, "jpn");
        JsonArray en = client.fetchItems(apiKind, "eng");

        // 英語のデータを ID で引けるようにしておく
        Map<String, JsonObject> englishById = new HashMap<>();
        for (JsonElement e : en) {
            englishById.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        }

        List<Item> items = new ArrayList<>();
        for (JsonElement e : ja) {
            JsonObject j = e.getAsJsonObject();
            String id = j.get("id").getAsString();
            JsonObject eng = englishById.get(id);

            // ショップの価格は {"base":175,"min":149,"max":201} の形（無いものは null）
            Integer base = null;
            Integer min = null;
            Integer max = null;
            JsonElement price = j.get("merchant_price");
            if (price != null && price.isJsonObject()) {
                base = integer(price.getAsJsonObject(), "base");
                min = integer(price.getAsJsonObject(), "min");
                max = integer(price.getAsJsonObject(), "max");
            }

            items.add(new Item(itemType, id,
                    text(j, "name"),
                    eng != null ? text(eng, "name") : null,
                    text(j, "rarity_key"),
                    text(j, "pool"),
                    text(j, "description"),
                    eng != null ? text(eng, "description") : null,
                    base, min, max));
        }
        return items;
    }

    /**
     * ゲームファイルの翻訳データと名前を照合します。
     * 違っていれば items 表の名前をゲームの名前に書き換え、item_mismatches 表に記録します。
     *
     * @param db       データベース
     * @param items    Spire Codex から取り込んだ内容
     * @param locKind  翻訳データのファイル名（"relics" か "potions"）
     * @param itemType データベースでの呼び名（"relic" か "potion"）
     * @param now      照合した日時
     * @throws Exception ゲームファイルやデータベースで失敗したとき
     */
    private static void syncWithGame(ItemDatabase db, List<Item> items, String locKind, String itemType, String now)
            throws Exception {
        GameFiles game = new GameFiles(GameFiles.DEFAULT_GAME_DIR);
        String version = game.readVersion();
        Map<String, String> textsJa;
        Map<String, String> textsEn;
        try (GamePck pck = new GamePck(game.pckFile())) {
            textsJa = GameFiles.readLocalization(pck, "jpn", locKind);
            textsEn = GameFiles.readLocalization(pck, "eng", locKind);
        }

        // 翻訳データのキーは "ID.title" の形。".title" があるものを1個とみなす
        int gameCount = 0;
        for (String key : textsJa.keySet()) {
            if (key.endsWith(".title")) {
                String id = key.substring(0, key.length() - ".title".length());
                db.saveGameItem(itemType, id, textsJa.get(key), textsEn.get(key), GAME_SOURCE, version, now);
                gameCount++;
            }
        }

        int renamed = 0;
        int notInGame = 0;
        for (Item item : items) {
            String gameJa = textsJa.get(item.itemId() + ".title");
            String gameEn = textsEn.get(item.itemId() + ".title");
            if (gameJa == null) {
                // Spire Codex にはあるが、今のゲームには無い（削除・改名された可能性）
                db.saveMismatch(itemType, item.itemId(), "not_in_game", item.nameJa(), null, now);
                notInGame++;
                continue;
            }
            if (!Objects.equals(gameJa, item.nameJa()) || !Objects.equals(gameEn, item.nameEn())) {
                if (!Objects.equals(gameJa, item.nameJa())) {
                    db.saveMismatch(itemType, item.itemId(), "name_ja", item.nameJa(), gameJa, now);
                    System.out.println("  名前の違い: " + item.itemId() + "「" + item.nameJa() + "」→ ゲームでは「" + gameJa + "」");
                }
                if (!Objects.equals(gameEn, item.nameEn())) {
                    db.saveMismatch(itemType, item.itemId(), "name_en", item.nameEn(), gameEn, now);
                }
                db.updateItemNames(itemType, item.itemId(), gameJa, gameEn);
                renamed++;
            }
        }
        System.out.println("  ゲームファイル（" + version + "）：" + gameCount + " 個。名前を直した: " + renamed
                + " 個、ゲームに無い: " + notInGame + " 個");
    }

    /**
     * レリック・ポーションの統計の JSON を、ItemStat の一覧に直します。
     *
     * @param json           APIから返ってきた JSON 全体
     * @param itemType       データベースでの呼び名（"relic" か "potion"）
     * @param character      キャラ
     * @param ascensionGroup アセンション区分
     * @param patch          パッチ
     * @return 統計の一覧
     */
    static List<ItemStat> parseItemStats(JsonObject json, String itemType, String character,
                                         String ascensionGroup, String patch) {
        Double baseline = decimal(json, "baseline_win_rate");
        String dataThrough = text(json, "data_through");
        List<ItemStat> stats = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("rows")) {
            JsonObject row = e.getAsJsonObject();
            // 強化済みを別に集計した行は使わない（カードと同じ扱い）
            if (row.has("upgraded") && row.get("upgraded").getAsBoolean()) {
                continue;
            }
            stats.add(new ItemStat(itemType, text(row, "id"), character, ascensionGroup, patch,
                    decimal(row, "win_rate"), decimal(row, "pick_rate"), decimal(row, "hold_rate"),
                    decimal(row, "lift"), integer(row, "lift_n"), integer(row, "picks"),
                    integer(row, "wins"), integer(row, "losses"), text(row, "tier"), integer(row, "score"),
                    baseline, dataThrough));
        }
        return stats;
    }

    /**
     * ショップの統計の JSON を、ShopStat の一覧に直します。
     *
     * @param json           APIから返ってきた JSON 全体
     * @param ascensionGroup アセンション区分
     * @param patch          パッチ
     * @return 統計の一覧
     */
    static List<ShopStat> parseShopStats(JsonObject json, String ascensionGroup, String patch) {
        String dataThrough = text(json, "data_through");
        List<ShopStat> stats = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("rows")) {
            JsonObject row = e.getAsJsonObject();
            stats.add(new ShopStat(text(row, "entity_type"), text(row, "id"), ascensionGroup, patch,
                    integer(row, "seen"), integer(row, "bought"), decimal(row, "buy_rate"),
                    decimal(row, "win_rate"), decimal(row, "lift"), integer(row, "lift_n"), dataThrough));
        }
        return stats;
    }

    /**
     * JSON から文字の項目を取り出します。項目がない、または空（null）なら null を返します。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前
     * @return 項目の文字、または null
     */
    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return (value == null || value.isJsonNull()) ? null : value.getAsString();
    }

    /**
     * JSON から小数の項目を取り出します。項目がない、または空（null）なら null を返します。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前
     * @return 項目の数値、または null
     */
    private static Double decimal(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return (value == null || value.isJsonNull()) ? null : value.getAsDouble();
    }

    /**
     * JSON から整数の項目を取り出します。項目がない、または空（null）なら null を返します。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前
     * @return 項目の数値、または null
     */
    private static Integer integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return (value == null || value.isJsonNull()) ? null : value.getAsInt();
    }
}

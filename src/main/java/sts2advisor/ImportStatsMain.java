package sts2advisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sts2advisor.db.CardDatabase;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.model.CardStat;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 【このファイルの役割】
 * Spire Codex から、カードの統計（勝率・ピック率など）を取り込む「入口」です（機能要件 F-14 の一部）。
 *
 * 取り込む条件：
 *   ・ソロのランだけ（CLAUDE.md で「対象：ソロのみ」と決めているため）
 *   ・統計がそろっている、いちばん新しいパッチだけ
 *   ・キャラごと（5キャラ）
 *   ・アセンション区分は2つ："all"（A0〜A10 全部）と "a10"（A10 だけ）
 *     ※ Spire Codex の統計APIで指定できるアセンションの区分はこの2つだけです
 *
 * 呼び出し回数は 5キャラ × 2区分 ＝ 10回です。
 * 回数制限（1つのAPIにつき1分15回）を超えないよう、1回ごとに6秒待ちます（全部で約1分）。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ImportStatsMain
 */
public class ImportStatsMain {

    /** データベースファイルの場所（ImportCardsMain と同じファイル） */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /** 取り込むキャラ（Spire Codex での呼び名） */
    private static final List<String> CHARACTERS =
            List.of("IRONCLAD", "SILENT", "DEFECT", "NECROBINDER", "REGENT");

    /** 取り込むアセンション区分（"all"＝A0〜A10 全部、"a10"＝A10 だけ） */
    private static final List<String> ASCENSION_GROUPS = List.of("all", "a10");

    /** APIを呼ぶ間に待つ時間（ミリ秒）。6秒ずつ空ければ、1分に10回までにおさまる */
    private static final long WAIT_MILLIS = 6_000;

    /**
     * プログラムの入口。ここから処理が始まります。
     *
     * @param args 起動時に渡す設定（今は使わない）
     * @throws Exception 通信やデータベースで失敗したとき（内容を表示して止まる）
     */
    public static void main(String[] args) throws Exception {
        SpireCodexClient client = new SpireCodexClient();

        // 統計がそろっている、いちばん新しいパッチを調べる
        String patch = client.fetchLatestStatVersion();
        String fetchedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        System.out.println("取得元: " + SpireCodexClient.SOURCE_NAME);
        System.out.println("対応パッチ: " + patch);

        List<CardStat> allStats = new ArrayList<>();
        for (String character : CHARACTERS) {
            for (String group : ASCENSION_GROUPS) {
                // 回数制限にかからないよう、少し待ってから呼ぶ
                Thread.sleep(WAIT_MILLIS);

                String bracket = toBracket(group, patch);
                JsonObject json = client.fetchCardMetrics(bracket, character);
                List<CardStat> stats = parseRows(json, character, group, patch);
                allStats.addAll(stats);
                System.out.println(character + " / " + group + ": " + stats.size() + " 枚分");
            }
        }

        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            int saved = db.saveCardStats(allStats, SpireCodexClient.SOURCE_NAME, fetchedAt);
            System.out.println("保存した統計: " + saved + " 行（" + DB_FILE.toAbsolutePath() + "）");
        }
    }

    /**
     * 条件を Spire Codex の bracket の書き方に直します。
     *
     * @param ascensionGroup アセンション区分（"all" または "a10"）
     * @param patch          パッチ（例："v0.111.0"）
     * @return bracket の文字列（例："solo:v0.111.0" や "solo:a10:v0.111.0"）
     */
    static String toBracket(String ascensionGroup, String patch) {
        // "all" のときはアセンションの指定を付けない（＝A0〜A10 全部）
        if (ascensionGroup.equals("all")) {
            return "solo:" + patch;
        }
        return "solo:" + ascensionGroup + ":" + patch;
    }

    /**
     * APIから返ってきた統計の JSON を、CardStat の一覧に直します。
     *
     * @param json           APIから返ってきた JSON 全体
     * @param character      キャラ
     * @param ascensionGroup アセンション区分
     * @param patch          パッチ
     * @return カードごとの統計の一覧
     */
    static List<CardStat> parseRows(JsonObject json, String character, String ascensionGroup, String patch) {
        // 表全体に共通の値（全ランの平均勝率、キャラのラン数、集計した日時）
        Double baselineWinRate = decimal(json, "baseline_win_rate");
        Integer characterRuns = integer(json, "character_runs");
        String dataThrough = json.has("data_through") && !json.get("data_through").isJsonNull()
                ? json.get("data_through").getAsString() : null;

        List<CardStat> stats = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("rows")) {
            JsonObject row = element.getAsJsonObject();

            // 強化済みのカードを別に集計した行が来た場合は、今は使わない（今回の調査では0件だった）
            if (row.has("upgraded") && row.get("upgraded").getAsBoolean()) {
                continue;
            }

            // 勝率の信頼区間は [下限, 上限] の2つの数字で来る
            Double ciLow = null;
            Double ciHigh = null;
            JsonElement ci = row.get("win_rate_ci");
            if (ci != null && ci.isJsonArray() && ci.getAsJsonArray().size() == 2) {
                ciLow = ci.getAsJsonArray().get(0).getAsDouble();
                ciHigh = ci.getAsJsonArray().get(1).getAsDouble();
            }

            // Act ごとのピック率は [Act1, Act2, Act3] の配列で来る
            Double[] byAct = new Double[3];
            JsonElement acts = row.get("pick_rate_by_act");
            if (acts != null && acts.isJsonArray()) {
                JsonArray array = acts.getAsJsonArray();
                for (int i = 0; i < 3 && i < array.size(); i++) {
                    byAct[i] = array.get(i).isJsonNull() ? null : array.get(i).getAsDouble();
                }
            }

            stats.add(new CardStat(
                    row.get("id").getAsString(),
                    character,
                    ascensionGroup,
                    patch,
                    decimal(row, "win_rate"),
                    ciLow,
                    ciHigh,
                    decimal(row, "pick_rate"),
                    decimal(row, "lift"),
                    integer(row, "lift_n"),
                    integer(row, "picks"),
                    integer(row, "wins"),
                    integer(row, "losses"),
                    integer(row, "offered"),
                    integer(row, "picked"),
                    byAct[0],
                    byAct[1],
                    byAct[2],
                    integer(row, "score"),
                    row.has("tier") && !row.get("tier").isJsonNull() ? row.get("tier").getAsString() : null,
                    decimal(row, "elo"),
                    baselineWinRate,
                    characterRuns,
                    dataThrough));
        }
        return stats;
    }

    /**
     * JSON から小数の項目を取り出します。項目がない、または空（null）なら null を返します。
     *
     * @param json 取り出し元の JSON
     * @param key  項目の名前（例："win_rate"）
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
     * @param key  項目の名前（例："picks"）
     * @return 項目の数値、または null
     */
    private static Integer integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return (value == null || value.isJsonNull()) ? null : value.getAsInt();
    }
}

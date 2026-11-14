package sts2advisor;

import com.google.gson.JsonObject;
import sts2advisor.db.CardDatabase;
import sts2advisor.external.MetabotClient;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【このファイルの役割】
 * MetaBot.GG から、カードの統計（ティア・勝率・ピック率）を取り込む「入口」です（機能要件 F-14 の一部）。
 * Spire Codex とは別の意見として使います（数え方が違うので、数字は混ぜずに順位で比べる予定）。
 *
 * 処理の流れ：
 *   1. データベースから、キャラのカードと無色カードの英語名を読み出す
 *      （先に ImportCardsMain でカードデータを取り込んでおく必要があります）
 *   2. 1枚ずつ英語名で MetaBot.GG に問い合わせる
 *   3. 返ってきたカード名が問い合わせた名前と同じか確かめる（似た名前の別カードを取り違えないため）
 *   4. 1枚ごとにデータベースの metabot_card_stats 表に保存する
 *   ※ 今日すでに取り込んだカードは飛ばすので、途中で止まっても、もう一度実行すれば続きから取れます
 *
 * MetaBot.GG の一覧取得（get_game_meta）は上位8枚しか返さないため、1枚ずつ問い合わせます。
 * 規約の「常識的な頻度」を守るため、1回ごとに1.5秒待ちます（約500枚で13分ほど）。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ImportMetabotMain
 */
public class ImportMetabotMain {

    /** データベースファイルの場所（ImportCardsMain と同じファイル） */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * 問い合わせるカードのキャラ（cards 表の character の値）。
     * 呪い・状態異常・イベントなどのカードはカード報酬に出ないので対象外にする
     */
    private static final List<String> TARGET_CHARACTERS =
            List.of("ironclad", "silent", "defect", "necrobinder", "regent", "colorless");

    /** 問い合わせの間に待つ時間（ミリ秒）。1.5秒ずつなら1分に40回で、目安の60回を下回る */
    private static final long WAIT_MILLIS = 1_500;

    /** MetaBot.GG は対応パッチを公開していないので、その旨を記録する */
    private static final String UNKNOWN_PATCH = "不明（MetaBot.GG は非公開）";

    /**
     * プログラムの入口。ここから処理が始まります。
     *
     * @param args 起動時に渡す設定（今は使わない）
     * @throws Exception 通信やデータベースで失敗したとき（内容を表示して止まる）
     */
    public static void main(String[] args) throws Exception {
        MetabotClient client = new MetabotClient();
        String fetchedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();

        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            Map<String, String> names = db.findEnglishNames(TARGET_CHARACTERS);

            // 同じ英語名のカードが複数ある（例：各キャラの「Defend」）と、MetaBot.GG の答えがどのキャラの
            // カードのものか区別できない。そこで、そういう名前をあらかじめ数えておき、問い合わせずに対象外にする
            Map<String, Integer> nameCounts = new HashMap<>();
            for (String nameEn : names.values()) {
                nameCounts.merge(nameEn, 1, Integer::sum);
            }

            // 今日すでに取り込んだカードは飛ばす（途中で止まったときに、続きから再開できるように）
            String today = fetchedAt.substring(0, 10);
            Set<String> alreadyFetched = db.findMetabotCardIdsFetchedOn(today);
            names.keySet().removeAll(alreadyFetched);
            if (!alreadyFetched.isEmpty()) {
                System.out.println("今日取り込み済みのため飛ばすカード: " + alreadyFetched.size() + " 枚");
            }

            System.out.println("取得元: " + MetabotClient.SOURCE_NAME);
            System.out.println("問い合わせるカード: " + names.size() + " 枚（約 "
                    + (names.size() * WAIT_MILLIS / 60_000 + 1) + " 分かかります）");

            int done = 0;
            int matchedCount = 0;
            for (Map.Entry<String, String> entry : names.entrySet()) {
                String cardId = entry.getKey();
                String nameEn = entry.getValue();

                if (nameCounts.get(nameEn) > 1) {
                    // 同じ英語名のカードが複数あるので区別できない。問い合わせずに「見つからなかった」と記録する
                    db.saveMetabotCardStat(
                            MetabotClient.SOURCE_NAME, cardId, nameEn, false,
                            null, null, null, null, null, null, UNKNOWN_PATCH, fetchedAt);
                    System.out.println("同じ英語名のカードが複数あるため対象外: " + nameEn + "（" + cardId + "）");
                    done++;
                    continue;
                }

                Thread.sleep(WAIT_MILLIS);
                JsonObject content = client.lookupCard(nameEn);

                // 返ってきたカード名が問い合わせた名前と同じなら「見つかった」とする（大文字・小文字の違いは無視）
                String title = content.has("title") ? content.get("title").getAsString() : null;
                boolean matched = "entity".equals(content.get("kind").getAsString())
                        && nameEn.equalsIgnoreCase(title);

                if (matched) {
                    matchedCount++;
                    db.saveMetabotCardStat(
                            MetabotClient.SOURCE_NAME, cardId, nameEn, true,
                            title,
                            content.has("subtitle") ? content.get("subtitle").getAsString() : null,
                            MetabotClient.statValue(content, "Tier"),
                            MetabotClient.parsePercent(MetabotClient.statValue(content, "Win rate")),
                            MetabotClient.parsePercent(MetabotClient.statValue(content, "Pick rate")),
                            content.getAsJsonObject("cta").get("url").getAsString(),
                            UNKNOWN_PATCH, fetchedAt);
                } else {
                    // 見つからなかった、または別のカードが返ってきた。統計は保存せず「見つからなかった」ことだけ記録する
                    db.saveMetabotCardStat(
                            MetabotClient.SOURCE_NAME, cardId, nameEn, false,
                            title, null, null, null, null, null, UNKNOWN_PATCH, fetchedAt);
                    System.out.println("見つからなかったカード: " + nameEn + "（返ってきた名前: " + title + "）");
                }

                done++;
                // 50枚ごとに進み具合を表示する
                if (done % 50 == 0) {
                    System.out.println(done + " / " + names.size() + " 枚 完了");
                }
            }
            System.out.println("完了: " + done + " 枚を問い合わせ、" + matchedCount + " 枚の統計を保存しました");
        }
    }
}

package sts2advisor;

import sts2advisor.db.CardDatabase;
import sts2advisor.game.SaveFileReader;
import sts2advisor.model.RunState;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 【このファイルの役割】
 * セーブファイルから今のランの状態を読み取って表示する「入口」です（機能要件 F-04 の確認用）。
 * 表示した内容がゲームの画面と合っているかを、目で見て確かめるために使います。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ShowRunStateMain
 */
public class ShowRunStateMain {

    /** データベースファイルの場所（カード名を日本語で表示するために使う） */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * プログラムの入口。
     *
     * @param args 使わない
     * @throws Exception セーブファイルやデータベースを読めなかったとき
     */
    public static void main(String[] args) throws Exception {
        Optional<RunState> found = new SaveFileReader().readLatest();
        if (found.isEmpty()) {
            System.out.println("セーブファイル（current_run.save）が見つかりませんでした");
            return;
        }
        RunState run = found.get();

        Map<String, String> namesJa;
        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            namesJa = db.findGameJapaneseNamesById();
        }

        System.out.println(describe(run, namesJa));
    }

    /**
     * ランの状態を、表示用の文字にまとめます。
     *
     * @param run     ランの状態
     * @param namesJa 「カードID → 日本語のカード名」の対応
     * @return 表示用の文字
     */
    static String describe(RunState run, Map<String, String> namesJa) {
        StringBuilder text = new StringBuilder();
        String savedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(run.savedAt());
        long minutesAgo = Duration.between(run.savedAt(), Instant.now()).toMinutes();

        text.append("セーブファイル: ").append(run.saveFile()).append('\n');
        text.append("最後の保存: ").append(savedAt).append("（").append(minutesAgo).append(" 分前）\n");
        text.append("キャラ: ").append(run.character())
                .append("　アセンション: ").append(run.ascension())
                .append("　Act: ").append(run.act())
                .append("　階層: ").append(run.floor())
                .append(run.isSolo() ? "　ソロ" : "　マルチ（" + run.playerCount() + "人）").append('\n');
        text.append("HP: ").append(run.hp()).append(" / ").append(run.maxHp())
                .append("　ゴールド: ").append(run.gold()).append('\n');

        // デッキは、同じカード（強化の回数も同じ）をまとめて「名前 ×枚数」で表示する
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RunState.DeckCard card : run.deck()) {
            String name = namesJa.getOrDefault(card.cardId(), card.cardId());
            String label = name + "+".repeat(card.upgradeLevel());
            counts.merge(label, 1, Integer::sum);
        }
        text.append("デッキ（").append(run.deck().size()).append(" 枚）: ");
        counts.forEach((label, count) -> text.append(label).append(count > 1 ? " ×" + count : "").append("、"));
        text.setLength(text.length() - 1);
        text.append('\n');

        text.append("レリック: ").append(String.join("、", run.relics())).append('\n');
        text.append("ポーション: ").append(run.potions().isEmpty() ? "なし" : String.join("、", run.potions()));
        return text.toString();
    }
}

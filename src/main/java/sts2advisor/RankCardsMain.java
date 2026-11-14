package sts2advisor;

import sts2advisor.db.CardDatabase;
import sts2advisor.reward.CardNameMatcher;
import sts2advisor.reward.CardRanker;
import sts2advisor.reward.RankingText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 【このファイルの役割】
 * キャラ・アセンション・カード名を指定して、カード報酬の順位を表示する「入口」です（確認用）。
 * ゲームを動かさずに、順位のつけ方が妥当かを確かめるために使います。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.RankCardsMain -Dexec.args="DEFECT 1 12 氷河 スクレイプ 大騒ぎ"
 * 引数：キャラ（IRONCLAD / SILENT / DEFECT / NECROBINDER / REGENT）、アセンション、デッキの枚数、カード名（いくつでも）
 */
public class RankCardsMain {

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * プログラムの入口。
     *
     * @param args キャラ、アセンション、デッキの枚数、カード名…
     * @throws Exception データベースを読めなかったとき
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("使い方: キャラ アセンション デッキの枚数 カード名1 カード名2 …");
            return;
        }
        String character = args[0].toUpperCase();
        int ascension = Integer.parseInt(args[1]);
        int deckSize = Integer.parseInt(args[2]);
        List<String> readNames = Arrays.asList(args).subList(3, args.length);

        try (CardDatabase db = new CardDatabase(DB_FILE);
             sts2advisor.db.RunHistoryDatabase history = new sts2advisor.db.RunHistoryDatabase(DB_FILE)) {
            // 自分のラン履歴での統計も、表示用に渡す（F-13。ImportRunHistoryMain で取り込んだもの）
            CardRanker ranker = new CardRanker(
                    db.findCodexStats(character, CardRanker.ascensionGroup(ascension)), db.findMetabotStats(), deckSize,
                    history.findOwnCardStats(character));
            CardNameMatcher matcher = new CardNameMatcher(db.findGameCardIdsByJapaneseName());

            List<String> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String name : readNames) {
                CardNameMatcher.Match m = matcher.match(name);
                ids.add(m.cardId());
                names.add(m.officialName() != null ? m.officialName() : name);
            }

            System.out.println(character + " / アセンション " + ascension + " / デッキ" + deckSize + "枚"
                    + "（使う統計: " + CardRanker.ascensionGroup(ascension) + "）");
            RankingText.lines(ranker.rank(ids, names)).forEach(System.out::println);
        }
    }
}

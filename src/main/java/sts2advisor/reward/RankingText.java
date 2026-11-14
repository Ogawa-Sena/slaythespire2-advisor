package sts2advisor.reward;

import java.util.List;

/**
 * 【このファイルの役割】
 * カード報酬の順位を、画面に表示するための文字にする係です。
 * ターミナルへの表示と、表示ウィンドウ（AdvisorWindow）の根拠の行の両方で使います。
 */
public final class RankingText {

    private RankingText() {
        // 文字を作る道具をまとめただけのクラスなので、インスタンスは作らない
    }

    /**
     * 順位の一覧を、1行に1つの選択肢の文字にします。
     *
     * @param options 点数の高い順に並べた選択肢（CardRanker.rank の結果）
     * @return 表示用の文字の一覧
     */
    public static List<String> lines(List<CardRanker.Option> options) {
        return java.util.stream.IntStream.range(0, options.size())
                .mapToObj(i -> (i + 1) + "位 " + describe(options.get(i)))
                .toList();
    }

    /**
     * 順位の一覧を、デッキとの相性つきの文字にします（ターミナルとログ用。ステップ11）。
     *
     * @param options 点数の高い順に並べた選択肢
     * @param deck    デッキとの相性（無ければ null）
     * @return 表示用の文字の一覧
     */
    public static List<String> lines(List<CardRanker.Option> options, sts2advisor.deck.DeckAdvisor.DeckView deck) {
        return java.util.stream.IntStream.range(0, options.size())
                .mapToObj(i -> (i + 1) + "位 " + describe(options.get(i), deck))
                .toList();
    }

    /**
     * 選択肢1つ分の説明に、デッキとの相性を付け足します。
     *
     * @param o    選択肢
     * @param deck デッキとの相性（無ければ null）
     * @return 説明の文字
     */
    public static String describe(CardRanker.Option o, sts2advisor.deck.DeckAdvisor.DeckView deck) {
        String fit = deck != null ? sts2advisor.deck.DeckAdvisor.describe(deck.fit(o.cardId())) : "";
        return fit.isEmpty() ? describe(o) : describe(o) + "｜" + fit;
    }

    /**
     * 選択肢1つ分の説明を作ります。
     * 例："輝き　コモンの中で上位 20%（勝率の上がり幅 -1.4 / 12,532件、Codex ティアC）｜Metabot 上位12%（ティアB）"
     *
     * @param o 選択肢
     * @return 説明の文字
     */
    public static String describe(CardRanker.Option o) {
        if (o.isSkip()) {
            return "スキップ　（" + o.note() + "）";
        }
        StringBuilder text = new StringBuilder(o.name());
        if (o.statScore() != null) {
            // デッキとの相性を混ぜたとき（2026-11-13）：順位に使った総合点と、混ぜる前の統計の点数を出す
            text.append(String.format("　総合 %.0f点（統計 %.0f点＋相性）", o.score(), o.statScore()));
        }
        if (o.hasScore()) {
            // 点数は「下から何%か」なので、見やすいように「上位何%か」に直して表示する
            int top = Math.max(1, (int) Math.round(100 - o.statOnlyScore()));
            text.append("　").append(rarityLabel(o.codex().rarity())).append("の中で上位 ").append(top).append("%");
            text.append(String.format("（勝率の上がり幅 %+.1f / %,d件、Codex ティア%s）",
                    o.codex().lift(), o.codex().liftN(), o.codex().tier()));
        }
        if (o.metabotTopPercent() != null) {
            text.append("｜Metabot 上位").append(o.metabotTopPercent()).append("%（ティア")
                    .append(o.metabot().tier()).append("）");
        }
        if (o.own() != null) {
            text.append("｜").append(ownText(o.own()));
        }
        if (!o.note().isEmpty()) {
            text.append("　※").append(o.note());
        }
        return text.toString();
    }

    /**
     * 自分のラン履歴での統計を、短い文字にします（F-13。点数には混ぜず、表示するだけ）。
     * 例："自分：出た 5回・取った 3回（取ったランの成績 1勝/2ラン）"
     *
     * @param own 自分の統計
     * @return 表示用の文字
     */
    public static String ownText(sts2advisor.db.RunHistoryDatabase.OwnCardStat own) {
        String text = "自分：出た " + own.offered() + "回・取った " + own.picked() + "回";
        if (own.finishedRuns() > 0) {
            text += "（取ったランの成績 " + own.wins() + "勝/" + own.finishedRuns() + "ラン）";
        }
        return text;
    }

    /**
     * レアリティを日本語にします。
     *
     * @param rarity cards 表のレアリティ（例："Common"）
     * @return 日本語の名前（知らないものはそのまま返す）
     */
    static String rarityLabel(String rarity) {
        return switch (rarity) {
            case "Common" -> "コモン";
            case "Uncommon" -> "アンコモン";
            case "Rare" -> "レア";
            default -> rarity;
        };
    }
}

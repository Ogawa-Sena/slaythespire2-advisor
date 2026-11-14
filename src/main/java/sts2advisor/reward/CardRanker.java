package sts2advisor.reward;

import sts2advisor.db.CardDatabase.CodexStat;
import sts2advisor.db.CardDatabase.MetabotStat;
import sts2advisor.db.RunHistoryDatabase.OwnCardStat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 【このファイルの役割】
 * カード報酬の候補のカードと「スキップ」に、順位をつける係です（機能要件 F-09 の簡単な版）。
 *
 * 点数の決め方（2026-11-12 に「同じレアリティの中で比べる」方式に変更）：
 *   1. Spire Codex の「lift」を使う。lift は、そのカードを取ったランの勝率が、
 *      同じプレイヤーのふだんの勝率より何ポイント高いか（低いか）を表す数字
 *   2. 件数が少ないと数字がぶれやすいので、件数に応じて 0 に近づける（「縮める」）
 *        縮めた lift = lift × 件数 ÷ (件数 + SHRINK_COUNT)
 *   3. 縮めた lift を、同じキャラの統計の中で「同じレアリティのカードの中で下から何%か」に直す。
 *      これが点数（0〜100。大きいほど良い）
 *      ・理由：lift にはプレイヤーの腕前の差が混ざっていて、コモンはほぼ全部マイナスになる
 *        （上手な人ほど弱いコモンをスキップするため）。コモンはコモン同士で比べると、その差が打ち消し合う
 *   4. 「スキップ」の点数は、今のデッキの枚数で決める基準（skipLine）。2026-11-12 にユーザーと決定
 *        15枚未満 → 20、15〜19枚 → 40、20枚以上 → 55
 *      ・デッキが小さい（序盤）うちはほとんど何か取り、デッキが大きくなるほど厳しく選ぶ、という考え方
 *        （カードが増えるほど、1枚あたりの引く回数が減り、弱いカードは足を引っ張るため）
 *      ・例：15枚未満なら、同じレアリティの下位20%に入るカードだけがスキップより下になる
 *   統計の無いカード・件数が少なすぎて lift の無いカードは、良し悪しが分からないのでスキップより下にする。
 *
 * MetaBot.GG の統計は数え方が違うので点数には混ぜず、「キャラ内で上位何%か」を参考として並べる
 * （CLAUDE.md の決定事項）。
 *
 * まだ考えていないこと（MVPの後で足す）：
 *   デッキとの相性（デッキの方向性 F-08）、コスト・手札圧迫、自分のプレイデータ（F-15）
 */
public class CardRanker {

    /** 縮める強さ。件数がこの数のとき、lift の半分になる（2026-11-12 の仮の値。あとで調整する） */
    static final double SHRINK_COUNT = 100;

    /**
     * これより件数が少ないと「件数少なめ」の印を付ける。
     * また、比べる相手（同じレアリティのカード）には、この件数以上のカードだけを使う
     * （他のキャラのカードなど、件数がごく少ないカードで基準がずれないようにするため）
     */
    public static final int FEW_SAMPLES = 200;

    /** デッキがこの枚数未満なら、スキップの点数は SKIP_LINE_SMALL */
    static final int SMALL_DECK = 15;

    /** デッキがこの枚数未満なら、スキップの点数は SKIP_LINE_MEDIUM（それ以上は SKIP_LINE_LARGE） */
    static final int MEDIUM_DECK = 20;

    /** スキップの点数（デッキ15枚未満 / 15〜19枚 / 20枚以上）。2026-11-12 にユーザーと決めた値 */
    static final double SKIP_LINE_SMALL = 20;
    static final double SKIP_LINE_MEDIUM = 40;
    static final double SKIP_LINE_LARGE = 55;

    /** 統計の無いカードの点数（スキップより必ず下になるよう、0 より小さくする） */
    static final double NO_DATA_SCORE = -1;

    /**
     * 順位をつけた選択肢1つ分。
     *
     * @param cardId        カードID（スキップなら null）
     * @param name          表示する名前（スキップなら「スキップ」）
     * @param score         点数（0〜100。同じレアリティの中で下から何%か。大きいほど良い。
     *                      スキップはデッキの枚数で決まる基準、統計の無いカードは NO_DATA_SCORE）
     * @param codex         Spire Codex の統計（なければ null）
     * @param metabot       MetaBot.GG の統計（なければ null）
     * @param metabotTopPercent MetaBot.GG の勝率で、同じキャラのカードの中で上位何%か（なければ null）
     * @param note          補足（「データ不足」など。なければ空文字）
     * @param own           自分のラン履歴での統計（F-13。点数には混ぜず、表示するだけ。無ければ null）
     * @param statScore     デッキとの相性を混ぜる前の、統計だけの点数（混ぜていなければ null。2026-11-13 追加）
     */
    public record Option(String cardId, String name, double score, CodexStat codex, MetabotStat metabot,
                         Integer metabotTopPercent, String note, OwnCardStat own, Double statScore) {

        /**
         * @return 統計だけの点数（相性を混ぜていれば混ぜる前の点数、混ぜていなければ score と同じ）
         */
        public double statOnlyScore() {
            return statScore != null ? statScore : score;
        }
        /**
         * @return スキップの選択肢なら true
         */
        public boolean isSkip() {
            return cardId == null && "スキップ".equals(name);
        }

        /**
         * @return 統計による点数があれば true（スキップと、統計の無いカードは false）
         */
        public boolean hasScore() {
            return !isSkip() && score != NO_DATA_SCORE;
        }
    }

    /** 今のキャラ・アセンションに合った Spire Codex の統計（カードID → 統計） */
    private final Map<String, CodexStat> codexStats;

    /** MetaBot.GG の統計（カードID → 統計） */
    private final Map<String, MetabotStat> metabotStats;

    /** 今のデッキの枚数 */
    private final int deckSize;

    /** 自分のラン履歴での統計（カードID → 統計） */
    private final Map<String, OwnCardStat> ownStats;

    /**
     * 自分のラン履歴を使わずに順位をつけます。
     *
     * @param codexStats   今のキャラ・アセンションに合った Spire Codex の統計
     * @param metabotStats MetaBot.GG の統計
     * @param deckSize     今のデッキの枚数（スキップの基準を決めるのに使う）
     */
    public CardRanker(Map<String, CodexStat> codexStats, Map<String, MetabotStat> metabotStats, int deckSize) {
        this(codexStats, metabotStats, deckSize, Map.of());
    }

    /**
     * @param codexStats   今のキャラ・アセンションに合った Spire Codex の統計
     * @param metabotStats MetaBot.GG の統計
     * @param deckSize     今のデッキの枚数（スキップの基準を決めるのに使う）
     * @param ownStats     自分のラン履歴での統計（RunHistoryDatabase.findOwnCardStats。表示するだけ）
     */
    public CardRanker(Map<String, CodexStat> codexStats, Map<String, MetabotStat> metabotStats, int deckSize,
                      Map<String, OwnCardStat> ownStats) {
        this.codexStats = codexStats;
        this.metabotStats = metabotStats;
        this.deckSize = deckSize;
        this.ownStats = ownStats;
    }

    /**
     * アセンションから、使う統計の区分を決めます。
     * Spire Codex では A10 だけを取り出せますが、A0〜A9 だけを取り出すことはできないため、
     * A10 なら "a10"、それ以外は "all"（A0〜A10 全部）を使います。
     *
     * @param ascension アセンション（0〜10）
     * @return "a10" か "all"
     */
    public static String ascensionGroup(int ascension) {
        return ascension >= 10 ? "a10" : "all";
    }

    /**
     * デッキの枚数から、スキップの点数（基準）を決めます。
     *
     * @param deckSize 今のデッキの枚数
     * @return スキップの点数。15枚未満は 20、15〜19枚は 40、20枚以上は 55
     */
    public static double skipLine(int deckSize) {
        if (deckSize < SMALL_DECK) {
            return SKIP_LINE_SMALL;
        }
        if (deckSize < MEDIUM_DECK) {
            return SKIP_LINE_MEDIUM;
        }
        return SKIP_LINE_LARGE;
    }

    /**
     * 候補のカードとスキップに、順位をつけます。
     *
     * @param cardIds 候補のカードIDの一覧（画面の左から順。見つからなかったカードは null でもよい）
     * @param names   表示する名前の一覧（cardIds と同じ順）
     * @return 点数の高い順に並べた選択肢（スキップを含む）
     */
    public List<Option> rank(List<String> cardIds, List<String> names) {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < cardIds.size(); i++) {
            options.add(score(cardIds.get(i), names.get(i)));
        }
        options.add(new Option(null, "スキップ", skipLine(deckSize), null, null, null,
                "デッキ" + deckSize + "枚の基準：同じレアリティの下位" + (int) skipLine(deckSize) + "%以下ならスキップ",
                null, null));
        // 点数の高い順に並べる。同じ点数なら「スキップ → 統計のあるカード → 統計の無いカード」の順にする
        // （基準ちょうどのカードは「下位N%以下」に入るので、スキップを優先する）
        options.sort(Comparator.comparingDouble(Option::score).reversed()
                .thenComparingInt(CardRanker::tieOrder));
        return options;
    }

    /** 序盤とみなすデッキの枚数（これ以下なら序盤。2026-11-13 ユーザーと決定した目安） */
    public static final int EARLY_DECK_SIZE = 13;

    /** 相性を混ぜる割合（序盤は 統計 8：相性 2、それ以降は 統計 6：相性 4。2026-11-12 ユーザーと決定） */
    static final double FIT_WEIGHT_EARLY = 0.2;
    static final double FIT_WEIGHT_LATER = 0.4;

    /**
     * デッキの枚数から、相性を混ぜる割合を決めます。
     *
     * @param deckSize 今のデッキの枚数
     * @return 相性の割合（0.2 か 0.4。残りが統計の割合）
     */
    public static double fitWeight(int deckSize) {
        return deckSize <= EARLY_DECK_SIZE ? FIT_WEIGHT_EARLY : FIT_WEIGHT_LATER;
    }

    /**
     * 相性（近い勝ちデッキの採用率）を、統計の点数と同じ 0〜100 の点数にならします。
     * 採用率は偏って出る（2026-11-12 のログで真ん中 17%、最大 77%）ので、50% 以上を満点にする。
     *
     * @param winnerSupport 近い勝ちデッキの採用率（%）
     * @return 相性の点数（0〜100）
     */
    public static double fitScore(double winnerSupport) {
        return Math.min(100, winnerSupport * 2);
    }

    /**
     * 統計で順位をつけた選択肢に、デッキとの相性を混ぜて、順位をつけ直します（2026-11-13 追加）。
     * 点数 ＝ 統計の点数 ×（1 − 割合）＋ 相性の点数 × 割合。割合は序盤 0.2、それ以降 0.4。
     * 統計の点数が無いカードと、相性が分からないカード（pick-coach がつながらない等）は、統計のままにします。
     * スキップの点数（デッキの枚数で決まる基準）は変えず、混ぜた点数と比べます。
     *
     * @param options  CardRanker.rank の結果
     * @param deck     デッキとの相性（null なら何もしない）
     * @param deckSize 今のデッキの枚数
     * @return 相性を混ぜて、点数の高い順に並べ直した選択肢
     */
    public static List<Option> mixDeckFit(List<Option> options, sts2advisor.deck.DeckAdvisor.DeckView deck,
                                          int deckSize) {
        if (deck == null) {
            return options;
        }
        double w = fitWeight(deckSize);
        List<Option> mixed = new ArrayList<>();
        for (Option o : options) {
            sts2advisor.deck.DeckAdvisor.CardFit fit = deck.fit(o.cardId());
            if (!o.hasScore() || fit == null || fit.winnerSupport() == null) {
                mixed.add(o);
                continue;
            }
            double score = o.score() * (1 - w) + fitScore(fit.winnerSupport()) * w;
            mixed.add(new Option(o.cardId(), o.name(), score, o.codex(), o.metabot(), o.metabotTopPercent(),
                    o.note(), o.own(), o.score()));
        }
        mixed.sort(Comparator.comparingDouble(Option::score).reversed().thenComparingInt(CardRanker::tieOrder));
        return mixed;
    }

    /**
     * 同じ点数のときの並び順を決める数（小さいほど上）。
     *
     * @param o 選択肢
     * @return 0＝スキップ、1＝統計のあるカード、2＝統計の無いカード
     */
    private static int tieOrder(Option o) {
        if (o.isSkip()) {
            return 0;
        }
        return o.hasScore() ? 1 : 2;
    }

    /**
     * カード1枚の点数を求めます。
     *
     * @param cardId カードID（null なら「カードが分からない」）
     * @param name   表示する名前
     * @return 点数をつけた選択肢
     */
    public Option score(String cardId, String name) {
        if (cardId == null) {
            return new Option(null, name, NO_DATA_SCORE, null, null, null, "カードを特定できませんでした", null, null);
        }
        CodexStat codex = codexStats.get(cardId);
        MetabotStat metabot = metabotStats.get(cardId);
        Integer topPercent = metabot != null ? metabotTopPercent(metabot) : null;

        if (codex == null || codex.lift() == null || codex.liftN() == null || codex.rarity() == null) {
            return new Option(cardId, name, NO_DATA_SCORE, codex, metabot, topPercent, "データ不足", ownStats.get(cardId), null);
        }
        Double percentile = rarityPercentile(codex);
        if (percentile == null) {
            return new Option(cardId, name, NO_DATA_SCORE, codex, metabot, topPercent, "比べるカードが無い", ownStats.get(cardId), null);
        }
        int n = codex.liftN();
        String note = n < FEW_SAMPLES ? "件数少なめ（" + n + "件）" : "";
        return new Option(cardId, name, percentile, codex, metabot, topPercent, note, ownStats.get(cardId), null);
    }

    /**
     * 件数に応じて 0 に近づけた lift を求めます。
     *
     * @param s Spire Codex の統計（lift と liftN があること）
     * @return 縮めた lift
     */
    public static double shrunkLift(CodexStat s) {
        int n = s.liftN();
        return s.lift() * n / (n + SHRINK_COUNT);
    }

    /**
     * 縮めた lift が、同じレアリティのカードの中で下から何%に入るかを求めます。
     * 比べる相手は、件数が FEW_SAMPLES 以上のカードだけです。同じ値のカードは半分ずつ数えます。
     *
     * @param target 調べるカードの統計
     * @return 下から何%か（0〜100。大きいほど良い）。比べる相手がいなければ null
     */
    Double rarityPercentile(CodexStat target) {
        double value = shrunkLift(target);
        int total = 0;
        double lower = 0;
        for (CodexStat other : codexStats.values()) {
            if (other.lift() == null || other.liftN() == null || other.liftN() < FEW_SAMPLES
                    || !target.rarity().equals(other.rarity())) {
                continue;
            }
            total++;
            double otherValue = shrunkLift(other);
            if (otherValue < value) {
                lower += 1;
            } else if (otherValue == value) {
                lower += 0.5;
            }
        }
        if (total == 0) {
            return null;
        }
        return 100.0 * lower / total;
    }

    /**
     * MetaBot.GG の勝率で、同じキャラのカードの中で上位何%に入るかを求めます。
     *
     * @param target 調べるカードの MetaBot.GG の統計
     * @return 上位何%か（1〜100。小さいほど良い）。勝率が無ければ null
     */
    Integer metabotTopPercent(MetabotStat target) {
        if (target.winRate() == null) {
            return null;
        }
        int total = 0;
        int better = 0;
        for (MetabotStat other : metabotStats.values()) {
            if (other.winRate() == null || !other.character().equals(target.character())) {
                continue;
            }
            total++;
            if (other.winRate() > target.winRate()) {
                better++;
            }
        }
        if (total == 0) {
            return null;
        }
        // 自分より勝率が高いカードの数から、上位何%かを出す（1位なら 1%付近）
        return Math.max(1, (int) Math.ceil(100.0 * (better + 1) / total));
    }
}

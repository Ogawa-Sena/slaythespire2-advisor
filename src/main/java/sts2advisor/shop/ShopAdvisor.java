package sts2advisor.shop;

import sts2advisor.db.CardDatabase.CodexStat;
import sts2advisor.db.ItemDatabase.ShopStat;
import sts2advisor.model.RunState;
import sts2advisor.reward.CardRanker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 【このファイルの役割】
 * ショップで「今の所持ゴールドで買える範囲での、いちばん良い買い物の組み合わせ」を求める係です
 * （機能要件 F-10 の簡単な版。MVP のステップ9）。
 * この先のマスでの出費・収入は予測できないので考えない（CLAUDE.md の決定事項）。
 *
 * 1. 品物ごとに「価値」を求める（単位はすべて「勝率の上がり幅（ポイント）」。2026-11-12 にユーザーと決定）
 *    ・レリック・ポーション：Spire Codex のショップの統計の「買った場合の勝率の上がり幅（lift）」
 *    ・カード：カードの統計の lift から、同じレアリティの平均を引いたもの
 *      （lift にはプレイヤーの腕前の差が混ざり、コモンはほぼ全部マイナスになるため。カード報酬と同じ考え方）
 *      さらにカード報酬と同じく、同じレアリティの中でデッキ枚数の基準（CardRanker.skipLine）以下なら買わない
 *    ・どれも件数が少ないとぶれるので、件数に応じて 0 に近づける（lift × 件数 ÷ (件数 + 100)）
 *    ・同じ種類の中で「上位何%か」も求める（カード削除を勧めるかの判断に使う）
 * 2. 価値がプラスで、所持ゴールドで買える品物を候補にする。ポーションは空きスロットの数までしか買えない
 * 3. カード削除：候補の中に「上位40%」に入る品物が1つも無ければ、カード削除を先に勧める
 *    （削除するカードは、ストライク → 防御 の順。2026-11-12 にユーザーと決定。
 *     デッキによってはストライクが大事なこともあるが、それはステップ11で判断する）
 * 4. 残りのゴールドで、価値の合計がいちばん大きくなる組み合わせを、全部の組み合わせを試して選ぶ
 *    （品物は多くても14個なので、組み合わせは約1万6千通り。すぐ終わる）
 *
 * まだ考えていないこと：デッキとの相性（ステップ11）、自分のプレイデータ
 */
public class ShopAdvisor {

    /** 件数に応じて 0 に近づける強さ（CardRanker と同じ値） */
    static final double SHRINK_COUNT = 100;

    /** 「上位何%か」を求めるときの比べる相手に入れる、最低の件数 */
    static final int MIN_POOL_COUNT = 50;

    /** これ以下の上位%の品物があれば、カード削除より優先する（2026-11-12 にユーザーと決定） */
    static final int REMOVAL_UNLESS_TOP_PERCENT = 40;

    /**
     * 価値をつけた品物1つ分。
     *
     * @param item       ショップの品物
     * @param value      価値（勝率の上がり幅。null ならデータ不足）
     * @param topPercent 同じ種類の中で上位何%か（null ならデータ不足）
     * @param candidate  買う候補に入れるか
     * @param reason     候補に入れない理由、または補足（なければ空文字）
     */
    public record Valued(ShopReader.Item item, Double value, Integer topPercent, boolean candidate, String reason) {
    }

    /**
     * おすすめの買い物。
     *
     * @param valued        全部の品物の価値
     * @param chosen        おすすめの組み合わせ（カード削除を含む）
     * @param removalTarget カード削除で消すカードの名前（勧めないときは null）
     * @param totalPrice    おすすめの組み合わせの合計金額
     * @param totalValue    おすすめの組み合わせの価値の合計
     * @param gold          所持ゴールド
     * @param summary       判断の説明
     */
    public record Advice(List<Valued> valued, List<Valued> chosen, String removalTarget, int totalPrice,
                         double totalValue, int gold, String summary) {
    }

    private final Map<String, CodexStat> cardStats;
    private final Map<String, ShopStat> relicShopStats;
    private final Map<String, ShopStat> potionShopStats;

    /**
     * @param cardStats       今のキャラ・アセンションのカードの統計（CardDatabase.findCodexStats）
     * @param relicShopStats  レリックのショップの統計（ItemDatabase.findShopStats("relics", 区分)）
     * @param potionShopStats ポーションのショップの統計（ItemDatabase.findShopStats("potions", 区分)）
     */
    public ShopAdvisor(Map<String, CodexStat> cardStats, Map<String, ShopStat> relicShopStats,
                       Map<String, ShopStat> potionShopStats) {
        this.cardStats = cardStats;
        this.relicShopStats = relicShopStats;
        this.potionShopStats = potionShopStats;
    }

    /**
     * おすすめの買い物を求めます。
     *
     * @param items ショップの品物（ShopReader の結果）
     * @param state 今のラン状態（所持ゴールド・デッキ・ポーション）
     * @return おすすめの買い物
     */
    public Advice advise(List<ShopReader.Item> items, RunState state) {
        int gold = state.gold();
        CardRanker cardRanker = new CardRanker(cardStats, Map.of(), state.deck().size());

        // 1. 品物ごとに価値を求める
        List<Valued> valued = new ArrayList<>();
        Valued removal = null;
        for (ShopReader.Item item : items) {
            Valued v = switch (item.kind()) {
                case CARD -> valueCard(item, cardRanker, state.deck().size());
                case RELIC -> valueByShopStats(item, relicShopStats);
                case POTION -> valueByShopStats(item, potionShopStats);
                case REMOVAL -> new Valued(item, null, null, false, "");
            };
            // 買えない値段なら候補から外す
            if (v.candidate() && item.price() > gold) {
                v = new Valued(item, v.value(), v.topPercent(), false, "お金が足りない");
            }
            if (item.kind() == ShopLayout.Kind.REMOVAL) {
                removal = v;
            }
            valued.add(v);
        }

        // 3. カード削除を勧めるか決める
        List<Valued> chosen = new ArrayList<>();
        int budget = gold;
        String removalTarget = null;
        StringBuilder summary = new StringBuilder();
        boolean hasTopItem = valued.stream().anyMatch(v -> v.candidate() && v.topPercent() != null
                && v.topPercent() <= REMOVAL_UNLESS_TOP_PERCENT);
        if (removal != null) {
            String target = removalTarget(state);
            String notReason = null;
            if (hasTopItem) {
                notReason = "上位" + REMOVAL_UNLESS_TOP_PERCENT + "%の品物を優先";
                summary.append("上位").append(REMOVAL_UNLESS_TOP_PERCENT).append("%の品物があるので、カード削除は勧めません。");
            } else if (target == null) {
                notReason = "ストライク・防御が無い";
                summary.append("ストライク・防御が無いので、カード削除は勧めません。");
            } else if (removal.item().price() > gold) {
                notReason = "お金が足りない";
                summary.append("カード削除はお金が足りません。");
            }
            if (notReason != null) {
                // 一覧に出す理由を書き換える（記録は値が変えられないので、作り直して入れ替える）
                valued.set(valued.indexOf(removal), new Valued(removal.item(), null, null, false, notReason));
            } else {
                removalTarget = target;
                chosen.add(removal);
                budget -= removal.item().price();
                summary.append("上位").append(REMOVAL_UNLESS_TOP_PERCENT).append("%の品物が無いので、カード削除（")
                        .append(target).append("）を勧めます。");
            }
        }

        // 4. 残りのゴールドで、価値の合計がいちばん大きい組み合わせを選ぶ
        int freePotionSlots = Math.max(0, state.potionSlots() - state.potions().size());
        List<Valued> candidates = valued.stream().filter(Valued::candidate).toList();
        List<Valued> best = bestCombination(candidates, budget, freePotionSlots);
        chosen.addAll(best);
        if (candidates.stream().anyMatch(v -> v.item().kind() == ShopLayout.Kind.POTION) && freePotionSlots == 0) {
            summary.append("ポーションの空きが無いので、ポーションは買えません。");
        }

        int totalPrice = chosen.stream().mapToInt(v -> v.item().price()).sum();
        double totalValue = best.stream().mapToDouble(Valued::value).sum();
        if (chosen.isEmpty()) {
            summary.append("買う価値のある品物はありません。");
        }
        return new Advice(valued, chosen, removalTarget, totalPrice, totalValue, gold, summary.toString());
    }

    /**
     * カードの価値を求めます。
     *
     * @param item      ショップのカード
     * @param ranker    カード報酬と同じ順位づけの係（同じレアリティの中で下から何%かを求める）
     * @param deckSize  今のデッキの枚数
     * @return 価値をつけた品物
     */
    private Valued valueCard(ShopReader.Item item, CardRanker ranker, int deckSize) {
        if (item.id() == null) {
            return new Valued(item, null, null, false, "カードを特定できなかった");
        }
        CardRanker.Option option = ranker.score(item.id(), item.name());
        if (!option.hasScore()) {
            return new Valued(item, null, null, false, "データ不足");
        }
        CodexStat stat = option.codex();
        double value = CardRanker.shrunkLift(stat) - rarityAverage(stat.rarity());
        int topPercent = Math.max(1, (int) Math.round(100 - option.score()));
        double skipLine = CardRanker.skipLine(deckSize);
        if (option.score() <= skipLine) {
            return new Valued(item, value, topPercent, false,
                    "デッキ" + deckSize + "枚の基準（同じレアリティの下位" + (int) skipLine + "%以下）");
        }
        return new Valued(item, value, topPercent, value > 0, value > 0 ? "" : "価値がマイナス");
    }

    /**
     * 同じレアリティのカードの、縮めた lift の平均を求めます（件数が十分なカードだけ）。
     *
     * @param rarity レアリティ
     * @return 平均（比べる相手がいなければ 0）
     */
    private double rarityAverage(String rarity) {
        return cardStats.values().stream()
                .filter(s -> s.lift() != null && s.liftN() != null && s.liftN() >= CardRanker.FEW_SAMPLES
                        && rarity.equals(s.rarity()))
                .mapToDouble(CardRanker::shrunkLift)
                .average().orElse(0);
    }

    /**
     * レリック・ポーションの価値を、ショップの統計から求めます。
     *
     * @param item  ショップの品物
     * @param stats そのショップの統計（ID → 統計）
     * @return 価値をつけた品物
     */
    private static Valued valueByShopStats(ShopReader.Item item, Map<String, ShopStat> stats) {
        if (item.id() == null) {
            return new Valued(item, null, null, false, "品物を特定できなかった");
        }
        ShopStat stat = stats.get(item.id());
        if (stat == null || stat.lift() == null || stat.liftN() == null) {
            return new Valued(item, null, null, false, "データ不足");
        }
        double value = shrink(stat.lift(), stat.liftN());
        // 同じ種類の中で上位何%か（件数が十分な品物と比べる）
        long total = stats.values().stream()
                .filter(s -> s.lift() != null && s.liftN() != null && s.liftN() >= MIN_POOL_COUNT).count();
        long better = stats.values().stream()
                .filter(s -> s.lift() != null && s.liftN() != null && s.liftN() >= MIN_POOL_COUNT
                        && shrink(s.lift(), s.liftN()) > value).count();
        Integer topPercent = total == 0 ? null : (int) Math.max(1, Math.ceil(100.0 * (better + 1) / total));
        return new Valued(item, value, topPercent, value > 0, value > 0 ? "" : "価値がマイナス");
    }

    /**
     * 件数に応じて 0 に近づけます（件数が少ないと数字がぶれやすいため）。
     *
     * @param lift 勝率の上がり幅
     * @param n    件数
     * @return 縮めた値
     */
    static double shrink(double lift, int n) {
        return lift * n / (n + SHRINK_COUNT);
    }

    /**
     * カード削除で消すカードを決めます（ストライク → 防御 の順）。
     *
     * @param state 今のラン状態
     * @return 消すカードの名前。どちらも無ければ null
     */
    static String removalTarget(RunState state) {
        boolean hasStrike = state.deck().stream().anyMatch(c -> c.cardId().startsWith("STRIKE"));
        if (hasStrike) {
            return "ストライク";
        }
        boolean hasDefend = state.deck().stream().anyMatch(c -> c.cardId().startsWith("DEFEND"));
        return hasDefend ? "防御" : null;
    }

    /**
     * 予算の中で、価値の合計がいちばん大きくなる組み合わせを、全部の組み合わせを試して探します。
     *
     * @param candidates      候補の品物（価値がプラスのもの）
     * @param budget          使えるゴールド
     * @param freePotionSlots ポーションの空きスロットの数
     * @return いちばん良い組み合わせ（価値の高い順）
     */
    static List<Valued> bestCombination(List<Valued> candidates, int budget, int freePotionSlots) {
        int n = candidates.size();
        int bestMask = 0;
        double bestValue = 0;
        int bestPrice = 0;
        // 0 から 2^n − 1 までの数の各ビットを「その品物を買うか」とみなして、全部の組み合わせを試す
        for (int mask = 1; mask < (1 << n); mask++) {
            int price = 0;
            int potions = 0;
            double value = 0;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    Valued v = candidates.get(i);
                    price += v.item().price();
                    value += v.value();
                    if (v.item().kind() == ShopLayout.Kind.POTION) {
                        potions++;
                    }
                }
            }
            if (price > budget || potions > freePotionSlots) {
                continue;
            }
            // 価値が同じなら、安い方を選ぶ
            if (value > bestValue + 1e-9 || (Math.abs(value - bestValue) <= 1e-9 && price < bestPrice)) {
                bestValue = value;
                bestMask = mask;
                bestPrice = price;
            }
        }
        List<Valued> chosen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if ((bestMask & (1 << i)) != 0) {
                chosen.add(candidates.get(i));
            }
        }
        chosen.sort(Comparator.comparingDouble(Valued::value).reversed());
        return chosen;
    }
}

package sts2advisor.shop;

import java.util.ArrayList;
import java.util.List;

/**
 * 【このファイルの役割】
 * ショップのおすすめ（ShopAdvisor の結果）を、表示用の文字にする係です。
 * ターミナルへの表示と、表示ウィンドウの両方で使います。
 */
public final class ShopAdviceText {

    private ShopAdviceText() {
        // 文字を作る道具をまとめただけのクラスなので、インスタンスは作らない
    }

    /**
     * おすすめの買い物を、表示用の行の一覧にします。
     * 例：
     *   おすすめ：レッドマスク（155）＋ カード削除（75）　合計 230 / 所持 250（残り 20）
     *   ★ レリック1　レッドマスク　155G　価値 +7.3（上位 5%）
     *   　 カード3　ウィスプ　52G　価値 -0.4（上位 61%）… 価値がマイナス
     *
     * @param advice おすすめの買い物
     * @return 表示用の行
     */
    public static List<String> lines(ShopAdvisor.Advice advice) {
        return lines(advice, null);
    }

    /**
     * おすすめの買い物を、デッキとの相性つきで表示用の行の一覧にします（ステップ11）。
     * カードの行の後ろに「相性：近い勝ちデッキの40%が採用」のような説明を付けます。
     *
     * @param advice おすすめの買い物
     * @param deck   デッキとの相性（無ければ null）
     * @return 表示用の行
     */
    public static List<String> lines(ShopAdvisor.Advice advice, sts2advisor.deck.DeckAdvisor.DeckView deck) {
        return lines(advice, deck, java.util.Map.of());
    }

    /**
     * おすすめの買い物を、デッキとの相性と自分のデータつきで表示用の行の一覧にします。
     * カードの行の後ろに「自分：出た 5回・取った 3回（…）」を足します（F-13。表示するだけ）。
     *
     * @param advice おすすめの買い物
     * @param deck   デッキとの相性（無ければ null）
     * @param own    自分のラン履歴での統計（カードID → 統計。無ければ空）
     * @return 表示用の行
     */
    public static List<String> lines(ShopAdvisor.Advice advice, sts2advisor.deck.DeckAdvisor.DeckView deck,
                                     java.util.Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> own) {
        List<String> lines = new ArrayList<>();
        // 1行目：おすすめの組み合わせのまとめ
        if (advice.chosen().isEmpty()) {
            lines.add("おすすめ：何も買わない　（所持 " + advice.gold() + "）");
        } else {
            StringBuilder head = new StringBuilder("おすすめ：");
            for (int i = 0; i < advice.chosen().size(); i++) {
                ShopReader.Item item = advice.chosen().get(i).item();
                if (i > 0) {
                    head.append(" ＋ ");
                }
                head.append(item.kind() == ShopLayout.Kind.REMOVAL
                        ? "カード削除（" + advice.removalTarget() + "）" : item.name()).append("（").append(item.price()).append("）");
            }
            head.append("　合計 ").append(advice.totalPrice()).append(" / 所持 ").append(advice.gold())
                    .append("（残り ").append(advice.gold() - advice.totalPrice()).append("）");
            lines.add(head.toString());
        }
        if (!advice.summary().isEmpty()) {
            lines.add(advice.summary());
        }
        if (deck != null) {
            lines.add(deck.headline());
        }

        // 2行目以降：品物ごとの価値（おすすめに入ったものに ★ を付ける。価値の高い順）
        List<ShopAdvisor.Valued> sorted = new ArrayList<>(advice.valued());
        sorted.sort((a, b) -> Double.compare(b.value() != null ? b.value() : -999, a.value() != null ? a.value() : -999));
        for (ShopAdvisor.Valued v : sorted) {
            ShopReader.Item item = v.item();
            boolean picked = advice.chosen().contains(v);
            String label = item.kind() == ShopLayout.Kind.REMOVAL ? "" : String.valueOf(item.index() + 1);
            StringBuilder line = new StringBuilder(picked ? "★ " : "　 ");
            line.append(item.kind().label()).append(label).append("　").append(item.name())
                    .append("　").append(item.price()).append("G");
            if (v.value() != null) {
                line.append(String.format("　価値 %+.1f", v.value()));
            }
            if (v.topPercent() != null) {
                line.append("（上位 ").append(v.topPercent()).append("%）");
            }
            if (!picked && !v.reason().isEmpty()) {
                line.append(" … ").append(v.reason());
            }
            if (deck != null && item.kind() == ShopLayout.Kind.CARD) {
                String fit = sts2advisor.deck.DeckAdvisor.describe(deck.fit(item.id()));
                if (!fit.isEmpty()) {
                    line.append("｜").append(fit);
                }
            }
            if (item.kind() == ShopLayout.Kind.CARD && item.id() != null && own.containsKey(item.id())) {
                line.append("｜").append(sts2advisor.reward.RankingText.ownText(own.get(item.id())));
            }
            lines.add(line.toString());
        }
        return lines;
    }
}

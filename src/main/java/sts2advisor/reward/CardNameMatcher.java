package sts2advisor.reward;

import java.util.Map;

/**
 * 【このファイルの役割】
 * 画面から読んだカード名を、ゲームファイルの公式カード名と照らし合わせて、どのカードかを決める係です。
 *
 * 決め方：
 *   1. 公式の名前と完全に一致すれば、そのカード
 *   2. 一致しなければ、いちばん似ている名前を探す。似ている度合いが基準以上なら、そのカード
 *      （例：「精度上塾」→「精度上昇」）
 *   3. どれとも似ていなければ「見つからない」
 *
 * 似ている度合いは「編集距離」で測ります。
 * 編集距離は、片方の文字列をもう片方に変えるのに、1文字の追加・削除・置き換えが何回いるかの数です。
 * これを長い方の文字数で割って、0（全く違う）〜1（同じ）の値にします。
 */
public class CardNameMatcher {

    /** 「似ている」とみなす下限（0〜1）。2026-11-11 の検証で、0.6 以上ならおおむね正しいカードだった */
    static final double MIN_SIMILARITY = 0.6;

    /**
     * 照合の結果。
     *
     * @param readName   画面から読んだ名前
     * @param cardId     決まったカードのID。見つからなければ null
     * @param officialName 決まったカードの公式の名前。見つからなければ null
     * @param similarity 似ている度合い（完全一致なら 1.0）
     * @param method     どうやって決めたか（例："名前が完全一致"、"名前と効果テキスト"）
     */
    public record Match(String readName, String cardId, String officialName, double similarity, String method) {
        /**
         * @return 完全に一致したなら true
         */
        public boolean exact() {
            return similarity >= 1.0;
        }
    }

    /** 「公式のカード名 → カードID」の対応 */
    private final Map<String, String> idsByName;

    /**
     * @param idsByName 「公式のカード名 → カードID」の対応（CardDatabase.findGameCardIdsByJapaneseName の結果）
     */
    public CardNameMatcher(Map<String, String> idsByName) {
        this.idsByName = idsByName;
    }

    /**
     * 読んだ名前に当てはまるカードを探します。
     *
     * @param readName 画面から読んだ名前
     * @return 照合の結果
     */
    public Match match(String readName) {
        // 空白や、強化済みを表す「+」は取り除いてから比べる（例：「強打+」→「強打」）
        String name = readName.replaceAll("[\\s+＋]", "");

        String exactId = idsByName.get(name);
        if (exactId != null) {
            return new Match(readName, exactId, name, 1.0, "名前が完全一致");
        }

        String bestName = null;
        double bestScore = 0;
        for (String official : idsByName.keySet()) {
            double score = similarity(name, official);
            if (score > bestScore) {
                bestScore = score;
                bestName = official;
            }
        }
        if (bestName != null && bestScore >= MIN_SIMILARITY) {
            return new Match(readName, idsByName.get(bestName), bestName, bestScore, "名前が似ている");
        }
        return new Match(readName, null, null, bestScore, "見つからない");
    }

    /**
     * 2つの文字列が似ている度合いを、0（全く違う）〜1（同じ）で求めます。
     *
     * @param a 文字列1
     * @param b 文字列2
     * @return 似ている度合い
     */
    static double similarity(String a, String b) {
        int longer = Math.max(a.length(), b.length());
        if (longer == 0) {
            return 1.0;
        }
        return 1.0 - (double) editDistance(a, b) / longer;
    }

    /**
     * 編集距離（a を b に変えるのに必要な、1文字の追加・削除・置き換えの最少回数）を求めます。
     * 表を少しずつ埋めていく方法（動的計画法）で計算します。
     *
     * @param a 文字列1
     * @param b 文字列2
     * @return 編集距離
     */
    static int editDistance(String a, String b) {
        // previous[j] = a の前の行までと、b の先頭 j 文字との編集距離
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j; // 空の文字列から b の先頭 j 文字を作るには j 回の追加が必要
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int delete = previous[j] + 1;
                int insert = current[j - 1] + 1;
                current[j] = Math.min(replace, Math.min(delete, insert));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}

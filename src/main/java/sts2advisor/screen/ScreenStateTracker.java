package sts2advisor.screen;

/**
 * 【このファイルの役割】
 * 1回ごとの判定結果の「揺れ」を抑えて、落ち着いた画面の種類を決める係です。
 *
 * 揺れの原因（2026-11-12 にゲーム中で確認）：
 *   カードや品物にマウスを乗せると、説明の枠が「カードを選択」「スキップ」や価格の文字に重なって読めなくなり、
 *   カード報酬やショップの画面なのに、1秒だけ「その他」と判定されることがある。
 *
 * 決まり：
 *   ・カード報酬・ショップへは、1回見つけたらすぐに切り替える（おすすめを早く出すため）
 *   ・「その他」へは、決まった回数続けて「その他」だったときだけ切り替える（一瞬読めなかっただけなら無視する）
 */
public class ScreenStateTracker {

    /** 「その他」に切り替えるのに必要な、続けての回数（1秒ごとに撮るなら、約3秒） */
    private final int otherThreshold;

    /** 今の落ち着いた画面の種類 */
    private ScreenType current = ScreenType.OTHER;

    /** 「その他」が今いくつ続いているか */
    private int otherStreak = 0;

    /**
     * @param otherThreshold 「その他」に切り替えるのに必要な、続けての回数（1以上）
     */
    public ScreenStateTracker(int otherThreshold) {
        this.otherThreshold = Math.max(1, otherThreshold);
    }

    /**
     * 新しい判定結果を受け取り、落ち着いた画面の種類を返します。
     *
     * @param detected 今回撮影した画面の判定結果
     * @return 揺れを抑えたあとの画面の種類
     */
    public ScreenType update(ScreenType detected) {
        if (detected == ScreenType.OTHER) {
            otherStreak++;
            // 「その他」が決まった回数続いたときだけ切り替える
            if (otherStreak >= otherThreshold) {
                current = ScreenType.OTHER;
            }
        } else {
            // カード報酬・ショップは、見つけたらすぐに切り替える
            otherStreak = 0;
            current = detected;
        }
        return current;
    }
}

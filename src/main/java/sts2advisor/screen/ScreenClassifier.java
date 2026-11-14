package sts2advisor.screen;

import java.util.regex.Pattern;

/**
 * 【このファイルの役割】
 * OCR で読んだ文字から、画面の種類（カード報酬・ショップ・その他）を判定する係です（機能要件 F-02）。
 *
 * 判定の手がかり（2026-11-11 の検証で確認したもの。docs/screen_reading_survey.md を参照）：
 *   ・カード報酬：画面に「カードを選択」と「スキップ」の両方の文字がある
 *   ・ショップ  ：価格（コインの絵＋数字。OCR ではコインが「◎」と読まれる）がたくさん並んでいる
 *                 ※ 画面の一番上にある所持ゴールドも同じ形なので、上の帯の部分は数えない
 *   ・その他    ：上のどちらでもない
 */
public class ScreenClassifier {

    /** ショップと判定するのに必要な価格の数（ショップには品物が10個以上並ぶので、余裕を見て5個） */
    static final int MIN_PRICES_FOR_SHOP = 5;

    /** 画面の上から何割までを「上の帯」（HP・ゴールドなどの表示）とみなして無視するか */
    static final double TOP_BAR_RATIO = 0.12;

    /**
     * 価格の形：コインの絵（OCR では「◎」などと読まれる）のあとに、2〜3桁の数字が続くもの。
     * 例："◎77"、"◎168"
     */
    private static final Pattern PRICE = Pattern.compile("^[◎◉©@]\\d{2,3}$");

    /**
     * OCR の結果から、画面の種類を判定します。
     *
     * @param result OCR で画面全体を読んだ結果
     * @return 画面の種類
     */
    public ScreenType classify(OcrResult result) {
        if (isCardReward(result)) {
            return ScreenType.CARD_REWARD;
        }
        if (countPrices(result) >= MIN_PRICES_FOR_SHOP) {
            return ScreenType.SHOP;
        }
        return ScreenType.OTHER;
    }

    /**
     * カード報酬の画面かどうかを調べます。
     * 「カードを選択」と「スキップ」の両方の文字があればカード報酬とみなします。
     *
     * @param result OCR の結果
     * @return カード報酬の画面なら true
     */
    boolean isCardReward(OcrResult result) {
        boolean hasTitle = false;
        boolean hasSkip = false;
        for (OcrLine line : result.lines()) {
            String text = normalize(line.text());
            if (isRewardTitle(text)) {
                hasTitle = true;
            }
            if (text.contains("スキップ")) {
                hasSkip = true;
            }
        }
        return hasTitle && hasSkip;
    }

    /**
     * カード報酬の見出し「カードを選択」かどうかを調べます。
     * 4K の画面では OCR が「カード.選択.」「カ.+下.選択.」のように読み違えた（2026-11-12）ので、少しの崩れは認める。
     * （「デッキにカードを追加」などは「選択」を含まないので、当てはまらない）
     *
     * @param normalized normalize で記号を取り除いた文字
     * @return 見出しなら true
     */
    public static boolean isRewardTitle(String normalized) {
        return REWARD_TITLE.matcher(normalized).find();
    }

    /**
     * カード報酬の見出しの形（「カードを選択」）。4K の画面では OCR が次のように読み違えた（2026-11-12）ので、どちらも認める：
     *   ・「カード.選択.」（「を」が抜ける）→ 「カード」＋1文字まで＋「選択」
     *   ・「カ.+下.選択.」（「ード」も崩れる）→ 「カ」で始まり「選択」で終わる、短い行（記号を除いて7文字まで）
     */
    private static final Pattern REWARD_TITLE = Pattern.compile("カード.?選択|^カ.{0,4}選択$");

    /**
     * OCR で読んだ文字から、空白や点などの記号を取り除きます（目印の文字を探しやすくするため）。
     * OCR は文字の間に空白や点を入れることがある（例：「カードを.選択」。2026-11-12 にゲーム中で確認）。
     *
     * @param text OCR で読んだ文字
     * @return 空白と点・中黒・句読点などを取り除いた文字
     */
    public static String normalize(String text) {
        return text.replaceAll("[\\s.．。、,，・･:：'`′\"]", "");
    }

    /**
     * 画面にある価格の数を数えます（上の帯にある所持ゴールドは数えない）。
     *
     * @param result OCR の結果
     * @return 価格の形をした行の数
     */
    int countPrices(OcrResult result) {
        int topBarBottom = (int) (result.height() * TOP_BAR_RATIO);
        int count = 0;
        for (OcrLine line : result.lines()) {
            if (line.y() < topBarBottom) {
                continue; // 上の帯（所持ゴールドなど）は数えない
            }
            if (PRICE.matcher(line.text().replaceAll("\\s", "")).matches()) {
                count++;
            }
        }
        return count;
    }
}

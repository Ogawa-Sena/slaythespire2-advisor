package sts2advisor.screen;

/**
 * 【このファイルの役割】
 * 画面の種類（機能要件 F-02 で判定するもの）の一覧です。
 * enum（列挙型）は、決まった選択肢だけを持つ型を作る Java の書き方です。
 */
public enum ScreenType {
    /** カード報酬の画面（3枚のカードから1枚選ぶか、スキップする） */
    CARD_REWARD("カード報酬"),
    /** ショップの画面（品物と価格が並んでいる） */
    SHOP("ショップ"),
    /** それ以外の画面（戦闘、マップ、イベントなど。おすすめは出さない） */
    OTHER("その他");

    /** 画面に表示するための日本語の名前 */
    private final String label;

    /**
     * @param label 日本語の名前
     */
    ScreenType(String label) {
        this.label = label;
    }

    /**
     * @return 日本語の名前（例："カード報酬"）
     */
    public String label() {
        return label;
    }
}

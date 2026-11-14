package sts2advisor.reward;

import sts2advisor.db.CardDatabase.CardText;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 【このファイルの役割】
 * AI が読んだカード名が公式の名前と一致しないとき、ほかの手がかりも使ってどのカードかを決める係です。
 * （2026-11-12 追加。AI が「微光」を「微兆」、「溶鉱炉」を「鋳鉄炉」と読み違えたため）
 *
 * 使う手がかり：
 *   1. 名前の似かた（CardNameMatcher と同じ「編集距離」）
 *   2. 効果テキストの似かた：Windows の OCR が読んだカードの説明文と、DB の効果テキストを比べる。
 *      名前を1文字読み違えても、説明文はほぼ一致するので、強い手がかりになる
 *   3. キャラ：今のキャラのカードと無色カードだけを候補にする（似た名前のほかのキャラのカードを除く）
 *
 * 決め方：
 *   1. 名前が完全に一致すれば、そのカード（どのキャラのカードでもよい）
 *   2. 候補ごとに「点数 = (名前の似かた + 効果テキストの似かた) ÷ 2」を求める
 *      （説明文が読めていないときは、名前の似かただけ）
 *   3. いちばん点数の高い候補が、基準（MIN_SCORE）以上で、2番目より MIN_MARGIN 以上高ければ、そのカード
 *      決まらず、説明文が読めているときは、全キャラのカードの中でもう一度同じように探す
 *   4. それでも決まらなければ、これまでどおり CardNameMatcher で全カードから名前だけで探す
 *
 * 似かたの数え方（効果テキスト）：
 *   2文字ずつの組（例：「カード」→「カー」「ード」）に分け、共通する組の割合を求める（ダイス係数）。
 *   語順が少し違っても、OCR が数文字読み違えても、だいたいの似かたが分かる。
 */
public class CardResolver {

    /** これより点数が低ければ、そのカードとは決めない */
    static final double MIN_SCORE = 0.5;

    /** 1位と2位の点数の差がこれより小さければ、どちらか分からないので決めない */
    static final double MIN_MARGIN = 0.1;

    /** 効果テキストの似かたを使うのに必要な、OCR が読んだ説明文の最低文字数 */
    static final int MIN_TEXT_LENGTH = 6;

    /** 画面の説明文とゲームファイルの効果テキストの似かたが、これ以上なら「OCR がちゃんと読めている」とみなす */
    static final double READABLE_LINE = 0.4;

    /** カードID → ゲームファイルの日本語の効果テキスト（数値の部分 {…} と飾りの記号を除いたもの） */
    private final Map<String, String> gameTexts = new HashMap<>();

    /** 効果テキストの食い違いを調べるのに必要な、説明文の長さ（短いと似かたがぶれるので、長めにする） */
    static final int CHECK_TEXT_LENGTH = 15;

    /** 全カードの名前と効果テキスト */
    private final List<CardText> cards;

    /** 名前だけで探す、これまでの照合係（最後の手段） */
    private final CardNameMatcher nameMatcher;

    /** 効果テキストを比べやすい形にしたもの（カードID → 整えた文字） */
    private final Map<String, String> cleanTexts = new HashMap<>();

    /**
     * @param cards       全カードの名前と効果テキスト（CardDatabase.findCardTexts の結果）
     * @param nameMatcher 名前だけで探す照合係
     */
    public CardResolver(List<CardText> cards, CardNameMatcher nameMatcher) {
        this(cards, nameMatcher, Map.of());
    }

    /**
     * @param cards            全カードの名前と効果テキスト（CardDatabase.findCardTexts の結果）
     * @param nameMatcher      名前だけで探す照合係
     * @param gameDescriptions ゲームファイルの日本語の効果テキスト（CardDatabase.findGameDescriptions。無ければ空）
     */
    public CardResolver(List<CardText> cards, CardNameMatcher nameMatcher, Map<String, String> gameDescriptions) {
        this.cards = cards;
        this.nameMatcher = nameMatcher;
        for (CardText c : cards) {
            cleanTexts.put(c.cardId(), clean(c.descriptionJa()));
        }
        gameDescriptions.forEach((id, text) -> gameTexts.put(id, clean(text.replaceAll("\\{[^}]*\\}", ""))));
    }

    /**
     * データベースから全カードの名前と効果テキストを読んで、この係を作ります。
     *
     * @param dbFile データベースファイルの場所
     * @return カードを特定する係
     * @throws Exception データベースを読めなかったとき
     */
    public static CardResolver fromDatabase(java.nio.file.Path dbFile) throws Exception {
        try (sts2advisor.db.CardDatabase db = new sts2advisor.db.CardDatabase(dbFile)) {
            return new CardResolver(db.findCardTexts(), new CardNameMatcher(db.findGameCardIdsByJapaneseName()),
                    db.findGameDescriptions());
        }
    }

    /**
     * 読んだ名前と説明文から、どのカードかを決めます。
     *
     * @param readName  AI が読んだカード名
     * @param ocrText   そのカードの説明文として OCR が読んだ文字（無ければ空文字や null でよい）
     * @param character 今のキャラ（例："REGENT"。分からなければ null。そのときは全キャラを候補にする）
     * @return 照合の結果
     */
    public CardNameMatcher.Match resolve(String readName, String ocrText, String character) {
        // 1. 名前が完全に一致すれば、それで決まり
        CardNameMatcher.Match byName = nameMatcher.match(readName);
        if (byName.exact()) {
            return byName;
        }

        String name = readName.replaceAll("[\\s+＋]", "");
        String text = clean(ocrText);
        boolean useText = text.length() >= MIN_TEXT_LENGTH;
        String chara = character != null ? character.toLowerCase(Locale.ROOT) : null;

        // 2・3. 今のキャラのカードと無色カードの中で探す
        CardNameMatcher.Match found = findBest(readName, name, text, useText, chara);
        if (found != null) {
            return found;
        }
        // 見つからず、説明文が読めているときは、全キャラのカードの中でもう一度探す
        // （セーブのキャラが古いなど、キャラの絞り込みが外れていた場合のため。2026-11-12 に確認）
        if (useText && chara != null) {
            found = findBest(readName, name, text, true, null);
            if (found != null) {
                return found;
            }
        }
        // 4. 決まらなければ、名前だけで全カードから探した結果を使う
        return byName;
    }

    /**
     * 画面で読んだ説明文が、DB の効果テキストとどれくらい似ているかを求めます（F-06。パッチで効果が変わったかの確認）。
     * 記録した画面で測ったところ、正しいカードなら 0.47〜1.0 だった（2026-11-12）。OCR の読み違いがあっても 0.3 は超える。
     *
     * @param cardId  カードID
     * @param ocrText そのカードの説明文として OCR が読んだ文字
     * @return 似かた（0〜1）。説明文が短すぎて比べられないときや、DB にカードが無いときは null
     */
    public Double textSimilarity(String cardId, String ocrText) {
        String text = clean(ocrText);
        String db = cleanTexts.get(cardId);
        // DB の効果テキストが英語のカード（パッチで変わった・増えたカード。ImportPatchCardsMain）は、
        // 日本語の画面の文字と比べられないので確認しない
        if (text.length() < CHECK_TEXT_LENGTH || db == null || !db.matches(".*[\\u3040-\\u30ff].*")) {
            return null;
        }
        // 画面の説明文が、ゲームファイルの今の効果テキストとも似ていなければ、OCR がうまく読めていないとみなして確かめない
        // （2026-11-13：4K の画面で OCR が「スキルプ終タすまを…」のように崩れて読み、間違った警告が出たため）
        String game = gameTexts.get(cardId);
        if (game != null && !game.isEmpty() && dice(text, game) < READABLE_LINE) {
            return null;
        }
        return dice(text, db);
    }

    /**
     * 候補ごとに点数を求め、1位がはっきりしていればそのカードを返します。
     *
     * @param readName AI が読んだ名前（そのまま）
     * @param name     読んだ名前から空白と「+」を除いたもの
     * @param text     OCR が読んだ説明文（整えたもの）
     * @param useText  説明文を使うか
     * @param chara    候補にするキャラ（小文字。null なら全キャラ）
     * @return 決まったカード。決まらなければ null
     */
    private CardNameMatcher.Match findBest(String readName, String name, String text, boolean useText, String chara) {
        CardText best = null;
        double bestScore = -1;
        double secondScore = -1;
        double bestNameScore = 0;
        for (CardText c : cards) {
            if (c.nameJa() == null) {
                continue;
            }
            if (chara != null && !c.character().equals(chara) && !c.character().equals("colorless")) {
                continue; // 今のキャラのカードと無色カードだけを候補にする
            }
            double nameScore = CardNameMatcher.similarity(name, c.nameJa());
            double score = useText ? (nameScore + dice(text, cleanTexts.get(c.cardId()))) / 2 : nameScore;
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = c;
                bestNameScore = nameScore;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        // 1位が基準以上で、2位よりはっきり高ければ、そのカード
        if (best == null || bestScore < MIN_SCORE || bestScore - secondScore < MIN_MARGIN) {
            return null;
        }
        String method;
        if (useText) {
            method = String.format("名前と効果テキスト（名前 %.2f%s）", bestNameScore, chara == null ? "、全キャラから" : "");
        } else {
            method = "名前（キャラで絞り込み）";
        }
        return new CardNameMatcher.Match(readName, best.cardId(), best.nameJa(), bestScore, method);
    }

    /**
     * 文字を比べやすい形に整えます。
     * 飾りの記号（[gold] や [star:1] など）、空白、改行、句読点を取り除きます。
     *
     * @param text 元の文字（null でもよい）
     * @return 整えた文字
     */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("[\\s。、，．,.・:：「」()（）!！?？]", "");
    }

    /**
     * 2つの文字の似かたを、2文字ずつの組の重なり（ダイス係数）で求めます。
     *
     * @param a 文字1（整えたもの）
     * @param b 文字2（整えたもの）
     * @return 似かた（0＝共通の組なし 〜 1＝同じ）
     */
    static double dice(String a, String b) {
        if (a == null || b == null || a.length() < 2 || b.length() < 2) {
            return 0;
        }
        Map<String, Integer> pairs = new HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++) {
            pairs.merge(a.substring(i, i + 2), 1, Integer::sum);
        }
        int common = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String pair = b.substring(i, i + 2);
            Integer left = pairs.get(pair);
            if (left != null && left > 0) {
                common++;
                pairs.put(pair, left - 1);
            }
        }
        return 2.0 * common / ((a.length() - 1) + (b.length() - 1));
    }
}

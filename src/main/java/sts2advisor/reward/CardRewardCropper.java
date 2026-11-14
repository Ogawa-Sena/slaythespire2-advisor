package sts2advisor.reward;

import sts2advisor.screen.OcrLine;
import sts2advisor.screen.OcrResult;
import sts2advisor.screen.ScreenClassifier;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * 【このファイルの役割】
 * カード報酬の画面から、カード名が書かれた帯の部分だけを切り出す係です。
 * AI に送るのは文字の部分だけにして、カードの絵は送りません（速くするためと、カードの絵に AI の判断が引っぱられないようにするため）。
 *
 * 切り出す場所の決め方：
 *   ウィンドウの大きさや位置が変わっても対応できるよう、OCR で見つけた2つの文字を目印にします。
 *     ・上の「カードを選択」の帯
 *     ・下の「スキップ」のボタン
 *   この2つの間の長さを「1」としたとき、カード名の帯は上から 0.30〜0.375 の位置にあります
 *   （2026-11-11 のスクリーンショットで測った値。「カードを選択」が y=259、「スキップ」が y=866、
 *     カード名の帯が y=448〜482）。
 *   横は「カードを選択」の真ん中を中心に、左右へ同じ長さ分を切り出します（カードが4枚並んでも入る幅）。
 */
public class CardRewardCropper {

    /**
     * カード名の帯の上端（目印の間の長さに対する割合）。
     * 帯そのものは 0.30 から始まるが、マウスを乗せたカードは拡大されて名前が上にずれるので
     * （2026-11-12 にゲーム中で確認）、上に余裕を持たせる。帯より上は背景なので、カードの絵は入らない
     */
    static final double NAME_TOP = 0.22;

    /** カード名の帯の下端（目印の間の長さに対する割合。これより下はカードの絵になる） */
    static final double NAME_BOTTOM = 0.375;

    /** 中心から左右に切り出す幅（目印の間の長さに対する割合） */
    static final double HALF_WIDTH = 1.0;

    /**
     * カード名の帯の場所を求めます。
     *
     * @param ocr 画面全体を OCR で読んだ結果
     * @return 帯の場所。目印の文字が見つからなければ空（Optional.empty）
     */
    public Optional<Rectangle> findNameStrip(OcrResult ocr) {
        OcrLine title = findTitle(ocr);
        OcrLine skip = title != null ? findSkipButton(ocr, title) : null;
        if (title == null || skip == null) {
            return Optional.empty();
        }

        // 目印の間の長さ（「カードを選択」の上端から「スキップ」の上端まで）
        double span = skip.y() - title.y();
        int centerX = title.x() + title.w() / 2;

        int top = (int) (title.y() + span * NAME_TOP);
        int bottom = (int) (title.y() + span * NAME_BOTTOM);
        int left = (int) (centerX - span * HALF_WIDTH);
        int right = (int) (centerX + span * HALF_WIDTH);

        // 画面の外にはみ出さないようにする
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(ocr.width(), right);
        bottom = Math.min(ocr.height(), bottom);
        // 目印を読み違えて、切り出す場所がつぶれたときは使わない（次の撮影で試す）
        if (right - left < MIN_STRIP_SIZE || bottom - top < MIN_STRIP_SIZE) {
            return Optional.empty();
        }
        return Optional.of(new Rectangle(left, top, right - left, bottom - top));
    }

    /** 切り出す場所の幅・高さの最小値（ピクセル）。これより小さければ目印の読み違いとみなす */
    static final int MIN_STRIP_SIZE = 10;

    /**
     * 「スキップ」のボタンを探します。
     * 表示ウィンドウなど、ゲーム以外の場所にも「1位 スキップ」のような文字が映ることがあるため
     * （2026-11-12 に確認）、次の条件をすべて満たす行だけを選びます。
     *   ・記号を除いた文字が「スキップ」だけ（「1位スキップ」などは除く）
     *   ・「カードを選択」より下にある
     *   ・その中で一番下にある（ボタンは画面の下の方にあるため）
     *
     * @param ocr   OCR の結果
     * @param title 「カードを選択」の行
     * @return 見つかった行。なければ null
     */
    private static OcrLine findSkipButton(OcrResult ocr, OcrLine title) {
        OcrLine best = null;
        for (OcrLine line : ocr.lines()) {
            if (ScreenClassifier.normalize(line.text()).equals("スキップ") && line.y() > title.y()
                    && (best == null || line.y() > best.y())) {
                best = line;
            }
        }
        return best;
    }

    /** カードの間隔（目印の間の長さに対する割合。2026-11-12 に2つの画面で測った値） */
    static final double CARD_SPACING = 0.54;

    /** カードの説明文がある高さ（目印の間の長さに対する割合） */
    static final double TEXT_TOP = 0.58;
    static final double TEXT_BOTTOM = 0.80;

    /** カードの中心から左右どこまでを、そのカードの説明文とみなすか（目印の間の長さに対する割合） */
    static final double TEXT_HALF_WIDTH = 0.25;

    /**
     * OCR の結果から、カードごとの説明文を集めます（読み違えたカード名を、説明文から特定するのに使う）。
     * カードは「カードを選択」の真ん中を中心に、同じ間隔で左右に並んでいるとして、場所を計算します。
     *
     * @param ocr   画面全体を OCR で読んだ結果
     * @param count カードの枚数
     * @return 左から順に、カードごとの説明文（見つからなければ空文字）。目印が無ければ全部空文字
     */
    public java.util.List<String> descriptionTexts(OcrResult ocr, int count) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        OcrLine title = findTitle(ocr);
        OcrLine skip = title != null ? findSkipButton(ocr, title) : null;
        for (int i = 0; i < count; i++) {
            if (title == null || skip == null) {
                texts.add("");
                continue;
            }
            double span = skip.y() - title.y();
            double centerX = title.x() + title.w() / 2.0 + (i - (count - 1) / 2.0) * CARD_SPACING * span;
            StringBuilder text = new StringBuilder();
            // 上から順に並んでいるとは限らないので、高さの順に並べてからつなげる
            ocr.lines().stream()
                    .filter(l -> {
                        double cx = l.x() + l.w() / 2.0;
                        double cy = l.y() + l.h() / 2.0;
                        return Math.abs(cx - centerX) <= TEXT_HALF_WIDTH * span
                                && cy >= title.y() + TEXT_TOP * span && cy <= title.y() + TEXT_BOTTOM * span;
                    })
                    .sorted(java.util.Comparator.comparingInt(OcrLine::y))
                    .forEach(l -> text.append(l.text()));
            texts.add(text.toString());
        }
        return texts;
    }

    /**
     * 画像から指定した場所を切り出します。
     *
     * @param image 元の画像
     * @param area  切り出す場所
     * @return 切り出した画像（元の画像とは別の画像として作る）
     */
    public BufferedImage crop(BufferedImage image, Rectangle area) {
        // ゲームのウィンドウが小さいと文字も小さく、AI が漢字を読み違えやすい（2026-11-12 に確認）。
        // そこで、お手本の大きさ（帯の幅が約 MIN_STRIP_WIDTH ピクセル）より小さければ拡大する
        double zoom = Math.max(1.0, MIN_STRIP_WIDTH / area.width);
        int w = (int) Math.round(area.width * zoom);
        int h = (int) Math.round(area.height * zoom);
        BufferedImage part = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = part.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, w, h, area.x, area.y, area.x + area.width, area.y + area.height, null);
        g.dispose();
        return part;
    }

    /** AI に送るカード名の帯の、最低限の幅（ピクセル）。お手本の画面（1920×1033）での帯の幅に近い値 */
    static final double MIN_STRIP_WIDTH = 1200;

    /**
     * OCR の結果から、見出し「カードを選択」の行を探します（空白や点などの記号は無視する。「を」は読み落としてもよい）。
     *
     * @param ocr OCR の結果
     * @return 見つかった行。なければ null
     */
    private static OcrLine findTitle(OcrResult ocr) {
        for (OcrLine line : ocr.lines()) {
            if (ScreenClassifier.isRewardTitle(ScreenClassifier.normalize(line.text()))) {
                return line;
            }
        }
        return null;
    }
}

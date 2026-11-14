package sts2advisor.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sts2advisor.ai.ClaudeVision;
import sts2advisor.reward.CardNameMatcher;
import sts2advisor.reward.CardResolver;
import sts2advisor.screen.OcrLine;
import sts2advisor.screen.OcrResult;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【このファイルの役割】
 * ショップ画面から、並んでいる品物（カード・レリック・ポーション・カード削除）と価格を読み取る係です
 * （機能要件 F-03。MVP のステップ8-3）。
 *
 * 処理の流れ：
 *   1. OCR で読めた価格を目印に、品物の場所を求める（ShopLayout）
 *   2. レリック・ポーションは、アイコンの照らし合わせで特定する（IconMatcher）
 *   3. カード名と全部の価格は、AI にまとめて読んでもらう（ClaudeVision）
 *      ・品物ごとに「カード名の帯」と「価格」を切り出し、1品物1行に並べた画像を1枚作って送る
 *        （1回で済むので速い。カードの絵は送らない）
 *      ・赤い価格（お金が足りない）や緑の価格（値引き）は OCR では読めないことがあるため、AI に読ませる
 *   4. カード名は、ゲームの公式名と照らし合わせる（CardResolver）。一致しないときは、
 *      OCR が読んだカードの説明文と今のキャラも手がかりにして特定する
 *   5. OCR でも価格が読めていれば比べ、違っていれば OCR の値を使い、印を付ける
 *      （OCR は白い文字ならとても正確なため）
 *
 * 売り切れた場所は、AI が価格を読めないので「無し」として扱う。
 */
public class ShopReader {

    /**
     * 読み取った品物1つ分。
     *
     * @param kind       品物の種類
     * @param index      同じ種類の中で何番目か（0から）
     * @param id         カード・レリック・ポーションの ID（カード削除や、特定できなかったときは null）
     * @param name       表示する名前
     * @param price      価格（読めなければ null）
     * @param confidence 特定の確かさの説明（「完全一致」「似ている度合い 0.83」「自信なし」など）
     * @param note       補足（価格の読みが食い違った、など。なければ空文字）
     */
    public record Item(ShopLayout.Kind kind, int index, String id, String name, Integer price,
                       String confidence, String note) {
    }

    /**
     * 読み取りの結果。
     *
     * @param items    並んでいる品物（売り切れは入らない）
     * @param layout   品物の場所を求めた結果
     * @param sheet    AI に送った画像
     * @param aiAnswer AI の答え
     * @param aiMillis AI にかかった時間（ミリ秒）
     * @param gold     画面の所持ゴールド（AI が読んだもの。読めなければ null）
     */
    public record Result(List<Item> items, ShopLayout.Result layout, BufferedImage sheet, String aiAnswer,
                         long aiMillis, Integer gold) {
    }

    /** AI への質問。答えの形を JSON に決めておくと、プログラムで扱いやすい */
    private static final String QUESTION = """
            この画像は、カードゲームのショップ画面から、品物ごとに「カード名」と「価格」を切り出して、
            1品物を1行にして並べたものです（左端の数字が行番号）。
            各行について、行の左側に写っている文字をカード名として、右側の数字を価格として読んでください。
            左側が黒く塗られている行は、カード名を "" にしてください。
            価格が写っていない行は、価格を null にしてください。
            カードの絵の一部や説明の文字が写り込んでいても、カード名の帯の文字だけを読んでください。
            カード名の後ろに「+」が付いていれば、それも含めてください。
            行番号の順に、次のような JSON の配列だけを出力してください（他には何も書かないこと）。
            [{"row":1,"name":"ガラガラ","price":76},{"row":2,"name":"","price":155}]
            行番号が「G」の行があれば、それは所持ゴールドの表示です。コインの絵（◎）のすぐ右にある数字を読み、
            配列の最後に {"row":"G","gold":433} の形で入れてください（HP などほかの数字は読まないこと）。""";

    /** お手本の大きさで、カード名の帯の中心が、価格の中心より何ピクセル上か */
    static final double NAME_ABOVE_PRICE = 259;

    /** お手本の大きさで、カード名の帯を切り出す範囲（中心からの左右・上下） */
    static final double NAME_HALF_WIDTH = 100;
    static final double NAME_UP = 38;   // マウスを乗せて拡大されたカードは名前が上にずれるので、上を広めに
    static final double NAME_DOWN = 28;

    /** お手本の大きさで、価格を切り出す範囲（中心からの左右・上下） */
    // 2026-11-13：48 → 72。4K 画面で上の段のカードの場所が少し左にずれ、価格の数字の右側が切れて
    // 「52」を「5」と読んだため、少しずれても数字が全部入るように広げた（隣の価格までは十分離れている）
    static final double PRICE_HALF_WIDTH = 72;
    static final double PRICE_HALF_HEIGHT = 17;

    /** AI に送る画像の、1行の高さと各欄の幅（ピクセル） */
    private static final int ROW_HEIGHT = 60;
    private static final int LABEL_WIDTH = 40;
    private static final int NAME_WIDTH = 2 * (int) NAME_HALF_WIDTH;
    private static final int PRICE_WIDTH = 2 * (int) PRICE_HALF_WIDTH;

    /** 価格の行の形（例："◎77"） */
    private static final Pattern PRICE = Pattern.compile("^[◎◉©@]?(\\d{2,3})$");

    /** 画面の上の帯にある、所持ゴールドの表示（例："◎433"）。コインの絵を OCR は「◎」と読む */
    private static final Pattern GOLD = Pattern.compile("[◎◉](\\d{1,4})");

    /** 所持ゴールドの表示を探す範囲（画面の上から何割まで） */
    private static final double GOLD_AREA_TOP_RATIO = 0.10;

    private final ShopLayout layout = new ShopLayout();
    private final ClaudeVision vision;
    private final CardResolver cardResolver;
    private final IconMatcher relicMatcher;
    private final IconMatcher potionMatcher;
    private final Map<String, String> relicNames;
    private final Map<String, String> potionNames;

    /**
     * @param vision        AI に画像を見せる係（起動したまま使い回す）
     * @param cardResolver  カードを特定する係
     * @param relicMatcher  レリックのアイコンを照らし合わせる係
     * @param potionMatcher ポーションのアイコンを照らし合わせる係
     * @param relicNames    レリックの ID → 日本語の名前
     * @param potionNames   ポーションの ID → 日本語の名前
     */
    public ShopReader(ClaudeVision vision, CardResolver cardResolver, IconMatcher relicMatcher,
                      IconMatcher potionMatcher, Map<String, String> relicNames, Map<String, String> potionNames) {
        this.vision = vision;
        this.cardResolver = cardResolver;
        this.relicMatcher = relicMatcher;
        this.potionMatcher = potionMatcher;
        this.relicNames = relicNames;
        this.potionNames = potionNames;
    }

    /**
     * ショップ画面から、品物と価格を読み取ります。
     *
     * @param screen 画面全体の画像
     * @param ocr       同じ画面を OCR で読んだ結果
     * @param character 今のキャラ（例："REGENT"。分からなければ null）
     * @return 読み取り結果。品物の場所が分からなかったときは空
     * @throws IOException AI とのやり取りに失敗したとき
     */
    public Optional<Result> read(BufferedImage screen, OcrResult ocr, String character) throws IOException {
        // 1. 品物の場所を求める
        Optional<ShopLayout.Result> located = layout.locate(ocr);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        ShopLayout.Result where = located.get();
        List<ShopLayout.Slot> slots = where.slots();

        // 3. カード名と価格を AI にまとめて読んでもらう（所持ゴールドの表示が見つかれば、それも最後の行に入れる）
        Optional<OcrLine> goldLine = goldLine(ocr, screen.getHeight());
        BufferedImage sheet = buildSheet(screen, slots, where.scale(), goldLine.orElse(null));
        long start = System.currentTimeMillis();
        String answer = vision.ask(sheet, QUESTION);
        long aiMillis = System.currentTimeMillis() - start;
        List<JsonObject> rows = parseRows(answer);

        List<Item> items = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            ShopLayout.Slot slot = slots.get(i);
            JsonObject row = findRow(rows, i + 1);
            Integer aiPrice = row != null ? number(row.get("price")) : null;
            String aiName = row != null && row.has("name") && !row.get("name").isJsonNull()
                    ? row.get("name").getAsString().strip() : "";

            // 5. 価格を決める（OCR で読めていれば、そちらを優先する）
            Integer ocrPrice = ocrPrice(ocr, slot.priceCenter(), where.scale());
            Integer price = choosePrice(ocrPrice, aiPrice);
            String note = (ocrPrice != null && aiPrice != null && !ocrPrice.equals(aiPrice))
                    ? "価格の読みが食い違い（OCR " + ocrPrice + "、AI " + aiPrice + "）" : "";
            if (price == null) {
                continue; // 価格が無い＝売り切れ
            }

            // 2・4. 品物を特定する
            items.add(switch (slot.kind()) {
                case CARD -> cardItem(slot, aiName, cardText(ocr, slot.priceCenter(), where.scale()), character, price, note);
                case RELIC -> iconItem(slot, screen, where.scale(), relicMatcher, relicNames, price, note);
                case POTION -> iconItem(slot, screen, where.scale(), potionMatcher, potionNames, price, note);
                case REMOVAL -> new Item(slot.kind(), slot.index(), null, "カード削除", price, "", note);
            });
        }
        // 所持ゴールド（AI が読めたときだけ。読めなければ null で、呼び出し側がセーブの値を使う）
        Integer gold = null;
        for (JsonObject r : rows) {
            if (r.has("gold")) {
                gold = number(r.get("gold"));
            }
        }
        return Optional.of(new Result(items, where, sheet, answer, aiMillis, gold));
    }

    /**
     * 画面の上の帯から、所持ゴールドの表示がある OCR の行を探します。
     *
     * @param ocr          OCR の結果
     * @param screenHeight 画面の高さ
     * @return 「◎」と数字を含む行。見つからなければ空
     */
    static Optional<OcrLine> goldLine(OcrResult ocr, int screenHeight) {
        return ocr.lines().stream()
                .filter(l -> l.y() < screenHeight * GOLD_AREA_TOP_RATIO)
                .filter(l -> GOLD.matcher(l.text().replaceAll("\\s", "")).find())
                .findFirst();
    }

    /**
     * OCR で読んだ所持ゴールドを返します（買い物をしたかどうかの「きっかけ」にだけ使う）。
     * OCR は「◎433」を「◎4330」と読むことがあるので、この値をそのまま使ってはいけません。
     * 値が変わったら AI でショップを読み直し、正確な値は AI に読ませます。
     *
     * @param ocr          OCR の結果
     * @param screenHeight 画面の高さ
     * @return OCR が読んだ値。見つからなければ空
     */
    public static Optional<Integer> ocrGold(OcrResult ocr, int screenHeight) {
        return goldLine(ocr, screenHeight).map(l -> {
            Matcher m = GOLD.matcher(l.text().replaceAll("\\s", ""));
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        });
    }

    /**
     * カードの品物を作ります（AI が読んだ名前を、公式のカード名と照らし合わせる）。
     * 名前が一致しないときは、説明文とキャラも手がかりにする（CardResolver）。
     */
    private Item cardItem(ShopLayout.Slot slot, String aiName, String ocrText, String character, int price,
                          String note) {
        // "?" は「隠れて読めない」の意味（カード報酬の質問で AI にそう頼んでいて、同じ会話の中でショップでも使うことがある）
        if (aiName.isEmpty() || aiName.equals("?") || aiName.equals("？")) {
            return new Item(slot.kind(), slot.index(), null, "（カード名を読めませんでした）", price, "", note);
        }
        CardNameMatcher.Match m = cardResolver.resolve(aiName, ocrText, character);
        if (m.cardId() == null) {
            return new Item(slot.kind(), slot.index(), null, aiName, price, "見つかりません", note);
        }
        String confidence = m.exact() ? "完全一致"
                : String.format("%s %.2f（読み「%s」）", m.method(), m.similarity(), aiName);
        // 強化済み（名前の後ろに +）なら、表示にも + を付ける
        String name = m.officialName() + (aiName.endsWith("+") || aiName.endsWith("＋") ? "+" : "");
        return new Item(slot.kind(), slot.index(), m.cardId(), name, price, confidence, note);
    }

    /**
     * レリック・ポーションの品物を作ります（アイコンを照らし合わせる）。
     */
    private Item iconItem(ShopLayout.Slot slot, BufferedImage screen, double scale, IconMatcher matcher,
                          Map<String, String> names, int price, String note) {
        List<IconMatcher.Candidate> top = matcher.match(screen, slot.iconCenter(), scale, 2);
        IconMatcher.Candidate best = top.get(0);
        if (best.score() > IconMatcher.RELIABLE_SCORE) {
            // マウスの説明の枠で隠れているなど、当てにならないとき
            return new Item(slot.kind(), slot.index(), null, "（特定できませんでした）", price,
                    String.format("自信なし（点数 %.2f、いちばん近いのは %s）", best.score(), names.get(best.id())), note);
        }
        return new Item(slot.kind(), slot.index(), best.id(), names.get(best.id()), price,
                String.format("点数 %.2f", best.score()), note);
    }

    /**
     * AI に送る画像を作ります。1品物を1行にして、「行番号｜カード名の帯（カードのみ）｜価格」を並べます。
     * 大きさは、お手本の大きさにそろえます（ゲームのウィンドウが小さいときは拡大される）。
     *
     * @param screen 画面全体
     * @param slots  品物の場所
     * @param scale  お手本に対する大きさの倍率
     * @param gold   所持ゴールドの表示がある OCR の行（無ければ null。あれば最後に「G」の行として足す）
     * @return AI に送る画像
     */
    BufferedImage buildSheet(BufferedImage screen, List<ShopLayout.Slot> slots, double scale, OcrLine gold) {
        int width = LABEL_WIDTH + NAME_WIDTH + 10 + PRICE_WIDTH;
        int rowCount = slots.size() + (gold != null ? 1 : 0);
        BufferedImage sheet = new BufferedImage(width, ROW_HEIGHT * rowCount, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));

        for (int i = 0; i < slots.size(); i++) {
            ShopLayout.Slot slot = slots.get(i);
            int top = i * ROW_HEIGHT;
            g.setColor(Color.WHITE);
            g.drawString(String.valueOf(i + 1), 6, top + 36);
            Point p = slot.priceCenter();

            // カード名の帯（カードの場所だけ）
            if (slot.kind() == ShopLayout.Kind.CARD) {
                double cy = p.y - NAME_ABOVE_PRICE * scale;
                drawPart(g, screen, p.x - NAME_HALF_WIDTH * scale, cy - NAME_UP * scale,
                        p.x + NAME_HALF_WIDTH * scale, cy + NAME_DOWN * scale,
                        LABEL_WIDTH, top, NAME_WIDTH, ROW_HEIGHT);
            }
            // 価格
            int priceHeight = (int) (2 * PRICE_HALF_HEIGHT);
            drawPart(g, screen, p.x - PRICE_HALF_WIDTH * scale, p.y - PRICE_HALF_HEIGHT * scale,
                    p.x + PRICE_HALF_WIDTH * scale, p.y + PRICE_HALF_HEIGHT * scale,
                    LABEL_WIDTH + NAME_WIDTH + 10, top + (ROW_HEIGHT - priceHeight) / 2, PRICE_WIDTH, priceHeight);
            // 行の区切り線
            g.setColor(Color.DARK_GRAY);
            g.drawLine(0, top + ROW_HEIGHT - 1, width, top + ROW_HEIGHT - 1);
        }
        // 最後の行：所持ゴールドの表示（OCR の行の範囲を、縦横の比を保ったまま行に収める）
        if (gold != null) {
            int top = slots.size() * ROW_HEIGHT;
            g.setColor(Color.WHITE);
            g.drawString("G", 6, top + 36);
            int boxWidth = width - LABEL_WIDTH;
            double fit = Math.min((double) boxWidth / gold.w(), (double) (ROW_HEIGHT - 4) / gold.h());
            int dw = (int) (gold.w() * fit);
            int dh = (int) (gold.h() * fit);
            drawPart(g, screen, gold.x(), gold.y(), gold.x() + gold.w(), gold.y() + gold.h(),
                    LABEL_WIDTH, top + (ROW_HEIGHT - dh) / 2, dw, dh);
        }
        g.dispose();
        return sheet;
    }

    /**
     * 画面の一部（画面の外にはみ出す部分は切り落とす）を、指定した場所に拡大・縮小して描きます。
     */
    private static void drawPart(Graphics2D g, BufferedImage screen, double sx1, double sy1, double sx2, double sy2,
                                 int dx, int dy, int dw, int dh) {
        int x1 = (int) Math.max(0, Math.round(sx1));
        int y1 = (int) Math.max(0, Math.round(sy1));
        int x2 = (int) Math.min(screen.getWidth(), Math.round(sx2));
        int y2 = (int) Math.min(screen.getHeight(), Math.round(sy2));
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        g.drawImage(screen, dx, dy, dx + dw, dy + dh, x1, y1, x2, y2, null);
    }

    /** お手本の大きさで、カードの説明文がある範囲（価格の中心からの左右・上下） */
    static final double TEXT_HALF_WIDTH = 90;
    static final double TEXT_TOP = 220;
    static final double TEXT_BOTTOM = 30;

    /**
     * OCR の結果から、カードの説明文（価格の上の、カードの下半分にある文字）を集めます。
     *
     * @param ocr    OCR の結果
     * @param center 価格の中心
     * @param scale  お手本に対する大きさの倍率
     * @return 説明文（上から順につなげたもの。無ければ空文字）
     */
    private static String cardText(OcrResult ocr, Point center, double scale) {
        StringBuilder text = new StringBuilder();
        ocr.lines().stream()
                .filter(l -> {
                    double cx = l.x() + l.w() / 2.0;
                    double cy = l.y() + l.h() / 2.0;
                    return Math.abs(cx - center.x) <= TEXT_HALF_WIDTH * scale
                            && cy >= center.y - TEXT_TOP * scale && cy <= center.y - TEXT_BOTTOM * scale;
                })
                .sorted(java.util.Comparator.comparingInt(OcrLine::y))
                .forEach(l -> text.append(l.text()));
        return text.toString();
    }

    /**
     * OCR と AI が読んだ価格から、使う方を決めます。
     * ふだんは OCR を優先する（AI は数字を読み違えることがあるため）。ただし片方がもう片方の頭の部分と同じとき
     * （例：OCR「19」、AI「192」）は、赤・緑の価格で OCR が最後の数字を落としたとみなし、長い方を使う（2026-11-13）。
     *
     * @param ocrPrice OCR が読んだ価格（読めなければ null）
     * @param aiPrice  AI が読んだ価格（読めなければ null）
     * @return 使う価格（どちらも無ければ null）
     */
    static Integer choosePrice(Integer ocrPrice, Integer aiPrice) {
        if (ocrPrice == null) {
            return aiPrice;
        }
        if (aiPrice == null) {
            return ocrPrice;
        }
        String o = ocrPrice.toString();
        String a = aiPrice.toString();
        if (a.length() > o.length() && a.startsWith(o)) {
            return aiPrice;
        }
        return ocrPrice;
    }

    /**
     * OCR の結果から、指定した場所にある価格を探します。
     *
     * @param ocr    OCR の結果
     * @param center 価格の中心（ShopLayout で求めたもの）
     * @param scale  お手本に対する大きさの倍率
     * @return 価格。その場所で読めていなければ null
     */
    private static Integer ocrPrice(OcrResult ocr, Point center, double scale) {
        for (OcrLine line : ocr.lines()) {
            Matcher m = PRICE.matcher(line.text().replaceAll("\\s", ""));
            if (!m.matches()) {
                continue;
            }
            double cx = line.x() + line.w() / 2.0;
            double cy = line.y() + line.h() / 2.0;
            if (Math.hypot(cx - center.x, cy - center.y) <= 15 * scale) {
                return Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    /**
     * AI の答えから、行ごとの読み取り結果を取り出します（[ から ] までを JSON として読む）。
     *
     * @param answer AI の答え
     * @return 行ごとの結果。読めなければ空の一覧
     */
    static List<JsonObject> parseRows(String answer) {
        List<JsonObject> rows = new ArrayList<>();
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return rows;
        }
        try {
            JsonArray array = JsonParser.parseString(answer.substring(start, end + 1)).getAsJsonArray();
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    rows.add(e.getAsJsonObject());
                }
            }
        } catch (RuntimeException e) {
            // JSON として読めなかったときは、空の一覧を返す（全部「売り切れ」扱いになる）
        }
        return rows;
    }

    /**
     * AI の答えの値を整数にします（"76" のような文字でもよい。数字以外が混ざっていれば数字だけ取り出す）。
     *
     * @param value AI の答えの値
     * @return 整数。値が無い・数字が無いときは null
     */
    private static Integer number(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        String digits = value.getAsString().replaceAll("[^0-9]", "");
        return digits.isEmpty() || digits.length() > 4 ? null : Integer.parseInt(digits);
    }

    /**
     * 行番号で、AI の読み取り結果を探します。
     *
     * @param rows 行ごとの結果
     * @param row  行番号（1から）
     * @return その行の結果。無ければ null
     */
    private static JsonObject findRow(List<JsonObject> rows, int row) {
        for (JsonObject r : rows) {
            Integer n = number(r.get("row"));
            if (n != null && n == row) {
                return r;
            }
        }
        return null;
    }
}

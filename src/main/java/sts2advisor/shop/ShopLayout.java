package sts2advisor.shop;

import sts2advisor.screen.OcrLine;
import sts2advisor.screen.OcrResult;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 【このファイルの役割】
 * ショップ画面で、品物（カード・レリック・ポーション・カード削除）がどこに並んでいるかを求める係です
 * （MVP のステップ8-2）。
 *
 * 考え方：
 *   ショップの並び方はいつも同じ（上の段にカード5枚、下の段にカード2枚・レリック3個・ポーション3個・カード削除）。
 *   ただし、ゲームのウィンドウの大きさや位置で、画面上の場所と大きさが変わる。
 *   そこで、OCR で読めた価格の位置を「お手本の並び（REFERENCE）」に重ねて、
 *   どれだけ拡大・移動すればぴったり合うかを求め、全部の品物の場所を計算する。
 *
 *   OCR は、赤い価格（お金が足りない）や緑の価格（値引き）を読めないことがある（2026-11-12 に確認）。
 *   そのため、全部の価格が読めなくても、読めた3つ以上が合えば場所を決められるようにしている。
 *
 * お手本の並びは、2026-11-11 のスクリーンショット（ゲームのウィンドウが約1920×1033）で測った値。
 * 価格の文字の高さは 24 ピクセル。
 */
public class ShopLayout {

    /** 品物の種類 */
    public enum Kind {
        CARD("カード"), RELIC("レリック"), POTION("ポーション"), REMOVAL("カード削除");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** @return 日本語の名前 */
        public String label() {
            return label;
        }
    }

    /**
     * 品物1つ分の場所。
     *
     * @param kind        品物の種類
     * @param index       同じ種類の中で何番目か（0から。左上から順）
     * @param priceCenter 価格の文字の中心（画面上の位置）
     * @param iconCenter  アイコン（レリック・ポーション）の中心。カード・カード削除は価格の中心と同じ
     */
    public record Slot(Kind kind, int index, Point priceCenter, Point iconCenter) {
    }

    /**
     * 場所を求めた結果。
     *
     * @param slots   全部の品物の場所
     * @param scale   お手本に対する大きさの倍率（1.0 ならお手本と同じ大きさ）
     * @param matched お手本と重なった価格の数
     */
    public record Result(List<Slot> slots, double scale, int matched) {
    }

    /** お手本での価格の文字の高さ（ピクセル） */
    static final double REFERENCE_PRICE_HEIGHT = 24;

    /** お手本で、レリックのアイコンの中心は価格の中心より何ピクセル上か */
    static final double RELIC_ICON_ABOVE = 57;

    /** お手本で、ポーションのアイコンの中心は価格の中心より何ピクセル上か */
    static final double POTION_ICON_ABOVE = 49;

    /** 場所を決めるのに必要な、お手本と重なった価格の数 */
    static final int MIN_MATCHED = 3;

    /** 価格の行の形（例："◎77"。先頭の◎は OCR で別の記号になることもある） */
    private static final Pattern PRICE = Pattern.compile("^[◎◉©@]?\\d{2,3}$");

    /** お手本での品物の並び（価格の文字の中心の位置） */
    private static final List<Slot> REFERENCE = List.of(
            slot(Kind.CARD, 0, 476, 531), slot(Kind.CARD, 1, 721, 531), slot(Kind.CARD, 2, 965, 531),
            slot(Kind.CARD, 3, 1207, 531), slot(Kind.CARD, 4, 1452, 531),
            slot(Kind.CARD, 5, 536, 880), slot(Kind.CARD, 6, 792, 880),
            slot(Kind.RELIC, 0, 991, 702), slot(Kind.RELIC, 1, 1131, 702), slot(Kind.RELIC, 2, 1271, 702),
            slot(Kind.POTION, 0, 991, 834), slot(Kind.POTION, 1, 1131, 834), slot(Kind.POTION, 2, 1271, 834),
            slot(Kind.REMOVAL, 0, 1457, 855));

    /**
     * お手本の品物1つ分を作ります（アイコンの中心は、価格の中心から決まった分だけ上）。
     */
    private static Slot slot(Kind kind, int index, int x, int y) {
        int above = switch (kind) {
            case RELIC -> (int) RELIC_ICON_ABOVE;
            case POTION -> (int) POTION_ICON_ABOVE;
            default -> 0;
        };
        return new Slot(kind, index, new Point(x, y), new Point(x, y - above));
    }

    /**
     * OCR の結果から、ショップの品物の場所を求めます。
     *
     * @param ocr ショップ画面を OCR で読んだ結果
     * @return 品物の場所。価格が3つ以上お手本と重ならなければ空
     */
    public Optional<Result> locate(OcrResult ocr) {
        // 1. 価格らしい行を集める。所持ゴールドの表示（上の方の大きい文字）を除くため、
        //    文字の高さが「価格らしい行の高さの真ん中の値」から大きく外れるものは使わない
        List<OcrLine> prices = new ArrayList<>();
        for (OcrLine line : ocr.lines()) {
            if (PRICE.matcher(line.text().replaceAll("\\s", "")).matches()) {
                prices.add(line);
            }
        }
        if (prices.size() < MIN_MATCHED) {
            return Optional.empty();
        }
        double medianHeight = prices.stream().mapToInt(OcrLine::h).sorted()
                .skip(prices.size() / 2).findFirst().orElse(0);
        prices.removeIf(l -> Math.abs(l.h() - medianHeight) > medianHeight * 0.3);
        if (prices.size() < MIN_MATCHED) {
            return Optional.empty();
        }

        // 2. 大きさの倍率は、価格の文字の高さから決める
        double scale = medianHeight / REFERENCE_PRICE_HEIGHT;
        double tolerance = 15 * scale;

        // 3. 「読めた価格の1つが、お手本のどれか1つに当たる」と仮定して移動量を決め、
        //    そのとき他の価格がいくつお手本と重なるかを数える。いちばん多く重なる仮定を選ぶ
        int bestMatched = 0;
        double bestError = Double.MAX_VALUE;
        double bestDx = 0;
        double bestDy = 0;
        for (OcrLine p : prices) {
            double px = p.x() + p.w() / 2.0;
            double py = p.y() + p.h() / 2.0;
            for (Slot s : REFERENCE) {
                double dx = px - s.priceCenter().x * scale;
                double dy = py - s.priceCenter().y * scale;
                int matched = 0;
                double error = 0;
                double sumDx = 0;
                double sumDy = 0;
                for (OcrLine q : prices) {
                    double qx = q.x() + q.w() / 2.0;
                    double qy = q.y() + q.h() / 2.0;
                    Slot nearest = null;
                    double nearestDist = Double.MAX_VALUE;
                    for (Slot r : REFERENCE) {
                        double d = Math.hypot(r.priceCenter().x * scale + dx - qx, r.priceCenter().y * scale + dy - qy);
                        if (d < nearestDist) {
                            nearestDist = d;
                            nearest = r;
                        }
                    }
                    if (nearestDist <= tolerance) {
                        matched++;
                        error += nearestDist;
                        sumDx += qx - nearest.priceCenter().x * scale;
                        sumDy += qy - nearest.priceCenter().y * scale;
                    }
                }
                if (matched > bestMatched || (matched == bestMatched && error < bestError)) {
                    bestMatched = matched;
                    bestError = error;
                    // 重なった価格全部の平均で、移動量を少しだけ正確にする
                    bestDx = sumDx / matched;
                    bestDy = sumDy / matched;
                }
            }
        }
        if (bestMatched < MIN_MATCHED) {
            return Optional.empty();
        }

        // 4. 倍率をもっと正確にする。文字の高さから求めた倍率は少しずれることがあり、
        //    読めた価格から遠い品物（上の段のカードなど）ほど場所がずれてしまう（2026-11-12 に確認）。
        //    そこで、重なった価格どうしの位置の関係から、倍率と移動量を計算し直す（最小二乗法）。
        //    計算し直した倍率で重なる価格が増えることもあるので、2回くり返す
        for (int round = 0; round < 2; round++) {
            List<double[]> pairs = matchPairs(prices, scale, bestDx, bestDy, tolerance);
            double[] fit = fitScaleAndOffset(pairs);
            if (fit == null) {
                break;
            }
            scale = fit[0];
            bestDx = fit[1];
            bestDy = fit[2];
            tolerance = 15 * scale;
            bestMatched = matchPairs(prices, scale, bestDx, bestDy, tolerance).size();
        }

        // 5. お手本の並びを拡大・移動して、画面上の場所にする
        List<Slot> slots = new ArrayList<>();
        for (Slot s : REFERENCE) {
            slots.add(new Slot(s.kind(), s.index(),
                    new Point((int) Math.round(s.priceCenter().x * scale + bestDx),
                            (int) Math.round(s.priceCenter().y * scale + bestDy)),
                    new Point((int) Math.round(s.iconCenter().x * scale + bestDx),
                            (int) Math.round(s.iconCenter().y * scale + bestDy))));
        }
        return Optional.of(new Result(slots, scale, bestMatched));
    }

    /**
     * 読めた価格のうち、お手本のどれかと重なるものを集めます。
     *
     * @param prices    読めた価格の行
     * @param scale     倍率
     * @param dx        横の移動量
     * @param dy        縦の移動量
     * @param tolerance どれだけ近ければ「重なる」とみなすか（ピクセル）
     * @return 重なった組の一覧。1組は {お手本のx, お手本のy, 画面のx, 画面のy}
     */
    private static List<double[]> matchPairs(List<OcrLine> prices, double scale, double dx, double dy,
                                             double tolerance) {
        List<double[]> pairs = new ArrayList<>();
        for (OcrLine q : prices) {
            double qx = q.x() + q.w() / 2.0;
            double qy = q.y() + q.h() / 2.0;
            Slot nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Slot r : REFERENCE) {
                double d = Math.hypot(r.priceCenter().x * scale + dx - qx, r.priceCenter().y * scale + dy - qy);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = r;
                }
            }
            if (nearestDist <= tolerance) {
                pairs.add(new double[]{nearest.priceCenter().x, nearest.priceCenter().y, qx, qy});
            }
        }
        return pairs;
    }

    /**
     * 重なった組から、「画面の位置 ≒ お手本の位置 × 倍率 + 移動量」がいちばん合う倍率と移動量を求めます。
     *
     * @param pairs 重なった組（matchPairs の結果）
     * @return {倍率, 横の移動量, 縦の移動量}。組が少ない・位置が近すぎて求められないときは null
     */
    private static double[] fitScaleAndOffset(List<double[]> pairs) {
        if (pairs.size() < MIN_MATCHED) {
            return null;
        }
        // それぞれの平均（重心）
        double rx = 0;
        double ry = 0;
        double qx = 0;
        double qy = 0;
        for (double[] p : pairs) {
            rx += p[0];
            ry += p[1];
            qx += p[2];
            qy += p[3];
        }
        int n = pairs.size();
        rx /= n;
        ry /= n;
        qx /= n;
        qy /= n;
        // 重心からの広がりどうしの比が倍率になる
        double numerator = 0;
        double denominator = 0;
        for (double[] p : pairs) {
            numerator += (p[0] - rx) * (p[2] - qx) + (p[1] - ry) * (p[3] - qy);
            denominator += (p[0] - rx) * (p[0] - rx) + (p[1] - ry) * (p[1] - ry);
        }
        // 価格どうしが近すぎる（お手本で100ピクセルくらいしか離れていない）と、倍率が不安定になるので使わない
        if (denominator < 100 * 100) {
            return null;
        }
        double scale = numerator / denominator;
        return new double[]{scale, qx - scale * rx, qy - scale * ry};
    }
}

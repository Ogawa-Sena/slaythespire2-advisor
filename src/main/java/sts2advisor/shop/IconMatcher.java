package sts2advisor.shop;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【このファイルの役割】
 * ショップ画面に描かれたレリック・ポーションのアイコンが、どれなのかを特定する係です（MVP のステップ8-2）。
 * ゲームファイルから取り出したアイコン（ExtractIconsMain で作った data/icons の画像）と照らし合わせます。
 *
 * 照らし合わせ方（テンプレートマッチング）：
 *   1. 画面から、アイコンがあるはずの場所のまわりを切り出す
 *   2. 速くするため、切り出した画像とお手本のアイコンを、アイコンが約 WORK_SIZE ピクセルになるまで縮める
 *   3. お手本のアイコンを、切り出した画像の上で少しずつずらしながら重ね、「似ている度合い」を求める
 *      ・似ている度合いには「正規化相互相関」を使う。明るい所・暗い所の並び方が同じほど 1 に近づく。
 *        色の差をそのまま比べると、背景（緑の紙）に近い色のアイコンが、背景そのものと似ていると判断されて
 *        しまったため（2026-11-12 に確認）。のっぺりした背景は、この方法では似ていると判断されない
 *      ・ただし正規化相互相関は明るさ・色合いの違いを気にしないので、形が似た色違いのポーション
 *        （青い缶と水色の瓶など）を取り違えた。そこで「色の違い（差の2乗の平均）」も足して点数にする
 *          点数 = (1 − 似ている度合い) + 色の違い ÷ COLOR_WEIGHT
 *      ・アイコンの透明な部分（背景）は比べない
 *      ・画面でのアイコンの大きさは少しずつ違うかもしれないので、いくつかの大きさで試す
 *   4. 点数がいちばん小さいアイコンを「これだ」と判断する
 */
public class IconMatcher {

    /**
     * 照らし合わせの結果1つ分。
     *
     * @param id    アイコンの ID（例："AKABEKO"）
     * @param score 点数（小さいほど似ている。0 ならまったく同じ）
     */
    public record Candidate(String id, double score) {
    }

    /**
     * 色の違いを点数に足すときに割る数。色の違い（赤・緑・青の差の2乗の合計の平均）は
     * 正しく当たったときで 1,000〜3,000 くらいなので、0.1〜0.3 くらいの重みになる（2026-11-12 のサンプルで調整）
     */
    static final double COLOR_WEIGHT = 10_000;

    /**
     * 点数がこれより大きければ「自信なし」とみなす。2026-11-12 のサンプルでは、正しく当たったときは最大 0.61、
     * マウスの説明の枠でアイコンが隠れていたときは 1.1 くらいだった
     */
    public static final double RELIABLE_SCORE = 0.8;

    /** 縮めたあとの、アイコンのおおよその大きさ（ピクセル） */
    static final double WORK_SIZE = 40;

    /** お手本のアイコンの、だいたいの大きさ（アトラスでの幅。ピクセル） */
    static final double ATLAS_ICON_SIZE = 80;

    /**
     * 画面（お手本の並びの大きさ）でのレリックのアイコンの大きさが、アトラスでの大きさの何倍か。
     * はっきりしないので、いくつか試す
     */
    public static final double[] RELIC_RATIOS = {0.72, 0.80, 0.88, 0.96};

    /** ポーションの場合（ショップではレリックより小さく描かれている。2026-11-12 のサンプルで確認） */
    public static final double[] POTION_RATIOS = {0.52, 0.58, 0.64, 0.70, 0.76};

    /** 試す大きさの一覧（RELIC_RATIOS か POTION_RATIOS） */
    private final double[] ratios;

    /** アイコンの中心から、切り出す範囲の半分の長さ（お手本の並びの大きさでのピクセル） */
    static final double SEARCH_HALF = 50;

    /** これより不透明なピクセルだけを比べる（0〜255） */
    static final int ALPHA_THRESHOLD = 200;

    /** お手本のアイコン（ID → 画像） */
    private final Map<String, BufferedImage> icons;

    /** 縮めたお手本のアイコン（「倍率 → ID → 縮めたもの」。同じ倍率で何度も縮めないよう、とっておく） */
    private final Map<Long, Map<String, List<Template>>> cache = new HashMap<>();

    /**
     * @param icons  お手本のアイコン（ID → 画像）。照らし合わせる候補だけを渡す
     * @param ratios 試す大きさの一覧（RELIC_RATIOS か POTION_RATIOS）
     */
    public IconMatcher(Map<String, BufferedImage> icons, double[] ratios) {
        this.icons = icons;
        this.ratios = ratios;
    }

    /**
     * フォルダから、指定した ID のアイコンを読み込みます。
     *
     * @param dir アイコンのフォルダ（例：data/icons/relic）
     * @param ids 読み込む ID の一覧
     * @return ID → 画像（ファイルが無い ID は入らない）
     * @throws IOException 画像を読めなかったとき
     */
    public static Map<String, BufferedImage> loadIcons(Path dir, Collection<String> ids) throws IOException {
        Map<String, BufferedImage> icons = new LinkedHashMap<>();
        for (String id : ids) {
            Path file = dir.resolve(id + ".png");
            if (Files.exists(file)) {
                icons.put(id, ImageIO.read(file.toFile()));
            }
        }
        return icons;
    }

    /**
     * 画面のアイコンを、お手本のアイコンと照らし合わせます。
     *
     * @param screen ショップ画面
     * @param center アイコンの中心（ShopLayout で求めたもの）
     * @param scale  お手本の並びに対する大きさの倍率（ShopLayout で求めたもの）
     * @param top    いくつまで結果を返すか
     * @return 似ている順に並べた候補（top 個まで）
     */
    public List<Candidate> match(BufferedImage screen, Point center, double scale, int top) {
        // 縮める倍率：アイコンが約 WORK_SIZE ピクセルになるようにする
        double shrink = Math.max(1.0, ATLAS_ICON_SIZE * ratios[ratios.length - 1] * scale / WORK_SIZE);

        // 1・2. アイコンのまわりを切り出して縮める
        int half = (int) Math.round(SEARCH_HALF * scale);
        int x0 = Math.max(0, center.x - half);
        int y0 = Math.max(0, center.y - half);
        int x1 = Math.min(screen.getWidth(), center.x + half);
        int y1 = Math.min(screen.getHeight(), center.y + half);
        BufferedImage area = resize(screen.getSubimage(x0, y0, x1 - x0, y1 - y0), 1.0 / shrink);
        Pixels target = new Pixels(area);

        // 3. 全部のお手本と比べる（速くするため、CPU のコアを全部使って並行して計算する）
        Map<String, List<Template>> templates = templatesFor(scale / shrink);
        List<Candidate> results = new ArrayList<>(templates.entrySet().parallelStream()
                .map(e -> {
                    double best = Double.MAX_VALUE;
                    for (Template t : e.getValue()) {
                        best = Math.min(best, bestScore(target, t));
                    }
                    return new Candidate(e.getKey(), best);
                })
                .toList());
        // 4. 色の違いが小さい順に並べる
        results.sort(Comparator.comparingDouble(Candidate::score));
        return results.subList(0, Math.min(top, results.size()));
    }

    /**
     * お手本のアイコンを、決まった倍率で縮めたものを用意します（一度作ったものは使い回す）。
     *
     * @param factor 画面での倍率 ÷ 縮める倍率（アトラスの大きさに掛ける数の元）
     * @return ID → いくつかの大きさに縮めたお手本
     */
    private Map<String, List<Template>> templatesFor(double factor) {
        long key = Math.round(factor * 1000);
        return cache.computeIfAbsent(key, k -> {
            Map<String, List<Template>> map = new LinkedHashMap<>();
            for (Map.Entry<String, BufferedImage> e : icons.entrySet()) {
                List<Template> list = new ArrayList<>();
                for (double ratio : ratios) {
                    list.add(new Template(resize(e.getValue(), ratio * factor)));
                }
                map.put(e.getKey(), list);
            }
            return map;
        });
    }

    /**
     * お手本を少しずつずらして重ね、いちばん似ている位置での点数を返します。
     *
     * 似ている度合い（正規化相互相関）の求め方：
     *   お手本の値を t、画面の値を s として（赤・緑・青をそれぞれ1つの値として並べる）、
     *   それぞれ平均を引いてから掛け合わせた合計を、それぞれのばらつきの大きさで割る。
     *   模様が同じなら 1、まったく関係なければ 0 付近、逆の模様なら −1 になる。
     *
     * @param target 画面から切り出して縮めたもの
     * @param t      縮めたお手本
     * @return 点数（(1 − 似ている度合い) + 色の違い ÷ COLOR_WEIGHT）の最小値。重ねられなければ非常に大きい値
     */
    private static double bestScore(Pixels target, Template t) {
        double best = Double.MAX_VALUE;
        int n = t.count * 3;
        for (int oy = 0; oy + t.height <= target.height; oy++) {
            for (int ox = 0; ox + t.width <= target.width; ox++) {
                long sumS = 0;
                long sumSS = 0;
                long sumST = 0;
                for (int k = 0; k < t.count; k++) {
                    int p = target.rgb[(oy + t.ys[k]) * target.width + ox + t.xs[k]];
                    int q = t.colors[k];
                    int sr = (p >> 16) & 0xFF;
                    int sg = (p >> 8) & 0xFF;
                    int sb = p & 0xFF;
                    sumS += sr + sg + sb;
                    sumSS += sr * sr + sg * sg + sb * sb;
                    sumST += sr * ((q >> 16) & 0xFF) + sg * ((q >> 8) & 0xFF) + sb * (q & 0xFF);
                }
                double varS = sumSS - (double) sumS * sumS / n;
                if (varS <= 0 || t.variance <= 0) {
                    continue; // 画面がのっぺりしている所は、似ているとは考えない
                }
                double cov = sumST - (double) sumS * t.sum / n;
                // 色の違い：差の2乗の合計 = Σs² − 2Σst + Σt² を、比べたピクセルの数で割る
                double colorDiff = (sumSS - 2.0 * sumST + t.sumSquares) / t.count;
                double score = 1 - cov / Math.sqrt(varS * t.variance) + colorDiff / COLOR_WEIGHT;
                if (score < best) {
                    best = score;
                }
            }
        }
        return best;
    }

    /**
     * 画像を拡大・縮小します（縮めるときは、まわりのピクセルを平均して、ちらつきを抑える）。
     *
     * @param image  元の画像
     * @param factor 倍率（0.5 なら半分の大きさ）
     * @return 拡大・縮小した画像（透明度つき）
     */
    static BufferedImage resize(BufferedImage image, double factor) {
        int w = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(image.getHeight() * factor));
        Image scaled = image.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(scaled, 0, 0, null);
        return out;
    }

    /**
     * 画面から切り出した画像のピクセル（速く読めるよう、配列にしたもの）。
     */
    private static final class Pixels {
        final int width;
        final int height;
        final int[] rgb;

        Pixels(BufferedImage image) {
            width = image.getWidth();
            height = image.getHeight();
            rgb = image.getRGB(0, 0, width, height, null, 0, width);
        }
    }

    /**
     * 縮めたお手本のアイコン。比べるのは不透明なピクセルだけなので、その位置と色だけを持つ。
     */
    private static final class Template {
        final int width;
        final int height;
        final int count;
        final int[] xs;
        final int[] ys;
        final int[] colors;
        /** 赤・緑・青の値の合計（似ている度合いの計算に使う） */
        final long sum;
        /** 赤・緑・青の値の2乗の合計（色の違いの計算に使う） */
        final long sumSquares;
        /** 赤・緑・青の値のばらつき（平均からの差の2乗の合計） */
        final double variance;

        Template(BufferedImage image) {
            width = image.getWidth();
            height = image.getHeight();
            int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
            int n = 0;
            for (int v : argb) {
                if ((v >>> 24) >= ALPHA_THRESHOLD) {
                    n++;
                }
            }
            count = Math.max(1, n);
            xs = new int[count];
            ys = new int[count];
            colors = new int[count];
            int k = 0;
            for (int i = 0; i < argb.length && k < n; i++) {
                if ((argb[i] >>> 24) >= ALPHA_THRESHOLD) {
                    xs[k] = i % width;
                    ys[k] = i / width;
                    colors[k] = argb[i];
                    k++;
                }
            }
            long s = 0;
            long ss = 0;
            for (int c : colors) {
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                s += r + g + b;
                ss += r * r + g * g + b * b;
            }
            sum = s;
            sumSquares = ss;
            variance = ss - (double) s * s / (count * 3.0);
        }
    }
}

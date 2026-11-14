package sts2advisor.reward;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import sts2advisor.ai.ClaudeVision;
import sts2advisor.screen.OcrResult;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 【このファイルの役割】
 * カード報酬の画面から、候補のカードがどれかを読み取る係です（機能要件 F-03）。
 *
 * 処理の流れ：
 *   1. OCR の結果を目印に、カード名の帯だけを切り出す（CardRewardCropper）
 *   2. 切り出した画像を AI に見せて、カード名を読んでもらう（ClaudeVision）
 *   3. 読んだ名前を、ゲームファイルの公式カード名と照らし合わせる（CardResolver）
 *      名前が一致しないときは、OCR が読んだ説明文と、今のキャラも手がかりにして特定する
 */
public class CardRewardReader {

    /** AI への質問。答えの形を JSON の配列に決めておくと、プログラムで扱いやすい */
    private static final String QUESTION = """
            この画像は、カードゲームの報酬画面から、カード名が書かれた帯の部分だけを切り出したものです。
            写っているカード名を、左から順に JSON の配列で出力してください。
            例: ["脱出計画","精度上昇","剣の舞"]
            カード名の後ろに「+」が付いていれば、それも含めてください。
            カードは横に等間隔に並んでいます。マウスを乗せたカードの横に、用語の説明の四角い枠
            （例：「集中力」「オーブの効果を増幅させる。」のような文が入った暗い枠）が出て、
            隣のカードの名前の帯を隠すことがあります。その枠の下にもカードがあるので、
            名前が見えないカードの位置には "?" を入れてください（例: ["脱出計画","?","剣の舞"]）。
            説明の枠の中の文字は、カード名ではありません。
            また、マウスを乗せたカードの左側に、灰色の枠の小さめの「参考のカード」（例：「粘液」「燃料+」。
            そのカードが作るカード）が出ることがあります。これは選べる候補ではないので、含めないでください。
            JSON の配列以外は何も書かないでください。""";

    /**
     * 結果。cards は左から順の照合結果、stripArea は切り出した場所、aiMillis は AI にかかった時間、
     * descriptions は OCR が読んだ各カードの説明文（cards と同じ順。効果テキストの食い違いの確認に使う。F-06）
     */
    public record Result(List<CardNameMatcher.Match> cards, Rectangle stripArea, BufferedImage strip,
                         String aiAnswer, long aiMillis, List<String> descriptions) {
    }

    private final CardRewardCropper cropper = new CardRewardCropper();
    private final ClaudeVision vision;
    private final CardResolver resolver;

    /**
     * @param vision   AI に画像を見せる係（起動したまま使い回す）
     * @param resolver カードを特定する係
     */
    public CardRewardReader(ClaudeVision vision, CardResolver resolver) {
        this.vision = vision;
        this.resolver = resolver;
    }

    /**
     * カード報酬の画面から、候補のカードを読み取ります。
     *
     * @param screen 画面全体の画像
     * @param ocr       同じ画面を OCR で読んだ結果（切り出す場所の目印と、カードの説明文に使う）
     * @param character 今のキャラ（例："REGENT"。分からなければ null）
     * @return 読み取り結果。目印の文字が見つからず切り出せなかったときは空
     * @throws IOException AI とのやり取りに失敗したとき
     */
    public Optional<Result> read(BufferedImage screen, OcrResult ocr, String character) throws IOException {
        Optional<Rectangle> area = cropper.findNameStrip(ocr);
        if (area.isEmpty()) {
            return Optional.empty();
        }
        BufferedImage strip = cropper.crop(screen, area.get());

        long start = System.currentTimeMillis();
        String answer = vision.ask(strip, QUESTION);
        long aiMillis = System.currentTimeMillis() - start;

        List<String> names = parseNames(answer);
        List<String> texts = cropper.descriptionTexts(ocr, names.size());
        List<CardNameMatcher.Match> cards = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            cards.add(resolver.resolve(names.get(i), texts.get(i), character));
        }
        return Optional.of(new Result(cards, area.get(), strip, answer, aiMillis, texts));
    }

    /**
     * AI の答えから、カード名の一覧を取り出します。
     * AI が ```json ... ``` のような囲みを付けてくることがあるので、[ から ] までを取り出して読みます。
     *
     * @param answer AI の答え
     * @return カード名の一覧。読めなければ空の一覧
     */
    static List<String> parseNames(String answer) {
        List<String> names = new ArrayList<>();
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return names;
        }
        try {
            JsonArray array = JsonParser.parseString(answer.substring(start, end + 1)).getAsJsonArray();
            for (JsonElement element : array) {
                names.add(element.getAsString());
            }
        } catch (RuntimeException e) {
            // JSON として読めなかったときは、空の一覧を返す（呼び出し側で「読めなかった」と扱う）
        }
        return names;
    }
}

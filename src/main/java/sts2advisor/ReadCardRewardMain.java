package sts2advisor;

import sts2advisor.ai.ClaudeVision;
import sts2advisor.game.SaveFileReader;
import sts2advisor.model.RunState;
import sts2advisor.reward.CardNameMatcher;
import sts2advisor.reward.CardResolver;
import sts2advisor.reward.CardRewardReader;
import sts2advisor.screen.OcrResult;
import sts2advisor.screen.ScreenClassifier;
import sts2advisor.screen.ScreenType;
import sts2advisor.screen.WindowsOcr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 【このファイルの役割】
 * 保存してあるスクリーンショットから、カード報酬の候補のカードを読み取って表示する「入口」です。
 * カード名の読み取り（F-03）と照合が正しく動くかを、ゲームを起動せずに確かめるために使います。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ReadCardRewardMain -Dexec.args="samples"
 * 引数には画像ファイルか、画像の入ったフォルダを指定します。カード報酬の画面だけを読み取ります。
 * 切り出したカード名の帯は data/tmp/name_strip_<番号>.png に保存します（確認用）。
 */
public class ReadCardRewardMain {

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * プログラムの入口。
     *
     * @param args 1つ目に画像ファイルかフォルダ（省略すると samples）
     * @throws Exception OCR・AI・データベースで失敗したとき
     */
    public static void main(String[] args) throws Exception {
        List<Path> images = listImages(Path.of(args.length > 0 ? args[0] : "samples"));

        CardResolver resolver = CardResolver.fromDatabase(DB_FILE);
        // キャラは、いちばん最近のセーブファイルから読む（無ければ全キャラを候補にする）
        String character = new SaveFileReader().readLatest().map(RunState::character).orElse(null);
        System.out.println("キャラ（セーブから）: " + character);

        ScreenClassifier classifier = new ScreenClassifier();
        try (WindowsOcr ocr = WindowsOcr.start(); ClaudeVision vision = new ClaudeVision()) {
            CardRewardReader reader = new CardRewardReader(vision, resolver);
            int number = 0;
            for (Path image : images) {
                OcrResult result = ocr.read(image);
                if (classifier.classify(result) != ScreenType.CARD_REWARD) {
                    continue; // カード報酬の画面だけを読む
                }
                number++;
                BufferedImage screen = ImageIO.read(image.toFile());
                Optional<CardRewardReader.Result> read = reader.read(screen, result, character);
                System.out.println("■ " + image.getFileName());
                if (read.isEmpty()) {
                    System.out.println("  カード名の帯の場所が分かりませんでした");
                    continue;
                }
                CardRewardReader.Result r = read.get();
                Path stripFile = Path.of("data", "tmp", "name_strip_" + number + ".png");
                ImageIO.write(r.strip(), "png", stripFile.toFile());
                System.out.println("  切り出した場所: " + r.stripArea() + " → " + stripFile);
                System.out.println("  AI の答え（" + r.aiMillis() + " ミリ秒）: " + r.aiAnswer().strip());
                for (CardNameMatcher.Match m : r.cards()) {
                    System.out.println("  ・" + describe(m));
                }
            }
            if (number == 0) {
                System.out.println("カード報酬の画面が見つかりませんでした");
            }
        }
    }

    /**
     * 照合の結果を、表示用の文字にします。
     *
     * @param m 照合の結果
     * @return 例："精度上昇 → ACCURACY（完全一致）"
     */
    static String describe(CardNameMatcher.Match m) {
        if (m.cardId() == null) {
            return m.readName() + " → 見つかりません";
        }
        if (m.exact()) {
            return m.readName() + " → " + m.cardId() + "（完全一致）";
        }
        return String.format("%s → %s「%s」（%s、度合い %.2f）", m.readName(), m.cardId(), m.officialName(),
                m.method(), m.similarity());
    }

    /**
     * 画像ファイル、またはフォルダの中の PNG 画像の一覧を返します。
     *
     * @param path 画像ファイルかフォルダ
     * @return 画像の場所の一覧
     * @throws java.io.IOException フォルダを読めなかったとき
     */
    private static List<Path> listImages(Path path) throws java.io.IOException {
        if (!Files.isDirectory(path)) {
            return List.of(path);
        }
        try (Stream<Path> files = Files.list(path)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")).sorted().toList();
        }
    }
}

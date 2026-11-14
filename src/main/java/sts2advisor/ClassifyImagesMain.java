package sts2advisor;

import sts2advisor.screen.OcrResult;
import sts2advisor.screen.ScreenClassifier;
import sts2advisor.screen.ScreenType;
import sts2advisor.screen.WindowsOcr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 【このファイルの役割】
 * 保存してあるスクリーンショットの画面の種類を判定して、結果を一覧表示する「入口」です。
 * 画面の種類の判定（F-02）が正しく動くかを、ゲームを起動せずに確かめるために使います。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ClassifyImagesMain -Dexec.args="samples"
 * 指定したフォルダの中の PNG 画像を、すべて判定します（下のフォルダの中までは見ません）。
 */
public class ClassifyImagesMain {

    /**
     * プログラムの入口。
     *
     * @param args 1つ目に画像のフォルダを渡す（省略すると samples）
     * @throws Exception OCR やファイルの読み込みに失敗したとき
     */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args.length > 0 ? args[0] : "samples");
        List<Path> images = listPngFiles(folder);
        System.out.println(folder.toAbsolutePath() + " の画像: " + images.size() + " 枚");

        ScreenClassifier classifier = new ScreenClassifier();
        try (WindowsOcr ocr = WindowsOcr.start()) {
            for (Path image : images) {
                OcrResult result = ocr.read(image);
                ScreenType type = classifier.classify(result);
                System.out.printf("%-6s（%4d ミリ秒）  %s%n", type.label(), result.elapsedMillis(), image.getFileName());
            }
        }
    }

    /**
     * フォルダの中の PNG 画像を、名前の順に並べて返します。
     *
     * @param folder 調べるフォルダ
     * @return PNG 画像の場所の一覧
     * @throws IOException フォルダを読めなかったとき
     */
    private static List<Path> listPngFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
        }
    }
}

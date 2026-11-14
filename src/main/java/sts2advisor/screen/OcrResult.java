package sts2advisor.screen;

import java.util.List;

/**
 * 【このファイルの役割】
 * 画像1枚を OCR で読んだ結果をまとめて持つ入れ物です。
 *
 * @param lines   読み取れた行の一覧（上から順とは限らない）
 * @param width   読んだ画像の幅（画素）
 * @param height  読んだ画像の高さ（画素）
 * @param elapsedMillis 読み取りにかかった時間（ミリ秒）
 */
public record OcrResult(List<OcrLine> lines, int width, int height, long elapsedMillis) {
}

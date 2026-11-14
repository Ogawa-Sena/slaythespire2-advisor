package sts2advisor.screen;

/**
 * 【このファイルの役割】
 * OCR（文字の読み取り）で読めた「1行分の文字」と、その行が画面のどこにあったかを持つ入れ物です。
 * 位置は画像の左上を (0, 0) とした画素（ピクセル）の数です。
 *
 * @param text 読み取った文字（例："カードを選択"）
 * @param x    行の左端の位置
 * @param y    行の上端の位置
 * @param w    行の幅
 * @param h    行の高さ
 */
public record OcrLine(String text, int x, int y, int w, int h) {
}

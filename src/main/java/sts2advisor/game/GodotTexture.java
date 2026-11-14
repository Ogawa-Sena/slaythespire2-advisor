package sts2advisor.game;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 【このファイルの役割】
 * ゲーム（Godot エンジン）の画像ファイル「.ctex」を、ふつうの画像（BufferedImage）に戻す係です。
 * レリック・ポーションのアイコンを取り出すのに使います（読むだけで、ゲームのファイルは変更しない）。
 *
 * .ctex の形（このゲームで確認したもの。数字はすべて「小さい桁が先」の並び）：
 *   0〜3   バイト目：目印の文字 "GST2"
 *   4〜35  バイト目：版・幅・高さ・設定など
 *   36〜39 バイト目：中身の種類（0＝そのままの画素、1＝PNG、2＝WebP）
 *   40〜43 バイト目：幅・高さ（2バイトずつ）
 *   44〜47 バイト目：縮小版（ミップマップ）の数
 *   48〜51 バイト目：画素の形式（中身の種類が0のとき使う。22＝BC7）
 *   52〜   バイト目：PNG・WebP なら「大きさ（4バイト）＋ファイルの中身」、そのままの画素ならデータ本体
 */
public final class GodotTexture {

    private GodotTexture() {
        // 処理をまとめただけのクラスなので、インスタンスは作らない
    }

    /** 中身の種類：そのままの画素 */
    private static final int DATA_IMAGE = 0;
    /** 中身の種類：PNG ファイル */
    private static final int DATA_PNG = 1;
    /** 中身の種類：WebP ファイル */
    private static final int DATA_WEBP = 2;

    /** 画素の形式：BC7（Godot での呼び名は BPTC_RGBA） */
    private static final int FORMAT_BPTC_RGBA = 22;

    /**
     * .ctex の中身を画像に戻します。
     *
     * @param ctex .ctex ファイルの中身
     * @return 画像
     * @throws IOException 目印が違う、または対応していない形式のとき
     */
    public static BufferedImage decode(byte[] ctex) throws IOException {
        if (ctex.length < 52 || !new String(ctex, 0, 4, StandardCharsets.US_ASCII).equals("GST2")) {
            throw new IOException("Godot の画像ファイル（GST2）ではありません");
        }
        ByteBuffer buf = ByteBuffer.wrap(ctex).order(ByteOrder.LITTLE_ENDIAN);
        int dataType = buf.getInt(36);
        int width = buf.getShort(40) & 0xFFFF;
        int height = buf.getShort(42) & 0xFFFF;
        int format = buf.getInt(48);

        switch (dataType) {
            case DATA_PNG, DATA_WEBP -> {
                // PNG・WebP はファイルの中身がそのまま入っているので、画像を読む道具に渡す
                // （WebP は pom.xml に入れた TwelveMonkeys のライブラリが読んでくれる）
                int size = buf.getInt(52);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(ctex, 56, size));
                if (image == null) {
                    throw new IOException("画像を読めませんでした（中身の種類 " + dataType + "）");
                }
                return image;
            }
            case DATA_IMAGE -> {
                if (format != FORMAT_BPTC_RGBA) {
                    throw new IOException("対応していない画素の形式です: " + format);
                }
                return Bc7Decoder.decode(ctex, 52, width, height);
            }
            default -> throw new IOException("対応していない中身の種類です: " + dataType);
        }
    }
}

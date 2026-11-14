package sts2advisor.game;

import java.awt.image.BufferedImage;

/**
 * 【このファイルの役割】
 * 「BC7」という形式で圧縮された画像を、ふつうの画像（BufferedImage）に戻す係です。
 * ゲームファイルのレリックのアイコン（relic_atlas）がこの形式で入っているため、
 * アイコンを取り出して画面と照らし合わせる（ショップのレリックを特定する）のに使います。
 *
 * BC7 のしくみ（かんたんな説明）：
 *   ・画像を 4×4 ピクセルの「ブロック」に分け、1ブロックを16バイトで表す
 *   ・ブロックの中は1〜3個の「グループ（サブセット）」に分かれ、グループごとに「両端の色」が2つ決まっている
 *   ・各ピクセルは「両端の色のあいだのどのあたりか」を表す番号（インデックス）を持つ
 *   ・どう分けるか・何ビットで表すかは「モード」（0〜7）で決まる
 * 詳しい決まりは BC7（BPTC）の公開仕様に従っています。
 */
public final class Bc7Decoder {

    private Bc7Decoder() {
        // 処理をまとめただけのクラスなので、インスタンスは作らない
    }

    /** モードごとの設定：グループの数 */
    private static final int[] SUBSETS = {3, 2, 3, 2, 1, 1, 1, 2};
    /** モードごとの設定：分け方の番号のビット数 */
    private static final int[] PARTITION_BITS = {4, 6, 6, 6, 0, 0, 0, 6};
    /** モードごとの設定：回転（色と透明度の入れ替え）のビット数 */
    private static final int[] ROTATION_BITS = {0, 0, 0, 0, 2, 2, 0, 0};
    /** モードごとの設定：インデックスの使い方を選ぶビットの数 */
    private static final int[] INDEX_SELECTION_BITS = {0, 0, 0, 0, 1, 0, 0, 0};
    /** モードごとの設定：色（赤・緑・青）1つあたりのビット数 */
    private static final int[] COLOR_BITS = {4, 6, 5, 7, 5, 7, 7, 5};
    /** モードごとの設定：透明度1つあたりのビット数（0 なら透明度なし＝不透明） */
    private static final int[] ALPHA_BITS = {0, 0, 0, 0, 6, 8, 7, 5};
    /** モードごとの設定：端の色ごとに付く追加の1ビット（P ビット）があるか */
    private static final boolean[] ENDPOINT_PBIT = {true, false, false, true, false, false, true, true};
    /** モードごとの設定：グループごとに共有の P ビットがあるか */
    private static final boolean[] SHARED_PBIT = {false, true, false, false, false, false, false, false};
    /** モードごとの設定：1つ目のインデックスのビット数 */
    private static final int[] INDEX_BITS = {3, 3, 2, 2, 2, 2, 4, 2};
    /** モードごとの設定：2つ目のインデックスのビット数（モード4・5だけ） */
    private static final int[] INDEX2_BITS = {0, 0, 0, 0, 3, 2, 0, 0};

    /** インデックスから「両端のあいだのどのあたりか」（0〜64）を求める表（2・3・4 ビット用） */
    private static final int[] WEIGHTS2 = {0, 21, 43, 64};
    private static final int[] WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    /**
     * 2グループの分け方（64通り）。1つの数字が 4×4 の16ピクセル分で、
     * 下のビットから順に、ピクセル0（左上）〜15（右下）がグループ1かどうかを表す
     */
    private static final int[] PARTITION2 = {
            0xCCCC, 0x8888, 0xEEEE, 0xECC8, 0xC880, 0xFEEC, 0xFEC8, 0xEC80,
            0xC800, 0xFFEC, 0xFE80, 0xE800, 0xFFE8, 0xFF00, 0xFFF0, 0xF000,
            0xF710, 0x008E, 0x7100, 0x08CE, 0x008C, 0x7310, 0x3100, 0x8CCE,
            0x088C, 0x3110, 0x6666, 0x366C, 0x17E8, 0x0FF0, 0x718E, 0x399C,
            0xAAAA, 0xF0F0, 0x5A5A, 0x33CC, 0x3C3C, 0x55AA, 0x9696, 0xA55A,
            0x73CE, 0x13C8, 0x324C, 0x3BDC, 0x6996, 0xC33C, 0x9966, 0x0660,
            0x0272, 0x04E4, 0x4E40, 0x2720, 0xC936, 0x936C, 0x39C6, 0x639C,
            0x9336, 0x9CC6, 0x817E, 0xE718, 0xCCF0, 0x0FCC, 0x7744, 0xEE22};

    /** 3グループの分け方（64通り）。1行が 4×4 の16ピクセル分で、左上から右へ、上から下へ並ぶ */
    private static final String[] PARTITION3 = {
            "0011001102212222", "0001001122112221", "0000200122112211", "0222002200110111",
            "0000000011221122", "0011001100220022", "0022002211111111", "0011001122112211",
            "0000000011112222", "0000111111112222", "0000111122222222", "0012001200120012",
            "0112011201120112", "0122012201220122", "0011011211221222", "0011200122002220",
            "0001001101121122", "0111001120012200", "0000112211221122", "0022002200221111",
            "0111011102220222", "0001000122212221", "0000001101220122", "0000110022102210",
            "0122012200110000", "0012001211222222", "0110122112210110", "0000011012211221",
            "0022110211020022", "0110011020022222", "0011012201220011", "0000200022112221",
            "0000000211221222", "0222002200120011", "0011001200220222", "0120012001200120",
            "0000111122220000", "0120120120120120", "0120201212010120", "0011220011220011",
            "0011112222000011", "0101010122222222", "0000000021212121", "0022112200221122",
            "0022001100220011", "0220122102201221", "0101222222220101", "0000212121212121",
            "0101010101012222", "0222011102220111", "0002111200021112", "0000211221122112",
            "0222011101110222", "0002111211120002", "0110011001102222", "0000000021122112",
            "0110011022222222", "0022001100110022", "0022112211220022", "0000000000002112",
            "0002000100020001", "0222122202221222", "0101222222222222", "0111201122012220"};

    /** 2グループのとき、グループ1の「基準のピクセル」（インデックスのビットが1つ少ない）の位置 */
    private static final int[] ANCHOR2 = {
            15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15,
            15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2,
            15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6,
            6, 2, 6, 8, 15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15};

    /** 3グループのとき、グループ1の基準のピクセルの位置 */
    private static final int[] ANCHOR3A = {
            3, 3, 15, 15, 8, 3, 15, 15, 8, 8, 6, 6, 6, 5, 3, 3,
            3, 3, 8, 15, 3, 3, 6, 10, 5, 8, 8, 6, 8, 5, 15, 15,
            8, 15, 3, 5, 6, 10, 8, 15, 15, 3, 15, 5, 15, 15, 15, 15,
            3, 15, 5, 5, 5, 8, 5, 10, 5, 10, 8, 13, 15, 12, 3, 3};

    /** 3グループのとき、グループ2の基準のピクセルの位置 */
    private static final int[] ANCHOR3B = {
            15, 8, 8, 3, 15, 15, 3, 8, 15, 15, 15, 15, 15, 15, 15, 8,
            15, 8, 15, 3, 15, 8, 15, 8, 3, 15, 6, 10, 15, 15, 10, 8,
            15, 3, 15, 10, 10, 8, 9, 10, 6, 15, 8, 15, 3, 6, 6, 8,
            15, 3, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 3, 15, 15, 8};

    /**
     * BC7 の画像データを、ふつうの画像に戻します。
     *
     * @param data   BC7 のデータ（ブロックが左上から右へ、上から下へ並んでいるもの）
     * @param offset data の中で、画像データが始まる位置
     * @param width  画像の幅（ピクセル）
     * @param height 画像の高さ（ピクセル）
     * @return 透明度つきの画像
     */
    public static BufferedImage decode(byte[] data, int offset, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int blocksX = (width + 3) / 4;
        int blocksY = (height + 3) / 4;
        int[] pixels = new int[16];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                decodeBlock(data, offset + (by * blocksX + bx) * 16, pixels);
                for (int i = 0; i < 16; i++) {
                    int x = bx * 4 + (i % 4);
                    int y = by * 4 + (i / 4);
                    if (x < width && y < height) {
                        image.setRGB(x, y, pixels[i]);
                    }
                }
            }
        }
        return image;
    }

    /**
     * ブロックのビットを、下の位置から順に読み出すための小さな道具。
     */
    private static final class BitReader {
        private final byte[] data;
        private final int start;
        private int position;

        BitReader(byte[] data, int start) {
            this.data = data;
            this.start = start;
        }

        /**
         * 次の count ビットを読みます（先に読んだビットが下の桁になる）。
         *
         * @param count 読むビット数（0〜8 くらい）
         * @return 読んだ値
         */
        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bit = (data[start + (position >> 3)] >> (position & 7)) & 1;
                value |= bit << i;
                position++;
            }
            return value;
        }
    }

    /**
     * 1ブロック（16バイト）を、16ピクセル分の色に戻します。
     *
     * @param data   BC7 のデータ
     * @param start  このブロックが始まる位置
     * @param pixels 結果を入れる配列（16個。色は 0xAARRGGBB の形）
     */
    static void decodeBlock(byte[] data, int start, int[] pixels) {
        BitReader bits = new BitReader(data, start);

        // 1. モードを調べる：一番下から数えて最初に 1 が出てきたビットの位置がモードの番号
        int mode = 0;
        while (mode < 8 && bits.read(1) == 0) {
            mode++;
        }
        if (mode == 8) {
            // 決まりにない形のブロックは、透明な黒にする
            java.util.Arrays.fill(pixels, 0);
            return;
        }

        int subsets = SUBSETS[mode];
        int partition = bits.read(PARTITION_BITS[mode]);
        int rotation = bits.read(ROTATION_BITS[mode]);
        int indexSelection = bits.read(INDEX_SELECTION_BITS[mode]);

        // 2. 両端の色を読む：赤 → 緑 → 青 → 透明度 の順に、グループごと・端ごとに並んでいる
        int endpoints = subsets * 2;
        int[][] color = new int[endpoints][4];
        for (int channel = 0; channel < 3; channel++) {
            for (int e = 0; e < endpoints; e++) {
                color[e][channel] = bits.read(COLOR_BITS[mode]);
            }
        }
        if (ALPHA_BITS[mode] > 0) {
            for (int e = 0; e < endpoints; e++) {
                color[e][3] = bits.read(ALPHA_BITS[mode]);
            }
        }

        // 3. P ビット（色の一番下に付け足す1ビット）を読む
        int[] pbit = new int[endpoints];
        if (ENDPOINT_PBIT[mode]) {
            for (int e = 0; e < endpoints; e++) {
                pbit[e] = bits.read(1);
            }
        } else if (SHARED_PBIT[mode]) {
            for (int s = 0; s < subsets; s++) {
                int p = bits.read(1);
                pbit[s * 2] = p;
                pbit[s * 2 + 1] = p;
            }
        }
        boolean hasPbit = ENDPOINT_PBIT[mode] || SHARED_PBIT[mode];

        // 4. 端の色を 0〜255 に広げる
        for (int e = 0; e < endpoints; e++) {
            for (int channel = 0; channel < 4; channel++) {
                int bitCount = channel < 3 ? COLOR_BITS[mode] : ALPHA_BITS[mode];
                if (channel == 3 && bitCount == 0) {
                    color[e][3] = 255; // 透明度が無いモードは不透明
                    continue;
                }
                int value = color[e][channel];
                if (hasPbit) {
                    value = (value << 1) | pbit[e];
                    bitCount++;
                }
                color[e][channel] = expand(value, bitCount);
            }
        }

        // 5. ピクセルごとに、どのグループに入るかを決める
        int[] subsetOf = new int[16];
        for (int i = 0; i < 16; i++) {
            if (subsets == 2) {
                subsetOf[i] = (PARTITION2[partition] >> i) & 1;
            } else if (subsets == 3) {
                subsetOf[i] = PARTITION3[partition].charAt(i) - '0';
            }
        }

        // 6. インデックスを読む。各グループの「基準のピクセル」は、ビットが1つ少ない
        int[] index1 = readIndices(bits, INDEX_BITS[mode], subsets, partition, subsetOf);
        int[] index2 = INDEX2_BITS[mode] > 0 ? readIndices(bits, INDEX2_BITS[mode], 1, 0, new int[16]) : null;

        // 7. 両端の色のあいだを、インデックスに応じて混ぜてピクセルの色を作る
        for (int i = 0; i < 16; i++) {
            int s = subsetOf[i];
            int[] e0 = color[s * 2];
            int[] e1 = color[s * 2 + 1];
            int r;
            int g;
            int b;
            int a;
            if (index2 == null) {
                int w = weight(INDEX_BITS[mode], index1[i]);
                r = mix(e0[0], e1[0], w);
                g = mix(e0[1], e1[1], w);
                b = mix(e0[2], e1[2], w);
                a = mix(e0[3], e1[3], w);
            } else {
                // モード4・5：色と透明度で別々のインデックスを使う（モード4は選び方のビットで入れ替わる）
                int colorBits = indexSelection == 0 ? INDEX_BITS[mode] : INDEX2_BITS[mode];
                int alphaBits = indexSelection == 0 ? INDEX2_BITS[mode] : INDEX_BITS[mode];
                int colorIndex = indexSelection == 0 ? index1[i] : index2[i];
                int alphaIndex = indexSelection == 0 ? index2[i] : index1[i];
                int wc = weight(colorBits, colorIndex);
                int wa = weight(alphaBits, alphaIndex);
                r = mix(e0[0], e1[0], wc);
                g = mix(e0[1], e1[1], wc);
                b = mix(e0[2], e1[2], wc);
                a = mix(e0[3], e1[3], wa);
            }
            // 回転：透明度と、赤・緑・青のどれかを入れ替える
            switch (rotation) {
                case 1 -> { int t = a; a = r; r = t; }
                case 2 -> { int t = a; a = g; g = t; }
                case 3 -> { int t = a; a = b; b = t; }
                default -> { }
            }
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * 16ピクセル分のインデックスを読みます。
     *
     * @param bits      ビットを読む道具
     * @param bitCount  1ピクセルあたりのビット数
     * @param subsets   グループの数
     * @param partition 分け方の番号
     * @param subsetOf  ピクセルごとのグループ（基準のピクセルを決めるのに使う）
     * @return 16ピクセル分のインデックス
     */
    private static int[] readIndices(BitReader bits, int bitCount, int subsets, int partition, int[] subsetOf) {
        int[] indices = new int[16];
        for (int i = 0; i < 16; i++) {
            boolean anchor = i == 0
                    || (subsets == 2 && i == ANCHOR2[partition])
                    || (subsets == 3 && (i == ANCHOR3A[partition] || i == ANCHOR3B[partition]));
            indices[i] = bits.read(anchor ? bitCount - 1 : bitCount);
        }
        return indices;
    }

    /**
     * 少ないビット数の値を、0〜255 に広げます（上の桁を下にも繰り返して埋める）。
     *
     * @param value    元の値
     * @param bitCount 元のビット数
     * @return 0〜255 の値
     */
    private static int expand(int value, int bitCount) {
        value <<= (8 - bitCount);
        return value | (value >> bitCount);
    }

    /**
     * インデックスから、混ぜる割合（0〜64）を求めます。
     *
     * @param bitCount インデックスのビット数（2〜4）
     * @param index    インデックス
     * @return 混ぜる割合
     */
    private static int weight(int bitCount, int index) {
        return switch (bitCount) {
            case 2 -> WEIGHTS2[index];
            case 3 -> WEIGHTS3[index];
            default -> WEIGHTS4[index];
        };
    }

    /**
     * 2つの値を、割合 w（0〜64）で混ぜます。
     *
     * @param a 1つ目の値（w が 0 のとき、この値）
     * @param b 2つ目の値（w が 64 のとき、この値）
     * @param w 割合
     * @return 混ぜた値
     */
    private static int mix(int a, int b, int w) {
        return ((64 - w) * a + w * b + 32) >> 6;
    }

    /**
     * 分け方の表と基準のピクセルの表が、互いに矛盾していないかを確かめます（自分の確認用）。
     * 基準のピクセルは、そのグループに入っていなければなりません。
     *
     * @return 矛盾していた分け方の説明（なければ空文字）
     */
    static String checkTables() {
        StringBuilder problems = new StringBuilder();
        for (int p = 0; p < 64; p++) {
            if (((PARTITION2[p] >> ANCHOR2[p]) & 1) != 1 || (PARTITION2[p] & 1) != 0) {
                problems.append("2グループの").append(p).append(" ");
            }
            String row = PARTITION3[p];
            if (row.length() != 16 || row.charAt(0) != '0'
                    || row.charAt(ANCHOR3A[p]) != '1' || row.charAt(ANCHOR3B[p]) != '2') {
                problems.append("3グループの").append(p).append(" ");
            }
        }
        return problems.toString();
    }
}

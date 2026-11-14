package sts2advisor.game;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 【このファイルの役割】
 * ゲームのデータが詰まった Godot の「.pck」ファイルから、中に入っているファイルを読み出す係です。
 * **読むだけで、.pck は一切書き換えません**（CLAUDE.md の「読み取り専用」）。
 *
 * .pck のつくり（Godot 4 の形式）：
 *   先頭    … "GDPC" という目印、形式のバージョン、Godot のバージョン
 *   続き    … 設定（暗号化されているか）、中身の始まる位置、目次の位置
 *   目次    … 入っているファイルの数と、ファイルごとの「名前・置き場所・大きさ」
 *   中身    … ファイルの中身がそのまま並んでいる
 * 目次を読めば、欲しいファイルの置き場所が分かり、その部分だけを読み出せます。
 *
 * 使い方：
 *   try (GamePck pck = new GamePck(.pckの場所)) {
 *       byte[] data = pck.read("localization/jpn/cards.json");
 *   }
 */
public class GamePck implements AutoCloseable {

    /** 目次の1件分（ファイル1つの置き場所と大きさ） */
    private record Entry(long offset, long size) {
    }

    /** .pck ファイルを、好きな位置から読めるように開いたもの */
    private final RandomAccessFile file;

    /** 目次（ファイル名 → 置き場所と大きさ） */
    private final Map<String, Entry> entries = new HashMap<>();

    /**
     * .pck ファイルを開き、目次を読み込みます。
     *
     * @param pckFile .pck ファイルの場所
     * @throws IOException 開けなかった、.pck の形式ではなかった、または暗号化されていたとき
     */
    public GamePck(Path pckFile) throws IOException {
        file = new RandomAccessFile(pckFile.toFile(), "r"); // "r" は読み取り専用で開くという意味
        try {
            readIndex();
        } catch (IOException e) {
            file.close();
            throw e;
        }
    }

    /**
     * 先頭の情報と目次を読み、entries に入れます。
     *
     * @throws IOException .pck の形式ではなかった、または暗号化されていたとき
     */
    private void readIndex() throws IOException {
        byte[] magic = new byte[4];
        file.readFully(magic);
        if (!"GDPC".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("Godot の .pck ファイルではありません");
        }

        // 形式のバージョン（2 か 3）と Godot のバージョン（4.5.1 なら 4, 5, 1）
        int packVersion = readInt();
        readInt(); // Godot の大きい番号（使わない）
        readInt(); // Godot の中くらいの番号（使わない）
        readInt(); // Godot の小さい番号（使わない）
        if (packVersion < 2 || packVersion > 3) {
            throw new IOException("対応していない .pck の形式です（バージョン " + packVersion + "）");
        }

        int flags = readInt();
        long fileBase = readLong(); // 中身の始まる位置。目次に書かれた位置はここからの距離
        if ((flags & 1) != 0) {
            throw new IOException(".pck の目次が暗号化されているため読めません");
        }

        if (packVersion == 3) {
            // 形式3では、目次の位置が書いてあるので、そこへ移動する
            long directoryOffset = readLong();
            file.seek(directoryOffset);
        } else {
            // 形式2では、予備の領域（64バイト）のすぐ後ろに目次がある
            file.skipBytes(64);
        }

        int fileCount = readInt();
        for (int i = 0; i < fileCount; i++) {
            int nameLength = readInt();
            byte[] nameBytes = new byte[nameLength];
            file.readFully(nameBytes);
            // 名前の後ろに長さ合わせの 0 が付いていることがあるので取り除く
            String name = new String(nameBytes, StandardCharsets.UTF_8).replace("\0", "");
            long offset = readLong();
            long size = readLong();
            file.skipBytes(16); // ファイルが壊れていないか確かめるための値（MD5。今は使わない）
            readInt();          // ファイルごとの設定（今は使わない）
            entries.put(name, new Entry(fileBase + offset, size));
        }
    }

    /**
     * .pck に入っているファイル名の一覧を返します（アイコンの画像を探すときなどに使う）。
     *
     * @return ファイル名の一覧（変更できない）
     */
    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * .pck の中のファイルを1つ読み出します。
     *
     * @param path .pck の中でのファイル名（例："localization/jpn/cards.json"）
     * @return ファイルの中身
     * @throws IOException そのファイルが入っていない、または読めなかったとき
     */
    public byte[] read(String path) throws IOException {
        Entry entry = entries.get(path);
        if (entry == null) {
            throw new IOException(".pck の中に " + path + " が見つかりません");
        }
        byte[] data = new byte[Math.toIntExact(entry.size())];
        file.seek(entry.offset());
        file.readFully(data);
        return data;
    }

    /**
     * 4バイトの整数を読みます。.pck は「リトルエンディアン」（小さい桁から順に並べる方式）なので、
     * Java の標準（大きい桁から）とは逆の順番で読みます。
     *
     * @return 読んだ整数
     * @throws IOException 読めなかったとき
     */
    private int readInt() throws IOException {
        byte[] bytes = new byte[4];
        file.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * 8バイトの整数を読みます（readInt と同じく、小さい桁から順に並んでいる）。
     *
     * @return 読んだ整数
     * @throws IOException 読めなかったとき
     */
    private long readLong() throws IOException {
        byte[] bytes = new byte[8];
        file.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /**
     * .pck ファイルを閉じます。try (...) を使っていれば自動で呼ばれます。
     *
     * @throws IOException 閉じるのに失敗したとき
     */
    @Override
    public void close() throws IOException {
        file.close();
    }
}

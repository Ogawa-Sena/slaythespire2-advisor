package sts2advisor.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sts2advisor.model.RunState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 【このファイルの役割】
 * ゲームのセーブファイル（current_run.save）から、今のランの状態を読み取る係です（機能要件 F-04）。
 * **読むだけで、セーブファイルは一切書き換えません**（CLAUDE.md の「読み取り専用」）。
 *
 * セーブファイルの場所（2026-11-11 の調査。docs/game_files_survey.md を参照）：
 *   %APPDATA%\SlayTheSpire2\steam\<SteamのID>\profile1\saves\current_run.save          … Mod なしで遊んだとき
 *   %APPDATA%\SlayTheSpire2\steam\<SteamのID>\modded\profile1\saves\current_run.save   … Mod ありで遊んだとき
 * どちらで遊んでいるかは分からないので、いちばん最近書き換えられたものを使います。
 *
 * セーブファイルには乱数の状態（rng）も入っていますが、先の報酬を予測することになるので**使いません**。
 */
public class SaveFileReader {

    /** セーブファイルの名前 */
    private static final String SAVE_NAME = "current_run.save";

    /** セーブフォルダの一番上（%APPDATA%\SlayTheSpire2\steam） */
    private final Path steamRoot;

    /**
     * 標準の場所（%APPDATA%\SlayTheSpire2\steam）のセーブを読みます。
     */
    public SaveFileReader() {
        this(Path.of(System.getenv("APPDATA"), "SlayTheSpire2", "steam"));
    }

    /**
     * @param steamRoot セーブフォルダの一番上（テスト用に別の場所を指定できる）
     */
    public SaveFileReader(Path steamRoot) {
        this.steamRoot = steamRoot;
    }

    /**
     * いちばん最近書き換えられた current_run.save を探します。
     *
     * @return セーブファイルの場所。見つからなければ空
     * @throws IOException フォルダを読めなかったとき
     */
    public Optional<Path> findLatestSave() throws IOException {
        if (!Files.isDirectory(steamRoot)) {
            return Optional.empty();
        }
        // steam の下を5段まで探す（<ID>\modded\profile1\saves\current_run.save が5段目）
        try (Stream<Path> files = Files.walk(steamRoot, 5)) {
            return files.filter(p -> p.getFileName().toString().equals(SAVE_NAME))
                    .max(Comparator.comparing(SaveFileReader::lastModified));
        }
    }

    /** 読めなかったときに読み直す回数（ゲームが書き込んでいる途中に読んだときのため） */
    private static final int READ_TRIES = 3;

    /** 読み直す前に待つ時間（ミリ秒） */
    private static final long RETRY_WAIT_MILLIS = 300;

    /**
     * いちばん最近のセーブファイルから、今のランの状態を読みます。
     * ゲームが書き込んでいる途中で読めなかったときは、少し待ってから読み直します（最大 READ_TRIES 回）。
     *
     * @return ランの状態。セーブファイルが見つからなければ空
     * @throws IOException 読み直しても読めなかったとき
     */
    public Optional<RunState> readLatest() throws IOException {
        Optional<Path> save = findLatestSave();
        if (save.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 1; ; i++) {
            try {
                return Optional.of(read(save.get()));
            } catch (IOException e) {
                if (i >= READ_TRIES) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * 指定したセーブファイルから、ランの状態を読みます。
     *
     * @param saveFile セーブファイルの場所
     * @return ランの状態
     * @throws IOException セーブファイルを読めなかった、または形式が想定と違ったとき
     */
    public RunState read(Path saveFile) throws IOException {
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(saveFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            // ゲームが書き込んでいる途中に読むと、途中までしかなくて読めないことがある
            throw new IOException("セーブファイルを読めませんでした（書き込み中の可能性）: " + saveFile, e);
        }

        JsonArray players = json.getAsJsonArray("players");
        if (players == null || players.isEmpty()) {
            throw new IOException("セーブファイルにプレイヤーの情報がありません: " + saveFile);
        }
        // ソロのみが対象なので、1人目のプレイヤーを読む
        JsonObject player = players.get(0).getAsJsonObject();

        // デッキ：カードIDと強化の回数
        List<RunState.DeckCard> deck = new ArrayList<>();
        for (JsonElement element : player.getAsJsonArray("deck")) {
            JsonObject card = element.getAsJsonObject();
            int upgrade = card.has("current_upgrade_level") ? card.get("current_upgrade_level").getAsInt() : 0;
            deck.add(new RunState.DeckCard(stripPrefix(card.get("id").getAsString()), upgrade));
        }

        // 階層：map_point_history に、Act ごとに通ったマスが並んでいるので、全部の数を足す
        int floor = 0;
        for (JsonElement act : json.getAsJsonArray("map_point_history")) {
            floor += act.getAsJsonArray().size();
        }

        return new RunState(
                stripPrefix(player.get("character_id").getAsString()),
                json.get("ascension").getAsInt(),
                json.get("current_act_index").getAsInt() + 1, // セーブは 0 から数えているので +1
                floor,
                player.get("gold").getAsInt(),
                player.get("current_hp").getAsInt(),
                player.get("max_hp").getAsInt(),
                deck,
                ids(player.getAsJsonArray("relics")),
                ids(player.getAsJsonArray("potions")),
                player.has("max_potion_slot_count") ? player.get("max_potion_slot_count").getAsInt() : 3,
                players.size(),
                saveFile,
                lastModified(saveFile));
    }

    /**
     * {"id": "RELIC.XXX", ...} が並んだ配列から、IDだけを取り出します（先頭の "RELIC." などは取る）。
     *
     * @param array 配列（null でもよい）
     * @return IDの一覧
     */
    private static List<String> ids(JsonArray array) {
        List<String> ids = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                ids.add(stripPrefix(element.getAsJsonObject().get("id").getAsString()));
            }
        }
        return ids;
    }

    /**
     * "CARD.ZAP" のような ID から、先頭の種類の部分（"CARD."）を取り除きます。
     * データベースのカードIDは "ZAP" の形なので、それに合わせるためです。
     *
     * @param id セーブファイルの ID
     * @return 先頭の種類を取り除いた ID
     */
    static String stripPrefix(String id) {
        int dot = id.indexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    /**
     * ファイルが最後に書き換えられた日時を返します。読めなければ、とても古い日時にします。
     *
     * @param file ファイル
     * @return 最後に書き換えられた日時
     */
    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}

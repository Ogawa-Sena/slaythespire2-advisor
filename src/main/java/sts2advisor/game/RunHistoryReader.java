package sts2advisor.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 【このファイルの役割】
 * ゲームが残している「ラン履歴」（1ラン＝1ファイルの history\番号.run）を読む係です（機能要件 F-13）。
 * 自分のプレイの統計（F-07）の元データにします。
 *
 * 場所（docs/game_files_survey.md の「4. セーブデータ・ラン履歴」）：
 *   %APPDATA%\SlayTheSpire2\steam\<SteamのID>\profile1\saves\history\*.run          … Mod なしで遊んだラン
 *   %APPDATA%\SlayTheSpire2\steam\<SteamのID>\modded\profile1\saves\history\*.run   … Mod ありで遊んだラン
 * 中身はふつうの JSON です。読むだけで、書き換えません（CLAUDE.md の「読み取り専用」）。
 *
 * 取り出すもの：
 *   ・ランの結果：キャラ、アセンション、ゲームのバージョン、勝ったか、途中でやめたか、何人で遊んだか、到達した階
 *   ・カードの選択：マスごとの card_choices（何が出て、どれを取ったか）。マスの種類（戦闘・ショップなど）も一緒に
 */
public class RunHistoryReader {

    /**
     * 1回のカードの選択肢（候補のカード1枚分）。
     *
     * @param floor    何階目か（1から）
     * @param act      何番目の Act か（1から）
     * @param roomType マスの種類（"monster"、"elite"、"boss"、"shop" など）
     * @param cardId   カードID（"CARD." を取ったもの。例："STRIKE_DEFECT"）
     * @param picked   取ったか（ショップなら買ったか）
     */
    public record Choice(int floor, int act, String roomType, String cardId, boolean picked) {
    }

    /**
     * 1ラン分の履歴。
     *
     * @param fileName  ファイルの名前（例："1793111068.run"。ランごとに違うので、見分けに使う）
     * @param modded    Mod ありで遊んだランなら true
     * @param character キャラ（"CHARACTER." を取ったもの。例："DEFECT"）
     * @param ascension アセンション
     * @param buildId   ゲームのバージョン（例："v0.111.0"）
     * @param win       勝ったか
     * @param abandoned 途中でやめたか
     * @param players   遊んだ人数（1 ならソロ）
     * @param floors    到達した階（通ったマスの数）
     * @param startTime ランを始めた時刻（1970年からの秒数）
     * @param choices   カードの選択の一覧
     */
    public record PastRun(String fileName, boolean modded, String character, int ascension, String buildId,
                          boolean win, boolean abandoned, int players, int floors, long startTime,
                          List<Choice> choices) {
    }

    /** セーブフォルダの一番上（%APPDATA%\SlayTheSpire2\steam） */
    private final Path steamRoot;

    /**
     * 標準の場所（%APPDATA%\SlayTheSpire2\steam）のラン履歴を読みます。
     */
    public RunHistoryReader() {
        this(Path.of(System.getenv("APPDATA"), "SlayTheSpire2", "steam"));
    }

    /**
     * @param steamRoot セーブフォルダの一番上（試すときに別の場所を指定できるように）
     */
    public RunHistoryReader(Path steamRoot) {
        this.steamRoot = steamRoot;
    }

    /**
     * ラン履歴のファイルを全部探します。
     *
     * @return ファイルの一覧（無ければ空）
     * @throws IOException フォルダを読めなかったとき
     */
    public List<Path> findFiles() throws IOException {
        if (!Files.isDirectory(steamRoot)) {
            return List.of();
        }
        // steam の下を6段まで探す（<ID>\modded\profile1\saves\history\*.run が6段目）
        try (Stream<Path> files = Files.walk(steamRoot, 6)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".run"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("history"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * ラン履歴のファイルを1つ読みます。
     *
     * @param file ファイル
     * @return 1ラン分の履歴
     * @throws IOException 読めなかった、または形が想定と違ったとき
     */
    public PastRun read(Path file) throws IOException {
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("ラン履歴を読めませんでした: " + file + "（" + e.getMessage() + "）", e);
        }
        JsonArray players = json.getAsJsonArray("players");
        String character = players.get(0).getAsJsonObject().get("character").getAsString().replace("CHARACTER.", "");
        boolean modded = file.toString().contains("\\modded\\") || file.toString().contains("/modded/");

        // マスごとの記録。Act ごとの並びの中に、通ったマスが順に入っている
        List<Choice> choices = new ArrayList<>();
        int floor = 0;
        int act = 0;
        for (JsonElement actPoints : json.getAsJsonArray("map_point_history")) {
            act++;
            for (JsonElement p : actPoints.getAsJsonArray()) {
                floor++;
                JsonObject point = p.getAsJsonObject();
                String roomType = point.has("map_point_type") ? point.get("map_point_type").getAsString() : "";
                // ソロなら player_stats は1人分。マルチのランは統計に使わないが、1人目の分だけ読んでおく
                JsonArray stats = point.getAsJsonArray("player_stats");
                if (stats == null || stats.isEmpty()) {
                    continue;
                }
                JsonObject mine = stats.get(0).getAsJsonObject();
                if (!mine.has("card_choices")) {
                    continue;
                }
                for (JsonElement c : mine.getAsJsonArray("card_choices")) {
                    JsonObject choice = c.getAsJsonObject();
                    String cardId = choice.getAsJsonObject("card").get("id").getAsString().replace("CARD.", "");
                    boolean picked = choice.has("was_picked") && choice.get("was_picked").getAsBoolean();
                    choices.add(new Choice(floor, act, roomType, cardId, picked));
                }
            }
        }
        return new PastRun(file.getFileName().toString(), modded, character,
                json.has("ascension") ? json.get("ascension").getAsInt() : 0,
                json.has("build_id") ? json.get("build_id").getAsString() : "",
                json.has("win") && json.get("win").getAsBoolean(),
                json.has("was_abandoned") && json.get("was_abandoned").getAsBoolean(),
                players.size(), floor,
                json.has("start_time") ? json.get("start_time").getAsLong() : 0,
                choices);
    }
}

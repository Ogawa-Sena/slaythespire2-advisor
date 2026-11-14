package sts2advisor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sts2advisor.db.CardDatabase;
import sts2advisor.game.GameFiles;
import sts2advisor.game.GamePck;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 【このファイルの役割】
 * ゲームファイルからカードの名前と効果テキストを読み、外部データ（Spire Codex）と照合する「入口」です
 * （機能要件 F-05）。食い違いがあれば、CLAUDE.md のとおり**ゲームファイルを正とします**。
 *
 * 処理の流れ：
 *   1. ゲームのバージョンを release_info.json から読む
 *   2. SlayTheSpire2.pck から、日本語と英語のカードの翻訳データを読む（読むだけで、ゲームは変更しない）
 *   3. ゲームのカードを game_cards 表に保存する
 *   4. cards 表（Spire Codex のデータ）と比べる
 *      ・名前が違う → cards 表の名前をゲームの名前に書き換え、card_mismatches 表に記録する
 *      ・効果テキストが違う → card_mismatches 表に記録するだけ（数字の入った文章はゲームファイルから作れないため）
 *
 * 注意：ImportCardsMain を実行すると、cards 表の名前が Spire Codex の名前に戻ります。
 *       カードデータを取り込み直したあとは、必ずこのプログラムも実行してください。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.SyncGameCardsMain
 * ゲームを標準以外の場所に入れている場合は、ゲームフォルダの場所を渡します：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.SyncGameCardsMain -Dexec.args="D:\SteamLibrary\steamapps\common\Slay the Spire 2"
 */
public class SyncGameCardsMain {

    /** データベースファイルの場所（ImportCardsMain と同じファイル） */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /** 取得元としてデータベースに記録する名前 */
    private static final String SOURCE_NAME = "ゲームファイル (SlayTheSpire2.pck)";

    /**
     * プログラムの入口。ここから処理が始まります。
     *
     * @param args 1つ目にゲームフォルダの場所を渡せる（省略すると Steam の標準の場所）
     * @throws Exception ゲームファイルやデータベースで失敗したとき（内容を表示して止まる）
     */
    public static void main(String[] args) throws Exception {
        Path gameDir = args.length > 0 ? Path.of(args[0]) : GameFiles.DEFAULT_GAME_DIR;
        GameFiles game = new GameFiles(gameDir);
        String version = game.readVersion();
        String now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        System.out.println("ゲームフォルダ: " + gameDir);
        System.out.println("ゲームのバージョン: " + version);

        // 2. 翻訳データを読む
        Map<String, String> textsJa;
        Map<String, String> textsEn;
        try (GamePck pck = new GamePck(game.pckFile())) {
            textsJa = GameFiles.readLocalization(pck, "jpn", "cards");
            textsEn = GameFiles.readLocalization(pck, "eng", "cards");
        }

        // 翻訳データのキーは "カードID.title" や "カードID.description" の形。".title" があるものをカードとみなす
        Set<String> gameCardIds = new TreeSet<>();
        for (String key : textsJa.keySet()) {
            if (key.endsWith(".title")) {
                gameCardIds.add(key.substring(0, key.length() - ".title".length()));
            }
        }
        System.out.println("ゲームファイルのカード: " + gameCardIds.size() + " 枚");

        int nameFixed = 0;
        int descriptionDiffs = 0;
        int notInGame = 0;
        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            Map<String, String[]> external = db.findCardsForComparison();

            // 途中で失敗したら全部取り消せるように、まとめて保存する
            db.beginBatch();
            try {
                // 3. ゲームのカードを保存する
                for (String id : gameCardIds) {
                    db.saveGameCard(id,
                            textsJa.get(id + ".title"), textsEn.get(id + ".title"),
                            textsJa.get(id + ".description"), textsEn.get(id + ".description"),
                            SOURCE_NAME, version, now);
                }

                // 4. 外部データと比べる
                for (Map.Entry<String, String[]> entry : external.entrySet()) {
                    String id = entry.getKey();
                    String externalNameJa = entry.getValue()[0];
                    String externalNameEn = entry.getValue()[1];
                    String externalRawJson = entry.getValue()[2];

                    if (!gameCardIds.contains(id)) {
                        // 外部データにはあるが、ゲームファイルにはないカード（今は使われていない可能性）
                        notInGame++;
                        System.out.println("ゲームファイルにないカード: " + id + "（" + externalNameJa + "）");
                        continue;
                    }

                    String gameNameJa = textsJa.get(id + ".title");
                    String gameNameEn = textsEn.getOrDefault(id + ".title", externalNameEn);

                    // 名前の照合：違えばゲームの名前に直す
                    boolean jaDiffers = !Objects.equals(gameNameJa, externalNameJa);
                    boolean enDiffers = !Objects.equals(gameNameEn, externalNameEn);
                    if (jaDiffers) {
                        db.saveMismatch(id, "name_ja", externalNameJa, gameNameJa, version, now);
                    }
                    if (enDiffers) {
                        db.saveMismatch(id, "name_en", externalNameEn, gameNameEn, version, now);
                    }
                    if (jaDiffers || enDiffers) {
                        db.updateCardNames(id, gameNameJa, gameNameEn);
                        nameFixed++;
                        System.out.println("名前をゲームに合わせて直しました: " + id + "（"
                                + externalNameJa + " → " + gameNameJa + "）");
                    }

                    // 効果テキストの照合：Spire Codex の「穴あきのままの効果テキスト」（description_raw）と比べる
                    String gameTemplate = textsJa.get(id + ".description");
                    String externalTemplate = rawDescription(externalRawJson);
                    if (gameTemplate != null && !Objects.equals(gameTemplate, externalTemplate)) {
                        db.saveMismatch(id, "description_ja_template", externalTemplate, gameTemplate, version, now);
                        descriptionDiffs++;
                    }
                }
                db.commitBatch();
            } catch (Exception e) {
                db.rollbackBatch();
                throw e;
            }
        }

        System.out.println("---- 結果 ----");
        System.out.println("名前を直したカード: " + nameFixed + " 枚");
        System.out.println("効果テキストが食い違っていたカード: " + descriptionDiffs + " 枚（card_mismatches 表に記録）");
        System.out.println("外部データにあるが、ゲームファイルにないカード: " + notInGame + " 枚");
    }

    /**
     * Spire Codex の元のJSONから、穴あきのままの効果テキスト（description_raw）を取り出します。
     *
     * @param rawJson cards 表の raw_json_ja の中身
     * @return 穴あきのままの効果テキスト。なければ null
     */
    private static String rawDescription(String rawJson) {
        if (rawJson == null) {
            return null;
        }
        JsonObject json = JsonParser.parseString(rawJson).getAsJsonObject();
        JsonElement value = json.get("description_raw");
        return (value == null || value.isJsonNull()) ? null : value.getAsString();
    }
}

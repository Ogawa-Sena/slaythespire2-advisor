package sts2advisor.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【このファイルの役割】
 * インストールされているゲーム（Slay the Spire 2）のフォルダから、必要な情報を読む係です。
 * **読むだけで、ゲームのファイルは一切書き換えません**。
 *
 * 読めるもの：
 *   ・ゲームのバージョン（release_info.json）
 *   ・.pck の中の翻訳データ（localization/<言語>/<種類>.json。カード名や効果テキストなど）
 */
public class GameFiles {

    /** Steam で標準の場所にインストールした場合のゲームフォルダ */
    public static final Path DEFAULT_GAME_DIR =
            Path.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Slay the Spire 2");

    /** ゲームフォルダの場所 */
    private final Path gameDir;

    /**
     * @param gameDir ゲームフォルダの場所（例：DEFAULT_GAME_DIR）
     */
    public GameFiles(Path gameDir) {
        this.gameDir = gameDir;
    }

    /**
     * ゲームのバージョンを読みます（パッチの検知に使う）。
     *
     * @return バージョン（例："v0.111.0"）
     * @throws IOException release_info.json が見つからない、または読めなかったとき
     */
    public String readVersion() throws IOException {
        String text = Files.readString(gameDir.resolve("release_info.json"), StandardCharsets.UTF_8);
        return JsonParser.parseString(text).getAsJsonObject().get("version").getAsString();
    }

    /**
     * .pck ファイルの場所を返します。
     *
     * @return SlayTheSpire2.pck の場所
     */
    public Path pckFile() {
        return gameDir.resolve("SlayTheSpire2.pck");
    }

    /**
     * .pck の中の翻訳データを1つ読みます。
     *
     * @param pck  開いた .pck
     * @param lang 言語（日本語は "jpn"、英語は "eng"）
     * @param kind 種類（カードは "cards"、レリックは "relics" など）
     * @return 「キー → 文字」の対応（例："ABRASIVE.title" → "棘纏い"）
     * @throws IOException そのファイルが入っていない、または読めなかったとき
     */
    public static Map<String, String> readLocalization(GamePck pck, String lang, String kind) throws IOException {
        byte[] data = pck.read("localization/" + lang + "/" + kind + ".json");
        JsonObject json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();

        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            texts.put(entry.getKey(), entry.getValue().getAsString());
        }
        return texts;
    }
}

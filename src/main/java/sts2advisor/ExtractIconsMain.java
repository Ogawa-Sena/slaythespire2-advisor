package sts2advisor;

import sts2advisor.game.GameFiles;
import sts2advisor.game.GamePck;
import sts2advisor.game.GodotTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【このファイルの役割】
 * ゲームファイルから、レリックとポーションのアイコンを1つずつ PNG 画像として取り出す「入口」です
 * （MVP のステップ8-1。ショップに並んだレリック・ポーションを、アイコンの照らし合わせで特定するため）。
 * 読むだけで、ゲームのファイルは変更しません。
 *
 * 処理の流れ：
 *   1. アイコンがまとめて描かれた大きな画像（アトラス）を .pck から読み、ふつうの画像に戻す
 *      ・レリック：relic_atlas（BC7 という圧縮形式）
 *      ・ポーション：potion_atlas（WebP 形式）
 *   2. アイコンごとの「アトラスのどこにあるか」の情報（.tres）を読み、その部分を切り出す
 *   3. data/icons/relic/ID.png、data/icons/potion/ID.png に保存する（ID は大文字。例：AKABEKO.png）
 *   4. データベースのレリック・ポーションのうち、アイコンが見つからなかったものを表示する
 *
 * ゲームのパッチでアイコンが変わったら、もう一度実行してください。
 * 取り出した画像はゲームの絵なので、data/icons/ は .gitignore で共有しないようにしています。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ExtractIconsMain
 */
public class ExtractIconsMain {

    /** 取り出したアイコンを保存するフォルダ */
    private static final Path ICON_DIR = Path.of("data", "icons");

    /** データベースファイルの場所（アイコンが見つからないものを調べるのに使う） */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /** .tres の中の「アトラスのどこにあるか」の行（例：region = Rect2(2644, 704, 81, 69)） */
    private static final Pattern REGION = Pattern.compile(
            "region\\s*=\\s*Rect2\\(\\s*([\\d.]+),\\s*([\\d.]+),\\s*([\\d.]+),\\s*([\\d.]+)\\s*\\)");

    /** .import の中の「本当の画像ファイルの場所」の行（例：path.bptc="res://.godot/imported/～.ctex"） */
    private static final Pattern IMPORT_PATH = Pattern.compile("path(?:\\.\\w+)?=\"res://([^\"]+)\"");

    /**
     * プログラムの入口。
     *
     * @param args 1つ目にゲームフォルダの場所を渡せる（省略すると Steam の標準の場所）
     * @throws Exception ゲームファイルや画像の保存で失敗したとき
     */
    public static void main(String[] args) throws Exception {
        Path gameDir = args.length > 0 ? Path.of(args[0]) : GameFiles.DEFAULT_GAME_DIR;
        GameFiles game = new GameFiles(gameDir);
        System.out.println("ゲームのバージョン: " + game.readVersion());

        try (GamePck pck = new GamePck(game.pckFile())) {
            extract(pck, "relic_atlas", "relic");
            extract(pck, "potion_atlas", "potion");
        }
        reportMissing("relic");
        reportMissing("potion");
        System.out.println("保存先: " + ICON_DIR.toAbsolutePath());
    }

    /**
     * 1つのアトラスから、アイコンを全部切り出して保存します。
     *
     * @param pck       ゲームの .pck
     * @param atlasName アトラスの名前（例："relic_atlas"）
     * @param itemType  保存するフォルダの名前（"relic" か "potion"）
     * @throws Exception 読み出しや保存に失敗したとき
     */
    private static void extract(GamePck pck, String atlasName, String itemType) throws Exception {
        // 1. アトラスの本当の画像ファイル（.ctex）の場所を .import から調べ、画像に戻す
        String importText = new String(pck.read("images/atlases/" + atlasName + ".png.import"), StandardCharsets.UTF_8);
        Matcher m = IMPORT_PATH.matcher(importText);
        if (!m.find()) {
            throw new IllegalStateException(atlasName + " の画像ファイルの場所が見つかりません");
        }
        BufferedImage atlas = GodotTexture.decode(pck.read(m.group(1)));

        // 2・3. アイコンごとの .tres を読み、その部分を切り出して保存する
        Path outDir = ICON_DIR.resolve(itemType);
        Files.createDirectories(outDir);
        String prefix = "images/atlases/" + atlasName + ".sprites/";
        int saved = 0;
        for (String name : pck.names()) {
            if (!name.startsWith(prefix) || !name.endsWith(".tres")) {
                continue;
            }
            String tres = new String(pck.read(name), StandardCharsets.UTF_8);
            Matcher r = REGION.matcher(tres);
            if (!r.find()) {
                continue;
            }
            int x = (int) Double.parseDouble(r.group(1));
            int y = (int) Double.parseDouble(r.group(2));
            int w = (int) Double.parseDouble(r.group(3));
            int h = (int) Double.parseDouble(r.group(4));
            if (w <= 0 || h <= 0 || x + w > atlas.getWidth() || y + h > atlas.getHeight()) {
                continue;
            }
            // ファイル名（例："akabeko.tres"）を、データベースの ID（例："AKABEKO"）の形にする
            String id = name.substring(prefix.length(), name.length() - ".tres".length()).toUpperCase(Locale.ROOT);
            BufferedImage icon = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            icon.getGraphics().drawImage(atlas.getSubimage(x, y, w, h), 0, 0, null);
            ImageIO.write(icon, "png", outDir.resolve(id + ".png").toFile());
            saved++;
        }
        System.out.println(itemType + "：アイコン " + saved + " 個を保存");
    }

    /**
     * データベースにあるのに、アイコンが取り出せなかったものを表示します。
     *
     * @param itemType "relic" か "potion"
     * @throws Exception データベースを読めなかったとき
     */
    private static void reportMissing(String itemType) throws Exception {
        Set<String> missing = new HashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT item_id FROM items WHERE item_type = '" + itemType + "'")) {
            while (rs.next()) {
                String id = rs.getString(1);
                if (!Files.exists(ICON_DIR.resolve(itemType).resolve(id + ".png"))) {
                    missing.add(id);
                }
            }
        }
        System.out.println(itemType + "：アイコンが無いもの " + missing.size() + " 個 " + missing);
    }
}

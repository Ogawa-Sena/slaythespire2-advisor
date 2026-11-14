package sts2advisor;

import sts2advisor.db.RunHistoryDatabase;
import sts2advisor.game.RunHistoryReader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【このファイルの役割】
 * 自分のラン履歴（ゲームの history\*.run）を、データベースに取り込む「入口」です（機能要件 F-13）。
 * まだ取り込んでいないランだけを読むので、何度動かしても大丈夫です。
 * 監視（WatchScreenMain）を起動したときにも、同じ処理（importNew）を自動で動かします。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ImportRunHistoryMain
 * 取り込んだあと、キャラごとの成績と、よく取ったカードを表示します（確認用）。
 */
public class ImportRunHistoryMain {

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /** 確認用に表示するキャラ（公式の5キャラ） */
    private static final List<String> CHARACTERS = List.of("IRONCLAD", "SILENT", "DEFECT", "NECROBINDER", "REGENT");

    /**
     * プログラムの入口。
     *
     * @param args 使わない
     * @throws Exception ファイルやデータベースを読み書きできなかったとき
     */
    public static void main(String[] args) throws Exception {
        int added = importNew(DB_FILE);
        System.out.println("新しく取り込んだラン：" + added);

        try (RunHistoryDatabase db = new RunHistoryDatabase(DB_FILE)) {
            for (String character : CHARACTERS) {
                int[] record = db.findOwnRecord(character);
                Map<String, RunHistoryDatabase.OwnCardStat> stats = db.findOwnCardStats(character);
                System.out.println("== " + character + "：最後まで遊んだソロのラン " + record[0] + "（勝ち " + record[1]
                        + "）、カード報酬で出たカード " + stats.size() + "種類");
                // よく取ったカードの上位5つ
                stats.values().stream()
                        .sorted((a, b) -> Integer.compare(b.picked(), a.picked()))
                        .limit(5)
                        .forEach(s -> System.out.println("   " + s.cardId() + "：出た " + s.offered() + "回・取った "
                                + s.picked() + "回、取ったランの成績 " + s.wins() + "勝/" + s.finishedRuns() + "ラン"));
            }
        }
    }

    /**
     * まだ取り込んでいないラン履歴を読んで、データベースに保存します。
     * 読めなかったファイルは、理由を表示して飛ばします（ほかのランの取り込みは続ける）。
     *
     * @param dbFile データベースファイルの場所
     * @return 新しく取り込んだランの数
     * @throws Exception フォルダやデータベースを読み書きできなかったとき
     */
    public static int importNew(Path dbFile) throws Exception {
        RunHistoryReader reader = new RunHistoryReader();
        int added = 0;
        try (RunHistoryDatabase db = new RunHistoryDatabase(dbFile)) {
            Set<String> known = db.findRunKeys();
            for (Path file : reader.findFiles()) {
                boolean modded = file.toString().contains("\\modded\\") || file.toString().contains("/modded/");
                String key = (modded ? "modded/" : "") + file.getFileName();
                if (known.contains(key)) {
                    continue;
                }
                try {
                    db.saveRun(reader.read(file));
                    added++;
                } catch (Exception e) {
                    System.out.println("ラン履歴を取り込めませんでした: " + file.getFileName() + "（" + e.getMessage() + "）");
                }
            }
        }
        return added;
    }
}

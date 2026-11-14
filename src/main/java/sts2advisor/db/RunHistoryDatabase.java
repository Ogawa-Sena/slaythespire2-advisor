package sts2advisor.db;

import sts2advisor.game.RunHistoryReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【このファイルの役割】
 * 自分のラン履歴（RunHistoryReader で読んだもの）をデータベースに保存し、
 * カードごとの「自分の統計」を集計する係です（機能要件 F-13, F-07）。
 *
 * 表：
 *   own_runs          … 1行＝1ラン（キャラ・アセンション・バージョン・勝敗・途中でやめたか など）
 *   own_card_choices  … 1行＝カードの選択肢1枚（何階で・どのマスで・どのカードが出て・取ったか）
 *
 * 集計のきまり（2026-11-12 の仮の決め方。CLAUDE.md の「未決定事項」にある重みの計算はまだ）：
 *   ・ソロのランだけを使う（CLAUDE.md で対象はソロのみ）
 *   ・「出た回数・取った回数」は、カード報酬のマス（ショップ以外）だけで数える
 *   ・「取ったランの勝率」は、途中でやめたランを除いて数える（やめたランは勝ち負けが分からないため）
 *   ・Mod ありのフォルダ（modded）のランも含める（カードの名前や効果は公式のままのため。
 *     含めるかは要相談。own_runs の modded 列で、あとから分けられる）
 */
public class RunHistoryDatabase implements AutoCloseable {

    /**
     * 1枚のカードについての、自分の統計。
     *
     * @param cardId       カードID
     * @param offered      カード報酬で出た回数
     * @param picked       そのうち取った回数
     * @param finishedRuns 取ったランのうち、最後まで遊んだ（途中でやめていない）ランの数
     * @param wins         そのうち勝ったランの数
     */
    public record OwnCardStat(String cardId, int offered, int picked, int finishedRuns, int wins) {

        /**
         * @return 取った割合（%）。出た回数が0なら null
         */
        public Double pickRate() {
            return offered == 0 ? null : 100.0 * picked / offered;
        }
    }

    /** データベースとのつながり（接続） */
    private final Connection connection;

    /**
     * データベースファイルを開き、表がなければ作ります。
     *
     * @param dbFile データベースファイルの場所（例：data/sts2advisor.db）
     * @throws IOException  フォルダを作れなかったとき
     * @throws SQLException データベースを開けなかった、または表を作れなかったとき
     */
    public RunHistoryDatabase(Path dbFile) throws IOException, SQLException {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        createTables();
    }

    /**
     * 表を作ります。すでにあれば何もしません。
     *
     * @throws SQLException 表を作れなかったとき
     */
    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS own_runs (
                        run_key     TEXT PRIMARY KEY,
                        file_name   TEXT NOT NULL,
                        modded      INTEGER NOT NULL,
                        character   TEXT NOT NULL,
                        ascension   INTEGER NOT NULL,
                        build_id    TEXT,
                        win         INTEGER NOT NULL,
                        abandoned   INTEGER NOT NULL,
                        players     INTEGER NOT NULL,
                        floors      INTEGER NOT NULL,
                        start_time  INTEGER,
                        imported_at TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS own_card_choices (
                        run_key   TEXT NOT NULL,
                        seq       INTEGER NOT NULL,
                        floor     INTEGER NOT NULL,
                        act       INTEGER NOT NULL,
                        room_type TEXT,
                        card_id   TEXT NOT NULL,
                        picked    INTEGER NOT NULL,
                        PRIMARY KEY (run_key, seq)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_own_choices_card ON own_card_choices(card_id)");
        }
    }

    /**
     * ランを見分ける名前を作ります（Mod あり・なしで同じファイル名があっても混ざらないように）。
     *
     * @param run 1ラン分の履歴
     * @return 例："modded/1793111068.run"、"1793111068.run"
     */
    public static String runKey(RunHistoryReader.PastRun run) {
        return (run.modded() ? "modded/" : "") + run.fileName();
    }

    /**
     * もう取り込んであるランの名前の一覧を返します（同じランを何度も読まないため）。
     *
     * @return ランの名前の一覧
     * @throws SQLException 読み出しに失敗したとき
     */
    public java.util.Set<String> findRunKeys() throws SQLException {
        java.util.Set<String> keys = new java.util.HashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet r = st.executeQuery("SELECT run_key FROM own_runs")) {
            while (r.next()) {
                keys.add(r.getString(1));
            }
        }
        return keys;
    }

    /**
     * 1ラン分の履歴を保存します。同じランがすでにあれば、入れ替えます。
     *
     * @param run 1ラン分の履歴
     * @throws SQLException 保存に失敗したとき
     */
    public void saveRun(RunHistoryReader.PastRun run) throws SQLException {
        String key = runKey(run);
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false); // 1ラン分をまとめて保存する（途中で失敗したら全部取り消す）
        try {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM own_card_choices WHERE run_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT OR REPLACE INTO own_runs (run_key, file_name, modded, character, ascension, build_id, win,
                        abandoned, players, floors, start_time, imported_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, key);
                ps.setString(2, run.fileName());
                ps.setInt(3, run.modded() ? 1 : 0);
                ps.setString(4, run.character());
                ps.setInt(5, run.ascension());
                ps.setString(6, run.buildId());
                ps.setInt(7, run.win() ? 1 : 0);
                ps.setInt(8, run.abandoned() ? 1 : 0);
                ps.setInt(9, run.players());
                ps.setInt(10, run.floors());
                ps.setLong(11, run.startTime());
                ps.setString(12, LocalDateTime.now().withNano(0).toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO own_card_choices (run_key, seq, floor, act, room_type, card_id, picked)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                int seq = 0;
                for (RunHistoryReader.Choice c : run.choices()) {
                    ps.setString(1, key);
                    ps.setInt(2, seq++);
                    ps.setInt(3, c.floor());
                    ps.setInt(4, c.act());
                    ps.setString(5, c.roomType());
                    ps.setString(6, c.cardId());
                    ps.setInt(7, c.picked() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /**
     * キャラを指定して、カードごとの自分の統計を集計します。
     *
     * @param character キャラ（例："DEFECT"。大文字・小文字は問わない）
     * @return カードID → 自分の統計（一度も出ていないカードは入らない）
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, OwnCardStat> findOwnCardStats(String character) throws SQLException {
        // 1. カード報酬で出た回数・取った回数（ショップ以外のマス、ソロのランだけ）
        String offeredSql = """
                SELECT c.card_id, COUNT(*), SUM(c.picked)
                FROM own_card_choices c JOIN own_runs r ON r.run_key = c.run_key
                WHERE r.players = 1 AND UPPER(r.character) = UPPER(?) AND c.room_type <> 'shop'
                GROUP BY c.card_id""";
        // 2. 取ったランのうち、最後まで遊んだランの数と勝った数（1ランで2枚取っても1と数える）
        String winSql = """
                SELECT t.card_id, COUNT(*), SUM(t.win) FROM (
                    SELECT DISTINCT c.card_id, r.run_key, r.win
                    FROM own_card_choices c JOIN own_runs r ON r.run_key = c.run_key
                    WHERE r.players = 1 AND UPPER(r.character) = UPPER(?) AND c.room_type <> 'shop'
                      AND c.picked = 1 AND r.abandoned = 0
                ) t GROUP BY t.card_id""";
        Map<String, int[]> counts = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(offeredSql)) {
            ps.setString(1, character);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    counts.put(r.getString(1), new int[]{r.getInt(2), r.getInt(3), 0, 0});
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(winSql)) {
            ps.setString(1, character);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    int[] c = counts.computeIfAbsent(r.getString(1), k -> new int[4]);
                    c[2] = r.getInt(2);
                    c[3] = r.getInt(3);
                }
            }
        }
        Map<String, OwnCardStat> stats = new LinkedHashMap<>();
        counts.forEach((id, c) -> stats.put(id, new OwnCardStat(id, c[0], c[1], c[2], c[3])));
        return stats;
    }

    /**
     * キャラを指定して、自分のランの成績（最後まで遊んだソロのランの数と、勝った数）を返します。
     * カードごとの勝率と比べるための「ふだんの勝率」に使います。
     *
     * @param character キャラ（例："DEFECT"）
     * @return { 最後まで遊んだランの数, 勝った数 }
     * @throws SQLException 読み出しに失敗したとき
     */
    public int[] findOwnRecord(String character) throws SQLException {
        String sql = """
                SELECT COUNT(*), COALESCE(SUM(win), 0) FROM own_runs
                WHERE players = 1 AND abandoned = 0 AND UPPER(character) = UPPER(?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, character);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? new int[]{r.getInt(1), r.getInt(2)} : new int[]{0, 0};
            }
        }
    }

    /**
     * データベースを閉じます（try (...) を使うと自動で呼ばれる）。
     *
     * @throws SQLException 閉じるのに失敗したとき
     */
    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

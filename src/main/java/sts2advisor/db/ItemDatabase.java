package sts2advisor.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/**
 * 【このファイルの役割】
 * レリック・ポーションのデータと統計、ショップの統計を SQLite のデータベースに保存する係です
 * （機能要件 F-05, F-14。ショップの判断（ステップ9）で使う）。
 * カードと同じファイル（data/sts2advisor.db）に、別の表として保存します。
 *
 * 作る表：
 *   items         … レリック・ポーションの内容（名前・レアリティ・効果テキスト・ショップの価格）
 *   item_stats    … レリック・ポーションの統計（キャラ別・アセンション区分別）
 *   shop_stats    … ショップに並んだ品物ごとの統計（買われた割合・買った人の勝率など）
 *   game_items    … ゲームファイルから読んだ、レリック・ポーションの名前
 *   item_mismatches … 外部データとゲームファイルで名前が違ったものの記録
 *
 * 使い方：
 *   try (ItemDatabase db = new ItemDatabase(Path.of("data/sts2advisor.db"))) {
 *       db.saveItems(items, 取得元, 取得日時, パッチ);
 *   }
 */
public class ItemDatabase implements AutoCloseable {

    /**
     * レリック・ポーション1個分の内容。
     *
     * @param itemType      種類（"relic" か "potion"）
     * @param itemId        ID（例："AKABEKO"）
     * @param nameJa        日本語の名前
     * @param nameEn        英語の名前
     * @param rarity        レアリティ（例："Common"、"Shop"、"Ancient"）
     * @param pool          出てくるキャラ（"shared"＝全キャラ共通、"regent" など）
     * @param descriptionJa 日本語の効果テキスト
     * @param descriptionEn 英語の効果テキスト
     * @param priceBase     ショップの基本価格（レリックのみ。なければ null）
     * @param priceMin      ショップの価格の下限（なければ null）
     * @param priceMax      ショップの価格の上限（なければ null）
     */
    public record Item(String itemType, String itemId, String nameJa, String nameEn, String rarity, String pool,
                       String descriptionJa, String descriptionEn, Integer priceBase, Integer priceMin,
                       Integer priceMax) {
    }

    /**
     * レリック・ポーション1個分の統計（1つのキャラ・アセンション区分）。
     *
     * @param itemType        種類（"relic" か "potion"）
     * @param itemId          ID
     * @param character       キャラ（例："REGENT"）
     * @param ascensionGroup  アセンション区分（"all" か "a10"）
     * @param patch           集計したパッチ
     * @param winRate         持っていたランの勝率（%）
     * @param pickRate        提示されたときに取った割合（%。ポーションは無い）
     * @param holdRate        全ランのうち、持っていたランの割合（%）
     * @param lift            持っていた場合に勝率が何ポイント上がるか（件数が20未満だと null）
     * @param liftN           lift の計算に使った件数
     * @param picks           持っていたランの数
     * @param wins            そのうち勝ったランの数
     * @param losses          そのうち負けたランの数
     * @param tier            Spire Codex のティア
     * @param score           Spire Codex の点数
     * @param baselineWinRate 全ランの平均勝率（%）
     * @param dataThrough     いつまでのランを集計したか
     */
    public record ItemStat(String itemType, String itemId, String character, String ascensionGroup, String patch,
                           Double winRate, Double pickRate, Double holdRate, Double lift, Integer liftN,
                           Integer picks, Integer wins, Integer losses, String tier, Integer score,
                           Double baselineWinRate, String dataThrough) {
    }

    /**
     * ショップに並んだ品物1つ分の統計。キャラ別には分かれていない。
     *
     * @param itemType       種類（"cards"、"relics"、"potions"。Spire Codex の呼び名のまま）
     * @param itemId         ID
     * @param ascensionGroup アセンション区分（"all" か "a10"）
     * @param patch          集計したパッチ
     * @param seen           ショップに並んだ回数
     * @param bought         買われた回数
     * @param buyRate        買われた割合（%）
     * @param winRate        買った人の勝率（%）
     * @param lift           買った場合に勝率が何ポイント上がるか（件数が20未満だと null）
     * @param liftN          lift の計算に使った件数
     * @param dataThrough    いつまでのランを集計したか
     */
    public record ShopStat(String itemType, String itemId, String ascensionGroup, String patch, Integer seen,
                           Integer bought, Double buyRate, Double winRate, Double lift, Integer liftN,
                           String dataThrough) {
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
    public ItemDatabase(Path dbFile) throws IOException, SQLException {
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
                    CREATE TABLE IF NOT EXISTS items (
                        item_type      TEXT NOT NULL,
                        item_id        TEXT NOT NULL,
                        name_ja        TEXT,
                        name_en        TEXT,
                        rarity         TEXT,
                        pool           TEXT,
                        description_ja TEXT,
                        description_en TEXT,
                        price_base     INTEGER,
                        price_min      INTEGER,
                        price_max      INTEGER,
                        source         TEXT NOT NULL,
                        fetched_at     TEXT NOT NULL,
                        patch          TEXT,
                        PRIMARY KEY (item_type, item_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS item_stats (
                        source            TEXT NOT NULL,
                        item_type         TEXT NOT NULL,
                        item_id           TEXT NOT NULL,
                        character         TEXT NOT NULL,
                        ascension_group   TEXT NOT NULL,
                        patch             TEXT NOT NULL,
                        win_rate          REAL,
                        pick_rate         REAL,
                        hold_rate         REAL,
                        lift              REAL,
                        lift_n            INTEGER,
                        picks             INTEGER,
                        wins              INTEGER,
                        losses            INTEGER,
                        tier              TEXT,
                        score             INTEGER,
                        baseline_win_rate REAL,
                        data_through      TEXT,
                        fetched_at        TEXT NOT NULL,
                        PRIMARY KEY (source, item_type, item_id, character, ascension_group, patch)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS shop_stats (
                        source          TEXT NOT NULL,
                        item_type       TEXT NOT NULL,
                        item_id         TEXT NOT NULL,
                        ascension_group TEXT NOT NULL,
                        patch           TEXT NOT NULL,
                        seen            INTEGER,
                        bought          INTEGER,
                        buy_rate        REAL,
                        win_rate        REAL,
                        lift            REAL,
                        lift_n          INTEGER,
                        data_through    TEXT,
                        fetched_at      TEXT NOT NULL,
                        PRIMARY KEY (source, item_type, item_id, ascension_group, patch)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS game_items (
                        item_type    TEXT NOT NULL,
                        item_id      TEXT NOT NULL,
                        name_ja      TEXT,
                        name_en      TEXT,
                        source       TEXT NOT NULL,
                        game_version TEXT,
                        fetched_at   TEXT NOT NULL,
                        PRIMARY KEY (item_type, item_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS item_mismatches (
                        item_type   TEXT NOT NULL,
                        item_id     TEXT NOT NULL,
                        field       TEXT NOT NULL,
                        codex_value TEXT,
                        game_value  TEXT,
                        checked_at  TEXT NOT NULL,
                        PRIMARY KEY (item_type, item_id, field)
                    )""");
        }
    }

    /**
     * レリック・ポーションの内容を保存します（同じ種類・IDがあれば上書き）。
     *
     * @param items     保存する内容の一覧
     * @param source    取得元
     * @param fetchedAt 取得した日時
     * @param patch     対応しているパッチ
     * @return 保存した件数
     * @throws SQLException 保存に失敗したとき
     */
    public int saveItems(List<Item> items, String source, String fetchedAt, String patch) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO items (item_type, item_id, name_ja, name_en, rarity, pool,
                    description_ja, description_en, price_base, price_min, price_max, source, fetched_at, patch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        return inTransaction(sql, items, (ps, it) -> {
            ps.setString(1, it.itemType());
            ps.setString(2, it.itemId());
            ps.setString(3, it.nameJa());
            ps.setString(4, it.nameEn());
            ps.setString(5, it.rarity());
            ps.setString(6, it.pool());
            ps.setString(7, it.descriptionJa());
            ps.setString(8, it.descriptionEn());
            setInt(ps, 9, it.priceBase());
            setInt(ps, 10, it.priceMin());
            setInt(ps, 11, it.priceMax());
            ps.setString(12, source);
            ps.setString(13, fetchedAt);
            ps.setString(14, patch);
        });
    }

    /**
     * レリック・ポーションの統計を保存します（同じ条件があれば上書き）。
     *
     * @param stats     保存する統計の一覧
     * @param source    取得元
     * @param fetchedAt 取得した日時
     * @return 保存した件数
     * @throws SQLException 保存に失敗したとき
     */
    public int saveItemStats(List<ItemStat> stats, String source, String fetchedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO item_stats (source, item_type, item_id, character, ascension_group, patch,
                    win_rate, pick_rate, hold_rate, lift, lift_n, picks, wins, losses, tier, score,
                    baseline_win_rate, data_through, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        return inTransaction(sql, stats, (ps, s) -> {
            ps.setString(1, source);
            ps.setString(2, s.itemType());
            ps.setString(3, s.itemId());
            ps.setString(4, s.character());
            ps.setString(5, s.ascensionGroup());
            ps.setString(6, s.patch());
            setDouble(ps, 7, s.winRate());
            setDouble(ps, 8, s.pickRate());
            setDouble(ps, 9, s.holdRate());
            setDouble(ps, 10, s.lift());
            setInt(ps, 11, s.liftN());
            setInt(ps, 12, s.picks());
            setInt(ps, 13, s.wins());
            setInt(ps, 14, s.losses());
            ps.setString(15, s.tier());
            setInt(ps, 16, s.score());
            setDouble(ps, 17, s.baselineWinRate());
            ps.setString(18, s.dataThrough());
            ps.setString(19, fetchedAt);
        });
    }

    /**
     * ショップの統計を保存します（同じ条件があれば上書き）。
     *
     * @param stats     保存する統計の一覧
     * @param source    取得元
     * @param fetchedAt 取得した日時
     * @return 保存した件数
     * @throws SQLException 保存に失敗したとき
     */
    public int saveShopStats(List<ShopStat> stats, String source, String fetchedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO shop_stats (source, item_type, item_id, ascension_group, patch,
                    seen, bought, buy_rate, win_rate, lift, lift_n, data_through, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        return inTransaction(sql, stats, (ps, s) -> {
            ps.setString(1, source);
            ps.setString(2, s.itemType());
            ps.setString(3, s.itemId());
            ps.setString(4, s.ascensionGroup());
            ps.setString(5, s.patch());
            setInt(ps, 6, s.seen());
            setInt(ps, 7, s.bought());
            setDouble(ps, 8, s.buyRate());
            setDouble(ps, 9, s.winRate());
            setDouble(ps, 10, s.lift());
            setInt(ps, 11, s.liftN());
            ps.setString(12, s.dataThrough());
            ps.setString(13, fetchedAt);
        });
    }

    /**
     * ゲームファイルから読んだレリック・ポーションの名前を保存します（同じ種類・IDがあれば上書き）。
     *
     * @param itemType    種類（"relic" か "potion"）
     * @param itemId      ID
     * @param nameJa      日本語の名前
     * @param nameEn      英語の名前
     * @param source      取得元（ゲームファイルの名前）
     * @param gameVersion ゲームのバージョン
     * @param fetchedAt   読んだ日時
     * @throws SQLException 保存に失敗したとき
     */
    public void saveGameItem(String itemType, String itemId, String nameJa, String nameEn, String source,
                             String gameVersion, String fetchedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO game_items (item_type, item_id, name_ja, name_en, source, game_version, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemType);
            ps.setString(2, itemId);
            ps.setString(3, nameJa);
            ps.setString(4, nameEn);
            ps.setString(5, source);
            ps.setString(6, gameVersion);
            ps.setString(7, fetchedAt);
            ps.executeUpdate();
        }
    }

    /**
     * items 表の名前を、ゲームファイルの名前に書き換えます（ゲームファイルを正とするため）。
     *
     * @param itemType 種類（"relic" か "potion"）
     * @param itemId   ID
     * @param nameJa   ゲームファイルの日本語の名前
     * @param nameEn   ゲームファイルの英語の名前
     * @throws SQLException 書き換えに失敗したとき
     */
    public void updateItemNames(String itemType, String itemId, String nameJa, String nameEn) throws SQLException {
        String sql = "UPDATE items SET name_ja = ?, name_en = ? WHERE item_type = ? AND item_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nameJa);
            ps.setString(2, nameEn);
            ps.setString(3, itemType);
            ps.setString(4, itemId);
            ps.executeUpdate();
        }
    }

    /**
     * 外部データとゲームファイルで食い違った項目を記録します。
     *
     * @param itemType   種類（"relic" か "potion"）
     * @param itemId     ID
     * @param field      食い違った項目（例："name_ja"、"not_in_game"）
     * @param codexValue 外部データ（Spire Codex）の値
     * @param gameValue  ゲームファイルの値
     * @param checkedAt  照合した日時
     * @throws SQLException 保存に失敗したとき
     */
    public void saveMismatch(String itemType, String itemId, String field, String codexValue, String gameValue,
                             String checkedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO item_mismatches (item_type, item_id, field, codex_value, game_value, checked_at)
                VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemType);
            ps.setString(2, itemId);
            ps.setString(3, field);
            ps.setString(4, codexValue);
            ps.setString(5, gameValue);
            ps.setString(6, checkedAt);
            ps.executeUpdate();
        }
    }

    /**
     * レリックかポーションの、ID と日本語の名前の一覧を読みます（ショップのアイコン照合の候補に使う）。
     *
     * @param itemType "relic" か "potion"
     * @param shopOnly true なら、ショップの価格が決まっているもの（＝ショップに並ぶことがあるもの）だけ
     * @return ID → 日本語の名前
     * @throws SQLException 読み出しに失敗したとき
     */
    public java.util.Map<String, String> findItemNames(String itemType, boolean shopOnly) throws SQLException {
        String sql = "SELECT item_id, name_ja FROM items WHERE item_type = ?"
                + (shopOnly ? " AND price_base IS NOT NULL" : "") + " ORDER BY item_id";
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemType);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return names;
    }

    /**
     * レリックかポーションの、日本語の名前と効果テキストを読みます（AI の一言コメントに渡す実データ。ステップ11-3）。
     *
     * @param itemType "relic" か "potion"
     * @return ID → { 日本語の名前, 日本語の効果テキスト }
     * @throws SQLException 読み出しに失敗したとき
     */
    public java.util.Map<String, String[]> findItemTexts(String itemType) throws SQLException {
        String sql = "SELECT item_id, name_ja, description_ja FROM items WHERE item_type = ? ORDER BY item_id";
        java.util.Map<String, String[]> texts = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemType);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    texts.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
                }
            }
        }
        return texts;
    }

    /**
     * ショップの統計を、種類とアセンション区分を指定して読みます（いちばん新しく取り込んだもの）。
     *
     * @param itemType       種類（Spire Codex の呼び名："relics"、"potions"、"cards"）
     * @param ascensionGroup アセンション区分（"all" か "a10"）
     * @return ID → ショップの統計
     * @throws SQLException 読み出しに失敗したとき
     */
    public java.util.Map<String, ShopStat> findShopStats(String itemType, String ascensionGroup) throws SQLException {
        String sql = """
                SELECT item_type, item_id, ascension_group, patch, seen, bought, buy_rate, win_rate, lift, lift_n,
                       data_through
                FROM shop_stats
                WHERE item_type = ? AND ascension_group = ?
                  AND fetched_at = (SELECT MAX(fetched_at) FROM shop_stats WHERE item_type = ? AND ascension_group = ?)""";
        java.util.Map<String, ShopStat> stats = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, itemType);
            ps.setString(2, ascensionGroup);
            ps.setString(3, itemType);
            ps.setString(4, ascensionGroup);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.put(rs.getString("item_id"), new ShopStat(rs.getString("item_type"), rs.getString("item_id"),
                            rs.getString("ascension_group"), rs.getString("patch"),
                            (Integer) nullable(rs, "seen", true), (Integer) nullable(rs, "bought", true),
                            (Double) nullable(rs, "buy_rate", false), (Double) nullable(rs, "win_rate", false),
                            (Double) nullable(rs, "lift", false), (Integer) nullable(rs, "lift_n", true),
                            rs.getString("data_through")));
                }
            }
        }
        return stats;
    }

    /**
     * 値が無い（NULL）かもしれない数の列を読みます。
     *
     * @param rs      読み出し結果
     * @param column  列の名前
     * @param integer true なら整数、false なら小数として返す
     * @return 値。無ければ null
     * @throws SQLException 読み出しに失敗したとき
     */
    private static Number nullable(java.sql.ResultSet rs, String column, boolean integer) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        Number n = (Number) value;
        // 「条件 ? 整数 : 小数」と書くと、Java では両方が小数にそろえられてしまうので、if で分ける
        if (integer) {
            return Integer.valueOf(n.intValue());
        }
        return Double.valueOf(n.doubleValue());
    }

    /**
     * 1行分の値を PreparedStatement に入れる処理（saveItems などで使う）。
     *
     * @param <T> 1行分のデータの型
     */
    @FunctionalInterface
    private interface RowBinder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }

    /**
     * 一覧をまとめて保存します。1件ずつ確定させると遅いので、最後にまとめて確定させます。
     * 途中で失敗したら、それまでの分も取り消します。
     *
     * @param sql    保存に使う SQL
     * @param rows   保存する一覧
     * @param binder 1行分の値を入れる処理
     * @param <T>    1行分のデータの型
     * @return 保存した件数
     * @throws SQLException 保存に失敗したとき
     */
    private <T> int inTransaction(String sql, List<T> rows, RowBinder<T> binder) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (T row : rows) {
                binder.bind(ps, row);
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
            return rows.size();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * 整数を入れます。null なら「値なし」を入れます。
     *
     * @param ps    入れる先
     * @param index 何番目の「?」か（1から）
     * @param value 入れる値
     * @throws SQLException 入れられなかったとき
     */
    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * 小数を入れます。null なら「値なし」を入れます。
     *
     * @param ps    入れる先
     * @param index 何番目の「?」か（1から）
     * @param value 入れる値
     * @throws SQLException 入れられなかったとき
     */
    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
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

package sts2advisor.db;

import sts2advisor.model.Card;
import sts2advisor.model.CardStat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【このファイルの役割】
 * カードのデータと、カードの統計（勝率・ピック率など）を SQLite のデータベースに保存する係です。
 *
 * SQLite は「ファイル1つがそのままデータベースになる」仕組みで、別のソフトを入れなくても使えます。
 * このプロジェクトでは data/sts2advisor.db というファイルに保存します。
 *
 * 使い方：
 *   try (CardDatabase db = new CardDatabase(Path.of("data/sts2advisor.db"))) {
 *       db.saveCards(cards, 取得元, 取得日時, パッチ);
 *   }
 * try (...) の書き方にすると、使い終わったときに自動でデータベースを閉じてくれます。
 */
public class CardDatabase implements AutoCloseable {

    /** データベースとのつながり（接続） */
    private final Connection connection;

    /**
     * データベースファイルを開きます。ファイルやフォルダがなければ新しく作ります。
     * あわせて、カードを入れる表（テーブル）がなければ作ります。
     *
     * @param dbFile データベースファイルの場所（例：data/sts2advisor.db）
     * @throws IOException  フォルダを作れなかったとき
     * @throws SQLException データベースを開けなかった、または表を作れなかったとき
     */
    public CardDatabase(Path dbFile) throws IOException, SQLException {
        // 保存先のフォルダ（data）がなければ作る
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // "jdbc:sqlite:ファイルの場所" と書くと、SQLite のデータベースに接続できる
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        createTables();
        createGameTables();
    }

    /**
     * カードを入れる表（テーブル）を作ります。すでにあれば何もしません。
     *
     * 表の各列の意味：
     *   card_id など      … カードの内容（model/Card.java の説明を参照）
     *   source            … データの取得元（どのサイトから取ったか）
     *   fetched_at        … データを取った日時
     *   patch             … データが対応しているゲームのバージョン
     *
     * @throws SQLException 表を作れなかったとき
     */
    private void createTables() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS cards (
                    card_id                TEXT PRIMARY KEY,
                    name_ja                TEXT NOT NULL,
                    name_en                TEXT,
                    cost                   INTEGER,
                    is_x_cost              INTEGER NOT NULL,
                    type                   TEXT,
                    rarity                 TEXT,
                    character              TEXT,
                    description_ja         TEXT,
                    description_en         TEXT,
                    upgrade_description_ja TEXT,
                    keywords_ja            TEXT,
                    raw_json_ja            TEXT,
                    source                 TEXT NOT NULL,
                    fetched_at             TEXT NOT NULL,
                    patch                  TEXT NOT NULL
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            // 画面から読み取った日本語名で探すことが多いので、日本語名で速く探せるようにしておく
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cards_name_ja ON cards(name_ja)");

            // カードの統計を入れる表。1行＝「カード × キャラ × アセンション区分 × パッチ × 取得元」の統計
            // （列の意味は model/CardStat.java の説明を参照）
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS card_stats (
                        source            TEXT NOT NULL,
                        card_id           TEXT NOT NULL,
                        character         TEXT NOT NULL,
                        ascension_group   TEXT NOT NULL,
                        patch             TEXT NOT NULL,
                        win_rate          REAL,
                        win_rate_ci_low   REAL,
                        win_rate_ci_high  REAL,
                        pick_rate         REAL,
                        lift              REAL,
                        lift_n            INTEGER,
                        picks             INTEGER,
                        wins              INTEGER,
                        losses            INTEGER,
                        offered           INTEGER,
                        picked            INTEGER,
                        pick_rate_act1    REAL,
                        pick_rate_act2    REAL,
                        pick_rate_act3    REAL,
                        score             INTEGER,
                        tier              TEXT,
                        elo               REAL,
                        baseline_win_rate REAL,
                        character_runs    INTEGER,
                        data_through      TEXT,
                        fetched_at        TEXT NOT NULL,
                        PRIMARY KEY (source, card_id, character, ascension_group, patch)
                    )
                    """);

            // MetaBot.GG のカード統計を入れる表。1行＝カード1枚
            // （MetaBot.GG はキャラ別・アセンション別に分けた数字を出していないため、card_stats とは別の表にする）
            //   name_queried … 問い合わせに使った英語名
            //   matched      … 問い合わせた名前と同じカードが見つかったら 1、見つからなかったら 0
            //   title        … MetaBot.GG が返したカード名
            //   subtitle     … MetaBot.GG が返した説明（例："ironclad · Power · Rare · Slay the Spire 2"）
            //   tier / win_rate / pick_rate … ティア、勝率（%）、ピック率（%）。数え方は Spire Codex と違う
            //   url          … 出典として表示する metabot.gg のページ（利用規約の条件）
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metabot_card_stats (
                        source       TEXT NOT NULL,
                        card_id      TEXT NOT NULL,
                        name_queried TEXT NOT NULL,
                        matched      INTEGER NOT NULL,
                        title        TEXT,
                        subtitle     TEXT,
                        tier         TEXT,
                        win_rate     REAL,
                        pick_rate    REAL,
                        url          TEXT,
                        patch        TEXT NOT NULL,
                        fetched_at   TEXT NOT NULL,
                        PRIMARY KEY (source, card_id)
                    )
                    """);
        }
    }

    /**
     * ゲームファイルから読んだカードの名前と効果テキストを入れる表と、
     * 外部データとゲームファイルの食い違いを記録する表を作ります。すでにあれば何もしません。
     *
     * game_cards（ゲームファイルのカード）：
     *   description_ja_template … 日本語の効果テキスト。数字の部分は {Damage:diff()} のような穴あきのまま
     *   game_version            … どのバージョンのゲームファイルから読んだか
     * card_mismatches（食い違い）：
     *   field          … 食い違った項目（name_ja、name_en、description_ja_template）
     *   external_value … 外部データ（Spire Codex）の値
     *   game_value     … ゲームファイルの値（こちらが正しい）
     *
     * @throws SQLException 表を作れなかったとき
     */
    private void createGameTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS game_cards (
                        card_id                 TEXT PRIMARY KEY,
                        name_ja                 TEXT,
                        name_en                 TEXT,
                        description_ja_template TEXT,
                        description_en_template TEXT,
                        source                  TEXT NOT NULL,
                        game_version            TEXT NOT NULL,
                        fetched_at              TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS card_mismatches (
                        card_id        TEXT NOT NULL,
                        field          TEXT NOT NULL,
                        external_value TEXT,
                        game_value     TEXT,
                        game_version   TEXT NOT NULL,
                        detected_at    TEXT NOT NULL,
                        PRIMARY KEY (card_id, field, game_version)
                    )
                    """);
        }
    }

    /**
     * ゲームファイルから読んだカード1枚分を保存します。同じカードIDの行があれば上書きします。
     *
     * @param cardId                カードID
     * @param nameJa                日本語名（なければ null）
     * @param nameEn                英語名（なければ null）
     * @param descriptionJaTemplate 日本語の効果テキスト（穴あきのまま。なければ null）
     * @param descriptionEnTemplate 英語の効果テキスト（穴あきのまま。なければ null）
     * @param source                取得元（例："ゲームファイル (SlayTheSpire2.pck)"）
     * @param gameVersion           ゲームのバージョン
     * @param fetchedAt             読んだ日時
     * @throws SQLException 保存に失敗したとき
     */
    public void saveGameCard(String cardId, String nameJa, String nameEn, String descriptionJaTemplate,
                             String descriptionEnTemplate, String source, String gameVersion, String fetchedAt)
            throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO game_cards (
                    card_id, name_ja, name_en, description_ja_template, description_en_template,
                    source, game_version, fetched_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, cardId);
            ps.setString(2, nameJa);
            ps.setString(3, nameEn);
            ps.setString(4, descriptionJaTemplate);
            ps.setString(5, descriptionEnTemplate);
            ps.setString(6, source);
            ps.setString(7, gameVersion);
            ps.setString(8, fetchedAt);
            ps.executeUpdate();
        }
    }

    /**
     * 外部データ（cards 表）の、照合に使う項目を読み出します。
     *
     * @return 「カードID → {日本語名, 英語名, 元のJSON}」の対応
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, String[]> findCardsForComparison() throws SQLException {
        Map<String, String[]> cards = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT card_id, name_ja, name_en, raw_json_ja FROM cards ORDER BY card_id")) {
            while (result.next()) {
                cards.put(result.getString("card_id"), new String[]{
                        result.getString("name_ja"), result.getString("name_en"), result.getString("raw_json_ja")});
            }
        }
        return cards;
    }

    /**
     * Spire Codex の統計のうち、順位づけに使う項目（1枚分）。
     *
     * @param cardId  カードID
     * @param lift    取った場合に勝率が何ポイント上がるか（件数が20未満だと null）
     * @param liftN   lift の計算に使った件数
     * @param winRate 取ったランの勝率（%）
     * @param pickRate 提示されたときに取られた割合（%）
     * @param tier    Spire Codex のティア（S〜F）
     * @param patch   集計したパッチ
     * @param rarity  カードのレアリティ（cards 表の rarity。例："Common"。カードが cards 表に無ければ null）
     */
    public record CodexStat(String cardId, Double lift, Integer liftN, Double winRate, Double pickRate,
                            String tier, String patch, String rarity) {
    }

    /**
     * Spire Codex の統計を、キャラとアセンション区分を指定して読みます（いちばん新しいパッチのもの）。
     *
     * @param character      キャラ（例："DEFECT"）
     * @param ascensionGroup アセンション区分（"all" か "a10"）
     * @return 「カードID → 統計」の対応
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, CodexStat> findCodexStats(String character, String ascensionGroup) throws SQLException {
        // パッチが複数あるときは、いちばん新しく取り込んだもの（fetched_at が最大）を使う。
        // 同じレアリティの中で比べるため、cards 表からレアリティも一緒に読む
        String sql = """
                SELECT s.card_id, s.lift, s.lift_n, s.win_rate, s.pick_rate, s.tier, s.patch, c.rarity
                FROM card_stats s LEFT JOIN cards c ON c.card_id = s.card_id
                WHERE s.character = ? AND s.ascension_group = ?
                  AND s.fetched_at = (SELECT MAX(fetched_at) FROM card_stats WHERE character = ? AND ascension_group = ?)
                """;
        Map<String, CodexStat> stats = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, character);
            ps.setString(2, ascensionGroup);
            ps.setString(3, character);
            ps.setString(4, ascensionGroup);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    stats.put(r.getString("card_id"), new CodexStat(
                            r.getString("card_id"),
                            nullableDouble(r, "lift"),
                            nullableInt(r, "lift_n"),
                            nullableDouble(r, "win_rate"),
                            nullableDouble(r, "pick_rate"),
                            r.getString("tier"),
                            r.getString("patch"),
                            r.getString("rarity")));
                }
            }
        }
        return stats;
    }

    /**
     * MetaBot.GG の統計のうち、表示に使う項目（1枚分）。
     *
     * @param cardId    カードID
     * @param character カードのキャラ（cards 表の character。例："defect"、"colorless"）
     * @param tier      MetaBot.GG のティア
     * @param winRate   MetaBot.GG の勝率（%）
     * @param url       出典として表示する metabot.gg のページ
     */
    public record MetabotStat(String cardId, String character, String tier, Double winRate, String url) {
    }

    /**
     * MetaBot.GG の統計を、名前が一致したカードについて全部読みます。
     *
     * @return 「カードID → 統計」の対応
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, MetabotStat> findMetabotStats() throws SQLException {
        String sql = """
                SELECT m.card_id, c.character, m.tier, m.win_rate, m.url
                FROM metabot_card_stats m JOIN cards c ON c.card_id = m.card_id
                WHERE m.matched = 1
                """;
        Map<String, MetabotStat> stats = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet r = statement.executeQuery(sql)) {
            while (r.next()) {
                stats.put(r.getString("card_id"), new MetabotStat(
                        r.getString("card_id"), r.getString("character"), r.getString("tier"),
                        nullableDouble(r, "win_rate"), r.getString("url")));
            }
        }
        return stats;
    }

    /**
     * カードIDと、ゲームファイルの公式カード名（日本語）の対応を返します（表示用）。
     *
     * @return 「カードID → 日本語のカード名」の対応
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, String> findGameJapaneseNamesById() throws SQLException {
        Map<String, String> names = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT card_id, name_ja FROM game_cards WHERE name_ja IS NOT NULL")) {
            while (result.next()) {
                names.put(result.getString("card_id"), result.getString("name_ja"));
            }
        }
        return names;
    }

    /**
     * カードの名前と効果テキスト（1枚分）。読み違えたカード名を、効果テキストから特定するのに使う。
     *
     * @param cardId        カードID
     * @param nameJa        日本語の名前（ゲームファイルの名前に合わせたもの）
     * @param character     カードのキャラ（例："regent"、"colorless"）
     * @param descriptionJa 日本語の効果テキスト（[gold] などの飾りの記号が入ったまま）
     */
    public record CardText(String cardId, String nameJa, String character, String descriptionJa) {
    }

    /**
     * 全カードの名前と効果テキストを読みます。
     *
     * @return カードの一覧
     * @throws SQLException 読み出しに失敗したとき
     */
    public List<CardText> findCardTexts() throws SQLException {
        String sql = "SELECT card_id, name_ja, character, description_ja FROM cards ORDER BY card_id";
        List<CardText> texts = new java.util.ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet r = st.executeQuery(sql)) {
            while (r.next()) {
                texts.add(new CardText(r.getString(1), r.getString(2), r.getString(3), r.getString(4)));
            }
        }
        return texts;
    }

    /**
     * ゲームファイルの日本語の効果テキスト（穴あきの文。例："{Damage:diff()}ダメージを与える。"）を読みます。
     * 画面で読んだ説明文が、ちゃんと読めているかを確かめるのに使います（2026-11-13 追加）。
     *
     * @return カードID → ゲームファイルの日本語の効果テキスト
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, String> findGameDescriptions() throws SQLException {
        Map<String, String> texts = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet r = st.executeQuery("SELECT card_id, description_ja_template FROM game_cards")) {
            while (r.next()) {
                if (r.getString(2) != null) {
                    texts.put(r.getString(1), r.getString(2));
                }
            }
        }
        return texts;
    }

    /**
     * 最後にゲームファイルからカードを取り込んだときの、ゲームのバージョンを返します（F-06。パッチの検知に使う）。
     *
     * @return バージョン（例："v0.111.0"）。まだ取り込んでいなければ null
     * @throws SQLException 読み出しに失敗したとき
     */
    public String findLastGameVersion() throws SQLException {
        String sql = "SELECT game_version FROM game_cards ORDER BY fetched_at DESC LIMIT 1";
        try (Statement st = connection.createStatement(); ResultSet r = st.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    /**
     * 取り込んであるカードの統計のパッチの一覧を返します（F-06。統計が今のゲームに追いついているかの確認に使う）。
     *
     * @return パッチの一覧（例："v0.111.0"）
     * @throws SQLException 読み出しに失敗したとき
     */
    public List<String> findStatPatches() throws SQLException {
        List<String> patches = new java.util.ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet r = st.executeQuery("SELECT DISTINCT patch FROM card_stats")) {
            while (r.next()) {
                patches.add(r.getString(1));
            }
        }
        return patches;
    }

    /**
     * 指定したゲームのバージョンで見つかった、外部データとの食い違いの数を返します（F-06）。
     *
     * @param gameVersion ゲームのバージョン
     * @param field       食い違いの種類（例："description_ja_template"）
     * @return 食い違ったカードの数
     * @throws SQLException 読み出しに失敗したとき
     */
    public int countMismatches(String gameVersion, String field) throws SQLException {
        String sql = "SELECT COUNT(*) FROM card_mismatches WHERE game_version = ? AND field = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, gameVersion);
            ps.setString(2, field);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    /**
     * カード1枚分の詳しい内容（AI の一言コメントに渡す実データ。ステップ11-3）。
     *
     * @param cardId               カードID
     * @param nameJa               日本語の名前
     * @param cost                 コスト（分からなければ null）
     * @param xCost                コストが X（使えるエナジーを全部使う）なら true
     * @param type                 種類（例："Attack"、"Skill"、"Power"）
     * @param rarity               レアリティ（例："Common"）
     * @param character            カードのキャラ（例："regent"、"colorless"）
     * @param descriptionJa        日本語の効果テキスト（[gold] などの飾りの記号が入ったまま）
     * @param upgradeDescriptionJa 強化したときの日本語の効果テキスト（無ければ null）
     */
    public record CardInfo(String cardId, String nameJa, Integer cost, boolean xCost, String type, String rarity,
                           String character, String descriptionJa, String upgradeDescriptionJa) {
    }

    /**
     * 全カードの詳しい内容を読みます。
     *
     * @return カードID → カードの内容
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, CardInfo> findCardInfos() throws SQLException {
        String sql = "SELECT card_id, name_ja, cost, is_x_cost, type, rarity, character, description_ja,"
                + " upgrade_description_ja FROM cards";
        Map<String, CardInfo> infos = new LinkedHashMap<>();
        try (Statement st = connection.createStatement(); ResultSet r = st.executeQuery(sql)) {
            while (r.next()) {
                // コストは空のこともあるので、getObject で null かどうかを確かめる
                Integer cost = r.getObject(3) == null ? null : r.getInt(3);
                infos.put(r.getString(1), new CardInfo(r.getString(1), r.getString(2), cost, r.getInt(4) != 0,
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9)));
            }
        }
        return infos;
    }

    /**
     * ゲームファイルの公式カード名（日本語）と、カードIDの対応を返します（画面から読んだ名前の照合用）。
     * 同じ名前のカードが複数ある場合（例：各キャラの「防御」）は、カードIDの順で最初のものになります。
     *
     * @return 「日本語のカード名 → カードID」の対応
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, String> findGameCardIdsByJapaneseName() throws SQLException {
        Map<String, String> ids = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT card_id, name_ja FROM game_cards WHERE name_ja IS NOT NULL ORDER BY card_id")) {
            while (result.next()) {
                ids.putIfAbsent(result.getString("name_ja"), result.getString("card_id"));
            }
        }
        return ids;
    }

    /**
     * cards 表のカード名を、ゲームファイルの名前に書き換えます（ゲームファイルを正とするため）。
     *
     * @param cardId カードID
     * @param nameJa 新しい日本語名
     * @param nameEn 新しい英語名
     * @throws SQLException 書き換えに失敗したとき
     */
    public void updateCardNames(String cardId, String nameJa, String nameEn) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cards SET name_ja = ?, name_en = ? WHERE card_id = ?")) {
            ps.setString(1, nameJa);
            ps.setString(2, nameEn);
            ps.setString(3, cardId);
            ps.executeUpdate();
        }
    }

    /**
     * 外部データとゲームファイルの食い違いを1件記録します。同じ食い違いがすでにあれば上書きします。
     *
     * @param cardId        カードID
     * @param field         食い違った項目の名前
     * @param externalValue 外部データの値
     * @param gameValue     ゲームファイルの値
     * @param gameVersion   ゲームのバージョン
     * @param detectedAt    見つけた日時
     * @throws SQLException 保存に失敗したとき
     */
    public void saveMismatch(String cardId, String field, String externalValue, String gameValue,
                             String gameVersion, String detectedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO card_mismatches (
                    card_id, field, external_value, game_value, game_version, detected_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, cardId);
            ps.setString(2, field);
            ps.setString(3, externalValue);
            ps.setString(4, gameValue);
            ps.setString(5, gameVersion);
            ps.setString(6, detectedAt);
            ps.executeUpdate();
        }
    }

    /**
     * まとめて保存するために、自動確定を止めます（beginBatch のあとは commitBatch を必ず呼ぶ）。
     *
     * @throws SQLException 設定に失敗したとき
     */
    public void beginBatch() throws SQLException {
        connection.setAutoCommit(false);
    }

    /**
     * beginBatch からの変更をまとめて確定し、自動確定に戻します。
     *
     * @throws SQLException 確定に失敗したとき
     */
    public void commitBatch() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    /**
     * beginBatch からの変更をすべて取り消し、自動確定に戻します（途中で失敗したとき用）。
     *
     * @throws SQLException 取り消しに失敗したとき
     */
    public void rollbackBatch() throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    /**
     * 指定したキャラのカードについて、カードIDと英語名の一覧を返します（MetaBot.GG への問い合わせ用）。
     *
     * @param characters キャラの一覧（cards 表の character の値。例："ironclad", "colorless"）
     * @return 「カードID → 英語名」の対応。カードIDの順に並ぶ
     * @throws SQLException 読み出しに失敗したとき
     */
    public Map<String, String> findEnglishNames(List<String> characters) throws SQLException {
        // 「?」をキャラの数だけ並べる（例：?, ?, ?）
        String placeholders = String.join(", ", Collections.nCopies(characters.size(), "?"));
        String sql = "SELECT card_id, name_en FROM cards WHERE name_en IS NOT NULL AND character IN ("
                + placeholders + ") ORDER BY card_id";

        Map<String, String> names = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < characters.size(); i++) {
                ps.setString(i + 1, characters.get(i));
            }
            try (ResultSet result = ps.executeQuery()) {
                while (result.next()) {
                    names.put(result.getString("card_id"), result.getString("name_en"));
                }
            }
        }
        return names;
    }

    /**
     * MetaBot.GG の統計を、指定した日にすでに取り込んだカードのIDを返します（途中から再開するため）。
     *
     * @param date 日付（例："2026-11-11"）。fetched_at がこの日付で始まる行を探す
     * @return カードIDの一覧
     * @throws SQLException 読み出しに失敗したとき
     */
    public Set<String> findMetabotCardIdsFetchedOn(String date) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT card_id FROM metabot_card_stats WHERE fetched_at LIKE ?")) {
            // LIKE の "%" は「後ろに何が続いてもよい」という意味
            ps.setString(1, date + "%");
            try (ResultSet result = ps.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getString("card_id"));
                }
            }
        }
        return ids;
    }

    /**
     * MetaBot.GG のカード統計を1枚分保存します。同じカードの行があれば上書きします。
     * 取り込みに時間がかかるので、途中で止まってもそこまでの分が残るよう、1枚ごとに保存します。
     *
     * @param source      データの取得元
     * @param cardId      カードID
     * @param nameQueried 問い合わせに使った英語名
     * @param matched     同じカードが見つかったか
     * @param title       MetaBot.GG が返したカード名（見つからなければ null）
     * @param subtitle    MetaBot.GG が返した説明（見つからなければ null）
     * @param tier        ティア（なければ null）
     * @param winRate     勝率（%。なければ null）
     * @param pickRate    ピック率（%。なければ null）
     * @param url         出典の metabot.gg のページ（なければ null）
     * @param patch       対応パッチ（MetaBot.GG が公開していなければ "不明"）
     * @param fetchedAt   データを取った日時
     * @throws SQLException 保存に失敗したとき
     */
    public void saveMetabotCardStat(String source, String cardId, String nameQueried, boolean matched,
                                    String title, String subtitle, String tier, Double winRate,
                                    Double pickRate, String url, String patch, String fetchedAt)
            throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO metabot_card_stats (
                    source, card_id, name_queried, matched, title, subtitle, tier,
                    win_rate, pick_rate, url, patch, fetched_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, cardId);
            ps.setString(3, nameQueried);
            ps.setInt(4, matched ? 1 : 0);
            ps.setString(5, title);
            ps.setString(6, subtitle);
            ps.setString(7, tier);
            ps.setObject(8, winRate);
            ps.setObject(9, pickRate);
            ps.setString(10, url);
            ps.setString(11, patch);
            ps.setString(12, fetchedAt);
            ps.executeUpdate();
        }
    }

    /**
     * カードの統計の一覧をまとめて保存します。
     * 同じ条件（取得元・カード・キャラ・アセンション区分・パッチ）の行がすでにあれば上書きします。
     *
     * @param stats     保存する統計の一覧
     * @param source    データの取得元（例："Spire Codex (https://spire-codex.com)"）
     * @param fetchedAt データを取った日時
     * @return 保存した行の数
     * @throws SQLException 保存に失敗したとき（そのときは1行も保存されない）
     */
    public int saveCardStats(List<CardStat> stats, String source, String fetchedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO card_stats (
                    source, card_id, character, ascension_group, patch,
                    win_rate, win_rate_ci_low, win_rate_ci_high, pick_rate, lift, lift_n,
                    picks, wins, losses, offered, picked,
                    pick_rate_act1, pick_rate_act2, pick_rate_act3,
                    score, tier, elo, baseline_win_rate, character_runs, data_through, fetched_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        // saveCards と同じく、全部まとめて保存し、失敗したら全部取り消す
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CardStat s : stats) {
                ps.setString(1, source);
                ps.setString(2, s.cardId());
                ps.setString(3, s.character());
                ps.setString(4, s.ascensionGroup());
                ps.setString(5, s.patch());
                ps.setObject(6, s.winRate());
                ps.setObject(7, s.winRateCiLow());
                ps.setObject(8, s.winRateCiHigh());
                ps.setObject(9, s.pickRate());
                ps.setObject(10, s.lift());
                ps.setObject(11, s.liftN());
                ps.setObject(12, s.picks());
                ps.setObject(13, s.wins());
                ps.setObject(14, s.losses());
                ps.setObject(15, s.offered());
                ps.setObject(16, s.picked());
                ps.setObject(17, s.pickRateAct1());
                ps.setObject(18, s.pickRateAct2());
                ps.setObject(19, s.pickRateAct3());
                ps.setObject(20, s.score());
                ps.setString(21, s.tier());
                ps.setObject(22, s.elo());
                ps.setObject(23, s.baselineWinRate());
                ps.setObject(24, s.characterRuns());
                ps.setString(25, s.dataThrough());
                ps.setString(26, fetchedAt);
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        return stats.size();
    }

    /**
     * カードの一覧をまとめて保存します。
     * 同じ card_id のカードがすでにあれば、新しい内容で上書きします（取り込み直し用）。
     *
     * @param cards     保存するカードの一覧
     * @param source    データの取得元（例："Spire Codex (https://spire-codex.com)"）
     * @param fetchedAt データを取った日時（例："2026-11-11T19:30:00+09:00"）
     * @param patch     データが対応しているゲームのバージョン（例："v0.111.0"）
     * @return 保存したカードの枚数
     * @throws SQLException 保存に失敗したとき（そのときは1枚も保存されない）
     */
    public int saveCards(List<Card> cards, String source, String fetchedAt, String patch) throws SQLException {
        // INSERT OR REPLACE：なければ追加、あれば上書き
        String sql = """
                INSERT OR REPLACE INTO cards (
                    card_id, name_ja, name_en, cost, is_x_cost, type, rarity, character,
                    description_ja, description_en, upgrade_description_ja, keywords_ja, raw_json_ja,
                    source, fetched_at, patch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        // 全部まとめて1回で保存する（途中で失敗したら全部取り消す）。
        // 1枚ずつ確定するより速く、中途半端な状態も残らない
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Card card : cards) {
                // 「?」の部分に、左から順に値を入れていく
                ps.setString(1, card.id());
                ps.setString(2, card.nameJa());
                ps.setString(3, card.nameEn());
                if (card.cost() != null) {
                    ps.setInt(4, card.cost());
                } else {
                    ps.setNull(4, Types.INTEGER);
                }
                // SQLite には true/false の型がないので、1/0 で保存する
                ps.setInt(5, card.xCost() ? 1 : 0);
                ps.setString(6, card.type());
                ps.setString(7, card.rarity());
                ps.setString(8, card.character());
                ps.setString(9, card.descriptionJa());
                ps.setString(10, card.descriptionEn());
                ps.setString(11, card.upgradeDescriptionJa());
                ps.setString(12, card.keywordsJa());
                ps.setString(13, card.rawJsonJa());
                ps.setString(14, source);
                ps.setString(15, fetchedAt);
                ps.setString(16, patch);
                // 1枚分をためておく
                ps.addBatch();
            }
            // ためておいた分をまとめて実行し、確定する
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            // 失敗したら、途中まで保存した分も取り消す
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        return cards.size();
    }

    /**
     * データベースに入っているカードの枚数を数えます（取り込みがうまくいったかの確認用）。
     *
     * @return カードの枚数
     * @throws SQLException 数えるのに失敗したとき
     */
    public int countCards() throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM cards")) {
            return result.getInt(1);
        }
    }

    /**
     * 結果の列から小数を読みます。空（NULL）なら null を返します。
     * SQLite は同じ列に整数と小数が混ざって入ることがあるので、どちらでも読めるようにします。
     *
     * @param r      読み出しの結果
     * @param column 列の名前
     * @return 小数、または null
     * @throws SQLException 読み出しに失敗したとき
     */
    private static Double nullableDouble(ResultSet r, String column) throws SQLException {
        Object value = r.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    /**
     * 結果の列から整数を読みます。空（NULL）なら null を返します。
     *
     * @param r      読み出しの結果
     * @param column 列の名前
     * @return 整数、または null
     * @throws SQLException 読み出しに失敗したとき
     */
    private static Integer nullableInt(ResultSet r, String column) throws SQLException {
        Object value = r.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    /**
     * データベースを閉じます。try (...) を使っていれば自動で呼ばれます。
     *
     * @throws SQLException 閉じるのに失敗したとき
     */
    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

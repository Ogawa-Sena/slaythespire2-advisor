package sts2advisor.game;

import sts2advisor.ExtractIconsMain;
import sts2advisor.SyncGameCardsMain;
import sts2advisor.db.CardDatabase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 【このファイルの役割】
 * ゲームのアップデート（パッチ）を見つけて、ゲームファイルのデータを取り直す係です（機能要件 F-06）。
 * 監視（WatchScreenMain）を起動したときに呼びます。
 *
 * しくみ：
 *   1. 今のゲームのバージョン（release_info.json）と、前回ゲームファイルからカードを取り込んだときのバージョンを比べる
 *   2. 違っていれば、ゲームファイルから次のものを取り直す（自分の PC のファイルを読むだけ。数十秒）
 *        ・カードの公式の日本語名と効果テキスト（SyncGameCardsMain と同じ処理）
 *        ・レリック・ポーションのアイコン（ExtractIconsMain と同じ処理）
 *   3. 次のことを「警告」として返す（表示ウィンドウに出す）
 *        ・パッチがあったこと、取り直したこと
 *        ・外部データ（Spire Codex）と効果テキストが食い違うカードの数
 *        ・外部の統計が、今のゲームのバージョンに追いついていないこと
 *
 * 外部サイトのデータ（Spire Codex の内容と統計）は、ここでは取り直しません。
 * 時間がかかり（数分）、外部サイトへの問い合わせ回数も増えるため、警告を見て自分で取り込みを実行します。
 */
public final class PatchChecker {

    /**
     * 調べた結果。
     *
     * @param gameVersion 今のゲームのバージョン（読めなければ null）
     * @param lastVersion 前回取り込んだときのバージョン（まだ取り込んでいなければ null）
     * @param resynced    ゲームファイルから取り直したなら true
     * @param warnings    表示する警告（無ければ空）
     */
    public record Report(String gameVersion, String lastVersion, boolean resynced, List<String> warnings) {
    }

    private PatchChecker() {
        // 道具をまとめただけのクラスなので、インスタンスは作らない
    }

    /**
     * パッチがあったかを調べ、あればゲームファイルのデータを取り直します。
     *
     * @param dbFile データベースファイルの場所
     * @return 調べた結果（失敗しても例外にせず、警告として返す。監視は止めない）
     */
    public static Report checkAndSync(Path dbFile) {
        List<String> warnings = new ArrayList<>();
        String gameVersion;
        try {
            gameVersion = new GameFiles(GameFiles.DEFAULT_GAME_DIR).readVersion();
        } catch (Exception e) {
            warnings.add("ゲームのバージョンを読めませんでした（パッチの確認をしていません）：" + e.getMessage());
            return new Report(null, null, false, warnings);
        }

        String lastVersion = null;
        boolean resynced = false;
        try {
            try (CardDatabase db = new CardDatabase(dbFile)) {
                lastVersion = db.findLastGameVersion();
            }
            if (!gameVersion.equals(lastVersion)) {
                // 2. ゲームファイルから取り直す
                SyncGameCardsMain.main(new String[0]);
                ExtractIconsMain.main(new String[0]);
                resynced = true;
                warnings.add("ゲームが " + (lastVersion != null ? lastVersion : "（未取り込み）") + " から " + gameVersion
                        + " になったので、カードの名前・効果テキストとアイコンをゲームファイルから取り直しました");
                warnings.add("外部データ（Spire Codex）は取り直していません。ImportCardsMain → SyncGameCardsMain、"
                        + "ImportStatsMain、ImportItemsMain を実行してください");
            }
            try (CardDatabase db = new CardDatabase(dbFile)) {
                if (resynced) {
                    int diffs = db.countMismatches(gameVersion, "description_ja_template");
                    if (diffs > 0) {
                        warnings.add("効果テキストが外部データと違うカードが " + diffs + " 枚あります（ゲームファイルが正）");
                    }
                }
                // 3. 外部の統計が、今のゲームに追いついているか
                String newest = newestVersion(db.findStatPatches());
                if (newest != null && compareVersions(newest, gameVersion) < 0) {
                    warnings.add("外部の統計は " + newest + " までのものです。今のゲーム（" + gameVersion
                            + "）でカードの強さが変わっている可能性があります");
                }
            }
        } catch (Exception e) {
            warnings.add("パッチの確認・取り直しに失敗しました：" + e.getMessage());
        }
        return new Report(gameVersion, lastVersion, resynced, warnings);
    }

    /**
     * いちばん新しいバージョンを選びます。
     *
     * @param versions バージョンの一覧（例："v0.107.1"、"v0.111.0"）
     * @return いちばん新しいもの。一覧が空なら null
     */
    static String newestVersion(List<String> versions) {
        String newest = null;
        for (String v : versions) {
            if (v != null && v.matches("v?\\d+(\\.\\d+)*") && (newest == null || compareVersions(v, newest) > 0)) {
                newest = v;
            }
        }
        return newest;
    }

    /**
     * バージョンを比べます（"v0.111.0" と "v0.107.1" なら、前の方が新しい）。
     * 文字のまま比べると "v0.99" が "v0.111" より新しくなってしまうので、数ごとに比べます。
     *
     * @param a バージョン1
     * @param b バージョン2
     * @return a が新しければ正の数、同じなら 0、古ければ負の数
     */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }
}

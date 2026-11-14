package sts2advisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sts2advisor.db.CardDatabase;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.model.Card;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 【このファイルの役割】
 * パッチが出たばかりのときに、カードの内容（名前・コスト・効果テキストなど）だけを新しいバージョンに取り込み直す「入口」です
 * （機能要件 F-14・F-06。2026-11-13 追加）。統計は取り込み直しません（出たばかりのパッチは件数が少ないため。ユーザーと決定）。
 *
 * 出たばかりのバージョン（v0.113.0 で確認）は、Spire Codex に英語のデータしか無いので、次のように組み合わせます。
 *   ・コスト・種類・レアリティ・英語の名前と効果テキストは、新しいバージョンのものを使う
 *   ・日本語の効果テキストは、前のバージョンと英語の効果テキストが同じカード（＝効果が変わっていない）なら、前の日本語をそのまま使う
 *   ・効果が変わったカード・新しく増えたカードは、新しいバージョンの英語の効果テキストを入れる（AI は英語でも読める）
 *   ・日本語の名前は、このあと SyncGameCardsMain がゲームファイルの公式の名前に合わせる（この入口の最後で自動で動かす）
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ImportPatchCardsMain -Dexec.args="v0.113.0"
 */
public class ImportPatchCardsMain {

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * プログラムの入口。
     *
     * @param args 1つ目に取り込むバージョン（例："v0.113.0"）
     * @throws Exception 通信やデータベースで失敗したとき
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("使い方: バージョン（例：v0.113.0）を指定してください");
            return;
        }
        String version = args[0];
        SpireCodexClient client = new SpireCodexClient();
        String fetchedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();

        // 1. 前のバージョン（正式版）の日本語・英語と、新しいバージョンの英語を取ってくる
        JsonArray oldJa = client.fetchCards("jpn");
        JsonArray oldEn = client.fetchCards("eng");
        JsonArray newEn = client.fetchCards("eng", version);
        System.out.println("前のバージョン：日本語 " + oldJa.size() + " 枚・英語 " + oldEn.size() + " 枚 / "
                + version + "：英語 " + newEn.size() + " 枚");

        // 2. 組み合わせる
        Map<String, JsonObject> oldJaById = byId(oldJa);
        Map<String, JsonObject> oldEnById = byId(oldEn);
        List<Card> cards = new ArrayList<>();
        int kept = 0;
        int changed = 0;
        int added = 0;
        for (JsonElement e : newEn) {
            JsonObject en = e.getAsJsonObject();
            String id = en.get("id").getAsString();
            JsonObject ja = oldJaById.get(id);
            JsonObject before = oldEnById.get(id);
            boolean same = ja != null && before != null
                    && Objects.equals(ImportCardsMain.text(before, "description"), ImportCardsMain.text(en, "description"))
                    && Objects.equals(ImportCardsMain.text(before, "upgrade_description"),
                    ImportCardsMain.text(en, "upgrade_description"));
            if (same) {
                kept++;
            } else if (ja != null) {
                changed++;
            } else {
                added++;
            }
            // 日本語のデータがあるカードは、名前は前の日本語（あとでゲームの名前に合わせる）。無ければ英語の名前を仮に入れる
            String nameJa = ja != null ? ImportCardsMain.text(ja, "name") : ImportCardsMain.text(en, "name");
            cards.add(new Card(
                    id,
                    nameJa,
                    ImportCardsMain.text(en, "name"),
                    ImportCardsMain.integer(en, "cost"),
                    ImportCardsMain.bool(en, "is_x_cost"),
                    ImportCardsMain.text(en, "type_key"),
                    ImportCardsMain.text(en, "rarity_key"),
                    ImportCardsMain.text(en, "color"),
                    // 効果が変わっていなければ前の日本語、変わった・新しいカードは新しい英語
                    same ? ImportCardsMain.text(ja, "description") : ImportCardsMain.text(en, "description"),
                    ImportCardsMain.text(en, "description"),
                    same ? ImportCardsMain.text(ja, "upgrade_description") : ImportCardsMain.text(en, "upgrade_description"),
                    same ? ImportCardsMain.joinedKeywords(ja) : ImportCardsMain.joinedKeywords(en),
                    // ゲームファイルとの照合（SyncGameCardsMain）は、日本語の穴あきの文と比べるので、変わっていなければ前の日本語の JSON
                    same ? ja.toString() : en.toString()));
        }
        System.out.println("効果が同じで日本語をそのまま使ったカード: " + kept + " 枚 / 効果が変わったカード: " + changed
                + " 枚 / 新しく増えたカード: " + added + " 枚（この2つは英語の効果テキスト）");

        // 3. 保存する（対応パッチは新しいバージョン）
        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            int saved = db.saveCards(cards, SpireCodexClient.SOURCE_NAME + " " + version + "（日本語は効果が同じカードのみ前の版）",
                    fetchedAt, version);
            System.out.println("保存したカード: " + saved + " 枚（データベース内 " + db.countCards() + " 枚）");
        }

        // 4. 日本語の名前をゲームファイルの公式の名前に合わせる
        System.out.println("--- ゲームファイルと照合します（SyncGameCardsMain）");
        SyncGameCardsMain.main(new String[0]);
    }

    /**
     * カードの一覧を「カードID → データ」の形にします。
     *
     * @param cards カードの一覧
     * @return カードID → データ
     */
    private static Map<String, JsonObject> byId(JsonArray cards) {
        Map<String, JsonObject> map = new HashMap<>();
        for (JsonElement e : cards) {
            JsonObject o = e.getAsJsonObject();
            map.put(o.get("id").getAsString(), o);
        }
        return map;
    }
}

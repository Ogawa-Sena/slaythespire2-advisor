package sts2advisor.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import sts2advisor.db.CardDatabase;
import sts2advisor.db.ItemDatabase;
import sts2advisor.deck.DeckAdvisor;
import sts2advisor.model.RunState;
import sts2advisor.reward.CardRanker;
import sts2advisor.reward.RankingText;
import sts2advisor.shop.ShopAdviceText;
import sts2advisor.shop.ShopAdvisor;
import sts2advisor.shop.ShopLayout;
import sts2advisor.shop.ShopReader;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【このファイルの役割】
 * AI に「一言コメント」と「デッキの方向性」を書かせる係です（機能要件 F-11、MVP のステップ11-3）。
 *
 * 統計による順位は先に表示しておき、このコメントは後から足します（CLAUDE.md の F-12）。
 * AI の記憶に頼らないよう、次の実データを必ず質問に入れます（CLAUDE.md の「絶対に守ること」）：
 *   ・ラン状態（キャラ・アセンション・Act・HP・ゴールド）… セーブファイルから
 *   ・デッキのカードとレリック・ポーションの効果テキスト … データベース（ゲームファイルと照合済み）から
 *   ・候補の効果テキスト・統計・デッキとの相性（pick-coach）… データベースと Spire Codex から
 * そのうえで「データに無い効果や数値を書かない」「順位は変えない」と指示します。
 *
 * AI の答えは JSON の形で受け取り、順位ごとのコメントに分けて返します。
 * 答えからコメントを拾えなかったときは、コメント無しとして扱います（順位の表示はそのまま）。
 */
public class CommentWriter {

    /** 1つのコメントの長さの目安（文字数）。表示ウィンドウの1行に収まるくらい */
    private static final int COMMENT_CHARS = 45;

    /*
     * AI の答えの読み方（2026-11-12）：
     * 答えの形の例をまねて JSON の形が崩れることがあった（6回中3回。例：「"comment": "…。", "}」）。
     * そのため JSON として丸ごと読むのではなく、決まった形の部分を探して拾う。
     */
    /** JSON の文字の中身（「\"」のような書き方も含める） */
    private static final String STRING = "((?:[^\"\\\\]|\\\\.)*)";

    /** 「"rank": 1, "comment": "…"」の組 */
    private static final Pattern RANK_COMMENT = Pattern.compile(
            "\"rank\"\\s*:\\s*\"?(\\d+)\"?\\s*,\\s*\"comment\"\\s*:\\s*\"" + STRING + "\"");

    /** 「"item": "カード3", "comment": "…"」の組 */
    private static final Pattern ITEM_COMMENT = Pattern.compile(
            "\"item\"\\s*:\\s*\"" + STRING + "\"\\s*,\\s*\"comment\"\\s*:\\s*\"" + STRING + "\"");

    /**
     * カード報酬のコメント。
     *
     * @param direction デッキの方向性（最大2つの型。AI が書いたもの。無ければ空文字）
     * @param byRank    順位（1から）→ コメント
     * @param prompt    AI に送った質問（ログ用）
     * @param answer    AI の答えそのもの（ログ用）
     * @param millis    AI にかかった時間（ミリ秒）
     */
    public record RewardComments(String direction, Map<Integer, String> byRank, String prompt, String answer,
                                 long millis) {
    }

    /**
     * ショップのコメント。
     *
     * @param direction デッキの方向性（無ければ空文字）
     * @param summary   おすすめの買い物についての一言（無ければ空文字）
     * @param removal   カード削除についての一言（ストライクを消してよいデッキかなど。無ければ空文字）
     * @param byItem    品物の呼び名（例："カード3"、"レリック1"）→ コメント
     * @param prompt    AI に送った質問（ログ用）
     * @param answer    AI の答えそのもの（ログ用）
     * @param millis    AI にかかった時間（ミリ秒）
     */
    public record ShopComments(String direction, String summary, String removal, Map<String, String> byItem,
                               String prompt, String answer, long millis) {
    }

    private final ClaudeVision ai;
    /** カードID → カードの内容 */
    private final Map<String, CardDatabase.CardInfo> cards;
    /** レリックID → { 名前, 効果テキスト } */
    private final Map<String, String[]> relics;
    /** ポーションID → { 名前, 効果テキスト } */
    private final Map<String, String[]> potions;

    /**
     * データベースから、カード・レリック・ポーションの効果テキストを読んでおきます。
     *
     * @param ai     コメントを書かせる AI（画面の読み取りとは別に起動したもの）
     * @param dbFile データベースファイルの場所
     * @throws IOException  データベースを開けなかったとき
     * @throws SQLException 読み出しに失敗したとき
     */
    public CommentWriter(ClaudeVision ai, Path dbFile) throws IOException, SQLException {
        this.ai = ai;
        try (CardDatabase c = new CardDatabase(dbFile); ItemDatabase i = new ItemDatabase(dbFile)) {
            this.cards = c.findCardInfos();
            this.relics = i.findItemTexts("relic");
            this.potions = i.findItemTexts("potion");
        }
    }

    /**
     * カード報酬の順位に、一言コメントを書かせます。
     *
     * @param state   今のラン状態
     * @param options 統計と相性でつけた順位（CardRanker.mixDeckFit の結果。この順のまま）
     * @param deck    デッキとの相性（無ければ null）
     * @return コメント（AI の答えが読めなかったときは、byRank が空）
     * @throws IOException AI とのやり取りに失敗したとき
     */
    public RewardComments forReward(RunState state, List<CardRanker.Option> options, DeckAdvisor.DeckView deck)
            throws IOException {
        StringBuilder q = new StringBuilder();
        q.append(intro());
        q.append(runSection(state, deck));
        q.append("\n## 今回のカード報酬（統計とデッキとの相性を混ぜてつけた順位。この順位は変えないこと）\n");
        q.append("「向いている型」は、そのカードを取ると近づきやすいデッキの型（Spire Codex の型の候補と比べたもの）。\n");
        for (int i = 0; i < options.size(); i++) {
            CardRanker.Option o = options.get(i);
            q.append(i + 1).append("位 ").append(RankingText.describe(o, deck)).append("\n");
            if (!o.isSkip() && o.cardId() != null) {
                // 強化済みの候補（名前の後ろに「+」）なら、強化後の効果テキストを渡す
                q.append("    ").append(cardLine(o.cardId(), o.name().endsWith("+") ? 1 : 0)).append("\n");
            }
        }
        q.append("""

                ## 答え方
                次の形の JSON だけを答えてください（前後に説明文を付けない）。例（順位が3つのとき）：
                {"direction": "毒 + 捨て札", "comments": [{"rank": 1, "comment": "1位の理由"}, {"rank": 2, "comment": "2位の理由"}, {"rank": 3, "comment": "3位の理由"}]}
                direction は今のデッキの方向性。型は最大2つまで。30字以内。
                comments は上の順位すべて（スキップも含む）に1つずつ。各 comment は%d字以内の日本語で、
                「このデッキに、なぜ取る／取らないか」の理由を1つ、効果テキスト・統計・デッキの中身から具体的に書く。
                統計と違う意見があるときは、その理由を書いてよい（順位そのものは変えない）。
                """.formatted(COMMENT_CHARS));

        long start = System.currentTimeMillis();
        String answer = ai.ask(q.toString());
        long millis = System.currentTimeMillis() - start;

        // 「"rank": 1, "comment": "…"」の組を探して拾う
        Map<Integer, String> byRank = new LinkedHashMap<>();
        Matcher m = RANK_COMMENT.matcher(answer);
        while (m.find()) {
            byRank.put(Integer.parseInt(m.group(1)), unescape(m.group(2)));
        }
        return new RewardComments(field(answer, "direction"), byRank, q.toString(), answer, millis);
    }

    /**
     * ショップのおすすめに、一言コメントを書かせます。
     *
     * @param state  今のラン状態
     * @param items  ショップの品物
     * @param advice 統計で求めたおすすめの買い物
     * @param deck   デッキとの相性（無ければ null）
     * @param own    自分のラン履歴での統計（カードID → 統計。無ければ空）
     * @return コメント（AI の答えが読めなかったときは、各項目が空）
     * @throws IOException AI とのやり取りに失敗したとき
     */
    public ShopComments forShop(RunState state, List<ShopReader.Item> items, ShopAdvisor.Advice advice,
                                DeckAdvisor.DeckView deck,
                                Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> own) throws IOException {
        StringBuilder q = new StringBuilder();
        q.append(intro());
        q.append(runSection(state, deck));
        q.append("\n## ショップの品物の効果\n");
        for (ShopReader.Item item : items) {
            String label = itemLabel(item);
            switch (item.kind()) {
                case CARD -> q.append(label).append("：").append(item.id() != null ? cardLine(item.id(), 0)
                        : item.name() + "（カードを特定できなかった）").append("\n");
                case RELIC -> q.append(label).append("：").append(itemLine(relics, item.id(), item.name())).append("\n");
                case POTION -> q.append(label).append("：").append(itemLine(potions, item.id(), item.name())).append("\n");
                case REMOVAL -> q.append(label).append("：デッキのカードを1枚取り除く\n");
            }
        }
        q.append("\n## 統計で求めたおすすめ（★が買う組み合わせ。価値は勝率の上がり幅。この組み合わせは変えないこと）\n");
        for (String line : ShopAdviceText.lines(advice, deck, own)) {
            q.append(line).append("\n");
        }
        q.append("""

                ## 答え方
                次の形の JSON だけを答えてください（前後に説明文を付けない）。例：
                {"direction": "毒 + 捨て札", "summary": "買い物の説明", "removal": "削除の説明", "comments": [{"item": "レリック1", "comment": "理由"}, {"item": "カード3", "comment": "理由"}]}
                direction は今のデッキの方向性。型は最大2つまで。30字以内。
                summary は★の買い物がこのデッキに合う理由、または気をつけること。%d字以内。
                removal はカード削除をするなら何を消すべきか（ストライクが大事なデッキかどうかも考える）。%d字以内。
                comments は★の品物と、統計の価値が上位の品物（最大5つ）について。各 comment は%d字以内の日本語で、
                効果テキスト・統計・デッキの中身から理由を1つ具体的に書く。
                """.formatted(COMMENT_CHARS * 2, COMMENT_CHARS, COMMENT_CHARS));

        long start = System.currentTimeMillis();
        String answer = ai.ask(q.toString());
        long millis = System.currentTimeMillis() - start;

        // 「"item": "カード3", "comment": "…"」の組を探して拾う
        Map<String, String> byItem = new LinkedHashMap<>();
        Matcher m = ITEM_COMMENT.matcher(answer);
        while (m.find()) {
            byItem.put(unescape(m.group(1)), unescape(m.group(2)));
        }
        return new ShopComments(field(answer, "direction"), field(answer, "summary"), field(answer, "removal"),
                byItem, q.toString(), answer, millis);
    }

    /**
     * ショップの品物の呼び名を作ります（ShopAdviceText の表示と同じ形。例："カード3"、"カード削除"）。
     *
     * @param item 品物
     * @return 呼び名
     */
    public static String itemLabel(ShopReader.Item item) {
        return item.kind() == ShopLayout.Kind.REMOVAL ? item.kind().label() : item.kind().label() + (item.index() + 1);
    }

    /**
     * 質問の最初の部分（AI の役割と、守ってほしいこと）。
     *
     * @return 文章
     */
    private static String intro() {
        return """
                あなたは Slay the Spire 2（ソロ）のデッキ構築のアドバイザーです。
                下の実データ（ゲームファイルの効果テキストと、プレイヤー全体の統計）だけを根拠にしてください。
                データに書かれていない効果や数値を、記憶や推測で書かないでください。
                「自分：」とある数字は、このプレイヤー自身の過去のランの記録です。件数が少ないので、参考程度にしてください。
                これは前の質問とは別のランの、別の場面です。前の答えは気にしないでください。

                """;
    }

    /**
     * ラン状態・デッキ・レリック・ポーションの部分を作ります。
     *
     * @param state 今のラン状態
     * @param deck  デッキとの相性（無ければ null）
     * @return 文章
     */
    private String runSection(RunState state, DeckAdvisor.DeckView deck) {
        StringBuilder s = new StringBuilder();
        s.append("## ラン状態\n");
        s.append("キャラ ").append(state.character()).append(" / アセンション ").append(state.ascension())
                .append(" / Act").append(state.act()).append(" / ").append(state.floor()).append("階")
                .append(" / HP ").append(state.hp()).append("/").append(state.maxHp())
                .append(" / ゴールド ").append(state.gold()).append("\n");
        if (deck != null && deck.error() == null && deck.archetype() != null) {
            s.append("統計上いちばん近いデッキの型（Spire Codex）：").append(deck.archetype());
            if (deck.similarity() != null) {
                s.append(String.format("（似ている度合い %.0f%%）", deck.similarity()));
            }
            s.append("\n");
        }

        // デッキ：同じカード・同じ強化回数はまとめて「×枚数」にする
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RunState.DeckCard card : state.deck()) {
            counts.merge(card.cardId() + "#" + card.upgradeLevel(), 1, Integer::sum);
        }
        s.append("\n## デッキ（").append(state.deck().size()).append("枚）\n");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String[] key = e.getKey().split("#");
            s.append("- ×").append(e.getValue()).append(" ").append(cardLine(key[0], Integer.parseInt(key[1])))
                    .append("\n");
        }

        s.append("\n## レリック\n");
        for (String id : state.relics()) {
            s.append("- ").append(itemLine(relics, id, id)).append("\n");
        }
        s.append("\n## ポーション（").append(state.potions().size()).append("/").append(state.potionSlots())
                .append("）\n");
        for (String id : state.potions()) {
            s.append("- ").append(itemLine(potions, id, id)).append("\n");
        }
        return s.toString();
    }

    /**
     * カード1枚分の説明を作ります（例："ストライク+（アタック・コスト1・ベーシック）：9ダメージを与える。"）。
     *
     * @param cardId       カードID
     * @param upgradeLevel 強化の回数（1以上なら、強化後の効果テキストを使う）
     * @return 説明。データベースに無いカードは ID だけ
     */
    private String cardLine(String cardId, int upgradeLevel) {
        CardDatabase.CardInfo c = cards.get(cardId);
        if (c == null) {
            return cardId + "（データ無し）";
        }
        // コストが負の数のカード（クエストなど）は、手札から使うカードではないので「コストなし」にする
        String cost = c.xCost() ? "X" : c.cost() == null ? "?" : c.cost() < 0 ? "なし" : String.valueOf(c.cost());
        String text = upgradeLevel > 0 && c.upgradeDescriptionJa() != null ? c.upgradeDescriptionJa()
                : c.descriptionJa();
        return c.nameJa() + (upgradeLevel > 0 ? "+" : "") + "（" + typeLabel(c.type()) + "・コスト" + cost + "・"
                + rarityLabel(c.rarity()) + "）：" + clean(text);
    }

    /**
     * レリック・ポーション1つ分の説明を作ります（例："ハッピーフラワー：3ターンごとに、エナジー1を得る。"）。
     *
     * @param texts    ID → { 名前, 効果テキスト }
     * @param id       ID（null でもよい）
     * @param fallback データが無いときに出す名前
     * @return 説明
     */
    private static String itemLine(Map<String, String[]> texts, String id, String fallback) {
        String[] t = id == null ? null : texts.get(id);
        if (t == null) {
            return fallback + "（データ無し）";
        }
        return t[0] + "：" + clean(t[1]);
    }

    /**
     * 効果テキストから、色を付けるための記号を取り除きます。
     * 例："[gold]ブロック[/gold]" → "ブロック"、"[energy:1]" → "エナジー1"、改行 → 空白
     *
     * @param text 効果テキスト（null でもよい）
     * @return 記号を取り除いた文
     */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\[energy:(\\d+)]", "エナジー$1")
                .replaceAll("\\[star:(\\d+)]", "スター$1")
                .replaceAll("\\[[^\\]]*]", "")
                .replaceAll("[\\r\\n]+", " ")
                .strip();
    }

    /** カードの種類を日本語にする */
    private static String typeLabel(String type) {
        if (type == null) {
            return "種類不明";
        }
        return switch (type) {
            case "Attack" -> "アタック";
            case "Skill" -> "スキル";
            case "Power" -> "パワー";
            case "Curse" -> "呪い";
            case "Status" -> "状態異常";
            default -> type;
        };
    }

    /** カードのレアリティを日本語にする */
    private static String rarityLabel(String rarity) {
        if (rarity == null) {
            return "レアリティ不明";
        }
        return switch (rarity) {
            case "Basic" -> "ベーシック";
            case "Common" -> "コモン";
            case "Uncommon" -> "アンコモン";
            case "Rare" -> "レア";
            default -> rarity;
        };
    }

    /**
     * AI の答えから、「"名前": "文字"」の文字を取り出します。
     *
     * @param answer AI の答え
     * @param key    名前（例："direction"）
     * @return 文字。見つからなければ空文字
     */
    static String field(String answer, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"" + STRING + "\"").matcher(answer);
        return m.find() ? unescape(m.group(1)) : "";
    }

    /**
     * JSON の文字の中の「\"」「\n」などを元の文字に戻し、前後の空白を取ります。
     *
     * @param text JSON の文字の中身
     * @return 元の文字
     */
    static String unescape(String text) {
        return text.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\").strip();
    }

    /**
     * 確認用に、コメントを表示用の行にします（ターミナルやログ用）。
     *
     * @param c カード報酬のコメント
     * @return 表示用の行
     */
    public static List<String> lines(RewardComments c) {
        List<String> lines = new ArrayList<>();
        if (!c.direction().isEmpty()) {
            lines.add("方向性：" + c.direction());
        }
        c.byRank().forEach((rank, comment) -> lines.add(rank + "位：" + comment));
        return lines;
    }

    /**
     * 確認用に、ショップのコメントを表示用の行にします。
     *
     * @param c ショップのコメント
     * @return 表示用の行
     */
    public static List<String> lines(ShopComments c) {
        List<String> lines = new ArrayList<>();
        if (!c.direction().isEmpty()) {
            lines.add("方向性：" + c.direction());
        }
        if (!c.summary().isEmpty()) {
            lines.add("買い物：" + c.summary());
        }
        if (!c.removal().isEmpty()) {
            lines.add("カード削除：" + c.removal());
        }
        c.byItem().forEach((item, comment) -> lines.add(item + "：" + comment));
        return lines;
    }

    /**
     * ログに保存するための JSON を作ります。
     *
     * @param c カード報酬のコメント
     * @return JSON
     */
    public static JsonObject toJson(RewardComments c) {
        JsonObject json = new JsonObject();
        json.addProperty("ai_millis", c.millis());
        json.addProperty("direction", c.direction());
        JsonArray list = new JsonArray();
        c.byRank().forEach((rank, comment) -> {
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank);
            o.addProperty("comment", comment);
            list.add(o);
        });
        json.add("comments", list);
        json.addProperty("ai_answer", c.answer());
        json.addProperty("prompt", c.prompt());
        return json;
    }

    /**
     * ログに保存するための JSON を作ります。
     *
     * @param c ショップのコメント
     * @return JSON
     */
    public static JsonObject toJson(ShopComments c) {
        JsonObject json = new JsonObject();
        json.addProperty("ai_millis", c.millis());
        json.addProperty("direction", c.direction());
        json.addProperty("summary", c.summary());
        json.addProperty("removal", c.removal());
        JsonArray list = new JsonArray();
        c.byItem().forEach((item, comment) -> {
            JsonObject o = new JsonObject();
            o.addProperty("item", item);
            o.addProperty("comment", comment);
            list.add(o);
        });
        json.add("comments", list);
        json.addProperty("ai_answer", c.answer());
        json.addProperty("prompt", c.prompt());
        return json;
    }
}

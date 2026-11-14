package sts2advisor.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import sts2advisor.model.RunState;
import sts2advisor.reward.CardNameMatcher;
import sts2advisor.reward.CardRanker;
import sts2advisor.reward.CardRewardReader;
import sts2advisor.reward.RankingText;
import sts2advisor.shop.ShopReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 【このファイルの役割】
 * おすすめを出したときの記録（判断ログ）をフォルダに保存する係です（機能要件 F-16 の簡単な版）。
 * カード報酬（saveCardReward）とショップ（saveShop）の2種類を保存します。
 * あとで「読み取りは正しかったか」「順位は妥当だったか」を見返すために使います。
 *
 * 保存する場所と中身（カード報酬1回につきフォルダ1つ）：
 *   data/logs/2026-11-12/121940_card_reward/
 *     screen.png  … 撮影した画面
 *     strip.png   … AI に送った、カード名の帯
 *     result.json … 読み取り結果（AI の答え・照合したカード）、ラン状態、順位と点数
 *     comment.json … AI の一言コメントと、AI に送った質問（順位より後に届くので別のファイル。ステップ11-3）
 *
 * 画面の画像には自分の PC の画面が写るので、data/logs/ は .gitignore で共有しないようにしています。
 * 画面の画像は1枚 約0.4〜0.8MB です。たまってきたら、古い日付のフォルダを消してかまいません。
 */
public class DecisionLogger {

    /** ログを保存する一番上のフォルダ */
    private final Path root;

    /** JSON を人が読みやすい形（改行・字下げあり）で書く道具。値が無い項目も null として書く（特定できなかったカードが分かるように） */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /**
     * @param root ログを保存する一番上のフォルダ（例：data/logs）
     */
    public DecisionLogger(Path root) {
        this.root = root;
    }

    /**
     * カード報酬の判断ログを保存します。
     *
     * @param screen      撮影した画面
     * @param read        カード名の読み取り結果
     * @param state       セーブから読んだラン状態（読めなかったときは null）
     * @param statsGroup  使った統計の区分（"all" か "a10"。順位をつけなかったときは null）
     * @param options     順位をつけた選択肢（順位をつけなかったときは null）
     * @param deck        デッキとの相性（ステップ11。無ければ null）
     * @param totalMillis 撮影から順位の表示までにかかった時間（ミリ秒）
     * @return 保存したフォルダ
     * @throws IOException 保存できなかったとき
     */
    public Path saveCardReward(BufferedImage screen, CardRewardReader.Result read, RunState state,
                               String statsGroup, List<CardRanker.Option> options,
                               sts2advisor.deck.DeckAdvisor.DeckView deck, long totalMillis)
            throws IOException {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Path dir = newFolder(now, "card_reward");

        // 1. 画像を保存する
        ImageIO.write(screen, "png", dir.resolve("screen.png").toFile());
        ImageIO.write(read.strip(), "png", dir.resolve("strip.png").toFile());

        // 2. 読み取り結果・ラン状態・順位を JSON にまとめる
        JsonObject json = new JsonObject();
        json.addProperty("time", now.toString());
        json.addProperty("screen_type", "card_reward");
        json.addProperty("total_millis", totalMillis);
        json.add("reading", readingJson(read));
        if (state != null) {
            json.add("run", runJson(state));
        }
        if (options != null) {
            json.addProperty("stats_group", statsGroup);
            json.add("ranking", rankingJson(options, deck));
        }
        if (deck != null) {
            json.add("deck_fit", deckJson(deck));
        }

        // 3. JSON をファイルに書く（日本語がそのまま読めるよう UTF-8 で）
        Files.writeString(dir.resolve("result.json"), gson.toJson(json), StandardCharsets.UTF_8);
        return dir;
    }

    /**
     * ショップの判断ログを保存します（MVP のステップ8。フォルダ名は「時刻_shop」）。
     *
     * 保存するもの：
     *   screen.png  … 撮影した画面
     *   sheet.png   … AI に送った画像（品物ごとのカード名と価格）
     *   result.json … AI の答え、読み取った品物（種類・ID・名前・価格・確かさ）、表示したおすすめ、ラン状態
     *
     * @param screen      撮影した画面
     * @param read        ショップの読み取り結果
     * @param state       セーブから読んだラン状態（読めなかったときは null）
     * @param shownLines  表示ウィンドウに出した文字（おすすめの買い物）
     * @param totalMillis 撮影から表示までにかかった時間（ミリ秒）
     * @return 保存したフォルダ
     * @throws IOException 保存できなかったとき
     */
    public Path saveShop(BufferedImage screen, ShopReader.Result read, RunState state, List<String> shownLines,
                         long totalMillis)
            throws IOException {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Path dir = newFolder(now, "shop");
        ImageIO.write(screen, "png", dir.resolve("screen.png").toFile());
        ImageIO.write(read.sheet(), "png", dir.resolve("sheet.png").toFile());

        JsonObject json = new JsonObject();
        json.addProperty("time", now.toString());
        json.addProperty("screen_type", "shop");
        json.addProperty("total_millis", totalMillis);
        json.addProperty("ai_answer", read.aiAnswer().strip());
        json.addProperty("ai_millis", read.aiMillis());
        json.addProperty("layout_scale", read.layout().scale());
        json.addProperty("layout_matched_prices", read.layout().matched());
        JsonArray items = new JsonArray();
        for (ShopReader.Item item : read.items()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", item.kind().name());
            o.addProperty("index", item.index());
            o.addProperty("id", item.id());
            o.addProperty("name", item.name());
            o.addProperty("price", item.price());
            o.addProperty("confidence", item.confidence());
            o.addProperty("note", item.note());
            items.add(o);
        }
        json.add("items", items);
        JsonArray shown = new JsonArray();
        shownLines.forEach(shown::add);
        json.add("shown", shown);
        if (state != null) {
            json.add("run", runJson(state));
        }
        Files.writeString(dir.resolve("result.json"), gson.toJson(json), StandardCharsets.UTF_8);
        return dir;
    }

    /**
     * AI の一言コメントを、判断ログのフォルダに comment.json として保存します（ステップ11-3）。
     * コメントは順位より後に届くので、result.json とは別のファイルにします。
     * AI に送った質問（実データ）も入れておき、あとでコメントの根拠を確かめられるようにします。
     *
     * @param dir     saveCardReward・saveShop が返したフォルダ
     * @param comment コメントの JSON（CommentWriter.toJson の結果）
     * @throws IOException 保存できなかったとき
     */
    public void saveComment(Path dir, JsonObject comment) throws IOException {
        Files.writeString(dir.resolve("comment.json"), gson.toJson(comment), StandardCharsets.UTF_8);
    }

    /**
     * デッキとの相性を JSON にします。
     *
     * @param deck デッキとの相性
     * @return アーキタイプ・似ている度合い・カードごとの相性
     */
    private static JsonObject deckJson(sts2advisor.deck.DeckAdvisor.DeckView deck) {
        JsonObject json = new JsonObject();
        json.addProperty("archetype", deck.archetype());
        json.addProperty("similarity", deck.similarity());
        json.addProperty("error", deck.error());
        JsonArray fits = new JsonArray();
        for (sts2advisor.deck.DeckAdvisor.CardFit f : deck.fits().values()) {
            JsonObject o = new JsonObject();
            o.addProperty("card_id", f.cardId());
            o.addProperty("winner_support", f.winnerSupport());
            o.addProperty("coach_score", f.coachScore());
            o.addProperty("copies_in_deck", f.copiesInDeck());
            // そのカードを取ると寄りやすいデッキの型（2026-11-13 追加）
            JsonArray archetypes = new JsonArray();
            for (sts2advisor.deck.DeckAdvisor.ArchetypeGain g : f.archetypes()) {
                JsonObject a = new JsonObject();
                a.addProperty("name", g.name());
                a.addProperty("gain", g.gain());
                archetypes.add(a);
            }
            o.add("archetypes", archetypes);
            fits.add(o);
        }
        json.add("cards", fits);
        json.addProperty("source", "Spire Codex pick-coach (https://spire-codex.com)");
        return json;
    }

    /**
     * 「日付のフォルダ / 時刻_種類」のフォルダを作ります。
     * 同じ秒に2回保存したときは、名前の後ろに番号を付けて別のフォルダにします。
     *
     * @param time 保存する時刻
     * @param kind 画面の種類（フォルダ名に使う。例："card_reward"）
     * @return 作ったフォルダ
     * @throws IOException フォルダを作れなかったとき
     */
    private Path newFolder(LocalDateTime time, String kind) throws IOException {
        Path dayDir = root.resolve(time.toLocalDate().toString());
        String base = time.format(DateTimeFormatter.ofPattern("HHmmss")) + "_" + kind;
        Path dir = dayDir.resolve(base);
        for (int i = 2; Files.exists(dir); i++) {
            dir = dayDir.resolve(base + "_" + i);
        }
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 読み取り結果を JSON にします。
     *
     * @param read カード名の読み取り結果
     * @return AI の答え・かかった時間・切り出した場所・照合したカードの一覧
     */
    private static JsonObject readingJson(CardRewardReader.Result read) {
        JsonObject reading = new JsonObject();
        reading.addProperty("ai_answer", read.aiAnswer().strip());
        reading.addProperty("ai_millis", read.aiMillis());
        JsonObject area = new JsonObject();
        area.addProperty("x", read.stripArea().x);
        area.addProperty("y", read.stripArea().y);
        area.addProperty("width", read.stripArea().width);
        area.addProperty("height", read.stripArea().height);
        reading.add("strip_area", area);

        JsonArray cards = new JsonArray();
        for (CardNameMatcher.Match m : read.cards()) {
            JsonObject card = new JsonObject();
            card.addProperty("read_name", m.readName());
            card.addProperty("card_id", m.cardId());
            card.addProperty("official_name", m.officialName());
            card.addProperty("similarity", m.similarity());
            cards.add(card);
        }
        reading.add("cards", cards);
        return reading;
    }

    /**
     * ラン状態を JSON にします（デッキは枚数とカードIDの一覧だけ）。
     *
     * @param state セーブから読んだラン状態
     * @return キャラ・アセンション・Act・階層・ゴールド・HP・デッキ
     */
    private static JsonObject runJson(RunState state) {
        JsonObject run = new JsonObject();
        run.addProperty("character", state.character());
        run.addProperty("ascension", state.ascension());
        run.addProperty("act", state.act());
        run.addProperty("floor", state.floor());
        run.addProperty("gold", state.gold());
        run.addProperty("hp", state.hp());
        run.addProperty("max_hp", state.maxHp());
        run.addProperty("deck_size", state.deck().size());
        JsonArray deck = new JsonArray();
        for (RunState.DeckCard card : state.deck()) {
            // 強化済みのカードは「ID+強化の回数」の形にする（例："ZAP+1"）
            deck.add(card.upgradeLevel() > 0 ? card.cardId() + "+" + card.upgradeLevel() : card.cardId());
        }
        run.add("deck", deck);
        run.addProperty("saved_at", String.valueOf(state.savedAt()));
        return run;
    }

    /**
     * 順位を JSON にします。
     *
     * @param options 点数の高い順に並べた選択肢
     * @param deck    デッキとの相性（表示した文字に含める。無ければ null）
     * @return 1位から順に、名前・点数・根拠の数字・表示した文字を並べたもの
     */
    private static JsonArray rankingJson(List<CardRanker.Option> options, sts2advisor.deck.DeckAdvisor.DeckView deck) {
        JsonArray ranking = new JsonArray();
        for (int i = 0; i < options.size(); i++) {
            CardRanker.Option o = options.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("rank", i + 1);
            item.addProperty("name", o.name());
            item.addProperty("card_id", o.cardId());
            item.addProperty("skip", o.isSkip());
            item.addProperty("score", o.score());
            // デッキとの相性を混ぜる前の、統計だけの点数（混ぜていなければ null。2026-11-13 追加）
            item.addProperty("stat_score", o.statScore());
            if (o.codex() != null) {
                item.addProperty("rarity", o.codex().rarity());
                item.addProperty("lift", o.codex().lift());
                item.addProperty("lift_n", o.codex().liftN());
                item.addProperty("codex_tier", o.codex().tier());
                item.addProperty("codex_patch", o.codex().patch());
            }
            if (o.metabot() != null) {
                item.addProperty("metabot_tier", o.metabot().tier());
                item.addProperty("metabot_top_percent", o.metabotTopPercent());
            }
            item.addProperty("note", o.note());
            item.addProperty("text", RankingText.describe(o, deck));
            ranking.add(item);
        }
        return ranking;
    }
}

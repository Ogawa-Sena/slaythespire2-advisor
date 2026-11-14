package sts2advisor.deck;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.model.RunState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 【このファイルの役割】
 * 今のデッキの中身を見て、候補のカードとの「相性」を求める係です（機能要件 F-08 の土台。MVP のステップ11）。
 *
 * 使う手がかり（2026-11-12 にユーザーと決定：「両方使う」）：
 *   1. Spire Codex の「pick-coach」API（主な手がかり）
 *      ・今のデッキとレリックを送ると、いちばん近いアーキタイプ（よくあるデッキの型）が分かる
 *      ・候補のカードごとに「近い勝ちデッキの何%がそのカードを持っているか（winner_support）」と、
 *        それをもとにした点数（coach_score）が分かる
 *      ・ネットにつながらない・サイトが止まっているときは、この手がかりは無しで続ける
 *   2. 簡単なルール：そのカードを、デッキにもう何枚持っているか
 *
 * 2026-11-13 追加：候補のカードごとに「どのデッキの型に向いているか」の順位（ユーザーの案）。
 *   pick-coach の答えには、近いデッキの型の候補（candidates、型ごとの似ている度合いつき）が入っている。
 *   「今のデッキ」と「今のデッキ＋そのカード」で聞き比べ、型ごとの似ている度合いの変わり方を求める。
 *   カードを1枚足すと、どの型も少しずつ似ている度合いが下がる（デッキが薄まるため）ので、
 *   「全部の型の平均より、どれだけ下がり方が少ないか（上がったか）」をその型への寄りやすさとする。
 *   問い合わせはカード1枚につき1回増えるので、同じデッキ・同じカードの答えは使い回し、
 *   1分間の問い合わせが多すぎるとき（Spire Codex は1分15回まで）は、この順位を出さずに続ける。
 */
public class DeckAdvisor {

    /** 型の順位を表示する数（上から何個まで） */
    private static final int ARCHETYPES_SHOWN = 2;

    /** 1分間に pick-coach に問い合わせてよい回数（Spire Codex の上限 15 回より少し少なく） */
    private static final int CALLS_PER_MINUTE = 13;

    /** 使い回す答えの数の上限（古いものから捨てる） */
    private static final int CACHE_SIZE = 200;

    /**
     * そのカードを取ると寄りやすいデッキの型。
     *
     * @param name 型の名前（例："ロケットパンチ + 煙突"）
     * @param gain 寄りやすさ（全部の型の平均より、似ている度合いの下がり方がどれだけ少ないか。ポイント）
     */
    public record ArchetypeGain(String name, double gain) {
    }

    /**
     * 候補のカード1枚分の相性。
     *
     * @param cardId        カードID
     * @param winnerSupport 近い勝ちデッキのうち、そのカードを持っていた割合（%。分からなければ null）
     * @param coachScore    pick-coach の点数（分からなければ null）
     * @param copiesInDeck  そのカードを、デッキにもう何枚持っているか
     * @param archetypes    そのカードを取ると寄りやすいデッキの型（寄りやすい順。分からなければ空）
     */
    public record CardFit(String cardId, Double winnerSupport, Double coachScore, int copiesInDeck,
                          List<ArchetypeGain> archetypes) {
    }

    /**
     * デッキとの相性の結果。
     *
     * @param archetype  いちばん近いアーキタイプの名前（分からなければ null）
     * @param similarity そのアーキタイプにどれくらい近いか（%。分からなければ null）
     * @param fits       カードID → 相性
     * @param error      pick-coach が使えなかったときの理由（使えたときは null）
     */
    public record DeckView(String archetype, Double similarity, Map<String, CardFit> fits, String error) {

        /**
         * 指定したカードの相性を返します。
         *
         * @param cardId カードID（null でもよい）
         * @return 相性。無ければ null
         */
        public CardFit fit(String cardId) {
            return cardId == null ? null : fits.get(cardId);
        }

        /**
         * @return 表示用の見出し（例："デッキの型：剣の研磨 + 刃の戦錬（似ている度合い 50%）"）
         */
        public String headline() {
            if (error != null) {
                return "デッキとの相性：取得できませんでした（" + error + "）";
            }
            if (archetype == null) {
                return "デッキの型：まだはっきりしない";
            }
            return String.format("デッキの型：%s（似ている度合い %.0f%%）", archetype, similarity != null ? similarity : 0);
        }
    }

    private final SpireCodexClient client;

    /** 問い合わせを同時に行うための流れ（カードごとの問い合わせを並べて行い、待ち時間を短くする） */
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "pick-coach");
        t.setDaemon(true);
        return t;
    });

    /** 使い回す答え（問い合わせの中身 → 答え）。古いものから捨てる */
    private final Map<String, JsonObject> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** 最近1分間に問い合わせた時刻（ミリ秒） */
    private final Deque<Long> recentCalls = new ArrayDeque<>();

    /**
     * @param client Spire Codex と通信する係
     */
    public DeckAdvisor(SpireCodexClient client) {
        this.client = client;
    }

    /**
     * 候補のカードと、今のデッキとの相性を求めます。
     *
     * @param state   今のラン状態（キャラ・デッキ・レリック）
     * @param offerIds 候補のカードIDの一覧（特定できなかったカードは null でもよい）
     * @return 相性の結果（pick-coach が使えなくても、デッキの枚数だけは入る）
     */
    public DeckView evaluate(RunState state, List<String> offerIds) {
        // 送るデッキは、強化の有無を区別しない ID の一覧（同じカードは枚数分並べる）
        List<String> deck = new ArrayList<>();
        for (RunState.DeckCard card : state.deck()) {
            deck.add(card.cardId());
        }
        List<String> offer = offerIds.stream().filter(id -> id != null).distinct().toList();

        // 2. 簡単なルール：デッキにもう何枚あるか
        Map<String, CardFit> fits = new LinkedHashMap<>();
        for (String id : offer) {
            int copies = (int) deck.stream().filter(id::equals).count();
            fits.put(id, new CardFit(id, null, null, copies, List.of()));
        }
        if (offer.isEmpty()) {
            return new DeckView(null, null, fits, null);
        }

        // 1. pick-coach に聞く（今のデッキ）
        JsonObject json;
        try {
            json = ask(state.character(), deck, state.relics(), offer);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new DeckView(null, null, fits, e.getMessage());
        }

        String archetype = null;
        Double similarity = null;
        if (json.has("target") && json.get("target").isJsonObject()) {
            JsonObject target = json.getAsJsonObject("target");
            archetype = text(target, "name");
            similarity = number(target, "similarity");
        }

        // 3. カードごとに「今のデッキ＋そのカード」で聞き、型ごとの寄りやすさを求める（同時に問い合わせる）
        Map<String, Double> before = candidates(json);
        Map<String, List<ArchetypeGain>> gains = archetypeGains(state, deck, offer, before);

        if (json.has("offers") && json.get("offers").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("offers")) {
                JsonObject o = e.getAsJsonObject();
                String id = text(o, "id");
                CardFit old = fits.get(id);
                if (old != null) {
                    fits.put(id, new CardFit(id, number(o, "winner_support"), number(o, "coach_score"),
                            old.copiesInDeck(), gains.getOrDefault(id, List.of())));
                }
            }
        }
        // offers に入っていなかったカードにも、型の順位だけは付ける
        for (String id : offer) {
            CardFit f = fits.get(id);
            if (f.archetypes().isEmpty() && gains.containsKey(id)) {
                fits.put(id, new CardFit(id, f.winnerSupport(), f.coachScore(), f.copiesInDeck(), gains.get(id)));
            }
        }
        return new DeckView(archetype, similarity, fits, null);
    }

    /**
     * 候補のカードごとに、取ると寄りやすいデッキの型を求めます。
     * 問い合わせが多すぎるとき・失敗したときは、そのカードは空にします（表示しないだけで、順位は出す）。
     *
     * @param state  今のラン状態
     * @param deck   今のデッキ（カードID）
     * @param offer  候補のカードID
     * @param before 今のデッキでの、型ごとの似ている度合い（型の名前 → %）
     * @return カードID → 寄りやすい型（寄りやすい順。最大 ARCHETYPES_SHOWN 個）
     */
    private Map<String, List<ArchetypeGain>> archetypeGains(RunState state, List<String> deck, List<String> offer,
                                                           Map<String, Double> before) {
        Map<String, List<ArchetypeGain>> result = new LinkedHashMap<>();
        if (before.size() < 2) {
            return result; // 型の候補が1つ以下なら、比べられない
        }
        Map<String, CompletableFuture<JsonObject>> futures = new LinkedHashMap<>();
        for (String id : offer) {
            List<String> withCard = new ArrayList<>(deck);
            withCard.add(id);
            futures.put(id, CompletableFuture.supplyAsync(() -> {
                try {
                    return ask(state.character(), withCard, state.relics(), offer);
                } catch (Exception e) {
                    return null; // 失敗したカードは、型の順位なしにする
                }
            }, pool));
        }
        for (Map.Entry<String, CompletableFuture<JsonObject>> f : futures.entrySet()) {
            JsonObject after;
            try {
                after = f.getValue().get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                after = null;
            }
            if (after == null) {
                continue;
            }
            result.put(f.getKey(), gains(before, candidates(after)));
        }
        return result;
    }

    /**
     * 型ごとの似ている度合いの変わり方から、寄りやすい型の順位を作ります。
     * 寄りやすさ ＝ その型の変わり方 − 全部の型の変わり方の平均（プラスなら、ほかの型よりその型に近づく）
     *
     * @param before カードを足す前（型の名前 → %）
     * @param after  カードを足した後（型の名前 → %）
     * @return 寄りやすさがプラスの型（大きい順。最大 ARCHETYPES_SHOWN 個）
     */
    static List<ArchetypeGain> gains(Map<String, Double> before, Map<String, Double> after) {
        Map<String, Double> delta = new LinkedHashMap<>();
        for (Map.Entry<String, Double> b : before.entrySet()) {
            Double a = after.get(b.getKey());
            if (a != null) {
                delta.put(b.getKey(), a - b.getValue());
            }
        }
        if (delta.size() < 2) {
            return List.of();
        }
        double mean = delta.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return delta.entrySet().stream()
                .map(e -> new ArchetypeGain(e.getKey(), e.getValue() - mean))
                .filter(g -> g.gain() > 0.05)
                .sorted((x, y) -> Double.compare(y.gain(), x.gain()))
                .limit(ARCHETYPES_SHOWN)
                .toList();
    }

    /**
     * pick-coach の答えから、近いデッキの型の候補と似ている度合いを取り出します。
     *
     * @param json pick-coach の答え
     * @return 型の名前 → 似ている度合い（%）
     */
    private static Map<String, Double> candidates(JsonObject json) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (json.has("candidates") && json.get("candidates").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("candidates")) {
                JsonObject c = e.getAsJsonObject();
                String name = text(c, "name");
                Double sim = number(c, "similarity");
                if (name != null && sim != null) {
                    map.put(name, sim);
                }
            }
        }
        return map;
    }

    /**
     * pick-coach に問い合わせます。同じ中身の問い合わせは、前の答えを使い回します。
     * 1分間の問い合わせが CALLS_PER_MINUTE 回を超えそうなときは、問い合わせずに失敗にします。
     *
     * @return 答え
     * @throws Exception 通信に失敗した、または問い合わせが多すぎるとき
     */
    private JsonObject ask(String character, List<String> deck, List<String> relics, List<String> offer)
            throws Exception {
        List<String> sortedDeck = new ArrayList<>(deck);
        sortedDeck.sort(null);
        String key = character + "|" + String.join(",", sortedDeck) + "|" + String.join(",", relics) + "|"
                + String.join(",", offer);
        synchronized (this) {
            JsonObject cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            long now = System.currentTimeMillis();
            while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() > 60_000) {
                recentCalls.pollFirst();
            }
            if (recentCalls.size() >= CALLS_PER_MINUTE) {
                throw new java.io.IOException("問い合わせが多すぎるので、少し待ちます");
            }
            recentCalls.addLast(now);
        }
        JsonObject json = client.fetchPickCoach(character, deck, relics, offer);
        synchronized (this) {
            cache.put(key, json);
        }
        return json;
    }

    /**
     * 相性を、表示用の短い文字にします。
     * 例："相性：近い勝ちデッキの40%が採用（coach 20.0）、デッキに2枚｜向いている型：①ロケットパンチ + 煙突（+0.4）"
     *
     * @param fit 相性（null なら空文字）
     * @return 表示用の文字
     */
    public static String describe(CardFit fit) {
        if (fit == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (fit.winnerSupport() != null) {
            parts.add(String.format("近い勝ちデッキの%.0f%%が採用（coach %.1f）", fit.winnerSupport(),
                    fit.coachScore() != null ? fit.coachScore() : 0));
        }
        if (fit.copiesInDeck() > 0) {
            parts.add("デッキに" + fit.copiesInDeck() + "枚");
        }
        String text = parts.isEmpty() ? "" : "相性：" + String.join("、", parts);
        if (!fit.archetypes().isEmpty()) {
            StringBuilder arch = new StringBuilder("向いている型：");
            String[] marks = {"①", "②", "③"};
            for (int i = 0; i < fit.archetypes().size(); i++) {
                ArchetypeGain g = fit.archetypes().get(i);
                arch.append(i > 0 ? " " : "").append(marks[i]).append(g.name())
                        .append(String.format("（+%.1f）", g.gain()));
            }
            text = text.isEmpty() ? arch.toString() : text + "｜" + arch;
        }
        return text;
    }

    /** JSON から文字を取り出す（無ければ null） */
    private static String text(JsonObject json, String key) {
        JsonElement v = json.get(key);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    /** JSON から数を取り出す（無ければ null） */
    private static Double number(JsonObject json, String key) {
        JsonElement v = json.get(key);
        return v == null || v.isJsonNull() ? null : v.getAsDouble();
    }
}

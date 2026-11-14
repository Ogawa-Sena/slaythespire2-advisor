package sts2advisor;

import sts2advisor.ai.ClaudeVision;
import sts2advisor.db.ItemDatabase;
import sts2advisor.game.SaveFileReader;
import sts2advisor.model.RunState;
import sts2advisor.reward.CardResolver;
import sts2advisor.screen.OcrResult;
import sts2advisor.screen.WindowsOcr;
import sts2advisor.db.CardDatabase;
import sts2advisor.deck.DeckAdvisor;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.reward.CardRanker;
import sts2advisor.shop.IconMatcher;
import sts2advisor.shop.ShopAdviceText;
import sts2advisor.shop.ShopAdvisor;
import sts2advisor.shop.ShopLayout;
import sts2advisor.shop.ShopReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【このファイルの役割】
 * 保存したショップ画面の画像から、品物（カード・レリック・ポーション・カード削除）と価格を読み取って表示する
 * 「入口」です（確認用。MVP のステップ8）。ゲームを動かさずに、ショップの読み取りが正しく動くかを確かめます。
 *
 * 処理の流れは ShopReader を参照。確認用に、AI に送った画像を data/tmp/shop_debug/ に保存します。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.ReadShopMain -Dexec.args="samples/shop/shop_20261112_1.png"
 */
public class ReadShopMain {

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /** 確認用の画像を保存するフォルダ */
    private static final Path DEBUG_DIR = Path.of("data", "tmp", "shop_debug");

    /**
     * プログラムの入口。
     *
     * @param args ショップ画面の画像ファイル（いくつでも）
     * @throws Exception 画像・OCR・AI・データベースで失敗したとき
     */
    public static void main(String[] args) throws Exception {
        Files.createDirectories(DEBUG_DIR);
        try (WindowsOcr ocr = WindowsOcr.start(); ClaudeVision vision = new ClaudeVision()) {
            ShopReader reader = createReader(vision, CardResolver.fromDatabase(DB_FILE));
            // キャラは、いちばん最近のセーブファイルから読む（無ければ全キャラを候補にする）
            String character = new SaveFileReader().readLatest().map(RunState::character).orElse(null);
            for (String arg : args) {
                Path file = Path.of(arg);
                BufferedImage screen = ImageIO.read(file.toFile());
                OcrResult result = ocr.read(file);
                System.out.println("■ " + file.getFileName());

                Optional<ShopReader.Result> read = reader.read(screen, result, character);
                if (read.isEmpty()) {
                    System.out.println("  品物の場所が分かりませんでした（読めた価格が少ない）");
                    continue;
                }
                ShopReader.Result r = read.get();
                System.out.printf("  倍率 %.2f、お手本と重なった価格 %d 個、AI %d ミリ秒、所持ゴールド（AI）%s%n",
                        r.layout().scale(), r.layout().matched(), r.aiMillis(), r.gold());
                for (ShopReader.Item item : r.items()) {
                    System.out.println("  " + describe(item));
                }
                // おすすめの買い物（セーブファイルの所持ゴールド・デッキで計算する）
                Optional<RunState> state = new SaveFileReader().readLatest();
                if (state.isPresent()) {
                    ShopAdvisor.Advice advice = createAdvisor(state.get()).advise(r.items(), state.get());
                    System.out.println("  --- おすすめ（" + state.get().character() + "、デッキ " + state.get().deck().size()
                            + "枚、ポーション " + state.get().potions().size() + "/" + state.get().potionSlots() + "）");
                    // デッキとの相性（ステップ11）
                    List<String> cardIds = r.items().stream()
                            .filter(i -> i.kind() == ShopLayout.Kind.CARD).map(ShopReader.Item::id).toList();
                    DeckAdvisor.DeckView deck = new DeckAdvisor(new SpireCodexClient()).evaluate(state.get(), cardIds);
                    java.util.Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> own;
                    try (sts2advisor.db.RunHistoryDatabase history = new sts2advisor.db.RunHistoryDatabase(DB_FILE)) {
                        own = history.findOwnCardStats(state.get().character());
                    }
                    for (String line : ShopAdviceText.lines(advice, deck, own)) {
                        System.out.println("  " + line);
                    }
                }
                String base = file.getFileName().toString().replaceAll("\\.png$", "");
                ImageIO.write(r.sheet(), "png", DEBUG_DIR.resolve(base + "_sheet.png").toFile());
            }
        }
        System.out.println("AI に送った画像: " + DEBUG_DIR.toAbsolutePath());
    }

    /**
     * ショップを読む係を用意します（データベースから候補を読み、アイコンを読み込む）。
     * WatchScreenMain からも使います。
     *
     * @param vision   AI に画像を見せる係
     * @param resolver カードを特定する係
     * @return ショップを読む係
     * @throws Exception データベースやアイコンを読めなかったとき
     */
    static ShopReader createReader(ClaudeVision vision, CardResolver resolver) throws Exception {
        Map<String, String> relicNames;
        Map<String, String> potionNames;
        try (ItemDatabase db = new ItemDatabase(DB_FILE)) {
            // レリックはショップに並ぶことがあるものだけ、ポーションは全部を候補にする
            relicNames = db.findItemNames("relic", true);
            potionNames = db.findItemNames("potion", false);
        }
        IconMatcher relics = new IconMatcher(
                IconMatcher.loadIcons(Path.of("data", "icons", "relic"), relicNames.keySet()), IconMatcher.RELIC_RATIOS);
        IconMatcher potions = new IconMatcher(
                IconMatcher.loadIcons(Path.of("data", "icons", "potion"), potionNames.keySet()), IconMatcher.POTION_RATIOS);
        return new ShopReader(vision, resolver, relics, potions, relicNames, potionNames);
    }

    /**
     * おすすめの買い物を求める係を用意します（今のキャラ・アセンションの統計をデータベースから読む）。
     * WatchScreenMain からも使います。
     *
     * @param state 今のラン状態
     * @return おすすめの買い物を求める係
     * @throws Exception データベースを読めなかったとき
     */
    static ShopAdvisor createAdvisor(RunState state) throws Exception {
        String group = CardRanker.ascensionGroup(state.ascension());
        try (CardDatabase cards = new CardDatabase(DB_FILE); ItemDatabase items = new ItemDatabase(DB_FILE)) {
            return new ShopAdvisor(cards.findCodexStats(state.character(), group),
                    items.findShopStats("relics", group), items.findShopStats("potions", group));
        }
    }

    /**
     * 品物1つ分を、表示用の文字にします。
     * 例："レリック1　レッドマスク　155ゴールド（点数 0.05）"
     *
     * @param item 品物
     * @return 表示用の文字
     */
    static String describe(ShopReader.Item item) {
        String label = item.kind() == ShopLayout.Kind.REMOVAL ? "" : String.valueOf(item.index() + 1);
        StringBuilder text = new StringBuilder(item.kind().label() + label + "　" + item.name()
                + "　" + item.price() + "ゴールド");
        if (!item.confidence().isEmpty()) {
            text.append("（").append(item.confidence()).append("）");
        }
        if (!item.note().isEmpty()) {
            text.append("　※").append(item.note());
        }
        return text.toString();
    }
}

package sts2advisor;

import sts2advisor.ai.ClaudeVision;
import sts2advisor.ai.CommentWriter;
import sts2advisor.db.CardDatabase;
import sts2advisor.deck.DeckAdvisor;
import sts2advisor.external.SpireCodexClient;
import sts2advisor.game.SaveFileReader;
import sts2advisor.log.DecisionLogger;
import sts2advisor.model.RunState;
import sts2advisor.reward.CardNameMatcher;
import sts2advisor.reward.CardRanker;
import sts2advisor.reward.CardResolver;
import sts2advisor.reward.CardRewardReader;
import sts2advisor.reward.RankingText;
import sts2advisor.screen.GameWindowLocator;
import sts2advisor.screen.OcrResult;
import sts2advisor.screen.ScreenCapture;
import sts2advisor.screen.ScreenClassifier;
import sts2advisor.screen.ScreenStateTracker;
import sts2advisor.screen.ScreenType;
import sts2advisor.screen.WindowsOcr;
import sts2advisor.shop.ShopAdviceText;
import sts2advisor.shop.ShopAdvisor;
import sts2advisor.shop.ShopReader;
import sts2advisor.ui.AdvisorWindow;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 【このファイルの役割】
 * ゲーム画面を一定の間隔で撮影し、画面の種類（カード報酬・ショップ・その他）を判定し続ける「入口」です
 * （機能要件 F-01, F-02）。画面の種類が変わったときだけ、その結果を表示します。
 * カード報酬の画面になったら、候補のカードを AI で読み取り（F-03）、順位をつけて（F-09）、
 * ゲームを映していないモニターの別ウィンドウに表示します（F-12）。ターミナルにも同じ内容を出します。
 * 表示した内容は、撮影した画面と一緒に data/logs/ に保存します（F-16）。
 * ショップの画面になったら、並んでいる品物と価格を読み取り（ShopReader）、同じように表示・保存します。
 * 順位（ショップはおすすめ）を表示したあと、AI に一言コメントを書かせ、届いたら書き足します（F-11。ステップ11-3）。
 * コメントは別の流れ（スレッド）で作るので、待っている間も画面の監視は続きます。
 *
 * **撮影するだけで、ゲームの操作は一切しません**（CLAUDE.md の「読み取り専用」）。
 * 止めるときは、このプログラムを動かしている画面で Ctrl + C を押します。
 *
 * 実行方法（プロジェクトのフォルダで）：
 *   mvn compile exec:java -Dexec.mainClass=sts2advisor.WatchScreenMain
 * ゲームのウィンドウがあるモニターを自動で探して撮影し、表示ウィンドウはもう一方のモニターに出します。
 * 遊んでいる途中でゲームを別のモニターに移しても、5秒以内に追いかけます（GameWindowLocator）。
 * 自動で探さずに番号を決めたいときは、引数で指定できます（1つ目：ゲームのモニター、2つ目：表示ウィンドウのモニター）。
 *   例：-Dexec.args="0 1"
 * 止めるときは、表示ウィンドウを閉じても止まります。
 */
public class WatchScreenMain {

    /** 撮影の間隔（ミリ秒）。1秒ごとに撮る */
    private static final long INTERVAL_MILLIS = 1_000;

    /**
     * 画面で読んだ説明文と DB の効果テキストの似かたが、これより低ければ警告する（F-06）。
     * 記録した画面で測ったところ、正しいカードなら 0.47〜1.0 だった（2026-11-12）
     */
    private static final double TEXT_MISMATCH_LINE = 0.3;

    /** ゲームのウィンドウがどのモニターにあるかを、調べ直す間隔（ミリ秒） */
    private static final long LOCATE_INTERVAL_MILLIS = 5_000;

    /** 撮影した画像を一時的に置く場所（毎回上書きする） */
    private static final Path CAPTURE_FILE = Path.of("data", "tmp", "current_screen.png");

    /** ショップを離れてから、この時間（ミリ秒）以内に戻ったときは、同じショップとみなして読み直さない */
    private static final long SHOP_REVISIT_MILLIS = 20_000;

    /**
     * カード報酬のふつうの枚数。これより少なく読めたときは、マウスの説明の枠でカード名が隠れていた
     * 可能性があるので読み直す（2026-11-12、4K の画面で真ん中のカードが「集中力」の説明の枠で隠れ、2枚しか読めなかった）。
     * 本当に2枚のときも、MAX_READ_TRIES 回読んだあとはそのまま表示する
     */
    private static final int USUAL_CARD_COUNT = 3;

    /** ショップで特定できなかった品物があったときに、読み直す回数の上限と間隔（ミリ秒） */
    private static final int SHOP_RETRIES = 2;
    private static final long SHOP_RETRY_MILLIS = 2_000;

    /** カードを特定できなかったときに、読み直す回数の上限（同じ画面で AI に聞く回数） */
    private static final int MAX_READ_TRIES = 3;

    /** カードを特定できなかった画面を保存するフォルダ（あとで原因を調べるため。上書きしない） */
    private static final Path FAILED_DIR = Path.of("data", "tmp", "failed");

    /** デッキとの相性を求める係（Spire Codex の pick-coach を使う。ステップ11） */
    private static final DeckAdvisor DECK_ADVISOR = new DeckAdvisor(new SpireCodexClient());

    /** 判断ログを保存するフォルダ */
    private static final DecisionLogger LOGGER = new DecisionLogger(Path.of("data", "logs"));

    /** データベースファイルの場所 */
    private static final Path DB_FILE = Path.of("data", "sts2advisor.db");

    /**
     * 一言コメントを書かせる AI のモデル。
     * 2026-11-12 に比べたところ、haiku（約4秒）は効果テキストに無い組み合わせを書くことがあり、
     * sonnet（約5〜6.5秒）のほうが正確だった。順位は先に出ているので、正確さを優先して sonnet にする
     */
    private static final String COMMENT_MODEL = "sonnet";

    /** AI のコメントを作る流れ（1つだけ。前のコメントが終わるまで、次は順番待ちにする） */
    private static final ExecutorService COMMENT_THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "comment");
        t.setDaemon(true); // 監視を止めたら、一緒に止まる
        return t;
    });

    /**
     * プログラムの入口。
     *
     * @param args 省略してよい（ゲームのウィンドウがあるモニターを自動で探す）。
     *             指定するときは、1つ目にゲームを映しているモニターの番号、
     *             2つ目に表示ウィンドウを出すモニターの番号（省略すると、ゲームではない方）
     * @throws Exception 撮影・OCR・AI・データベースで失敗したとき
     */
    public static void main(String[] args) throws Exception {
        System.out.println("つながっているモニター：");
        System.out.print(ScreenCapture.listMonitors());

        // 番号の指定が無ければ、ゲームのウィンドウがあるモニターを自動で探す（2026-11-12 追加）。
        // ゲームがまだ起動していなければ、ひとまずモニター 0 を撮り、見つかったら切り替える
        boolean autoLocate = args.length == 0;
        int monitor = autoLocate ? GameWindowLocator.findGameMonitor().orElse(0) : Integer.parseInt(args[0].trim());
        System.out.println(autoLocate ? "ゲームのウィンドウを探しました：モニター " + monitor + " を撮影します"
                : "指定されたモニター " + monitor + " を撮影します");
        ScreenCapture capture = new ScreenCapture(monitor);
        int displayMonitor = args.length > 1 ? Integer.parseInt(args[1].trim()) : otherMonitor(monitor);
        AdvisorWindow window = AdvisorWindow.open(displayMonitor);
        System.out.println("表示ウィンドウをモニター " + displayMonitor + " に開きました");
        ScreenClassifier classifier = new ScreenClassifier();
        // マウスを乗せたときなどの一瞬の読み違いで表示が揺れないよう、「その他」は3回続いたときだけ切り替える
        ScreenStateTracker tracker = new ScreenStateTracker(3);

        // パッチの確認（F-06）：ゲームが更新されていれば、ゲームファイルからカードの名前・効果テキストと
        // アイコンを取り直す。気をつけることがあれば、表示ウィンドウに警告として出す
        window.showMessage("ゲームのバージョンを確認しています…");
        sts2advisor.game.PatchChecker.Report patch = sts2advisor.game.PatchChecker.checkAndSync(DB_FILE);
        System.out.println("ゲームのバージョン：" + patch.gameVersion()
                + (patch.resynced() ? "（前回 " + patch.lastVersion() + " から変わったので取り直しました）" : "（前回と同じ）"));
        for (String warning : patch.warnings()) {
            System.out.println("  警告：" + warning);
            window.showWarning(warning);
        }

        // カード名の照合に使う、公式のカード名の一覧をデータベースから読んでおく
        // カードを特定する係（公式のカード名と効果テキストをデータベースから読んでおく）
        CardResolver resolver = CardResolver.fromDatabase(DB_FILE);

        try (WindowsOcr ocr = WindowsOcr.start(); ClaudeVision vision = new ClaudeVision();
             ClaudeVision commentAi = new ClaudeVision(COMMENT_MODEL)) {
            // AI は起動に約5秒かかるので、最初のカード報酬の前に起動しておく
            // （画面を読む AI と、コメントを書く AI は別々に起動する。コメントを待つ間も画面を読めるように）
            window.showMessage("AI を起動しています…");
            vision.warmUp();
            commentAi.warmUp();
            CommentWriter commentWriter = new CommentWriter(commentAi, DB_FILE);
            // 自分のラン履歴（F-13）のうち、まだ取り込んでいないものを取り込む
            System.out.println("自分のラン履歴を新しく取り込みました：" + ImportRunHistoryMain.importNew(DB_FILE) + "ラン");
            window.showMessage("カード報酬・ショップの画面を待っています");
            CardRewardReader rewardReader = new CardRewardReader(vision, resolver);
            ShopReader shopReader = ReadShopMain.createReader(vision, resolver);
            System.out.println("モニター " + monitor + " を " + INTERVAL_MILLIS + " ミリ秒ごとに撮影します（止めるには Ctrl + C）");

            ScreenType previous = null;
            // 今のカード報酬の画面で、カードをもう読み取ったか（同じ画面で何度も AI に聞かないため）
            boolean rewardRead = false;
            // カード報酬の画面が何回続けて見えたか。カードは1枚ずつ出てくるので、
            // 出てくる途中で読むと枚数が足りなくなる（2026-11-12 に確認）。そのため2回目から読む
            int rewardFrames = 0;
            // 今のカード報酬の画面で、AI に読ませた回数
            int readTries = 0;
            // 今のカード報酬の画面で、カード名を切り出せなかった画面をもう保存したか（1つの画面につき1枚だけ残す）
            boolean rewardMissSaved = false;
            // 今のショップの画面で、品物をもう読み取ったか・何回続けて見えたか
            // （ショップは開くときに品物が動くので、カード報酬と同じく2回目の撮影から読む）
            boolean shopRead = false;
            int shopFrames = 0;
            // 最後にショップの画面が見えた時刻（ミリ秒）。マウスの説明の枠で価格が隠れると、
            // 一瞬「その他」になってすぐショップに戻ることがある（2026-11-12 に確認）。
            // そのたびに AI に読ませると時間がかかるので、SHOP_REVISIT_MILLIS 以内に戻ったら読み直さない
            long lastShopSeen = 0;
            // 買い物の後の読み直し（2026-11-12 追加）：画面の上の所持ゴールドを OCR で毎回読み、
            // ショップを読んだときの値（shopGoldOcr）から変わって2回続いたら、買い物をしたとみなして AI で読み直す。
            // OCR は「◎433」を「◎4330」と読むことがあるので、値そのものではなく「変わったか」だけを見る
            Integer previousGoldOcr = null;
            // 今の表示に使った所持ゴールド（AI が読んだ値）
            Integer shopAiGold = null;
            // 「今の所持ゴールドのときに OCR が読んだことのある値」。OCR は同じ金額でも読むたびに違う数字
            // （読み違い）になることがあり、そのたびに読み直していた（2026-11-12）。
            // ここに入っていない値が2回続いたときだけ、AI で読み直す
            java.util.Set<Integer> knownGoldOcr = new java.util.HashSet<>();
            // 特定できなかった品物があったときの読み直し（2026-11-12 追加。店主の絵やマウスでカード名が隠れていた）。
            // 表示は先に出しておき、2秒ごとに最大 SHOP_RETRIES 回読み直して、特定できた品物が増えたら表示を差し替える
            int shopUnknown = 0;
            int shopRetriesLeft = 0;
            long shopNextRetry = 0;
            // 最後にゲームのウィンドウの場所を調べた時刻（ミリ秒）
            long lastLocated = System.currentTimeMillis();
            while (true) {
                long start = System.currentTimeMillis();
                // 遊んでいる途中でゲームを別のモニターに移したら、撮るモニターと表示ウィンドウの場所を入れ替える
                if (autoLocate && start - lastLocated >= LOCATE_INTERVAL_MILLIS) {
                    lastLocated = start;
                    java.util.OptionalInt found = GameWindowLocator.findGameMonitor();
                    if (found.isPresent() && found.getAsInt() != monitor) {
                        monitor = found.getAsInt();
                        capture = new ScreenCapture(monitor);
                        window.moveTo(otherMonitor(monitor));
                        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
                                + "  ゲームがモニター " + monitor + " に移ったので、撮るモニターを切り替えました");
                    }
                }
                // 1回分の処理で失敗しても（セーブファイルが書き込み中だった、など）、止まらずに次の撮影へ進む
                try {
                    // 1. 撮影する → 2. OCR で読む → 3. 画面の種類を判定する
                    BufferedImage screen = capture.captureTo(CAPTURE_FILE);
                    OcrResult result = ocr.read(CAPTURE_FILE);
                    // （2026-11-12〜13 は、カード報酬かもしれない画面を調べるために保存していたが、
                    //   原因が分かって直したので、2026-11-13 にやめた）
                    ScreenType type = tracker.update(classifier.classify(result));

                    // 画面の種類が変わったときだけ表示する（同じ表示が何度も並ばないように）
                    if (type != previous) {
                        long took = System.currentTimeMillis() - start;
                        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
                                + "  画面: " + type.label() + "（撮影から判定まで " + took + " ミリ秒）");
                        previous = type;
                        window.showScreenType(type);
                        rewardRead = false; // 新しい画面になったので、まだ読み取っていない
                        rewardFrames = 0;
                        readTries = 0;
                        rewardMissSaved = false;
                        if (type == ScreenType.SHOP
                                && System.currentTimeMillis() - lastShopSeen > SHOP_REVISIT_MILLIS) {
                            shopRead = false; // しばらくショップを離れていたので、新しいショップとして読む
                            shopFrames = 0;
                        }
                    }
                    if (type == ScreenType.CARD_REWARD) {
                        rewardFrames++;
                    }
                    if (type == ScreenType.SHOP) {
                        shopFrames++;
                        lastShopSeen = System.currentTimeMillis();
                    }

                    // ショップの画面なら、品物と価格を読み取る（MVP のステップ8）
                    //    品物の場所が分からなかったとき（価格が読めない）は、AI に聞かずに次の撮影で試す
                    Integer goldOcr = type == ScreenType.SHOP
                            ? ShopReader.ocrGold(result, screen.getHeight()).orElse(null) : null;
                    if (type == ScreenType.SHOP && shopFrames >= 2 && !shopRead) {
                        // 読み取りの間は「読み取り中」と出す（AI の答えが遅いと、止まって見えるため。2026-11-13）
                        window.showMessage("ショップを読み取り中…");
                        Optional<ShopReader.Result> read = shopReader.read(screen, result, currentCharacter());
                        if (read.isPresent()) {
                            shopRead = true;
                            shopAiGold = read.get().gold();
                            knownGoldOcr.clear();
                            if (goldOcr != null) {
                                knownGoldOcr.add(goldOcr);
                            }
                            showShop(screen, read.get(), start, window, commentWriter);
                            shopUnknown = unknownItems(read.get());
                            shopRetriesLeft = shopUnknown > 0 ? SHOP_RETRIES : 0;
                            shopNextRetry = System.currentTimeMillis() + SHOP_RETRY_MILLIS;
                        }
                    } else if (type == ScreenType.SHOP && shopRead && shopRetriesLeft > 0
                            && System.currentTimeMillis() >= shopNextRetry) {
                        // 特定できなかった品物があるので、読み直す（マウスが動いて見えるようになっていることがある）
                        shopRetriesLeft--;
                        shopNextRetry = System.currentTimeMillis() + SHOP_RETRY_MILLIS;
                        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
                                + "  特定できなかった品物が " + shopUnknown + " 個あるので、ショップを読み直します");
                        Optional<ShopReader.Result> read = shopReader.read(screen, result, currentCharacter());
                        if (read.isPresent()) {
                            int unknown = unknownItems(read.get());
                            Integer gold = read.get().gold();
                            boolean sameGold = gold == null || shopAiGold == null || gold.equals(shopAiGold);
                            if (sameGold && unknown < shopUnknown) {
                                shopUnknown = unknown;
                                showShop(screen, read.get(), start, window, commentWriter);
                            } else {
                                System.out.println("    特定できた品物は増えませんでした。表示はそのままにします");
                            }
                            if (shopUnknown == 0) {
                                shopRetriesLeft = 0;
                            }
                        }
                    } else if (type == ScreenType.SHOP && shopRead && goldOcr != null
                            && !knownGoldOcr.contains(goldOcr) && goldOcr.equals(previousGoldOcr)) {
                        // 所持ゴールドの表示が変わったかもしれない＝買い物をしたかもしれないので、AI で読み直す
                        knownGoldOcr.add(goldOcr);
                        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
                                + "  所持ゴールドの表示が変わったようなので、ショップを読み直します（OCR " + goldOcr + "）");
                        Optional<ShopReader.Result> read = shopReader.read(screen, result, currentCharacter());
                        if (read.isPresent()) {
                            Integer gold = read.get().gold();
                            if (gold == null || gold.equals(shopAiGold)) {
                                // AI が読むと金額は同じ＝OCR の読み違いだった。表示はそのままにする
                                System.out.println("    所持ゴールドは " + gold + " のままでした（OCR の読み違い）。表示はそのままにします");
                            } else {
                                // 本当に減っていた＝買い物をした。売り切れになった品物と残りのゴールドで出し直す
                                shopAiGold = gold;
                                knownGoldOcr.clear();
                                knownGoldOcr.add(goldOcr);
                                showShop(screen, read.get(), start, window, commentWriter);
                            }
                        }
                    }
                    previousGoldOcr = goldOcr;

                    // 4. カード報酬の画面なら、候補のカードを読み取る（2回目の撮影から、読み取れるまで毎回試す）
                    // （エラーで表示まで進めなかったときも、同じ画面で AI に聞くのは MAX_READ_TRIES 回まで）
                    if (type == ScreenType.CARD_REWARD && rewardFrames >= 2 && !rewardRead
                            && readTries < MAX_READ_TRIES) {
                        if (readTries == 0) {
                            // 読み取りの間は「読み取り中」と出す（AI の答えが遅いと、止まって見えるため。2026-11-13）
                            window.showMessage("カード報酬を読み取り中…");
                        }
                        Optional<CardRewardReader.Result> read = rewardReader.read(screen, result, currentCharacter());
                        // 目印の文字がマウスの説明の枠で隠れていると切り出せないので、そのときは次の撮影で試す
                        if (read.isPresent() && !read.get().cards().isEmpty()) {
                            readTries++;
                            // 特定できないカードがあれば、その画面を保存して、次の撮影で読み直す
                            // （名前の一部しか読めなかったときなど。2026-11-12 に「隠れ」と読んだことがある）
                            boolean retry = false;
                            if (hasUnknownCard(read.get().cards()) || read.get().cards().size() < USUAL_CARD_COUNT) {
                                saveFailed(screen, read.get());
                                retry = readTries < MAX_READ_TRIES;
                            }
                            if (retry) {
                                System.out.println("    特定できないカード（または足りないカード）があったので読み直します（"
                                        + readTries + "回目）　AI の答え: " + read.get().aiAnswer().strip());
                            } else {
                                rewardRead = true;
                                checkCardTexts(read.get(), resolver, window);
                                showResult(screen, read.get(), start, window, commentWriter);
                            }
                        } else if (!rewardMissSaved) {
                            // カード報酬と判定されたのに、カード名の場所を切り出せなかった。
                            // 2026-11-12 に、カード報酬と判定されて3秒で「その他」に戻る（読み取りもしない）ことが
                            // 何度かあった。別の画面を取り違えていないか、あとで調べられるように画面を1枚残す
                            rewardMissSaved = true;
                            saveScreen(screen, "_no_cards_screen.png");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("    エラーが起きましたが、監視は続けます: " + e);
                    window.showMessage("エラー: " + e.getMessage());
                }

                // 次の撮影まで待つ（処理にかかった時間を差し引く）
                long wait = INTERVAL_MILLIS - (System.currentTimeMillis() - start);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
            }
        }
    }

    /**
     * 読み取ったカードと順位を、ターミナルと表示ウィンドウに出します。
     *
     * @param screen 撮影した画面
     * @param read   読み取りの結果
     * @param start  撮影を始めた時刻（ミリ秒。かかった時間の表示に使う）
     * @param window 表示ウィンドウ
     * @param writer AI に一言コメントを書かせる係
     * @throws Exception 画像の保存・セーブファイル・データベースで失敗したとき
     */
    private static void showResult(BufferedImage screen, CardRewardReader.Result read, long start,
                                   AdvisorWindow window, CommentWriter writer) throws Exception {
        // 確認用に、読み取りに使った画面と切り出した帯を保存しておく（毎回上書き）
        ImageIO.write(screen, "png", Path.of("data", "tmp", "last_reward_screen.png").toFile());
        ImageIO.write(read.strip(), "png", Path.of("data", "tmp", "last_name_strip.png").toFile());
        long took = System.currentTimeMillis() - start;
        System.out.println("    候補のカード（撮影から " + took + " ミリ秒、うち AI "
                + read.aiMillis() + " ミリ秒）　AI の答え: " + read.aiAnswer().strip());
        for (CardNameMatcher.Match m : read.cards()) {
            System.out.println("    ・" + ReadCardRewardMain.describe(m));
        }
        // 5. セーブファイルから今のキャラ・アセンションを読み、順位をつけて表示する
        Ranking ranking = printRanking(read.cards(), window);
        long total = System.currentTimeMillis() - start;
        System.out.println("    （撮影から順位まで " + total + " ミリ秒）");

        // 6. 撮影した画面・読み取り結果・順位を保存する（F-16）
        Path logDir = LOGGER.saveCardReward(screen, read,
                ranking != null ? ranking.state() : null,
                ranking != null ? ranking.statsGroup() : null,
                ranking != null ? ranking.options() : null,
                ranking != null ? ranking.deck() : null, total);
        System.out.println("    ログを保存しました: " + logDir);

        // 7. AI に一言コメントを書かせる（別の流れで。届いたら表示に足し、ログにも保存する）
        if (ranking != null) {
            COMMENT_THREAD.submit(() -> {
                try {
                    CommentWriter.RewardComments c = writer.forReward(ranking.state(), ranking.options(), ranking.deck());
                    if (c.byRank().isEmpty()) {
                        window.showCommentError(ranking.shown(), "AI の答えを読めませんでした");
                    } else {
                        window.showRewardComments(ranking.shown(), c);
                    }
                    System.out.println("    AI のコメント（撮影から " + (System.currentTimeMillis() - start)
                            + " ミリ秒、うち AI " + c.millis() + " ミリ秒）：");
                    for (String line : CommentWriter.lines(c)) {
                        System.out.println("      " + line);
                    }
                    LOGGER.saveComment(logDir, CommentWriter.toJson(c));
                } catch (Exception e) {
                    System.out.println("    AI のコメントを作れませんでした: " + e);
                    window.showCommentError(ranking.shown(), e.getMessage());
                }
            });
        }
    }

    /**
     * 読み取ったショップの品物を、ターミナルと表示ウィンドウに出し、判断ログに保存します。
     *
     * @param screen 撮影した画面
     * @param read   ショップの読み取り結果
     * @param start  撮影を始めた時刻（ミリ秒。かかった時間の表示に使う）
     * @param window 表示ウィンドウ
     * @param writer AI に一言コメントを書かせる係
     * @throws Exception 画像の保存・セーブファイルで失敗したとき
     */
    private static void showShop(BufferedImage screen, ShopReader.Result read, long start,
                                 AdvisorWindow window, CommentWriter writer) throws Exception {
        // キャラ・デッキなどはセーブファイルから読む。所持ゴールドは、AI が画面から読めていればそちらを使う
        // （セーブはショップに入る前の値なので、買い物をした後は画面の値が正しい。2026-11-12 追加）
        Optional<RunState> saved = new SaveFileReader().readLatest();
        Optional<RunState> run = saved.map(s -> read.gold() == null ? s
                : new RunState(s.character(), s.ascension(), s.act(), s.floor(), read.gold(), s.hp(), s.maxHp(),
                s.deck(), s.relics(), s.potions(), s.potionSlots(), s.playerCount(), s.saveFile(), s.savedAt()));
        String goldSource = read.gold() != null ? "（画面から）" : "（セーブから）";
        String header = run.map(s -> s.character() + " / 所持ゴールド " + s.gold() + goldSource + " / デッキ "
                        + s.deck().size() + "枚 / ポーション " + s.potions().size() + "/" + s.potionSlots())
                .orElse("セーブファイルが見つかりません");
        System.out.println("    ショップの品物（AI " + read.aiMillis() + " ミリ秒）：");
        for (ShopReader.Item item : read.items()) {
            System.out.println("      " + ReadShopMain.describe(item));
        }

        // おすすめの買い物を求める（MVP のステップ9）。セーブが無ければ品物の一覧だけを出す
        List<String> lines = new ArrayList<>();
        ShopAdvisor.Advice advice = null;
        DeckAdvisor.DeckView deck = null;
        java.util.Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> own = java.util.Map.of();
        if (run.isPresent()) {
            advice = ReadShopMain.createAdvisor(run.get()).advise(read.items(), run.get());
            // デッキとの相性（ステップ11）：ショップのカードについて pick-coach に聞く
            List<String> cardIds = read.items().stream()
                    .filter(i -> i.kind() == sts2advisor.shop.ShopLayout.Kind.CARD).map(ShopReader.Item::id).toList();
            deck = DECK_ADVISOR.evaluate(run.get(), cardIds);
            own = ownStats(run.get().character());
            lines.addAll(ShopAdviceText.lines(advice, deck, own));
        } else {
            for (ShopReader.Item item : read.items()) {
                lines.add(ReadShopMain.describe(item));
            }
        }
        long total = System.currentTimeMillis() - start;
        System.out.println("    おすすめ（撮影から " + total + " ミリ秒）　" + header + "：");
        for (String line : lines) {
            System.out.println("      " + line);
        }
        int shown = window.showShop(header, lines);

        Path logDir = LOGGER.saveShop(screen, read, run.orElse(null), lines, total);
        System.out.println("    ログを保存しました: " + logDir);

        // AI に一言コメントを書かせる（別の流れで。届いたら表示に足し、ログにも保存する）
        if (advice != null) {
            RunState state = run.get();
            ShopAdvisor.Advice shopAdvice = advice;
            DeckAdvisor.DeckView shopDeck = deck;
            java.util.Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> shopOwn = own;
            COMMENT_THREAD.submit(() -> {
                try {
                    CommentWriter.ShopComments c = writer.forShop(state, read.items(), shopAdvice, shopDeck, shopOwn);
                    List<String> commentLines = CommentWriter.lines(c);
                    if (commentLines.isEmpty()) {
                        window.showCommentError(shown, "AI の答えを読めませんでした");
                    } else {
                        window.showShopComments(shown, c);
                    }
                    System.out.println("    AI のコメント（撮影から " + (System.currentTimeMillis() - start)
                            + " ミリ秒、うち AI " + c.millis() + " ミリ秒）：");
                    for (String line : commentLines) {
                        System.out.println("      " + line);
                    }
                    LOGGER.saveComment(logDir, CommentWriter.toJson(c));
                } catch (Exception e) {
                    System.out.println("    AI のコメントを作れませんでした: " + e);
                    window.showCommentError(shown, e.getMessage());
                }
            });
        }
    }

    /**
     * 順位をつけた結果（判断ログに保存するため、まとめて返す）。
     *
     * @param state      セーブから読んだラン状態
     * @param statsGroup 使った統計の区分（"all" か "a10"）
     * @param options    点数の高い順に並べた選択肢
     * @param deck       デッキとの相性（ステップ11）
     * @param shown      表示ウィンドウでの表示の番号（AI のコメントを足すときに使う）
     */
    private record Ranking(RunState state, String statsGroup, List<CardRanker.Option> options,
                           DeckAdvisor.DeckView deck, int shown) {
    }

    /**
     * セーブファイルから今のキャラとアセンションを読み、候補のカードに順位をつけて表示します（F-04, F-09）。
     *
     * 同じ内容を、表示ウィンドウにも出します（F-12）。
     *
     * @param cards  画面から読み取った候補のカード（照合の結果）
     * @param window 表示ウィンドウ
     * @return 順位をつけた結果。セーブファイルが見つからず順位をつけられなかったときは null
     * @throws Exception セーブファイルやデータベースを読めなかったとき
     */
    private static Ranking printRanking(List<CardNameMatcher.Match> cards, AdvisorWindow window) throws Exception {
        Optional<RunState> run = new SaveFileReader().readLatest();
        if (run.isEmpty()) {
            System.out.println("    セーブファイルが見つからないため、順位をつけられません");
            window.showMessage("セーブファイルが見つからないため、順位をつけられません");
            return null;
        }
        RunState state = run.get();
        String group = CardRanker.ascensionGroup(state.ascension());

        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (CardNameMatcher.Match m : cards) {
            ids.add(m.cardId());
            // 強化済みのカード（読んだ名前の後ろに「+」）は、表示する名前にも「+」を付ける（統計は強化前と同じ）
            boolean upgraded = m.readName().endsWith("+") || m.readName().endsWith("＋");
            names.add(m.officialName() != null ? m.officialName() + (upgraded ? "+" : "") : m.readName());
        }

        java.util.Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> own = ownStats(state.character());

        try (CardDatabase db = new CardDatabase(DB_FILE)) {
            CardRanker ranker = new CardRanker(db.findCodexStats(state.character(), group), db.findMetabotStats(),
                    state.deck().size(), own);
            int fitPercent = (int) Math.round(CardRanker.fitWeight(state.deck().size()) * 100);
            String header = state.character() + " / アセンション " + state.ascension() + " / Act" + state.act()
                    + " / デッキ" + state.deck().size() + "枚、使う統計: " + group
                    + "、統計 " + (100 - fitPercent) / 10 + "：相性 " + fitPercent / 10;
            // デッキとの相性（ステップ11）を求め、統計の順位に混ぜる（2026-11-13。序盤は 8:2、それ以降は 6:4）
            DeckAdvisor.DeckView deck = DECK_ADVISOR.evaluate(state, ids);
            List<CardRanker.Option> options = CardRanker.mixDeckFit(ranker.rank(ids, names), deck,
                    state.deck().size());
            System.out.println("    順位（" + header + "）　" + deck.headline() + "：");
            for (String line : RankingText.lines(options, deck)) {
                System.out.println("      " + line);
            }
            int shown = window.showRanking(header, options, deck);
            return new Ranking(state, group, options, deck, shown);
        }
    }

    /**
     * 自分のラン履歴での、カードごとの統計を読みます（F-13。順位には混ぜず、表示するだけ）。
     * 前のランが終わっていれば新しい履歴があるので、先に取り込んでから集計します。
     *
     * @param character 今のキャラ（例："DEFECT"）
     * @return カードID → 自分の統計。読めなかったときは空（表示するだけなので、順位やおすすめは出す）
     */
    private static java.util.Map<String, sts2advisor.db.RunHistoryDatabase.OwnCardStat> ownStats(String character) {
        try {
            ImportRunHistoryMain.importNew(DB_FILE);
            try (sts2advisor.db.RunHistoryDatabase history = new sts2advisor.db.RunHistoryDatabase(DB_FILE)) {
                return history.findOwnCardStats(character);
            }
        } catch (Exception e) {
            System.out.println("    自分のラン履歴を読めませんでした: " + e.getMessage());
            return java.util.Map.of();
        }
    }

    /**
     * セーブファイルから、今のキャラを読みます（カードを特定するときの候補を絞るのに使う）。
     *
     * @return 今のキャラ（例："REGENT"）。セーブが無い・読めないときは null（全キャラを候補にする）
     */
    private static String currentCharacter() {
        try {
            return new SaveFileReader().readLatest().map(RunState::character).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 画面で読んだカードの説明文が、DB の効果テキストと大きく違っていないかを調べます（F-06）。
     * パッチで効果が変わったのに、DB がまだ古いときに気づけるようにするためです。
     * 違っていれば、表示ウィンドウに警告を出します（順位はそのまま出す）。
     *
     * @param read     カード報酬の読み取り結果
     * @param resolver カードを特定する係（DB の効果テキストを持っている）
     * @param window   表示ウィンドウ
     */
    private static void checkCardTexts(CardRewardReader.Result read, CardResolver resolver, AdvisorWindow window) {
        for (int i = 0; i < read.cards().size() && i < read.descriptions().size(); i++) {
            CardNameMatcher.Match m = read.cards().get(i);
            if (m.cardId() == null) {
                continue;
            }
            Double similarity = resolver.textSimilarity(m.cardId(), read.descriptions().get(i));
            if (similarity != null && similarity < TEXT_MISMATCH_LINE) {
                String warning = "「" + m.officialName() + "」の効果が DB と違うかもしれません（似かた "
                        + String.format("%.2f", similarity) + "）。パッチで変わった可能性があります";
                System.out.println("    警告：" + warning + "　画面の説明文: " + read.descriptions().get(i));
                window.showWarning(warning);
            }
        }
    }

    /**
     * ショップで特定できなかった品物（カード名を読めなかった・アイコンが当てにならなかった）の数を数えます。
     * カード削除は ID が無いので数えません。
     *
     * @param read ショップの読み取り結果
     * @return 特定できなかった品物の数
     */
    private static int unknownItems(ShopReader.Result read) {
        return (int) read.items().stream()
                .filter(i -> i.kind() != sts2advisor.shop.ShopLayout.Kind.REMOVAL && i.id() == null).count();
    }

    /**
     * 特定できなかったカード（公式の名前と一致しなかったカード）があるかを調べます。
     *
     * @param cards 照合の結果
     * @return 1枚でも特定できなければ true
     */
    private static boolean hasUnknownCard(List<CardNameMatcher.Match> cards) {
        return cards.stream().anyMatch(m -> m.cardId() == null);
    }

    /**
     * カードを特定できなかった画面と、切り出した帯を保存します（ファイル名に時刻を付け、上書きしない）。
     *
     * @param screen 撮影した画面
     * @param read   読み取りの結果
     * @throws java.io.IOException 保存できなかったとき
     */
    private static void saveFailed(BufferedImage screen, CardRewardReader.Result read) throws java.io.IOException {
        java.nio.file.Files.createDirectories(FAILED_DIR);
        String time = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString().replace(":", "");
        ImageIO.write(screen, "png", FAILED_DIR.resolve(time + "_screen.png").toFile());
        ImageIO.write(read.strip(), "png", FAILED_DIR.resolve(time + "_strip.png").toFile());
        System.out.println("    特定できなかった画面を保存しました: " + FAILED_DIR.resolve(time + "_screen.png"));
    }

    /**
     * 調べるための画面を、時刻を付けて FAILED_DIR に保存します（上書きしない）。
     *
     * @param screen 撮影した画面
     * @param suffix ファイル名の後ろの部分（例："_no_cards_screen.png"）
     * @throws java.io.IOException 保存できなかったとき
     */
    private static void saveScreen(BufferedImage screen, String suffix) throws java.io.IOException {
        java.nio.file.Files.createDirectories(FAILED_DIR);
        String time = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString().replace(":", "");
        Path file = FAILED_DIR.resolve(time + suffix);
        ImageIO.write(screen, "png", file.toFile());
        System.out.println("    調べるための画面を保存しました: " + file);
    }

    /**
     * ゲームを映していない方のモニターの番号を選びます（表示ウィンドウを出す場所）。
     *
     * @param gameMonitor ゲームを映しているモニターの番号
     * @return ゲームではない最初のモニターの番号。モニターが1つしかなければ、そのモニター（0）
     */
    private static int otherMonitor(int gameMonitor) {
        int count = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length;
        for (int i = 0; i < count; i++) {
            if (i != gameMonitor) {
                return i;
            }
        }
        return 0;
    }
}

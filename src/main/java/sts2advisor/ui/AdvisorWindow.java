package sts2advisor.ui;

import sts2advisor.ai.CommentWriter;
import sts2advisor.deck.DeckAdvisor;
import sts2advisor.reward.CardRanker;
import sts2advisor.reward.RankingText;
import sts2advisor.screen.ScreenType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.net.URI;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【このファイルの役割】
 * おすすめ（順位）を表示する別ウィンドウです（機能要件 F-12）。
 * ゲームを映していないモニターに開き、次のものを表示します。
 *   ・いまの画面の種類（カード報酬・ショップ・その他）
 *   ・カード報酬の順位と、その根拠の数字
 *   ・出典のリンク（Spire Codex と MetaBot.GG。MetaBot.GG は利用条件で出典リンクの表示が必要）
 *   ・AI の一言コメント（ステップ11-3）。順位より後に届くので、届いたら各行に書き足す
 *
 * このウィンドウは表示するだけで、ゲームには何も送りません（CLAUDE.md の「読み取り専用」）。
 * リンクを押すと、PC の既定のブラウザでそのページが開きます。
 *
 * 画面の更新について：
 *   Swing（Java の画面の部品）は、画面の書き換えを専用の流れ（イベントディスパッチスレッド）で
 *   行う決まりがあります。そのため、外から呼ばれる show… の処理は SwingUtilities.invokeLater で
 *   その流れに渡してから書き換えます。
 */
public class AdvisorWindow {

    /** Spire Codex のサイト（出典リンク） */
    private static final String SPIRE_CODEX_URL = "https://spire-codex.com";

    /** MetaBot.GG のサイト（出典リンク） */
    private static final String METABOT_URL = "https://metabot.gg";

    /** ウィンドウの幅と高さ（ピクセル。高解像度の画面では Java が自動で拡大する） */
    private static final int WIDTH = 760;
    private static final int HEIGHT = 640;

    /** 1位の背景の色（目立たせるため） */
    private static final Color FIRST_PLACE = new Color(255, 244, 200);

    /** AI のコメントの文字の色（統計の数字と見分けるため、青にする） */
    private static final String COMMENT_COLOR = "#1f4fa8";

    /**
     * AI のコメントを折り返す文字数。カード報酬は右に MetaBot のリンクがあるので短くする。
     * （Swing の HTML 表示は、日本語のように空白の無い文を折り返さないので、決まった文字数ごとに改行を入れる）
     */
    private static final int REWARD_COMMENT_CHARS = 44;
    private static final int SHOP_COMMENT_CHARS = 46;

    /** カード報酬の統計・相性の行を折り返す文字数（文字が小さいので、コメントより多く入る） */
    private static final int REWARD_DETAIL_CHARS = 50;

    /** AI のコメントを待っている間の文字の色（灰色） */
    private static final String WAITING_COLOR = "#888888";

    private final JFrame frame;
    /** 一番上の行：いまの画面の種類 */
    private final JLabel statusLabel = new JLabel();
    /** その下の行：キャラ・アセンション・使った統計など */
    private final JLabel headerLabel = new JLabel();
    /** 警告の行（パッチがあった・効果テキストが DB と違う など。F-06）。警告が無いときは隠す */
    private final JLabel warningLabel = new JLabel();
    /** これまでに出した警告（新しいものを下に足していく） */
    private final List<String> warnings = new ArrayList<>();
    /** 順位を並べる場所 */
    private final JPanel rankingPanel = new JPanel();

    /**
     * 表示を入れ替えた回数。AI のコメントは遅れて届くので、届いたときに表示がもう別の画面に
     * 変わっていたら（回数が違えば）、そのコメントは出さない。
     * 外の流れ（監視の処理）から数を進め、画面の書き換え専用の流れで読むので、AtomicInteger を使う
     */
    private final AtomicInteger generation = new AtomicInteger();

    /** カード報酬の見出し（AI の見立てを後から足すために、元の文を覚えておく） */
    private String rewardHeader = "";

    /** カード報酬の各行の、AI のコメントを書く場所（順位の順） */
    private final List<JLabel> commentLabels = new ArrayList<>();

    /**
     * ウィンドウを作ります（まだ表示はしない）。open から呼ぶ。
     */
    private AdvisorWindow() {
        frame = new JFrame("StS2 Advisor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(WIDTH, HEIGHT);

        statusLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 18));
        headerLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        top.add(statusLabel);
        top.add(Box.createVerticalStrut(4));
        warningLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 13));
        warningLabel.setVisible(false);
        top.add(warningLabel);
        top.add(headerLabel);

        rankingPanel.setLayout(new BoxLayout(rankingPanel, BoxLayout.Y_AXIS));
        rankingPanel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        JPanel rankingHolder = new JPanel(new BorderLayout());
        rankingHolder.add(rankingPanel, BorderLayout.NORTH); // 上詰めにする
        JScrollPane scroll = new JScrollPane(rankingHolder);
        scroll.setBorder(null);

        // 一番下の行：出典のリンク
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        footer.add(new JLabel("出典："));
        footer.add(link("Spire Codex", SPIRE_CODEX_URL));
        footer.add(link("MetaBot.GG", METABOT_URL));

        frame.getContentPane().add(top, BorderLayout.NORTH);
        frame.getContentPane().add(scroll, BorderLayout.CENTER);
        frame.getContentPane().add(footer, BorderLayout.SOUTH);

        statusLabel.setText("起動中…");
        headerLabel.setText(" ");
    }

    /**
     * ウィンドウを作り、指定したモニターの右上に表示します。
     *
     * @param monitorIndex 表示するモニターの番号（ScreenCapture.listMonitors の番号と同じ）。
     *                     範囲外なら一番目のモニターに出す
     * @return 作ったウィンドウ
     * @throws Exception ウィンドウを作れなかったとき
     */
    public static AdvisorWindow open(int monitorIndex) throws Exception {
        AdvisorWindow[] holder = new AdvisorWindow[1];
        // ウィンドウを作る処理も、画面の書き換え専用の流れで行い、終わるまで待つ
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new AdvisorWindow();
            // モニターの右上に置く（端から少し離す）
            place(holder[0].frame, monitorIndex);
            holder[0].frame.setVisible(true);
        });
        return holder[0];
    }

    /**
     * ウィンドウを、指定したモニターの右上に動かします（ゲームを別のモニターに移したときに使う）。
     *
     * @param monitorIndex 動かす先のモニターの番号。範囲外なら一番目のモニター
     */
    public void moveTo(int monitorIndex) {
        SwingUtilities.invokeLater(() -> place(frame, monitorIndex));
    }

    /**
     * ウィンドウを、指定したモニターの右上に置きます（端から少し離す）。
     *
     * @param frame        置くウィンドウ
     * @param monitorIndex モニターの番号。範囲外なら一番目のモニター
     */
    private static void place(JFrame frame, int monitorIndex) {
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        int index = (monitorIndex >= 0 && monitorIndex < screens.length) ? monitorIndex : 0;
        Rectangle area = screens[index].getDefaultConfiguration().getBounds();
        frame.setLocation(area.x + area.width - WIDTH - 40, area.y + 40);
    }

    /**
     * 警告を1つ足して表示します（赤い文字。同じ警告は2回出さない。新しい5つまで表示する）。
     *
     * @param warning 警告の文
     */
    public void showWarning(String warning) {
        SwingUtilities.invokeLater(() -> {
            if (warnings.contains(warning)) {
                return;
            }
            warnings.add(warning);
            List<String> shown = warnings.subList(Math.max(0, warnings.size() - 5), warnings.size());
            StringBuilder html = new StringBuilder("<html><font color='#c00000'>");
            for (int i = 0; i < shown.size(); i++) {
                html.append(i > 0 ? "<br>" : "").append(wrapLines("⚠ " + shown.get(i), SHOP_COMMENT_CHARS + 6));
            }
            warningLabel.setText(html.append("</font></html>").toString());
            warningLabel.setVisible(true);
            frame.getContentPane().revalidate();
        });
    }

    /**
     * いまの画面の種類を表示します。順位の表示はそのまま残します（前回の結果を見返せるように）。
     *
     * @param type 画面の種類
     */
    public void showScreenType(ScreenType type) {
        String time = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        SwingUtilities.invokeLater(() -> statusLabel.setText("画面：" + type.label() + "　（" + time + "）"));
    }

    /**
     * 順位の場所に、お知らせの文を1行だけ表示します（「読み取り中…」や、うまくいかなかったときの説明など）。
     *
     * @param message 表示する文
     */
    public void showMessage(String message) {
        generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            headerLabel.setText(" ");
            rankingPanel.removeAll();
            JLabel label = new JLabel(message);
            label.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
            rankingPanel.add(label);
            refresh();
        });
    }

    /**
     * カード報酬の順位を表示します。
     *
     * @param header  順位の上に出す説明（キャラ・アセンション・使った統計など）
     * @param options 点数の高い順に並べた選択肢（CardRanker.rank の結果）
     * @param deck    デッキとの相性（ステップ11。無ければ null）
     * @return この表示の番号（AI のコメントを showRewardComments で足すときに渡す）
     */
    public int showRanking(String header, List<CardRanker.Option> options, DeckAdvisor.DeckView deck) {
        int number = generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            // 見出しは2行にする（1行目：キャラなど、2行目：デッキの型）
            rewardHeader = deck == null ? escape(header) : escape(header) + "<br>" + escape(deck.headline());
            headerLabel.setText("<html>" + rewardHeader + "</html>");
            rankingPanel.removeAll();
            commentLabels.clear();
            for (int i = 0; i < options.size(); i++) {
                rankingPanel.add(row(i + 1, options.get(i), deck));
                rankingPanel.add(Box.createVerticalStrut(6));
            }
            refresh();
        });
        return number;
    }

    /**
     * カード報酬の順位に、AI の一言コメントを書き足します（順位の表示より後に呼ぶ）。
     * 表示がもう別の画面に変わっていたら、何もしません。
     *
     * @param number   showRanking が返した表示の番号
     * @param comments AI のコメント
     */
    public void showRewardComments(int number, CommentWriter.RewardComments comments) {
        SwingUtilities.invokeLater(() -> {
            if (number != generation.get()) {
                return;
            }
            if (!comments.direction().isEmpty()) {
                headerLabel.setText("<html>" + rewardHeader + "<br><font color='" + COMMENT_COLOR + "'>AI の見立て："
                        + escape(comments.direction()) + "</font></html>");
            }
            for (int i = 0; i < commentLabels.size(); i++) {
                String comment = comments.byRank().get(i + 1);
                commentLabels.get(i).setText(comment == null || comment.isEmpty() ? " "
                        : wrapped("AI：" + comment, REWARD_COMMENT_CHARS));
            }
            refresh();
        });
    }

    /**
     * AI のコメントを作れなかったことを、コメントの場所に表示します。
     * 表示がもう別の画面に変わっていたら、何もしません。
     *
     * @param number  showRanking・showShop が返した表示の番号
     * @param message 理由
     */
    public void showCommentError(int number, String message) {
        SwingUtilities.invokeLater(() -> {
            if (number != generation.get()) {
                return;
            }
            String text = "<html><font color='" + WAITING_COLOR + "'>AI のコメントを作れませんでした（"
                    + escape(message) + "）</font></html>";
            if (commentLabels.isEmpty()) {
                // ショップのときは、一覧の一番上に出す
                rankingPanel.add(new JLabel(text), 0);
            } else {
                commentLabels.get(0).setText(text);
                for (int i = 1; i < commentLabels.size(); i++) {
                    commentLabels.get(i).setText(" ");
                }
            }
            refresh();
        });
    }

    /**
     * ショップに並んでいる品物の一覧を表示します（MVP のステップ8。おすすめの買い物はステップ9で足す）。
     *
     * @param header 一覧の上に出す説明（所持ゴールドなど）
     * @param lines  品物1つにつき1行の説明
     * @return この表示の番号（AI のコメントを showShopComments で足すときに渡す）
     */
    public int showShop(String header, List<String> lines) {
        int number = generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            headerLabel.setText(header);
            rankingPanel.removeAll();
            commentLabels.clear();
            for (String line : lines) {
                // 長い行はウィンドウの右で切れてしまうので、折り返して2行ほどで全部見えるようにする
                JLabel label = new JLabel("<html>" + wrapLines(line, SHOP_COMMENT_CHARS) + "</html>");
                label.setFont(new Font(Font.DIALOG, Font.PLAIN, 15));
                rankingPanel.add(label);
                rankingPanel.add(Box.createVerticalStrut(4));
            }
            refresh();
        });
        return number;
    }

    /**
     * ショップのおすすめの上に、AI の一言コメントを書き足します（おすすめの表示より後に呼ぶ）。
     * 表示がもう別の画面に変わっていたら、何もしません。
     *
     * @param number   showShop が返した表示の番号
     * @param comments AI のコメント
     */
    public void showShopComments(int number, CommentWriter.ShopComments comments) {
        SwingUtilities.invokeLater(() -> {
            if (number != generation.get()) {
                return;
            }
            // 一番上に、AI のコメントをまとめて入れる（おすすめの一覧は長いので、下に置くと見えにくい）
            List<String> lines = CommentWriter.lines(comments);
            for (int i = lines.size() - 1; i >= 0; i--) {
                JLabel label = new JLabel(wrapped("AI：" + lines.get(i), SHOP_COMMENT_CHARS));
                label.setFont(new Font(Font.DIALOG, Font.PLAIN, 15));
                rankingPanel.add(label, 0);
            }
            refresh();
        });
    }

    /**
     * 選択肢1つ分の行を作ります。
     * 1行目に「順位と名前」を大きく、2行目に根拠の数字を小さく出し、
     * MetaBot.GG のデータがあれば、そのカードのページへのリンクを付けます。
     *
     * @param rank   順位（1から）
     * @param option 選択肢
     * @param deck   デッキとの相性（無ければ null）
     * @return 行の部品
     */
    private JPanel row(int rank, CardRanker.Option option, DeckAdvisor.DeckView deck) {
        JPanel row = new JPanel(new BorderLayout(8, 2));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        if (rank == 1) {
            row.setBackground(FIRST_PLACE);
        }

        JLabel title = new JLabel(rank + "位　" + option.name());
        title.setFont(new Font(Font.DIALOG, Font.BOLD, 20));
        row.add(title, BorderLayout.NORTH);

        // 2行目：RankingText の説明から、先頭の名前の部分を除いたもの
        String detail = RankingText.describe(option);
        if (detail.startsWith(option.name())) {
            detail = detail.substring(option.name().length()).strip();
        }
        // 3行目：デッキとの相性（あれば）
        String fit = deck != null ? DeckAdvisor.describe(deck.fit(option.cardId())) : "";
        // 長い行はウィンドウの右で切れてしまうので、折り返して2行ほどで全部見えるようにする
        JLabel detailLabel = new JLabel("<html>" + wrapLines(detail, REWARD_DETAIL_CHARS)
                + (fit.isEmpty() ? "" : "<br><font color='#1a6b2f'>" + wrapLines(fit, REWARD_DETAIL_CHARS) + "</font>")
                + "</html>");
        detailLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        row.add(detailLabel, BorderLayout.CENTER);

        if (option.metabot() != null && option.metabot().url() != null) {
            row.add(link("MetaBot", option.metabot().url()), BorderLayout.EAST);
        }

        // 4行目：AI の一言コメント（後から届くので、それまでは「待っています」と出す）
        JLabel comment = new JLabel("<html><font color='" + WAITING_COLOR + "'>AI のコメントを待っています…</font></html>");
        comment.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        row.add(comment, BorderLayout.SOUTH);
        commentLabels.add(comment);
        return row;
    }

    /**
     * AI のコメントを、決まった文字数で折り返す青い文字にします（長いコメントがウィンドウの外にはみ出さないように）。
     *
     * @param text     表示する文字
     * @param maxChars 1行の文字数
     * @return ラベルに入れる HTML
     */
    private static String wrapped(String text, int maxChars) {
        return "<html><font color='" + COMMENT_COLOR + "'>" + wrapLines(text, maxChars) + "</font></html>";
    }

    /**
     * 文字を、決まった文字数ごとに改行を入れた HTML の部品にします（&lt;html&gt; は付けない）。
     * （Swing の HTML 表示は、日本語のように空白の無い文を折り返さないため）
     *
     * @param text     表示する文字
     * @param maxChars 1行の文字数
     * @return 改行（&lt;br&gt;）を入れた HTML の部品
     */
    private static String wrapLines(String text, int maxChars) {
        StringBuilder html = new StringBuilder();
        int from = 0;
        while (from < text.length()) {
            int end = breakPoint(text, from, maxChars);
            if (from > 0) {
                html.append("<br>　　"); // 2行目からは少し字下げして、続きだと分かるようにする
            }
            html.append(escape(text.substring(from, end)));
            from = end;
        }
        return html.toString();
    }

    /**
     * 折り返す場所を決めます。次のときは、ちょうどの文字数からずらします。
     *   ・残りが3文字以下 → 次の行に回さず、この行に入れる（「。」だけの行を作らない）
     *   ・行の後ろの方に区切り（「｜」・空白・「、」「）」など）があれば、そこで折り返す
     *     （"Metabot" のような言葉の途中で切らないため。「｜」はその前で、ほかはその後ろで折り返す）
     *   ・数の途中（例："-1.4" の "-1" と ".4" の間）→ 数の前で折り返す
     *   ・次の行が「。」「、」などで始まる → その記号までこの行に入れる
     *
     * @param text     文字
     * @param from     この行の始まり
     * @param maxChars 1行の文字数
     * @return この行の終わり（この位置の文字は次の行に入る）
     */
    private static int breakPoint(String text, int from, int maxChars) {
        int end = from + maxChars;
        if (end >= text.length() - 3) {
            return text.length();
        }
        // 行の後ろ3分の1の中で、いちばん後ろの区切りを探す
        for (int i = end; i > from + maxChars * 2 / 3; i--) {
            char c = text.charAt(i);
            if (c == '｜' || c == '|') {
                return i;
            }
            if ("　 、。）)".indexOf(c) >= 0 && i + 1 <= end) {
                return i + 1;
            }
        }
        int numberStart = end;
        while (numberStart > from + maxChars / 2 && isNumberChar(text.charAt(numberStart - 1))
                && isNumberChar(text.charAt(numberStart))) {
            numberStart--;
        }
        if (numberStart > from + maxChars / 2) {
            end = numberStart;
        }
        while (end < text.length() && "。、）」％%".indexOf(text.charAt(end)) >= 0) {
            end++;
        }
        return end;
    }

    /** 数の一部になる文字か（数字・小数点・符号） */
    private static boolean isNumberChar(char c) {
        return Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == '/';
    }

    /**
     * 文字を HTML の中に入れても崩れないようにします（&lt; などに置き換える）。
     *
     * @param text 元の文字
     * @return 置き換えた文字
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 押すとブラウザでページを開く、リンクのボタンを作ります。
     *
     * @param text 表示する文字
     * @param url  開くページ
     * @return ボタンの部品
     */
    private static JButton link(String text, String url) {
        JButton button = new JButton("<html><u>" + text + "</u></html>");
        button.setForeground(new Color(0, 90, 200));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(url);
        button.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception ex) {
                System.out.println("ページを開けませんでした: " + url + "（" + ex.getMessage() + "）");
            }
        });
        return button;
    }

    /**
     * 部品を入れ替えたあと、画面を描き直します。
     */
    private void refresh() {
        rankingPanel.revalidate();
        rankingPanel.repaint();
    }
}

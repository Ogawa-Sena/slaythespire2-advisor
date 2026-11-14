package sts2advisor.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 【このファイルの役割】
 * AI（Claude Code の `claude -p`）に画像を見せて、写っている文字を読んでもらう係です（機能要件 F-03）。
 *
 * 速さのための工夫（2026-11-12 の計測。docs/screen_reading_survey.md を参照）：
 *   ・`claude -p` は起動に約5秒かかるので、最初に1回だけ起動し、起動したまま画像を送り続ける（1回あたり約3秒）
 *   ・軽いモデル（haiku）を使い、AI が使う道具（ファイル操作など）はすべて無しにする
 *   ・画像はファイルの場所ではなく、中身をそのまま（Base64 という文字の形にして）渡す
 * 送った画像は会話の履歴にたまっていくので、決まった回数ごとに起動し直します。
 *
 * ログインは、あなたの Claude アカウント（Pro プラン）を使います。
 * 自分のパソコンで自分のために使う前提です（他の人に使ってもらう場合は、その人自身のログインが必要）。
 */
public class ClaudeVision implements AutoCloseable {

    /** winget で入れた claude.exe の場所。環境変数 CLAUDE_PATH があれば、そちらを使う */
    private static final String DEFAULT_CLAUDE_PATH = System.getenv("LOCALAPPDATA")
            + "\\Microsoft\\WinGet\\Packages\\Anthropic.ClaudeCode_Microsoft.Winget.Source_8wekyb3d8bbwe\\claude.exe";

    /** 起動し直すまでに送る画像の数（履歴がたまりすぎないように） */
    private static final int REQUESTS_BEFORE_RESTART = 15;

    /** 使うモデル（"haiku" や "sonnet"） */
    private final String model;

    /** 起動中の claude のプロセス。まだ起動していなければ null */
    private Process process;

    /** claude へ質問を送る書き口 */
    private BufferedWriter toClaude;

    /** claude から答えを受け取る読み口 */
    private BufferedReader fromClaude;

    /** 今のプロセスに送った質問の数 */
    private int requestCount;

    /**
     * 答えを待つ時間の上限（ミリ秒）。これを過ぎたら claude を止めて、失敗にする（次の質問で起動し直す）。
     * 2026-11-13 に、画面を読む AI の答えが 44 秒かかり、その間ツール全体が止まって見えたため追加した
     */
    private final long timeoutMillis;

    /** 答えを待つための流れ（待つ時間に上限を付けるため、読み取りを別の流れで行う） */
    private static final java.util.concurrent.ExecutorService READER = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "claude-reader");
        t.setDaemon(true);
        return t;
    });

    /**
     * 軽くて速いモデル（haiku）で使います（画面の文字を読む用。答えを待つのは最大15秒）。
     */
    public ClaudeVision() {
        this("haiku", 15);
    }

    /**
     * モデルを指定して使います（一言コメントを書く用に、より賢いモデルを使うときなど。答えを待つのは最大60秒）。
     *
     * @param model モデルの名前（"haiku"、"sonnet" など。claude の --model に渡す）
     */
    public ClaudeVision(String model) {
        this(model, 60);
    }

    /**
     * モデルと、答えを待つ時間の上限を指定して使います。
     *
     * @param model          モデルの名前（"haiku"、"sonnet" など）
     * @param timeoutSeconds 答えを待つ時間の上限（秒）
     */
    public ClaudeVision(String model, int timeoutSeconds) {
        this.model = model;
        this.timeoutMillis = timeoutSeconds * 1000L;
    }

    /**
     * 先に claude を起動しておきます（起動に約5秒かかるため、最初の画面を読む前に済ませておく）。
     * 呼ばなくても、最初の ask のときに自動で起動します。
     *
     * @throws IOException claude を起動できなかったとき
     */
    public synchronized void warmUp() throws IOException {
        ensureStarted();
    }

    /**
     * claude を起動します（まだ起動していない、または起動し直す時期のとき）。
     *
     * @throws IOException claude を起動できなかったとき
     */
    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive() && requestCount < REQUESTS_BEFORE_RESTART) {
            return;
        }
        close();

        String claudePath = System.getenv().getOrDefault("CLAUDE_PATH", DEFAULT_CLAUDE_PATH);
        if (!Files.exists(Path.of(claudePath))) {
            throw new IOException("claude.exe が見つかりません: " + claudePath
                    + "（環境変数 CLAUDE_PATH で場所を指定できます）");
        }
        ProcessBuilder builder = new ProcessBuilder(List.of(
                claudePath, "-p",
                // 質問と答えを1行ずつの JSON でやり取りする（起動したまま何回も質問するため）
                "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                // モデルを指定する（読み取りは軽くて速い haiku）
                "--model", model,
                // AI が使う道具（ファイルの読み書きやコマンド実行）はすべて無しにする。画像を読むだけなので要らない
                "--tools", "",
                // 会話を保存しない・外部の道具を読み込まない・スラッシュコマンドを使わない（起動を軽くするため）
                "--no-session-persistence", "--strict-mcp-config", "--disable-slash-commands"));
        // claude の作業フォルダは、データのフォルダにしておく（プロジェクトのファイルに触らせないため）
        Path workDir = Path.of("data", "tmp");
        Files.createDirectories(workDir);
        builder.directory(workDir.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        process = builder.start();
        toClaude = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        fromClaude = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        requestCount = 0;
    }

    /**
     * 画像と質問を送り、AI の答え（文字）を受け取ります。
     *
     * @param image    見せる画像
     * @param question 質問の文章
     * @return AI の答え
     * @throws IOException claude とのやり取りに失敗した、または AI がエラーを返したとき
     */
    public synchronized String ask(BufferedImage image, String question) throws IOException {
        // 画像を PNG にして、Base64（画像などのデータを文字だけで表す形）にする
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        String base64 = Base64.getEncoder().encodeToString(png.toByteArray());

        // 送る内容：画像1枚と質問の文章を、1つの「ユーザーの発言」としてまとめる
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png");
        source.addProperty("data", base64);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image");
        imagePart.add("source", source);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", question);
        JsonArray content = new JsonArray();
        content.add(imagePart);
        content.add(textPart);
        return send(content);
    }

    /**
     * 文字だけの質問を送り、AI の答え（文字）を受け取ります（一言コメントを書かせるときなど）。
     *
     * @param question 質問の文章
     * @return AI の答え
     * @throws IOException claude とのやり取りに失敗した、または AI がエラーを返したとき
     */
    public synchronized String ask(String question) throws IOException {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", question);
        JsonArray content = new JsonArray();
        content.add(textPart);
        return send(content);
    }

    /**
     * 質問の中身（画像や文章）を1つの「ユーザーの発言」として送り、最終的な答えを受け取ります。
     *
     * @param content 送る中身（画像・文章の部品の並び）
     * @return AI の答え
     * @throws IOException claude とのやり取りに失敗した、または AI がエラーを返したとき
     */
    private String send(JsonArray content) throws IOException {
        ensureStarted();
        requestCount++;

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonObject request = new JsonObject();
        request.addProperty("type", "user");
        request.add("message", message);

        toClaude.write(request.toString());
        toClaude.newLine();
        toClaude.flush();

        // 答えを待つ。途中経過の行も届くので、"type":"result"（最終結果）の行が来るまで読み進める。
        // 待つ時間に上限を付けるため、読み取りは別の流れで行い、ここでは上限まで待つ
        BufferedReader reader = fromClaude;
        java.util.concurrent.Future<String> answer = READER.submit(() -> {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"type\":\"result\"")) {
                    continue;
                }
                JsonObject result = JsonParser.parseString(line).getAsJsonObject();
                if (result.has("is_error") && result.get("is_error").getAsBoolean()) {
                    throw new IOException("AI がエラーを返しました: " + result);
                }
                return result.get("result").getAsString();
            }
            return null; // claude が途中で止まった
        });
        String text;
        try {
            text = answer.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 答えが遅すぎる：claude を止める（読み取りの流れも終わる）。次の質問で起動し直す
            close();
            throw new IOException("AI の答えが " + timeoutMillis / 1000 + " 秒以内に来なかったので、AI を起動し直します");
        } catch (java.util.concurrent.ExecutionException e) {
            close();
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("AI の答えを待っている間に止められました");
        }
        if (text == null) {
            // ここに来たのは、claude が途中で止まったとき。次の質問で起動し直す
            close();
            throw new IOException("claude が途中で止まりました（ログインが切れていないか確認してください）");
        }
        return text;
    }

    /**
     * claude を終わらせます。try (...) を使っていれば自動で呼ばれます。
     */
    @Override
    public void close() {
        if (process != null) {
            try {
                // 入力を閉じると、claude は自分で終わる
                toClaude.close();
            } catch (IOException ignored) {
                // すでに止まっている場合は何もしない
            }
            process.destroy();
            process = null;
        }
    }
}

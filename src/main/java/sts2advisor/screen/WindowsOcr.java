package sts2advisor.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 【このファイルの役割】
 * Windows に標準で入っている日本語の OCR（文字の読み取り）を、Java から使うための係です。
 *
 * Java から Windows の OCR を直接呼ぶのは難しいため、PowerShell のスクリプト
 * （scripts/winocr_server.ps1）を1回だけ起動しておき、
 *   Java → スクリプト：画像ファイルの場所を1行送る
 *   スクリプト → Java：読み取り結果を JSON で1行返す
 * というやり取りをします。起動したまま使うので、1枚あたり約0.1秒で読めます。
 *
 * 使い方：
 *   try (WindowsOcr ocr = WindowsOcr.start()) {
 *       OcrResult result = ocr.read(画像の場所);
 *   }
 */
public class WindowsOcr implements AutoCloseable {

    /** OCR のスクリプトの場所（プロジェクトのフォルダから見た位置） */
    private static final Path SCRIPT = Path.of("scripts", "winocr_server.ps1");

    /** 起動した PowerShell のプロセス（動いているプログラム） */
    private final Process process;

    /** スクリプトへ画像の場所を送るための書き口 */
    private final BufferedWriter toScript;

    /** スクリプトから結果を受け取るための読み口 */
    private final BufferedReader fromScript;

    /**
     * @param process 起動した PowerShell のプロセス
     */
    private WindowsOcr(Process process) {
        this.process = process;
        this.toScript = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromScript = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * OCR のスクリプトを起動し、準備ができるまで待ちます。
     *
     * @return 使える状態になった WindowsOcr
     * @throws IOException スクリプトを起動できなかった、または日本語の OCR が使えなかったとき
     */
    public static WindowsOcr start() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", SCRIPT.toAbsolutePath().toString());
        // スクリプトのエラー表示は、この画面にそのまま出す（原因を調べやすくするため）
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        WindowsOcr ocr = new WindowsOcr(builder.start());

        // スクリプトが {"ready":true} を返したら準備完了
        String first = ocr.fromScript.readLine();
        if (first == null || !first.contains("\"ready\"")) {
            ocr.close();
            throw new IOException("Windows の OCR を起動できませんでした: " + first);
        }
        return ocr;
    }

    /**
     * 画像1枚を OCR で読みます。
     *
     * @param image 画像ファイルの場所（PNG など）
     * @return 読み取り結果
     * @throws IOException スクリプトとのやり取りに失敗した、または画像を読めなかったとき
     */
    public synchronized OcrResult read(Path image) throws IOException {
        long start = System.currentTimeMillis();

        // 画像の場所を1行送る（スクリプトは絶対パスを必要とする）
        toScript.write(image.toAbsolutePath().toString());
        toScript.newLine();
        toScript.flush();

        // 結果を1行受け取る
        String line = fromScript.readLine();
        if (line == null) {
            throw new IOException("OCR のスクリプトが止まりました");
        }
        JsonObject json = JsonParser.parseString(line).getAsJsonObject();
        if (json.has("error")) {
            throw new IOException("OCR で読めませんでした: " + json.get("error").getAsString());
        }

        List<OcrLine> lines = new ArrayList<>();
        // 1行も読めなかった画像では "lines" が空、または null になる
        JsonElement array = json.get("lines");
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                JsonObject l = element.getAsJsonObject();
                lines.add(new OcrLine(
                        l.get("text").getAsString(),
                        l.get("x").getAsInt(), l.get("y").getAsInt(),
                        l.get("w").getAsInt(), l.get("h").getAsInt()));
            }
        }
        return new OcrResult(lines, json.get("width").getAsInt(), json.get("height").getAsInt(),
                System.currentTimeMillis() - start);
    }

    /**
     * スクリプトを終わらせます。try (...) を使っていれば自動で呼ばれます。
     */
    @Override
    public void close() {
        try {
            // 空の行を送ると、スクリプトは自分で終わる
            toScript.newLine();
            toScript.flush();
        } catch (IOException ignored) {
            // すでに止まっている場合は何もしない
        }
        process.destroy();
    }
}

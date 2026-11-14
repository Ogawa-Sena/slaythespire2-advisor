package sts2advisor.screen;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 【このファイルの役割】
 * モニターの画面を撮影（スクリーンショット）する係です（機能要件 F-01）。
 * **撮影するだけで、マウスやキーボードの操作は一切しません**（CLAUDE.md の「読み取り専用」）。
 *
 * Java の標準機能 Robot を使います。Robot は本来マウス操作などもできる道具ですが、
 * ここでは画面を撮影する機能（createScreenCapture）だけを使います。
 */
public class ScreenCapture {

    /** 撮影する範囲（モニター1枚分の位置と大きさ） */
    private final Rectangle bounds;

    /** 画面を撮影するための道具 */
    private final Robot robot;

    /**
     * 撮影するモニターを決めます。
     *
     * @param monitorIndex モニターの番号（0 から数える。listMonitors で確認できる）
     * @throws AWTException             撮影の道具を用意できなかったとき
     * @throws IllegalArgumentException その番号のモニターがないとき
     */
    public ScreenCapture(int monitorIndex) throws AWTException {
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (monitorIndex < 0 || monitorIndex >= screens.length) {
            throw new IllegalArgumentException("モニター " + monitorIndex + " はありません（0〜" + (screens.length - 1) + "）");
        }
        this.bounds = screens[monitorIndex].getDefaultConfiguration().getBounds();
        this.robot = new Robot(screens[monitorIndex]);
    }

    /**
     * つながっているモニターの一覧を、番号・位置・大きさの文字にして返します（どれを撮るか選ぶため）。
     *
     * @return 例："0: 1920x1080（位置 0,0）メイン"
     */
    public static String listMonitors() {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice main = env.getDefaultScreenDevice();
        GraphicsDevice[] screens = env.getScreenDevices();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < screens.length; i++) {
            Rectangle r = screens[i].getDefaultConfiguration().getBounds();
            text.append(i).append(": ").append(r.width).append("x").append(r.height)
                    .append("（位置 ").append(r.x).append(",").append(r.y).append("）")
                    .append(screens[i] == main ? " メイン" : "").append(System.lineSeparator());
        }
        return text.toString();
    }

    /**
     * 画面を撮影して、PNG 画像として保存します（OCR に渡すため）。
     *
     * @param file 保存先のファイル（同じ名前のファイルがあれば上書きする）
     * @return 撮影した画像
     * @throws IOException 保存に失敗したとき
     */
    public BufferedImage captureTo(Path file) throws IOException {
        BufferedImage image = robot.createScreenCapture(bounds);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", file.toFile());
        return image;
    }
}

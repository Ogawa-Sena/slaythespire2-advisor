package sts2advisor.screen;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.OptionalInt;

/**
 * 【このファイルの役割】
 * ゲーム（Slay the Spire 2）のウィンドウが、どのモニターに映っているかを調べる係です。
 * 起動するときにモニターの番号を指定しなくてよいように、また遊んでいる途中でゲームを
 * 別のモニターに移しても追いかけられるようにするために使います（2026-11-12 追加）。
 *
 * しくみ：
 *   1. Windows に「Slay the Spire 2」という名前のウィンドウを探してもらう（FindWindow）
 *   2. そのウィンドウが一番多く重なっているモニターを教えてもらう（MonitorFromWindow）
 *   3. そのモニターの左上の位置と、Java が知っているモニターの左上の位置を比べて、番号を決める
 *      （Java のモニターの左上の位置は、Windows の位置と同じ数になる。大きさは画面の拡大率で縮めた数になる）
 *
 * ウィンドウの位置を読むだけで、ゲームには何も送りません（CLAUDE.md の「読み取り専用」）。
 */
public final class GameWindowLocator {

    /** ゲームのウィンドウの名前（タスクマネージャーなどで見えるもの） */
    private static final String GAME_WINDOW_TITLE = "Slay the Spire 2";

    private GameWindowLocator() {
        // 道具をまとめただけのクラスなので、インスタンスは作らない
    }

    /**
     * ゲームのウィンドウが映っているモニターの番号を調べます。
     *
     * @return モニターの番号（ScreenCapture.listMonitors の番号と同じ）。
     *         ゲームが起動していない・最小化されている・番号を決められないときは空
     */
    public static OptionalInt findGameMonitor() {
        try {
            WinDef.HWND window = User32.INSTANCE.FindWindow(null, GAME_WINDOW_TITLE);
            if (window == null) {
                return OptionalInt.empty();
            }
            // 最小化されているときは、どのモニターにも映っていないので「分からない」とする
            WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
            if (User32.INSTANCE.GetWindowPlacement(window, placement).booleanValue()
                    && placement.showCmd == WinUser.SW_SHOWMINIMIZED) {
                return OptionalInt.empty();
            }
            WinUser.HMONITOR monitor = User32.INSTANCE.MonitorFromWindow(window, WinUser.MONITOR_DEFAULTTONEAREST);
            WinUser.MONITORINFO info = new WinUser.MONITORINFO();
            if (!User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) {
                return OptionalInt.empty();
            }
            return indexAt(info.rcMonitor.left, info.rcMonitor.top);
        } catch (Throwable e) {
            // Windows 以外や、ライブラリが読み込めないときは「分からない」とする
            return OptionalInt.empty();
        }
    }

    /**
     * 左上の位置が一致する、Java のモニターの番号を返します。
     *
     * @param left Windows が教えてくれたモニターの左端
     * @param top  Windows が教えてくれたモニターの上端
     * @return モニターの番号。一致するものが無ければ空
     */
    private static OptionalInt indexAt(int left, int top) {
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        // ぴったり一致しなくても（画面の拡大率の組み合わせによってはずれることがある）、
        // 左上の位置がいちばん近いモニターを選ぶ
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < screens.length; i++) {
            Rectangle r = screens[i].getDefaultConfiguration().getBounds();
            long dx = r.x - left;
            long dy = r.y - top;
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best >= 0 ? OptionalInt.of(best) : OptionalInt.empty();
    }
}

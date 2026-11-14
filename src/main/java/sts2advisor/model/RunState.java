package sts2advisor.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 【このファイルの役割】
 * 今遊んでいるラン（1回の挑戦）の状態をまとめて持つ入れ物です（機能要件 F-04）。
 * セーブファイル（current_run.save）から読み取ります。
 *
 * @param character   キャラ（例："DEFECT"。セーブの "CHARACTER.DEFECT" から先頭を取ったもの）
 * @param ascension   アセンション（0〜10）
 * @param act         今の Act（1 から数える。セーブでは 0 から数えているので +1 したもの）
 * @param floor       今までに進んだマスの数（階層）
 * @param gold        所持ゴールド
 * @param hp          今の HP
 * @param maxHp       最大 HP
 * @param deck        デッキのカードの一覧
 * @param relics      レリックのIDの一覧（例："BOUND_PHYLACTERY"）
 * @param potions     ポーションのIDの一覧（例："ENERGY_POTION"）
 * @param potionSlots ポーションを持てる数（ショップでポーションを買えるか判断するのに使う）
 * @param playerCount 遊んでいる人数（1 ならソロ）
 * @param saveFile    読んだセーブファイルの場所
 * @param savedAt     セーブファイルが最後に書き換えられた日時（古すぎるなら、今のランではない可能性がある）
 */
public record RunState(
        String character,
        int ascension,
        int act,
        int floor,
        int gold,
        int hp,
        int maxHp,
        List<DeckCard> deck,
        List<String> relics,
        List<String> potions,
        int potionSlots,
        int playerCount,
        Path saveFile,
        Instant savedAt) {

    /**
     * デッキのカード1枚分。
     *
     * @param cardId       カードのID（例："ZAP"。cards 表の card_id と同じ形）
     * @param upgradeLevel 強化の回数（強化していなければ 0）
     */
    public record DeckCard(String cardId, int upgradeLevel) {
    }

    /**
     * @return ソロで遊んでいるなら true（CLAUDE.md で対象はソロのみと決めている）
     */
    public boolean isSolo() {
        return playerCount == 1;
    }
}

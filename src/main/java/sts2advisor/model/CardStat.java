package sts2advisor.model;

/**
 * 【このファイルの役割】
 * カード1枚の「性能データ（統計）」を、1つの条件（キャラ・アセンション区分・パッチ）について持つ入れ物です。
 * 同じカードでも、キャラやアセンション区分が違えば別の CardStat になります。
 *
 * @param cardId          カードのID（cards 表の card_id と同じ。例："BARRICADE"）
 * @param character       どのキャラのランで集計したか（例："IRONCLAD"）
 * @param ascensionGroup  アセンションの区分。"all"＝A0〜A10 全部、"a10"＝A10 だけ
 * @param patch           どのゲームバージョンのランで集計したか（例："v0.111.0"）
 * @param winRate         勝率（%）。このカードを取ったランのうち、勝ったランの割合
 * @param winRateCiLow    勝率の信頼区間の下限（%）。件数が少ないと、上限との幅が広くなる
 * @param winRateCiHigh   勝率の信頼区間の上限（%）
 * @param pickRate        ピック率（%）。カード報酬で提示されたときに取られた割合
 * @param lift            取った場合に勝率がどれだけ上がるか（%ポイント）。件数が20未満だと null
 * @param liftN           lift の計算に使った件数
 * @param picks           このカードを持っていたランの数
 * @param wins            そのうち勝ったランの数
 * @param losses          そのうち負けたランの数
 * @param offered         カード報酬で提示された回数
 * @param picked          カード報酬で取られた回数
 * @param pickRateAct1    Act1 でのピック率（%）。データがなければ null
 * @param pickRateAct2    Act2 でのピック率（%）
 * @param pickRateAct3    Act3 でのピック率（%）
 * @param score           Spire Codex 独自の総合点（0〜100）
 * @param tier            Spire Codex 独自のランク（S / A / B / C / D / F）
 * @param elo             Spire Codex 独自の「選ばれやすさ」の強さの数値
 * @param baselineWinRate 同じ条件の全ランの平均勝率（%）。このカードの勝率と比べる基準
 * @param characterRuns   同じ条件での、そのキャラのランの総数
 * @param dataThrough     Spire Codex が何日時までのランを集計したか
 */
public record CardStat(
        String cardId,
        String character,
        String ascensionGroup,
        String patch,
        Double winRate,
        Double winRateCiLow,
        Double winRateCiHigh,
        Double pickRate,
        Double lift,
        Integer liftN,
        Integer picks,
        Integer wins,
        Integer losses,
        Integer offered,
        Integer picked,
        Double pickRateAct1,
        Double pickRateAct2,
        Double pickRateAct3,
        Integer score,
        String tier,
        Double elo,
        Double baselineWinRate,
        Integer characterRuns,
        String dataThrough) {
}

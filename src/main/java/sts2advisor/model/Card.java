package sts2advisor.model;

/**
 * 【このファイルの役割】
 * カード1枚分の情報をまとめて持つための「入れ物」です。
 * 外部サイトから取ってきたデータを、この形に整えてからデータベースに保存します。
 *
 * record（レコード）は、値をまとめて持つだけのクラスを短く書くための Java の書き方です。
 * 書いた項目ごとに、値を取り出すメソッド（例：card.nameJa()）が自動で作られます。
 *
 * @param id                   カードのID（英語の大文字。例："ABRASIVE"）。日本語と英語のデータをつなぐ目印
 * @param nameJa               日本語のカード名（ゲーム内の公式名。例："棘纏い"）
 * @param nameEn               英語のカード名（例："Abrasive"）
 * @param cost                 エネルギーのコスト。コストがないカードは null
 * @param xCost                コストが「X」（残りエネルギーを全部使う）なら true
 * @param type                 種類（Attack / Skill / Power など。英語のキーで持つ）
 * @param rarity               レアリティ（Basic / Common / Uncommon / Rare など。英語のキーで持つ）
 * @param character            どのキャラのカードか（ironclad / silent / colorless など）
 * @param descriptionJa        日本語の効果テキスト
 * @param descriptionEn        英語の効果テキスト
 * @param upgradeDescriptionJa 強化後の日本語の効果テキスト。強化がなければ null
 * @param keywordsJa           キーワード（例："廃棄"）を「,」でつないだもの。なければ null
 * @param rawJsonJa            取ってきた日本語データの元のJSONそのもの（あとで別の項目が必要になったとき用）
 */
public record Card(
        String id,
        String nameJa,
        String nameEn,
        Integer cost,
        boolean xCost,
        String type,
        String rarity,
        String character,
        String descriptionJa,
        String descriptionEn,
        String upgradeDescriptionJa,
        String keywordsJa,
        String rawJsonJa) {
}

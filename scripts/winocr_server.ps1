# 【このファイルの役割】
# Windows に標準で入っている文字読み取り（OCR、日本語）を、Java のプログラムから使うための「窓口」です。
# Java から1回だけ起動され、起動したまま次のことを繰り返します。
#   1. 標準入力から、画像ファイルの場所を1行読む
#   2. その画像を OCR で読む
#   3. 読み取った行ごとの文字と位置を、JSON 1行にして標準出力に書く
# 毎回起動し直すと1秒近くかかるため、起動したまま使います。
# ※ Windows PowerShell 5.1（powershell.exe）で動かします（PowerShell 7 では Windows の OCR 機能を呼べないため）。

# 日本語の文字を正しくやり取りするため、Java とのやり取りの文字コードを UTF-8 にする。
# ※ [Console]::OutputEncoding を変えると、同じ画面（ターミナル）全体の文字コードまで変わり、
#    Java 側の日本語の表示が文字化けする。そのため、やり取り専用の読み口・書き口を作って UTF-8 を指定する
$utf8 = New-Object System.Text.UTF8Encoding $false   # $false = 先頭に BOM（目印の3バイト）を付けない
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), $utf8)
$stdout = New-Object System.IO.StreamWriter([Console]::OpenStandardOutput(), $utf8)
$ErrorActionPreference = 'Stop'

# Windows の機能（WinRT）を PowerShell から使えるように読み込む
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]

# Windows の機能は「あとで結果が届く」形（非同期）なので、結果が届くまで待つための道具を用意する
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]

# 目的：非同期の処理の結果が届くまで待って、結果を返す
# 入力：$op = 非同期の処理、$type = 結果の型
# 出力：処理の結果
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait()
    return $task.Result
}

# 日本語の OCR エンジンを用意する（日本語が使えなければエラーを返して終わる）
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('ja'))
if ($null -eq $engine) {
    $stdout.WriteLine('{"error":"日本語のOCRが使えません"}')
    $stdout.Flush()
    exit 1
}
# 準備ができたことを Java に知らせる
$stdout.WriteLine('{"ready":true}')
$stdout.Flush()

# 目的：画像1枚を OCR で読み、結果を JSON の文字列にする
# 入力：$path = 画像ファイルの場所（絶対パス）
# 出力：{"lines":[{"text":"…","x":…,"y":…,"w":…,"h":…}, …], "width":…, "height":…} という JSON の文字列
function Read-Image([string]$path) {
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    } finally {
        $stream.Dispose()
    }

    # 行ごとに、文字と「行を囲む四角」の位置・大きさをまとめる
    $lines = foreach ($line in $result.Lines) {
        $rects = $line.Words | ForEach-Object { $_.BoundingRect }
        $left = ($rects | ForEach-Object { $_.X } | Measure-Object -Minimum).Minimum
        $top = ($rects | ForEach-Object { $_.Y } | Measure-Object -Minimum).Minimum
        $right = ($rects | ForEach-Object { $_.X + $_.Width } | Measure-Object -Maximum).Maximum
        $bottom = ($rects | ForEach-Object { $_.Y + $_.Height } | Measure-Object -Maximum).Maximum
        [pscustomobject]@{
            # 日本語は単語の間に空白が入らないので、単語をそのままつなぐ
            text = ($line.Words | ForEach-Object { $_.Text }) -join ''
            x = [int]$left; y = [int]$top; w = [int]($right - $left); h = [int]($bottom - $top)
        }
    }
    return ([pscustomobject]@{ lines = @($lines); width = $bitmap.PixelWidth; height = $bitmap.PixelHeight } |
            ConvertTo-Json -Depth 4 -Compress)
}

# 1行ずつ画像の場所を受け取り、結果を1行ずつ返す。空の行や入力の終わりが来たら終わる
while ($true) {
    $path = $stdin.ReadLine()
    if ([string]::IsNullOrWhiteSpace($path)) { break }
    try {
        $json = Read-Image $path.Trim()
    } catch {
        # 読めなかったときは、エラーの内容を JSON で返す（Java 側で表示する）
        $json = ([pscustomobject]@{ error = $_.Exception.Message } | ConvertTo-Json -Compress)
    }
    $stdout.WriteLine($json)
    $stdout.Flush()
}

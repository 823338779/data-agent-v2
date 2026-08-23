param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$longMemRevision = '98d7416c24c778c2fee6e6f3006e7a073259d48f'
$clongRevision = '178d74d88f525f494441f94ef8cff9185a3d3b3a'
$clongCodeRevision = '81ba789bdbf83b88579c0a0ae780fa04090899cd'
$needleRevision = '9f76ad26e7699d914722a41924171ee1022c426f'
$dataRoot = Join-Path $PSScriptRoot '..\src\test\resources\compact-eval\data'
$longMemRoot = Join-Path $dataRoot 'longmemeval'
$clongRoot = Join-Path $dataRoot 'clongeval-lcvmem'
$needleRoot = Join-Path $dataRoot 'needlebench-zh'

New-Item -ItemType Directory -Force -Path $longMemRoot, $clongRoot, $needleRoot | Out-Null
$dataRoot = (Resolve-Path -LiteralPath $dataRoot).Path
$longMemRoot = (Resolve-Path -LiteralPath $longMemRoot).Path
$clongRoot = (Resolve-Path -LiteralPath $clongRoot).Path
$needleRoot = (Resolve-Path -LiteralPath $needleRoot).Path

function Receive-DatasetFile {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (-not $Force -and (Test-Path -LiteralPath $Destination) -and
        (Get-Item -LiteralPath $Destination).Length -gt 0) {
        Write-Host "Already downloaded: $Destination"
        return
    }

    $partial = "$Destination.part"
    curl.exe --fail --location --retry 3 --output $partial $Uri
    if ($LASTEXITCODE -ne 0) {
        throw "Download failed: $Uri"
    }
    Move-Item -Force -LiteralPath $partial -Destination $Destination
    Write-Host "Downloaded: $Destination"
}

foreach ($file in @(
        'longmemeval_s_cleaned.json',
        'longmemeval_m_cleaned.json',
        'longmemeval_oracle.json'
    )) {
    $uri = "https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/$longMemRevision/$file"
    Receive-DatasetFile -Uri $uri -Destination (Join-Path $longMemRoot $file)
}

foreach ($size in @('small', 'medium', 'large')) {
    $uri = "https://huggingface.co/datasets/zexuanqiu22/CLongEval/resolve/$clongRevision/1-2_long_conversation_memory/$size.jsonl"
    Receive-DatasetFile -Uri $uri -Destination (Join-Path $clongRoot "$size.jsonl")
}

$clongPositionUri = "https://raw.githubusercontent.com/zexuanqiu/CLongEval/$clongCodeRevision/lost_in_the_middle/data_position_info/long_conversation_memory_with_pos.jsonl"
Receive-DatasetFile -Uri $clongPositionUri -Destination (Join-Path $clongRoot 'long_conversation_memory_with_pos.jsonl')

$needleFiles = @(
    'multi_needle_reasoning_zh.json',
    'zh_finance.jsonl',
    'zh_game.jsonl',
    'zh_general.jsonl',
    'zh_government.jsonl',
    'zh_movie.jsonl',
    'zh_tech.jsonl',
    'README.md'
)
foreach ($file in $needleFiles) {
    $uri = "https://huggingface.co/datasets/opencompass/NeedleBench/resolve/$needleRevision/$file"
    Receive-DatasetFile -Uri $uri -Destination (Join-Path $needleRoot $file)
}

Write-Host "Evaluation datasets are available under $dataRoot"

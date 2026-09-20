# 每次只执行用户指定的一步；不指定步骤时仅显示用法，不连接数据库。
[CmdletBinding()]
param(
    [ValidateSet('help', 'debug', 'lineage', 'seed', 'staging', 'fact', 'marts',
        'test', 'compile', 'batch2-view', 'batch2-fact', 'batch2-marts', 'docs', 'serve')]
    [string]$Step = 'help',
    [ValidateSet(1, 2)]
    [int]$Batch = 1,
    [switch]$Preview
)

$ErrorActionPreference = 'Stop'
if ($Step -eq 'help') {
    Write-Host '按 README 顺序逐步运行，每一步之后暂停并查询数据库。'
    Write-Host '步骤：debug, lineage, seed, staging, fact, marts, test, compile'
    Write-Host '第二批：batch2-view, batch2-fact, batch2-marts'
    Write-Host '文档：docs, serve（第二批使用 -Batch 2）'
    Write-Host '示例：.\lab.ps1 -Step seed -Preview   # 仅显示命令'
    Write-Host '示例：.\lab.ps1 -Step seed            # 只执行 seed'
    Write-Host '重置：由你在 SQL 客户端执行 reset_demo.sql；本脚本不自动清理。'
    return
}

$dbtExecutable = Join-Path $PSScriptRoot '.venv/Scripts/dbt.exe'
# 三个第二批实验各自只推进一层，保留中间不一致状态供观察。
$demoBatch = if ($Step -like 'batch2-*') { 2 } else { $Batch }
$dbtArguments = switch ($Step) {
    'debug'        { @('debug') }
    'lineage'      { @('ls', '--select', '+mart_customer_sales', '--resource-type', 'model', 'seed') }
    'seed'         { @('seed') }
    'staging'      { @('run', '--select', 'stg_customers', 'stg_orders') }
    'fact'         { @('run', '--select', 'fct_orders') }
    'marts'        { @('run', '--select', 'mart_daily_sales', 'mart_customer_sales') }
    'test'         { @('test') }
    'compile'      { @('compile', '--select', 'fct_orders') }
    'batch2-view'  { @('run', '--select', 'stg_orders') }
    'batch2-fact'  { @('run', '--select', 'fct_orders') }
    'batch2-marts' { @('run', '--select', 'mart_daily_sales', 'mart_customer_sales') }
    'docs'         { @('docs', 'generate') }
    'serve'        { @('docs', 'serve', '--port', '8088') }
}
$dbtArguments = @($dbtArguments) + @('--project-dir', $PSScriptRoot, '--profiles-dir', $PSScriptRoot)
if ($Step -notin @('debug', 'serve')) {
    $dbtArguments += @('--vars', "demo_batch: $demoBatch")
}

Write-Host ('步骤：{0}；可见批次：{1}' -f $Step, $demoBatch)
Write-Host ('& "{0}" {1}' -f $dbtExecutable,
    (($dbtArguments | ForEach-Object { '"{0}"' -f $_ }) -join ' '))
if ($Preview) { return }
if (-not (Test-Path -LiteralPath $dbtExecutable)) {
    throw '未找到项目虚拟环境中的 dbt.exe，请先按 README 完成环境安装。'
}

# 参数以数组传递，不使用字符串求值；失败后停在当前步骤。
& $dbtExecutable @dbtArguments
if ($LASTEXITCODE -ne 0) {
    throw "dbt 步骤 $Step 失败，退出码 $LASTEXITCODE。请先查看错误，再决定下一步。"
}
Write-Host '当前步骤已结束。请按 README 查询和观察，再手动选择下一步。'

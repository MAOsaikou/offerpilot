# 从 O*NET 数据库 CSV 生成待翻译的候选清单（candidates.json）。
#
# 前置：先从 https://www.onetcenter.org/database.html 下载 CSV 版本并解压，
# 至少需要 occupation_data.csv、career_interest_types.csv、job_zones.csv。
#
# 用法：powershell -File build-candidates.ps1 -OnetDir <CSV 目录> -OutFile candidates.json
param(
    [Parameter(Mandatory = $true)][string]$OnetDir,
    [Parameter(Mandatory = $true)][string]$OutFile,
    [int]$MinJobZone = 4
)

$ErrorActionPreference = 'Stop'

$occs = Import-Csv (Join-Path $OnetDir 'occupation_data.csv') -Encoding UTF8
$interests = Import-Csv (Join-Path $OnetDir 'career_interest_types.csv') -Encoding UTF8
$zones = Import-Csv (Join-Path $OnetDir 'job_zones.csv') -Encoding UTF8

# 每个职业 6 行兴趣分数，按维度归集
$scores = @{}
foreach ($r in $interests) {
    $c = $r.'O*NET-SOC Code'
    if (-not $scores.ContainsKey($c)) { $scores[$c] = @{} }
    $scores[$c][$r.'Element Name'] = [double]$r.'Data Value'
}

$zoneOf = @{}
foreach ($z in $zones) {
    if (-not $zoneOf.ContainsKey($z.'O*NET-SOC Code')) { $zoneOf[$z.'O*NET-SOC Code'] = [int]$z.'Job Zone' }
}

# SOC 大类 -> 中文岗位族
$familyMap = @{
    '11' = '管理与行政'; '13' = '商务与财务'; '15' = '计算机与数学'; '17' = '建筑与工程'; '19' = '自然科学与社会科学'
    '21' = '社会服务'; '23' = '法律'; '25' = '教育与图书'; '27' = '艺术设计与传媒'; '29' = '医疗健康'
    '31' = '医疗辅助'; '33' = '安保与公共安全'; '35' = '餐饮服务'; '37' = '建筑与场地维护'; '39' = '个人服务'
    '41' = '销售'; '43' = '行政与办公支持'; '45' = '农林'; '47' = '建筑与采掘'; '49' = '安装与维修'
    '51' = '生产制造'; '53' = '运输与物流'; '55' = '军事'
}

$dims = 'Realistic', 'Investigative', 'Artistic', 'Social', 'Enterprising', 'Conventional'
$letterOf = @{ Realistic = 'R'; Investigative = 'I'; Artistic = 'A'; Social = 'S'; Enterprising = 'E'; Conventional = 'C' }

$list = New-Object System.Collections.ArrayList
foreach ($o in $occs) {
    $code = $o.'O*NET-SOC Code'
    # 只保留 Job Zone >= MinJobZone 的职业：Zone 4 通常要求本科，Zone 5 要求研究生
    $zone = $zoneOf[$code]
    if (-not $zone -or $zone -lt $MinJobZone) { continue }
    $s = $scores[$code]
    if (-not $s) { continue }

    # O*NET 用 1-7 分制，线性归一化到 0-1
    $norm = @{}
    foreach ($d in $dims) { $norm[$d] = [math]::Round((($s[$d] - 1) / 6), 2) }
    $top3 = ($dims | Sort-Object { -$norm[$_] }, { $dims.IndexOf($_) } |
        Select-Object -First 3 | ForEach-Object { $letterOf[$_] }) -join ''

    [void]$list.Add([ordered]@{
        code          = $code
        title         = $o.Title
        description   = $o.Description
        hollandCode   = $top3
        hollandScores = $norm
        jobZone       = $zone
        family        = if ($familyMap[$code.Substring(0, 2)]) { $familyMap[$code.Substring(0, 2)] } else { '其他' }
    })
}

[IO.File]::WriteAllText($OutFile, ($list | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("已生成候选 {0} 条（Job Zone >= {1}）-> {2}" -f $list.Count, $MinJobZone, $OutFile)

#requires -Version 7.0
param([string[]]$Targets,[switch]$CheckReceipts)
$ErrorActionPreference='Stop'
$repositoryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$versionLine=@(Select-String -LiteralPath (Join-Path $repositoryRoot 'gradle.properties') -Pattern '^modVersion=(.+)$')
if ($versionLine.Count -ne 1) { throw 'The root gradle.properties must define one modVersion.' }
$modVersion=$versionLine[0].Matches[0].Groups[1].Value.Trim()
function Get-SourceDigest {
    $sourceRoots=@((Join-Path $PSScriptRoot 'src'),(Join-Path $PSScriptRoot '../src'),
        (Join-Path $repositoryRoot 'core-common/src'))
    $files=@(Get-ChildItem -LiteralPath $sourceRoots -Recurse -File)
    $files+=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object Extension -In '.gradle','.json')
    $files+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gradle') -Recurse -File)
    $files+=@(Get-Item -LiteralPath (Join-Path $repositoryRoot 'gradle/wrapper/gradle-wrapper.jar'),
        (Join-Path $repositoryRoot 'gradle.properties'),
        (Join-Path $repositoryRoot 'gradle/wrapper/gradle-wrapper.properties'),
        (Join-Path $repositoryRoot 'gradlew'),(Join-Path $repositoryRoot 'gradlew.bat'))
    $rows=@($files | Sort-Object FullName -Unique | ForEach-Object {
        $relative=[IO.Path]::GetRelativePath($repositoryRoot,$_.FullName).Replace('\','/')
        $hash=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative $hash"
    })
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try {
        $digest=$algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes(($rows -join "`n")))
        return [BitConverter]::ToString($digest).Replace('-','').ToLowerInvariant()
    } finally { $algorithm.Dispose() }
}
$sourceDigest=Get-SourceDigest
$matrix=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'targets.json') | ConvertFrom-Json -AsHashtable
if (-not $Targets) { $Targets=@($matrix.Keys | Sort-Object) }
$report=Join-Path $PSScriptRoot 'build/target-results.json'
if ($CheckReceipts) {
    $receipts=@(Get-Content -Raw -LiteralPath $report | ConvertFrom-Json)
    foreach ($targetName in $Targets) {
        if (-not $matrix.ContainsKey($targetName)) { throw "Unknown target: $targetName" }
        $matches=@($receipts | Where-Object target -EQ $targetName)
        if ($matches.Count -ne 1) { throw "Expected one receipt for $targetName" }
        $receipt=$matches[0]
        if ($receipt.exitCode -ne 0 -or $receipt.sourceSha256 -ne $sourceDigest) {
            throw "Unsuccessful or stale source receipt: $targetName"
        }
        $artifact=Join-Path $PSScriptRoot "build/$targetName/libs/overhaul-$targetName-$modVersion.jar"
        $actual=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $receipt.sha256) { throw "Artifact differs from receipt: $targetName" }
    }
    Write-Output "Verified $($Targets.Count) current source and artifact receipts."
    return
}
$wrapper=Join-Path $PSScriptRoot '../../gradlew.bat'
if (-not $IsWindows) { $wrapper=Join-Path $PSScriptRoot '../../gradlew' }
$results=@()
foreach ($targetName in $Targets) {
    if (-not $matrix.ContainsKey($targetName)) { throw "Unknown target: $targetName" }
    & $wrapper -p $PSScriptRoot "-Ptarget=$targetName" build --no-configuration-cache --console=plain
    $exitCode=$LASTEXITCODE
    $artifact=Join-Path $PSScriptRoot "build/$targetName/libs/overhaul-$targetName-$modVersion.jar"
    $hash=$null
    if ($exitCode -eq 0) {
        if (-not (Test-Path -LiteralPath $artifact)) { throw "Missing packaged target: $artifact" }
        $hash=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ((Get-SourceDigest) -ne $sourceDigest) { throw 'Adapter inputs changed during verification; rerun the full matrix.' }
    $results+=@{target=$targetName;exitCode=$exitCode;sha256=$hash;artifact=$artifact;
        sourceSha256=$sourceDigest;verifiedAtUtc=[DateTime]::UtcNow.ToString('o')}
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $report -Encoding utf8
    if ($exitCode -ne 0) { throw "Target failed: $targetName; see build/target-results.json" }
}

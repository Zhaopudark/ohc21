#Requires -Version 5.1
<#
.SYNOPSIS
    给仓库打本地 git tag 并推送到 origin（供 GitHub Actions 的 release 发布流程触发）。

.DESCRIPTION
    用法：pwsh -File scripts/tag_and_push.ps1 0.9.3
    安全校验通过后创建 annotated tag 并推送（默认也推送当前分支）。
    推送后到 GitHub 用该 tag 创建 Release 即触发 .github/workflows/release.yml。

.PARAMETER Version
    版本号，例如 0.9.3。需与各 pom 的 <version> 一致，且不能是 SNAPSHOT。

.PARAMETER Force
    跳过“工作树干净 / 分支为 master-main / 版本匹配 pom”这三类安全闸。

.PARAMETER NoBranchPush
    只推 tag，不推当前分支。

.EXAMPLE
    pwsh -File scripts/tag_and_push.ps1 0.9.3 -WhatIf   # 预演，不改动
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Version,

    [switch]$Force,

    [switch]$NoBranchPush
)

$ErrorActionPreference = 'Stop'

function Invoke-Git {
    # 用数组接收 git 参数，避免把 -a/-m/--xxx 误当作 PowerShell 参数名
    param([Parameter(Mandatory = $true, Position = 0)][string[]]$ArgList)
    & git @ArgList
    if ($LASTEXITCODE -ne 0) {
        throw "git $($ArgList -join ' ') failed (exit=$LASTEXITCODE)"
    }
}

# 切到仓库根（脚本在 scripts/ 下）
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    # 1) 必须在 git 仓库内
    Invoke-Git @('rev-parse','--is-inside-work-tree') | Out-Null

    # 2) 版本格式校验
    if ($Version -notmatch '^\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.]+)?$') {
        throw "Invalid version '$Version' (expected e.g. 0.9.3 or 1.0.0-rc1)"
    }
    if ($Version -match 'SNAPSHOT') {
        throw "Refusing to tag a SNAPSHOT version: $Version"
    }

    # 3) 已跟踪文件是否干净
    $dirty = (git status --porcelain -uno)
    if ($dirty -and -not $Force) {
        throw "Working tree has uncommitted changes; commit first (or use -Force):`n$dirty"
    }

    # 4) 当前分支应为 master/main
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($branch -notin @('master','main') -and -not $Force) {
        throw "On branch '$branch' (not master/main); re-run with -Force if intentional"
    }

    # 5) 版本与根 pom <version> 一致性（排除 parent 块）
    $pom = Join-Path $repoRoot 'pom.xml'
    [xml]$xml = Get-Content $pom -Raw
    $pomVersion = $xml.project.version
    if ($pomVersion -ne $Version -and -not $Force) {
        throw "pom version ($pomVersion) != tag ($Version); bump the poms or use -Force"
    }

    # 6) tag 是否已存在（本地 / 远端）
    if ((git tag -l $Version)) {
        throw "Local tag '$Version' already exists (remove with: git tag -d $Version)"
    }
    $remoteTag = git ls-remote --tags origin "refs/tags/$Version" 2>$null
    if ($remoteTag) {
        throw "Remote tag '$Version' already exists; published versions cannot be overwritten"
    }

    # 7) 执行（受 -WhatIf 控制）
    if ($PSCmdlet.ShouldProcess("HEAD", "create annotated tag $Version")) {
        Invoke-Git @('tag','-a',$Version,'-m',"Release $Version")
        Write-Host "Created local tag: $Version" -ForegroundColor Green
    }

    if ($PSCmdlet.ShouldProcess("origin", "push tag $Version")) {
        if (-not $NoBranchPush) {
            Invoke-Git @('push','origin',$branch)
            Write-Host "Pushed branch: $branch" -ForegroundColor Green
        }
        Invoke-Git @('push','origin',"refs/tags/$Version")
        Write-Host "Pushed tag: $Version" -ForegroundColor Green
    }

    Write-Host ""
    Write-Host "Next: create the GitHub Release from this tag to trigger the publish workflow:" -ForegroundColor Cyan
    Write-Host "  web:  repo -> Releases -> Draft a release -> pick tag $Version -> Publish release"
    Write-Host "  gh:   gh release create $Version --title '$Version' --generate-notes"
}
finally {
    Pop-Location
}

<#
.SYNOPSIS
  Publishes BBS Voice: pushes the branches and tags, then creates the two GitHub Releases.

.DESCRIPTION
  This is the half of the release that needs your GitHub credential. A non-interactive shell cannot
  complete the Git credential prompt (the helper waits for a window nobody can click), and no token is
  stored in this repo - so run this from your own terminal, once.

  What it does, in order:
    1. preflight: jars + notes + tags exist, working tree is clean
    2. git push origin main / fabric-26.3 / the two release tags
    3. create the GitHub Releases from build/release/RELEASE-*.md and attach the jars
    4. verify by listing what the API sees

  Needs a token with 'repo' scope (classic) or 'Contents: write' (fine-grained).

.PARAMETER Token
  GitHub token. Defaults to $env:GITHUB_TOKEN.

.PARAMETER RemoveLegacy
  Also delete any release whose tag is not one of the two this repo ships. Use it to clear an older,
  broken release - e.g. one uploaded before the Edge endpoint migration was fixed.

.EXAMPLE
  $env:GITHUB_TOKEN = 'ghp_...'
  .\tools\Publish.ps1

.EXAMPLE
  .\tools\Publish.ps1 -RemoveLegacy
#>
param(
    [string] $Token = $env:GITHUB_TOKEN,
    [switch] $RemoveLegacy,
    [string] $Owner = 'minecraft-DYL',
    [string] $Repo = 'BBS_Voice'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$releases = @(
    @{ Tag = '1.0-1.20.4Fabric'; Jar = 'build/release/bbsvoice-1.0-1.20.4.jar'; Notes = 'docs/releases/1.0-1.20.4Fabric.md' },
    @{ Tag = '1.0-26.3Fabric';   Jar = 'build/release/bbsvoice-1.0-26.3.jar';   Notes = 'docs/releases/1.0-26.3Fabric.md' }
)

if (-not $Token) { throw "No token. Pass -Token or set `$env:GITHUB_TOKEN (needs 'repo' or 'Contents: write')." }

Write-Host '--- preflight ---' -ForegroundColor Cyan
foreach ($r in $releases) {
    foreach ($file in @($r.Jar, $r.Notes)) {
        if (-not (Test-Path $file)) { throw "missing $file - build the jars first (.\\gradlew build)" }
    }
    git rev-parse --verify --quiet "refs/tags/$($r.Tag)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "tag $($r.Tag) does not exist locally" }
    Write-Host "  ok: $($r.Tag)"
}
if (git status --porcelain) { throw 'working tree is dirty - commit before publishing' }

Write-Host '--- push ---' -ForegroundColor Cyan
git push origin main
git push origin fabric-26.3
git push origin ($releases.Tag)
if ($LASTEXITCODE -ne 0) { throw 'push failed (credentials?)' }

$api = "https://api.github.com/repos/$Owner/$Repo"
$headers = @{
    Authorization = "token $Token"
    Accept        = 'application/vnd.github+json'
    'User-Agent'  = 'bbs-voice-publish'
}

if ($RemoveLegacy) {
    Write-Host '--- remove legacy releases ---' -ForegroundColor Cyan
    $wanted = @($releases.Tag)
    foreach ($old in (Invoke-RestMethod -Uri "$api/releases?per_page=100" -Headers $headers)) {
        if ($wanted -notcontains $old.tag_name) {
            Write-Host "  deleting $($old.tag_name) ($($old.name))"
            Invoke-RestMethod -Uri "$api/releases/$($old.id)" -Method Delete -Headers $headers | Out-Null
        }
    }
}

Write-Host '--- releases ---' -ForegroundColor Cyan
foreach ($r in $releases) {
    $name = Split-Path $r.Jar -Leaf
    $json = @{
        tag_name   = $r.Tag
        name       = $r.Tag
        body       = (Get-Content $r.Notes -Raw -Encoding UTF8)
        draft      = $false
        prerelease = $false
    } | ConvertTo-Json
    $body = [Text.Encoding]::UTF8.GetBytes($json)

    $release = $null
    try { $release = Invoke-RestMethod -Uri "$api/releases/tags/$($r.Tag)" -Headers $headers } catch { $release = $null }

    if ($release) {
        Write-Host "  $($r.Tag): exists, updating body"
        $release = Invoke-RestMethod -Uri "$api/releases/$($release.id)" -Method Patch -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $body
        foreach ($asset in $release.assets) {
            if ($asset.name -eq $name) {
                Write-Host "    replacing asset $name"
                Invoke-RestMethod -Uri "$api/releases/assets/$($asset.id)" -Method Delete -Headers $headers | Out-Null
            }
        }
    }
    else {
        Write-Host "  $($r.Tag): creating"
        $release = Invoke-RestMethod -Uri "$api/releases" -Method Post -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $body
    }

    $upload = $release.upload_url -replace '\{.*\}$', ''
    Invoke-RestMethod -Uri "$upload?name=$name" -Method Post -InFile $r.Jar `
        -Headers @{ Authorization = "token $Token"; 'User-Agent' = 'bbs-voice-publish'; 'Content-Type' = 'application/java-archive' } | Out-Null

    Write-Host "    $name uploaded" -ForegroundColor Green
    Write-Host "    $($release.html_url)"
}

Write-Host '--- what GitHub sees now ---' -ForegroundColor Cyan
foreach ($check in (Invoke-RestMethod -Uri "$api/releases?per_page=100" -Headers $headers)) {
    $assets = ($check.assets | ForEach-Object { $_.name }) -join ', '
    Write-Host "  $($check.tag_name) | $($check.name) | assets: $assets"
}
foreach ($head in (git ls-remote --heads origin)) { Write-Host "  ref: $head" }

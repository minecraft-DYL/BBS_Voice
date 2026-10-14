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
       (the tags go out with --force-with-lease: they are re-pointed whenever a release is replaced
       before anyone could have downloaded it, and a plain push is rejected as clobbering)
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

# git reports progress on stderr ("Everything up-to-date", "To https://github.com/..."), and PowerShell
# turns a native command's stderr into an ErrorRecord. With the Stop preference this script relies on
# everywhere else, that aborts the run on a *successful* push - the first release attempt died at
# "git push origin main" while main was simply already up to date. So the preference is relaxed for the
# git calls and $LASTEXITCODE is checked explicitly; the strict behaviour resumes right after.
$strictPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

git push origin main
git push origin fabric-26.3

if ($LASTEXITCODE -ne 0) { throw 'push failed (credentials?)' }

# The release tags are re-pointed whenever a release gets replaced before anyone could have downloaded
# it - that already happened once, when the voice catalog feature landed after the first tagging. So a
# plain push is rejected as clobbering; --force-with-lease is the safe form (it still fails if somebody
# else moved the tag), with --force only for a clone that has no remote-tracking value to compare to.
foreach ($r in $releases) {
    git push --force-with-lease origin ($r.Tag)

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  --force-with-lease unavailable, falling back to --force for $($r.Tag)" -ForegroundColor Yellow
        git push --force origin ($r.Tag)
    }

    if ($LASTEXITCODE -ne 0) { throw "push of tag $($r.Tag) failed" }
}

$ErrorActionPreference = $strictPreference

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
        # [IO.File]::ReadAllText, not Get-Content -Raw: the latter hands back a string carrying the
        # provider's note properties (PSPath, PSDrive, ReadCount...), and ConvertTo-Json then serialises
        # the body as an object - GitHub answers 422 "For 'properties/body', {...} is not a string".
        # The absolute path matters too: Set-Location does not move the process's current directory,
        # which is the one .NET resolves relative paths against.
        body       = [IO.File]::ReadAllText((Join-Path $root $r.Notes), [Text.Encoding]::UTF8)
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

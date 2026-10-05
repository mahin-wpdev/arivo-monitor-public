$ErrorActionPreference = 'Stop'
$sourceRoot = (Get-Location).Path
$publicRepo = 'mahin-wpdev/arivo-monitor-public'
$version = $env:VERSION_NAME
$build = $env:BUILD_NUMBER
if ($version -notmatch '^\d+\.\d+\.\d+$' -or $build -notmatch '^\d+$') { throw 'Release version/build missing' }
$apk = $env:ARIVO_RELEASE_APK
if (-not $apk) { $apk = Join-Path $sourceRoot 'build\app\outputs\flutter-apk\app-release.apk' }
$runtime = $env:ARIVO_SOURCE_CONFIG
$serverEnv = $env:ARIVO_SERVER_ENV
python tools/verify-public-apk.py --apk $apk --runtime-config $runtime --server-env $serverEnv
if ($LASTEXITCODE -ne 0) { throw 'Public APK credential scan failed' }
$secrets = @()
foreach ($file in @($runtime, $serverEnv)) {
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        if ($line -match '^\s*([^#=]+)=(.*)$') {
            $field = $Matches[1]; $value = $Matches[2].Trim().Trim('"').Trim("'")
            if ($field -match '(?i)PASSWORD|DEVICE_KEY|TOKEN|SECRET' -and $value.Length -ge 8) { $secrets += $value }
        }
    }
}
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('arivo-public-release-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$archive = Join-Path $temporaryRoot 'source.zip'
$exportRoot = Join-Path $temporaryRoot 'export'
$publicRoot = Join-Path $temporaryRoot 'repository'
git archive HEAD --format=zip "--output=$archive"
if ($LASTEXITCODE -ne 0) { throw 'Source export failed' }
Expand-Archive -LiteralPath $archive -DestinationPath $exportRoot
$files = @(Get-ChildItem -LiteralPath $exportRoot -File -Recurse | Where-Object { $_.FullName.Substring($exportRoot.Length + 1) -notlike '.github\*' })
foreach ($file in $files) {
    $relative = $file.FullName.Substring($exportRoot.Length + 1)
    if ($relative -match '(?i)(^|\\)(\.env|local\.properties|key\.properties)$|\.(apk|jks|keystore|pem)$') { throw 'Private file found in source export' }
    $content = [IO.File]::ReadAllText($file.FullName)
    if ($content -match '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}') { throw 'Credential pattern found in source export' }
    foreach ($secret in $secrets) { if ($content.Contains($secret)) { throw 'Production credential found in source export' } }
}
git clone --single-branch --branch master "https://github.com/$publicRepo.git" $publicRoot
if ($LASTEXITCODE -ne 0) { throw 'Public repository clone failed' }
$resolvedPublicRoot = [IO.Path]::GetFullPath($publicRoot)
$expectedRoot = [IO.Path]::GetFullPath($temporaryRoot).TrimEnd('\') + '\'
if (-not $resolvedPublicRoot.StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath (Join-Path $publicRoot '.git'))) { throw 'Public snapshot path validation failed' }
git -C $publicRoot rm -r --ignore-unmatch . | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Public snapshot preparation failed' }
foreach ($file in $files) {
    $relative = $file.FullName.Substring($exportRoot.Length + 1)
    $target = Join-Path $publicRoot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target -Force
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
$readme = Join-Path $publicRoot 'README.md'
$notice = "> Public source and credential-free Android APK releases. Download the latest APK from [Releases](https://github.com/$publicRepo/releases/latest). Connect once through your dashboard's Devices page. Mobile UI is unchanged. See [public APK setup and auto-updates](docs/PUBLIC-APK.md).`n`n"
[IO.File]::WriteAllText($readme, $notice + [IO.File]::ReadAllText($readme), $utf8)
$pubspec = Join-Path $publicRoot 'pubspec.yaml'
$versionText = [IO.File]::ReadAllText($pubspec) -replace '(?m)^version:\s*.*$', "version: $version+$build"
[IO.File]::WriteAllText($pubspec, $versionText, $utf8)
[IO.File]::AppendAllText((Join-Path $publicRoot '.gitignore'), "`n.github/workflows/`n*.apk`n*.jks`n*.keystore`nandroid/key.properties`n", $utf8)
$author = (git log -1 --format=%an).Trim()
$email = (git log -1 --format=%ae).Trim()
git -C $publicRoot add .
git -C $publicRoot diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'Public snapshot diff validation failed' }
git -C $publicRoot diff --cached --quiet
if ($LASTEXITCODE -eq 1) {
    git -c "user.name=$author" -c "user.email=$email" -C $publicRoot commit -m "Publish Arivo $version+$build source snapshot"
    if ($LASTEXITCODE -ne 0) { throw 'Public snapshot commit failed' }
    git -C $publicRoot push origin master
    if ($LASTEXITCODE -ne 0) { throw 'Public snapshot push failed' }
} elseif ($LASTEXITCODE -ne 0) { throw 'Public snapshot status failed' }
$tag = "v$version-build$build"
$asset = "$apk#Arivo-$version-$build.apk"
$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
gh release view $tag --repo $publicRepo *> $null
$releaseExists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $savedErrorPreference
if ($releaseExists) {
    gh release upload $tag $asset --repo $publicRepo --clobber
} else {
    gh release create $tag $asset --repo $publicRepo --target master --title "Arivo $version+$build" --notes "$env:RELEASE_NOTES"
}
if ($LASTEXITCODE -ne 0) { throw 'Public APK release failed' }
gh repo edit $publicRepo --description 'Arivo Monitor source and credential-free Android APK releases with self-hosted automatic updates.'
if ($LASTEXITCODE -ne 0) { throw 'Public repository description update failed' }
Write-Output "Public APK release published: https://github.com/$publicRepo/releases/tag/$tag"

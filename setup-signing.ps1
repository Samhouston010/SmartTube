# One-time setup: give GitHub Actions the same signing key the TV's SmartTube is signed with,
# then push the fixed auto-update workflow. Run from C:\website\SmartTube in PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-signing.ps1
$ErrorActionPreference = "Stop"
$Repo = "Samhouston010/SmartTube"
$Keystore = "$env:USERPROFILE\.android\debug.keystore"
$Expected = "7D:C0:6A:2A:53:12:DD:76:D0:9E:24:61:17:B2:CB:94:55:E6:48:E1:B1:96:3E:F7:9D:17:38:35:C0:11:79:25"

if (-not (Test-Path $Keystore)) { throw "Not found: $Keystore" }

# Check it really is the key the installed builds were signed with
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    $cand = @("$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe") + (Get-ChildItem "$env:USERPROFILE\.jdks" -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $keytool = $cand | Where-Object { Test-Path $_ } | Select-Object -First 1
} else { $keytool = $keytool.Source }
if ($keytool) {
    $sha = (& $keytool -list -v -keystore $Keystore -storepass android -alias androiddebugkey) | Select-String "SHA256:" | Select-Object -First 1
    Write-Host "key fingerprint: $sha"
    if ("$sha" -notmatch [regex]::Escape($Expected)) {
        throw "This debug.keystore is NOT the key SmartTube on the TV is signed with. Stopping."
    }
    Write-Host "OK: matches the key of the build on the TV" -ForegroundColor Green
} else {
    Write-Warning "keytool not found -- skipping fingerprint check (the workflow checks it again before publishing)"
}

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Keystore))

if (Get-Command gh -ErrorAction SilentlyContinue) {
    gh secret set SIGNING_KEY        -R $Repo -b $b64
    gh secret set KEY_STORE_PASSWORD -R $Repo -b "android"
    gh secret set ALIAS              -R $Repo -b "androiddebugkey"
    gh secret set KEY_PASSWORD       -R $Repo -b "android"
    Write-Host "GitHub secrets set." -ForegroundColor Green
} else {
    Set-Clipboard $b64
    Write-Host "gh CLI not installed. Add these 4 secrets by hand at:" -ForegroundColor Yellow
    Write-Host "  https://github.com/$Repo/settings/secrets/actions"
    Write-Host "  SIGNING_KEY        = (already copied to your clipboard -- just paste)"
    Write-Host "  KEY_STORE_PASSWORD = android"
    Write-Host "  ALIAS              = androiddebugkey"
    Write-Host "  KEY_PASSWORD       = android"
    Read-Host "Press Enter once they are added"
}

# .github is write-protected for Claude, so the fixed workflow was left in patches/ -- move it into place
Copy-Item patches\auto-update.yml .github\workflows\auto-update.yml -Force
Remove-Item patches\auto-update.yml
Remove-Item patches\apply-extra-fixes.py -ErrorAction SilentlyContinue
git add .github/workflows/auto-update.yml patches/apply-patch.py patches/TranslatingDataSource.java setup-signing.ps1
git commit -m "auto-update: sign every build with the TV's key, verify signature, anchor-based patch instead of cherry-pick, track-restore fix, mark release latest"
git push origin master

if (Get-Command gh -ErrorAction SilentlyContinue) {
    # Rebuild 32.68 now with the right key, so the TV moves onto the auto-update track right away
    gh workflow run auto-update.yml -R $Repo -f force=true -f fresh=true
    Write-Host "Workflow started: https://github.com/$Repo/actions" -ForegroundColor Green
} else {
    Write-Host "Now open https://github.com/$Repo/actions/workflows/auto-update.yml -> Run workflow, tick 'force' and 'fresh'."
}

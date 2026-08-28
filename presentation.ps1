[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('prepare', 'verify', 'pulse', 'credentials', 'reset', 'test')]
    [string]$Command = 'prepare',
    [Parameter(Position = 1)]
    [ValidateSet('quick', 'regression')]
    [string]$TestProfile = 'quick'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ComposeFile = Join-Path $ProjectRoot 'docker-compose.presentation.yml'
$EnvironmentFile = Join-Path $ProjectRoot '.presentation.env'
$ArtifactDirectory = Join-Path $ProjectRoot '.presentation-artifacts'
$SourceDatabase = Join-Path $ProjectRoot 'magti_portal.db'
$SourceUploads = Join-Path $ProjectRoot 'uploads'
$ExpectedDatabaseHash = '299248EF1859799BC372932173BEE0182411391104064FFEABEB770DAD61B5D8'
$PresentationVolume = 'magti-portal-presentation-oracle-data'
$QaSchemaCreate = Join-Path $ProjectRoot 'scripts\presentation\qa_schema_create.sql'
$QaSchemaDrop = Join-Path $ProjectRoot 'scripts\presentation\qa_schema_drop.sql'
$DockerCommand = $null

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Ensure-EnvironmentFile {
    if (Test-Path -LiteralPath $EnvironmentFile) {
        return
    }
    $lines = @(
        'PRESENTATION_SYS_PASSWORD=local_only_presentation_sys_pw',
        'PRESENTATION_DB_PASSWORD=local_only_presentation_app_pw',
        'PRESENTATION_DEMO_PASSWORD=MagtiDemo2026!',
        'PRESENTATION_SEED_CONFIRM=LOCAL_ONLY_MAGTI_PRESENTATION_V1',
        'PRESENTATION_JWT_SECRET=magti-presentation-local-only-jwt-key-2026-DO-NOT-DEPLOY'
    )
    [System.IO.File]::WriteAllLines(
        $EnvironmentFile,
        $lines,
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Host "Created gitignored local environment: $EnvironmentFile"
}

function Resolve-DockerCommand {
    $installedCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($installedCommand) {
        $script:DockerCommand = $installedCommand.Source
        return
    }

    $dockerDesktopCommand = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $dockerDesktopCommand -PathType Leaf) {
        $dockerDesktopBin = Split-Path -Parent $dockerDesktopCommand
        if (($env:Path -split ';') -notcontains $dockerDesktopBin) {
            $env:Path = "$dockerDesktopBin;$env:Path"
        }
        $script:DockerCommand = $dockerDesktopCommand
        return
    }

    throw 'Docker CLI was not found. Install/start Docker Desktop and retry.'
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $DockerCommand compose --env-file $EnvironmentFile --file $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE"
    }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $ProjectRoot
    )
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
        }
    }
    finally {
        Pop-Location
    }
}

function Read-PresentationEnvironment {
    Ensure-EnvironmentFile
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -eq 2) {
            $values[$parts[0]] = $parts[1]
        }
    }
    return $values
}

function Set-TemporaryEnvironmentValue {
    param([string]$Name, [AllowNull()][string]$Value)
    $previous = [Environment]::GetEnvironmentVariable($Name, 'Process')
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    return $previous
}

function Restore-EnvironmentValue {
    param([string]$Name, [AllowNull()][string]$Value)
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Assert-Preflight {
    Write-Step 'Preflight checks'
    Resolve-DockerCommand
    & $DockerCommand info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop is not running or the Docker daemon is unavailable.'
    }
    if (-not (Test-Path -LiteralPath $SourceDatabase -PathType Leaf)) {
        throw "Missing source database: $SourceDatabase"
    }
    if (-not (Test-Path -LiteralPath $SourceUploads -PathType Container)) {
        throw "Missing source uploads directory: $SourceUploads"
    }
    $actualHash = (Get-FileHash -LiteralPath $SourceDatabase -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedDatabaseHash) {
        throw "Source database checksum differs from the approved manifest. Expected $ExpectedDatabaseHash, got $actualHash"
    }
    Ensure-EnvironmentFile
    New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null
    Write-Host 'Source checksum and local prerequisites are valid.' -ForegroundColor Green
}

function Show-Credentials {
    $rows = @(
        [pscustomobject]@{ Account = 'admin@magti.ge'; Role = 'System admin'; Password = 'MagtiDemo2026!' },
        [pscustomobject]@{ Account = 'content@magti.ge'; Role = 'Content admin'; Password = 'MagtiDemo2026!' },
        [pscustomobject]@{ Account = 'manager@magti.ge'; Role = 'Group/department leader'; Password = 'MagtiDemo2026!' },
        [pscustomobject]@{ Account = 'info@magti.ge'; Role = 'Information operator'; Password = 'MagtiDemo2026!' },
        [pscustomobject]@{ Account = 'tech@magti.ge'; Role = 'Technical operator'; Password = 'MagtiDemo2026!' },
        [pscustomobject]@{ Account = 'nino@magti.ge'; Role = 'Office operator'; Password = 'MagtiDemo2026!' }
    )
    $rows | Format-Table -AutoSize
    Write-Host 'Local-only URL: http://127.0.0.1:8081'
}

function Prepare-Presentation {
    param([switch]$HideCredentials)
    Assert-Preflight
    Write-Step 'Starting isolated Oracle and Spring Boot services'
    Invoke-Compose up --detach --build --wait oracle backend
    Write-Step 'Validating and loading the approved baseline'
    Invoke-Compose run --rm --build seeder seed
    Write-Step 'Starting Angular frontend on 127.0.0.1:8081'
    Invoke-Compose up --detach --build --wait frontend
    Write-Step 'Running database, API, credential, scope, image and audit verification'
    Invoke-Compose run --rm seeder verify
    Write-Host "`nPresentation environment is ready: http://127.0.0.1:8081" -ForegroundColor Green
    if (-not $HideCredentials) {
        Show-Credentials
    }
}

function Test-PresentationReachable {
    try {
        $frontend = Invoke-WebRequest -Uri 'http://127.0.0.1:8081/' -UseBasicParsing -TimeoutSec 15
        $health = Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/health' -UseBasicParsing -TimeoutSec 15
        return $frontend.StatusCode -eq 200 -and $health.StatusCode -eq 200
    }
    catch {
        return $false
    }
}

function Ensure-PresentationForTests {
    Assert-Preflight
    if (-not (Test-PresentationReachable)) {
        Write-Host 'Presentation stack is not reachable; preparing it now.' -ForegroundColor Yellow
        Prepare-Presentation -HideCredentials
    }
    if (-not (Test-PresentationReachable)) {
        throw 'Presentation frontend or proxied backend health endpoint is not reachable.'
    }
}

function Remove-PresentationDataAndRebuild {
    Write-Step 'Restoring the isolated presentation baseline'
    Invoke-Compose --profile tools down --volumes --remove-orphans
    & $DockerCommand volume inspect $PresentationVolume *> $null
    if ($LASTEXITCODE -eq 0) {
        throw "Scoped volume still exists after reset: $PresentationVolume"
    }
    Prepare-Presentation -HideCredentials
}

function New-TestContext([string]$Profile) {
    $runId = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$Profile"
    $directory = Join-Path $ArtifactDirectory "tests\$runId"
    $logs = Join-Path $directory 'logs'
    New-Item -ItemType Directory -Path $logs -Force | Out-Null
    return [pscustomobject]@{
        RunId = $runId
        Profile = $Profile
        Directory = $directory
        Logs = $logs
        StartedAt = [DateTimeOffset]::Now
        Results = [System.Collections.Generic.List[object]]::new()
    }
}

function Invoke-TestStep {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )
    $slug = ($Name.ToLowerInvariant() -replace '[^a-z0-9]+', '-').Trim('-')
    $log = Join-Path $Context.Logs "$slug.log"
    $started = [DateTimeOffset]::Now
    $status = 'passed'
    $detail = ''
    Write-Step "TEST: $Name"
    try {
        & $Action *>&1 | Tee-Object -FilePath $log | Out-Host
    }
    catch {
        $status = 'failed'
        $detail = $_.Exception.Message
        ($_ | Out-String) | Add-Content -LiteralPath $log -Encoding UTF8
        Write-Host "FAILED: $Name — $detail" -ForegroundColor Red
    }
    $finished = [DateTimeOffset]::Now
    $Context.Results.Add([pscustomobject]@{
        name = $Name
        status = $status
        duration_seconds = [math]::Round(($finished - $started).TotalSeconds, 2)
        detail = $detail
        log = $log
    })
    return $status -eq 'passed'
}

function Write-TestReport {
    param([Parameter(Mandatory = $true)]$Context)
    $finished = [DateTimeOffset]::Now
    $passed = @($Context.Results | Where-Object status -eq 'passed').Count
    $failed = @($Context.Results | Where-Object status -eq 'failed').Count
    $report = [ordered]@{
        run_id = $Context.RunId
        profile = $Context.Profile
        started_at = $Context.StartedAt.ToString('o')
        finished_at = $finished.ToString('o')
        duration_seconds = [math]::Round(($finished - $Context.StartedAt).TotalSeconds, 2)
        passed = $passed
        failed = $failed
        status = if ($failed -eq 0) { 'passed' } else { 'failed' }
        steps = @($Context.Results)
    }
    $jsonPath = Join-Path $Context.Directory 'results.json'
    [System.IO.File]::WriteAllText(
        $jsonPath,
        ($report | ConvertTo-Json -Depth 8),
        [System.Text.UTF8Encoding]::new($false)
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Magti Portal local QA — $($Context.Profile)")
    $lines.Add('')
    $lines.Add("- Run ID: ``$($Context.RunId)``")
    $lines.Add("- Status: **$($report.status.ToUpperInvariant())**")
    $lines.Add("- Passed steps: $passed")
    $lines.Add("- Failed steps: $failed")
    $lines.Add("- Duration: $($report.duration_seconds) seconds")
    $lines.Add('')
    $lines.Add('| Step | Status | Seconds |')
    $lines.Add('|---|---:|---:|')
    foreach ($result in $Context.Results) {
        $lines.Add("| $($result.name) | $($result.status) | $($result.duration_seconds) |")
    }
    $failures = @($Context.Results | Where-Object status -eq 'failed')
    if ($failures.Count -gt 0) {
        $lines.Add('')
        $lines.Add('## Findings')
        $lines.Add('')
        foreach ($failure in $failures) {
            $lines.Add("- **HIGH** — $($failure.name): $($failure.detail). Log: ``$($failure.log)``")
        }
    }
    $summaryPath = Join-Path $Context.Directory 'summary.md'
    [System.IO.File]::WriteAllLines($summaryPath, $lines, [System.Text.UTF8Encoding]::new($false))
    Write-Host "`nQA report: $summaryPath" -ForegroundColor Cyan
    return $failed -eq 0
}

function Invoke-PresentationPythonTests {
    Invoke-Compose run --rm --build qa-python
}

function Invoke-JavaUnitTests {
    $wrapper = Join-Path $ProjectRoot 'java-backend\mvnw.cmd'
    Invoke-NativeChecked -FilePath $wrapper -Arguments @('-B', 'test', '-DexcludedGroups=oracle') `
        -WorkingDirectory (Join-Path $ProjectRoot 'java-backend')
}

function Invoke-AngularUnitTests {
    $ng = Join-Path $ProjectRoot 'angular-frontend\node_modules\.bin\ng.cmd'
    if (-not (Test-Path -LiteralPath $ng -PathType Leaf)) {
        throw 'Angular dependencies are missing. Run npm ci in angular-frontend.'
    }
    Invoke-NativeChecked -FilePath $ng -Arguments @('test', '--watch=false') `
        -WorkingDirectory (Join-Path $ProjectRoot 'angular-frontend')
}

function Invoke-AngularBuild {
    $ng = Join-Path $ProjectRoot 'angular-frontend\node_modules\.bin\ng.cmd'
    Invoke-NativeChecked -FilePath $ng -Arguments @('build', '--configuration', 'production') `
        -WorkingDirectory (Join-Path $ProjectRoot 'angular-frontend')
}

function Get-OracleContainerId {
    $containerId = & $DockerCommand compose --env-file $EnvironmentFile --file $ComposeFile ps -q oracle
    if ($LASTEXITCODE -ne 0 -or -not $containerId) {
        throw 'Could not resolve the isolated presentation Oracle container.'
    }
    return ($containerId | Select-Object -First 1).Trim()
}

function Invoke-OracleSqlFile {
    param([Parameter(Mandatory = $true)][string]$ContainerId, [Parameter(Mandatory = $true)][string]$Path)
    $remote = "/tmp/$([System.IO.Path]::GetFileName($Path))"
    & $DockerCommand cp $Path "${ContainerId}:$remote"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy QA SQL into presentation Oracle: $Path"
    }
    & $DockerCommand exec $ContainerId bash -lc "sqlplus -s `"sys/`${ORACLE_PASSWORD}@XEPDB1 as sysdba`" @$remote"
    if ($LASTEXITCODE -ne 0) {
        throw "Oracle QA SQL failed: $Path"
    }
}

function Invoke-JavaOracleIntegrationTests {
    Invoke-Compose up --detach --wait oracle
    $oracleId = Get-OracleContainerId
    Invoke-OracleSqlFile -ContainerId $oracleId -Path $QaSchemaCreate

    $oldUrl = Set-TemporaryEnvironmentValue 'ORACLE_DB_URL' 'jdbc:oracle:thin:@127.0.0.1:1523/XEPDB1'
    $oldUser = Set-TemporaryEnvironmentValue 'ORACLE_DB_USER' 'MAGTI_QA'
    $oldPassword = Set-TemporaryEnvironmentValue 'ORACLE_DB_PASSWORD' 'local_only_magti_qa_pw'
    try {
        $wrapper = Join-Path $ProjectRoot 'java-backend\mvnw.cmd'
        Invoke-NativeChecked -FilePath $wrapper -Arguments @('-B', 'test', '-Dgroups=oracle') `
            -WorkingDirectory (Join-Path $ProjectRoot 'java-backend')
    }
    finally {
        Restore-EnvironmentValue 'ORACLE_DB_URL' $oldUrl
        Restore-EnvironmentValue 'ORACLE_DB_USER' $oldUser
        Restore-EnvironmentValue 'ORACLE_DB_PASSWORD' $oldPassword
        Invoke-OracleSqlFile -ContainerId $oracleId -Path $QaSchemaDrop
        Invoke-Compose up --detach --wait backend frontend
    }
}

function Invoke-RuntimeAccessMatrix {
    param([Parameter(Mandatory = $true)]$Context)
    $oldRunId = Set-TemporaryEnvironmentValue 'PRESENTATION_QA_RUN_ID' $Context.RunId
    try {
        Invoke-Compose run --rm --build qa
    }
    finally {
        Restore-EnvironmentValue 'PRESENTATION_QA_RUN_ID' $oldRunId
    }
}

function Invoke-PlaywrightRegression {
    param([Parameter(Mandatory = $true)]$Context)
    $values = Read-PresentationEnvironment
    $reportDirectory = Join-Path $Context.Directory 'playwright-report'
    $oldBase = Set-TemporaryEnvironmentValue 'E2E_BASE_URL' 'http://127.0.0.1:8081'
    $oldPassword = Set-TemporaryEnvironmentValue 'E2E_PASSWORD' $values['PRESENTATION_DEMO_PASSWORD']
    $oldProvision = Set-TemporaryEnvironmentValue 'E2E_AUTO_PROVISION' 'true'
    $oldReport = Set-TemporaryEnvironmentValue 'PLAYWRIGHT_REPORT_DIR' $reportDirectory
    try {
        $playwright = Join-Path $ProjectRoot 'angular-frontend\node_modules\.bin\playwright.cmd'
        $specs = @(
            'e2e/presentation-personas.spec.ts',
            'e2e/department-visibility.spec.ts',
            'e2e/operator-browsing.spec.ts',
            'e2e/quiz-gate.spec.ts',
            'e2e/reading-and-history.spec.ts',
            'e2e/admin-article-history.spec.ts',
            'e2e/admin-categories.spec.ts',
            'e2e/shell-and-stats.spec.ts'
        )
        $presentationFlows = 'presentation personas|operators in different departments|operator browsing|mandatory reading with a quiz|reading and version history|article history:|categories:|videos: search narrows|a category page comes back'
        Invoke-NativeChecked -FilePath $playwright -Arguments (@('test') + $specs + @('--grep', $presentationFlows)) `
            -WorkingDirectory (Join-Path $ProjectRoot 'angular-frontend')
    }
    finally {
        Restore-EnvironmentValue 'E2E_BASE_URL' $oldBase
        Restore-EnvironmentValue 'E2E_PASSWORD' $oldPassword
        Restore-EnvironmentValue 'E2E_AUTO_PROVISION' $oldProvision
        Restore-EnvironmentValue 'PLAYWRIGHT_REPORT_DIR' $oldReport
    }
}

function Install-PlaywrightChromium {
    $playwright = Join-Path $ProjectRoot 'angular-frontend\node_modules\.bin\playwright.cmd'
    if (-not (Test-Path -LiteralPath $playwright -PathType Leaf)) {
        throw 'Playwright dependencies are missing. Run npm ci in angular-frontend.'
    }
    # This is idempotent: Playwright reuses the versioned browser cache when
    # the exact Chromium revision required by package-lock.json is present.
    Invoke-NativeChecked -FilePath $playwright -Arguments @('install', 'chromium') `
        -WorkingDirectory (Join-Path $ProjectRoot 'angular-frontend')
}

function Invoke-TestProfile {
    param([Parameter(Mandatory = $true)][ValidateSet('quick', 'regression')][string]$Profile)
    Resolve-DockerCommand
    Ensure-EnvironmentFile
    New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null
    $context = New-TestContext $Profile
    $allPassed = $true

    $allPassed = (Invoke-TestStep $context 'Environment and health preflight' { Ensure-PresentationForTests }) -and $allPassed
    $allPassed = (Invoke-TestStep $context 'Presentation Python safety tests' { Invoke-PresentationPythonTests }) -and $allPassed
    $allPassed = (Invoke-TestStep $context 'Java DB-free unit tests' { Invoke-JavaUnitTests }) -and $allPassed
    $allPassed = (Invoke-TestStep $context 'Angular unit tests' { Invoke-AngularUnitTests }) -and $allPassed
    $allPassed = (Invoke-TestStep $context 'Angular production build' { Invoke-AngularBuild }) -and $allPassed

    if ($Profile -eq 'regression' -and $allPassed) {
        $allPassed = (Invoke-TestStep $context 'Presentation database and API verifier' {
            Invoke-Compose run --rm --build seeder verify
        }) -and $allPassed
        $allPassed = (Invoke-TestStep $context 'Runtime RBAC and scope matrix' {
            Invoke-RuntimeAccessMatrix $context
        }) -and $allPassed
        $allPassed = (Invoke-TestStep $context 'Java Oracle integration tests in isolated QA schema' {
            Invoke-JavaOracleIntegrationTests
        }) -and $allPassed
        $allPassed = (Invoke-TestStep $context 'Playwright Chromium dependency' {
            Install-PlaywrightChromium
        }) -and $allPassed
        $allPassed = (Invoke-TestStep $context 'Selected Playwright presentation persona regression' {
            Invoke-PlaywrightRegression $context
        }) -and $allPassed
        if ($allPassed) {
            $allPassed = (Invoke-TestStep $context 'Restore and verify clean presentation baseline' {
                Remove-PresentationDataAndRebuild
            }) -and $allPassed
        }
        else {
            Write-Host 'Regression failed; presentation data is preserved for investigation.' -ForegroundColor Yellow
        }
    }

    $reportPassed = Write-TestReport $context
    if (-not $allPassed -or -not $reportPassed) {
        throw "Local QA profile '$Profile' failed. See $($context.Directory)"
    }
    Write-Host "Local QA profile '$Profile' passed." -ForegroundColor Green
}

switch ($Command) {
    'prepare' {
        Prepare-Presentation
    }
    'verify' {
        Assert-Preflight
        Write-Step 'Verifying presentation environment'
        Invoke-Compose run --rm --build seeder verify
    }
    'pulse' {
        Assert-Preflight
        Write-Step 'Running a 24-operator API activity pulse'
        Invoke-Compose run --rm --build pulse
    }
    'credentials' {
        Show-Credentials
    }
    'reset' {
        Assert-Preflight
        Write-Host "This will delete only Docker volume '$PresentationVolume' and rebuild the presentation stack." -ForegroundColor Yellow
        $confirmation = Read-Host "Type RESET to continue"
        if ($confirmation -cne 'RESET') {
            Write-Host 'Reset cancelled; no data was changed.'
            exit 0
        }
        Write-Step 'Removing the isolated presentation project and volume'
        Invoke-Compose --profile tools down --volumes --remove-orphans
        & $DockerCommand volume inspect $PresentationVolume *> $null
        if ($LASTEXITCODE -eq 0) {
            throw "Scoped volume still exists after reset: $PresentationVolume"
        }
        Write-Host "Removed isolated volume '$PresentationVolume'. Rebuilding now." -ForegroundColor Green
        Prepare-Presentation
    }
    'test' {
        Invoke-TestProfile $TestProfile
    }
}

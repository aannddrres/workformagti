[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('prepare', 'credentials', 'status', 'reset')]
    [string]$Command = 'prepare',
    # reset destroys the UAT database. Without this it asks first; with it,
    # it does not. Scripted callers pass it, humans should not.
    [switch]$Force
)

# Laptop-only UAT stack for the WS6-02 acceptance run. A trimmed sibling of
# presentation.ps1 -- same shape, but this one has no verification or QA
# subcommands, because the whole point of UAT is that a person does the
# verifying.
#
#   ./uat.ps1 prepare      bring the stack up and seed it (first run: minutes)
#   ./uat.ps1 credentials  print the eleven accounts and the URL
#   ./uat.ps1 status       show what is running
#   ./uat.ps1 reset        destroy the UAT database and rebuild from scratch

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ComposeFile = Join-Path $ProjectRoot 'docker-compose.uat.yml'
$EnvironmentFile = Join-Path $ProjectRoot '.uat.env'
$ArtifactDirectory = Join-Path $ProjectRoot '.uat-artifacts'
$UatVolume = 'magti-portal-uat-oracle-data'
$PortalUrl = 'http://127.0.0.1:8082'
$DockerCommand = $null

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Ensure-EnvironmentFile {
    if (Test-Path -LiteralPath $EnvironmentFile) {
        return
    }
    # Fixed local-only values rather than generated ones: the stack listens on
    # 127.0.0.1 only, and a stable file means a rebuild does not invalidate the
    # database that is already on disk.
    $lines = @(
        'UAT_SYS_PASSWORD=local_only_uat_sys_pw',
        'UAT_DB_PASSWORD=local_only_uat_app_pw',
        # Must be exactly this string: the shared seeder refuses to run
        # against any other value (scripts/presentation/common.py), which is
        # one of the guards that keeps it pointed at a throwaway database.
        # The value itself is inert here -- dev login accepts any password.
        'UAT_DEMO_PASSWORD=MagtiDemo2026!',
        'UAT_JWT_SECRET=magti-uat-local-only-jwt-key-2026-DO-NOT-DEPLOY'
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

function Assert-Preflight {
    Write-Step 'Preflight'
    Resolve-DockerCommand
    & $DockerCommand info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop is not running or the Docker daemon is unavailable.'
    }
    $sourceDatabase = Join-Path $ProjectRoot 'magti_portal.db'
    if (-not (Test-Path -LiteralPath $sourceDatabase -PathType Leaf)) {
        throw "Missing source database: $sourceDatabase"
    }
    Ensure-EnvironmentFile
    New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null
    Write-Host 'Prerequisites are in place.' -ForegroundColor Green
}

function Show-Accounts {
    # Kept in step with scripts/uat/seed_uat_accounts.py:ACCOUNTS.
    $rows = @(
        [pscustomobject]@{ Account = 'uat.operator1@magti.ge';     Name = 'ნინო ბერიძე';      Role = 'Operator';      Scope = 'ოფისი — ჯგუფი 01' }
        [pscustomobject]@{ Account = 'uat.operator2@magti.ge';     Name = 'გიორგი კაპანაძე';  Role = 'Operator';      Scope = 'ტექნიკური — ჯგუფი 02' }
        [pscustomobject]@{ Account = 'uat.operator3@magti.ge';     Name = 'თამარ ლომიძე';     Role = 'Operator';      Scope = 'საინფორმაციო — ჯგუფი 03' }
        [pscustomobject]@{ Account = 'uat.operator4@magti.ge';     Name = 'ლევან ჩხეიძე';     Role = 'Operator';      Scope = 'ტექნიკური — ჯგუფი 02' }
        [pscustomobject]@{ Account = 'uat.newbie@magti.ge';        Name = 'ანა მაისურაძე';    Role = 'Operator';      Scope = 'ოფისი — ჯგუფი 01 (no history)' }
        [pscustomobject]@{ Account = 'uat.manager.dept@magti.ge';  Name = 'დავით წერეთელი';   Role = 'Manager';       Scope = 'ტექნიკური (parent)' }
        [pscustomobject]@{ Account = 'uat.manager.group@magti.ge'; Name = 'მარიამ ჯანელიძე';  Role = 'Manager';       Scope = 'ოფისი — ჯგუფი 01 (group)' }
        [pscustomobject]@{ Account = 'uat.content@magti.ge';       Name = 'სოფო გელაშვილი';   Role = 'Content admin'; Scope = 'კონტენტი' }
        [pscustomobject]@{ Account = 'uat.admin@magti.ge';         Name = 'ზურაბ ნოზაძე';     Role = 'System admin';  Scope = 'ადმინისტრაცია' }
        [pscustomobject]@{ Account = 'uat.admin2@magti.ge';        Name = 'ეკა ხურციძე';      Role = 'System admin';  Scope = 'ადმინისტრაცია' }
        [pscustomobject]@{ Account = 'uat.inactive@magti.ge';      Name = 'ირაკლი ბოლქვაძე';  Role = 'Operator';      Scope = 'DEACTIVATED — login must fail' }
    )
    $rows | Format-Table -AutoSize
    Write-Host "URL: $PortalUrl"
    Write-Host 'Password: any value is accepted while dev login is on.' -ForegroundColor Yellow
}

function Invoke-Prepare {
    Assert-Preflight
    Write-Step 'Starting isolated Oracle and Spring Boot (first run takes minutes)'
    Invoke-Compose up --detach --build --wait oracle backend
    Write-Step 'Seeding the demo organisation (602 people, departments, content)'
    Invoke-Compose run --rm --build seeder seed
    Write-Step 'Seeding the eleven named UAT accounts'
    Invoke-Compose run --rm --build accounts
    Write-Step 'Starting the Angular frontend'
    Invoke-Compose up --detach --build --wait frontend
    Write-Host "`nUAT environment is ready: $PortalUrl" -ForegroundColor Green
    Show-Accounts
}

function Invoke-Status {
    Resolve-DockerCommand
    & $DockerCommand compose --env-file $EnvironmentFile --file $ComposeFile ps
    Write-Host "`nURL: $PortalUrl"
}

function Invoke-Reset {
    Resolve-DockerCommand
    if (-not $Force) {
        Write-Host 'This destroys the UAT database and every test result recorded in it.' -ForegroundColor Yellow
        Write-Host "Volume to be removed: $UatVolume"
        $answer = Read-Host 'Type RESET to continue'
        if ($answer -ne 'RESET') {
            Write-Host 'Cancelled; nothing was changed.'
            return
        }
    }
    Write-Step 'Removing UAT containers and database volume'
    & $DockerCommand compose --env-file $EnvironmentFile --file $ComposeFile down --volumes
    & $DockerCommand volume rm $UatVolume 2>$null | Out-Null
    Invoke-Prepare
}

switch ($Command) {
    'prepare'     { Invoke-Prepare }
    'credentials' { Show-Accounts }
    'status'      { Invoke-Status }
    'reset'       { Invoke-Reset }
}

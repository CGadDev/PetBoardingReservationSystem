# Pet Boarding Reservation System
# Automated build, test, deployment, and verification workflow

$ErrorActionPreference = "Stop"

# Deployment configuration
$imageName = "petboarding-final:latest"
$containerName = "petboarding-final-app"

$dataVolume = "petboarding-final-data"
$logVolume = "petboarding-final-logs"

$httpPort = 8085
$tcpPort = 5055

$baseUrl = "http://localhost:$httpPort"

# Executes Docker commands and stops on failure.
function Invoke-Docker {

    param(
        [string[]]$Arguments
    )

    & docker @Arguments

    if ($LASTEXITCODE -ne 0) {

        throw "Docker command failed with exit code $LASTEXITCODE."
    }
}

# Verify that the application has an API key.
if ([string]::IsNullOrWhiteSpace(
        $env:PET_BOARDING_API_KEY)) {

    throw "PET_BOARDING_API_KEY must be configured."
}

# Verify that Docker is available.
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {

    throw "Docker is not available. Start Docker Desktop."
}

Write-Host ""
Write-Host "Pet Boarding Reservation System"
Write-Host "Automated Deployment Workflow"
Write-Host "========================================"

# Execute from the repository containing this script.
Push-Location $PSScriptRoot

try {

    Write-Host ""
    Write-Host "[1/5] Verifying Docker environment..."

    Invoke-Docker -Arguments @(
        "info",
        "--format",
        "{{.ServerVersion}}"
    )

    Write-Host ""
    Write-Host "[2/5] Building and testing application..."

    # The Dockerfile executes Maven compilation,
    # automated tests, and application packaging.
    Invoke-Docker -Arguments @(
        "build",
        "--progress=plain",
        "--tag",
        $imageName,
        "."
    )

    Write-Host "Application build completed successfully."

    # Deployment occurs only after a successful build.
    Write-Host ""
    Write-Host "[3/5] Preparing persistent storage..."

    Invoke-Docker -Arguments @(
        "volume",
        "create",
        $dataVolume
    )

    Invoke-Docker -Arguments @(
        "volume",
        "create",
        $logVolume
    )

    # Determine whether a previous container exists.
    $existingContainers = & docker ps -a `
        --format "{{.Names}}"

    if ($LASTEXITCODE -ne 0) {

        throw "Unable to inspect existing containers."
    }

    Write-Host ""
    Write-Host "[4/5] Deploying application container..."

    if ($existingContainers -contains $containerName) {

        Write-Host "Removing previous application container..."

        Invoke-Docker -Arguments @(
            "rm",
            "-f",
            $containerName
        )
    }

    # The API key is inherited from the environment.
    # Persistent volumes survive container replacement.
    Invoke-Docker -Arguments @(
        "run",
        "-d",
        "--name",
        $containerName,
        "--publish",
        "127.0.0.1:${httpPort}:8082",
        "--publish",
        "127.0.0.1:${tcpPort}:5050",
        "--env",
        "PET_BOARDING_API_KEY",
        "--env",
        "PET_BOARDING_STORAGE=json",
        "--env",
        "PET_BOARDING_CACHE=true",
        "--mount",
        "type=volume,source=$dataVolume,target=/app/data",
        "--mount",
        "type=volume,source=$logVolume,target=/app/logs",
        $imageName
    )

    Write-Host ""
    Write-Host "[5/5] Verifying deployed application..."

    $headers = @{
        "X-API-Key" = $env:PET_BOARDING_API_KEY
    }

    $verified = $false

    # Allow time for the application to initialize.
    for ($attempt = 1; $attempt -le 30; $attempt++) {

        try {

            $resources = Invoke-RestMethod `
                -Uri "$baseUrl/resources" `
                -Method Get `
                -TimeoutSec 3

            $reservations = Invoke-RestMethod `
                -Uri "$baseUrl/reservations" `
                -Method Get `
                -Headers $headers `
                -TimeoutSec 3

            # Verify that both expected API responses exist.
            if ($null -eq $resources.DOG -or
                $null -eq $resources.CAT) {

                throw "Invalid availability response."
            }

            if ($null -eq
                $reservations.PSObject.Properties["reservations"]) {

                throw "Invalid reservations response."
            }

            $verified = $true
            break

        } catch {

            Write-Host "Verification attempt $attempt of 30..."

            Start-Sleep -Seconds 1
        }
    }

    if (-not $verified) {

        Write-Host "Application verification failed."
        Write-Host "Recent container logs:"

        & docker logs --tail 40 $containerName

        throw "The deployed API could not be verified."
    }

    Write-Host ""
    Write-Host "Boarding availability:"

    $resources | ConvertTo-Json -Depth 3

    Write-Host ""
    Write-Host "Reservation count: $($reservations.reservations.Count)"

    Write-Host ""
    Write-Host "========================================"
    Write-Host "DEPLOYMENT SUCCESSFUL" -ForegroundColor Green
    Write-Host "========================================"

    Write-Host "Image: $imageName"
    Write-Host "Container: $containerName"
    Write-Host "Storage: JSON"
    Write-Host "Repository caching: enabled"
    Write-Host "Data volume: $dataVolume"
    Write-Host "Audit log volume: $logVolume"
    Write-Host "HTTP API: $baseUrl"
    Write-Host "TCP port: $tcpPort"

} catch {

    Write-Host ""
    Write-Host "DEPLOYMENT FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message

    exit 1

} finally {

    Pop-Location
}

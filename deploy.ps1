$ErrorActionPreference = "Stop"

$image = "petboarding-automation:latest"
$container = "petboarding-automation-app"
$volume = "petboarding-automation-data"

$reservationsUrl = "http://localhost:8085/reservations"
$resourcesUrl = "http://localhost:8085/resources"

# Verify that an API key is configured.
if ([string]::IsNullOrWhiteSpace($env:PET_BOARDING_API_KEY)) {
    throw "PET_BOARDING_API_KEY is not configured."
}

# Stage 1: Build and test the application.
Write-Host "`n=== Building and Testing ==="

docker build --progress=plain -t $image .

if ($LASTEXITCODE -ne 0) {
    throw "Docker build failed. Deployment cancelled."
}

Write-Host "Docker build completed successfully."

# Stage 2: Replace the previous container.
Write-Host "`n=== Deploying Application ==="

$existing = docker ps -aq --filter "name=^/$($container)$"

if ($LASTEXITCODE -ne 0) {
    throw "Unable to check existing containers."
}

if ($existing) {
    docker rm -f $container

    if ($LASTEXITCODE -ne 0) {
        throw "Unable to remove previous container."
    }
}

# Start the updated application with JSON persistence.
docker run -d --name $container `
    -p 8085:8082 `
    -e PET_BOARDING_API_KEY `
    -e PET_BOARDING_STORAGE=json `
    -v "${volume}:/app/data" `
    $image

if ($LASTEXITCODE -ne 0) {
    throw "Container deployment failed."
}

Write-Host "Docker container started successfully."

# Stage 3: Verify the authenticated reservations API.
Write-Host "`n=== Verifying Deployment ==="

$verified = $false
$response = $null

for ($attempt = 1; $attempt -le 10; $attempt++) {
    try {
        $response = Invoke-RestMethod `
            -Uri $reservationsUrl `
            -Method Get `
            -Headers @{
                "X-API-Key" = $env:PET_BOARDING_API_KEY
            } `
            -TimeoutSec 5

        if ($response.PSObject.Properties.Name -contains "reservations") {
            $verified = $true
            break
        }
    }
    catch {
        Write-Host "Waiting for application (attempt $attempt)..."
    }

    if (-not $verified) {
        Start-Sleep -Seconds 2
    }
}

if (-not $verified) {
    throw "Deployment verification failed."
}

Write-Host "Reservations API verification passed."

# Stage 4: Verify the updated availability endpoint.
Write-Host "`n=== Verifying Availability ==="

$resources = $null

try {
    $resources = Invoke-RestMethod `
        -Uri $resourcesUrl `
        -Method Get `
        -TimeoutSec 5
}
catch {
    throw "Unable to access the availability endpoint: $_"
}

# Confirm the required fields exist.
$properties = $resources.PSObject.Properties.Name

if ($properties -notcontains "DOG" -or
    $properties -notcontains "CAT" -or
    $properties -notcontains "totalAvailable") {
    throw "Availability response is missing required fields."
}

# Confirm the total availability is calculated correctly.
if ($resources.totalAvailable -ne ($resources.DOG + $resources.CAT)) {
    throw "Availability verification failed: incorrect total."
}

Write-Host "Availability verification passed."

# Stage 5: Report successful deployment.
Write-Host "`n=== DEPLOYMENT SUCCESSFUL ==="

Write-Host "Reservations API: $reservationsUrl"
Write-Host "Availability API: $resourcesUrl"

Write-Host "`nReservations Response:"
$response | ConvertTo-Json -Depth 5

Write-Host "`nAvailability Response:"
$resources | ConvertTo-Json -Depth 5

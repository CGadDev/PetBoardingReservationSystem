# Pet Boarding Reservation System



## Project Overview



The Pet Boarding Reservation System is a Java application that manages reservations for a pet boarding facility. The application provides an HTTP API and a TCP server for network communication.



The system supports creating, retrieving, and managing pet boarding reservations. It also tracks available boarding capacity for dogs and cats.



The application supports CSV, JSON, and in-memory storage and uses an API key to authenticate reservation requests.



## Technologies and Requirements



- Java 17

- Apache Maven

- Docker Desktop

- Git and GitHub

- Render for cloud deployment



Docker is used to package the application and its runtime dependencies into a reproducible container image.



The application uses HTTP port 8082 and TCP port 5050. Only the HTTP service is published for the Docker and Render demonstrations.



## Running the Application with Docker



### 1. Clone the Repository



```powershell

git clone https://github.com/CGadDev/PetBoardingReservationSystem.git

cd PetBoardingReservationSystem

```



### 2. Build the Docker Image



```powershell

docker build -t petboarding:local .

```



The Dockerfile uses a multi-stage build. Maven compiles and packages the application, and a Java 17 runtime image is used to execute it.



### 3. Start the Container



```powershell

docker run -d --name petboarding-local -p 127.0.0.1:8085:8082 -e PET_BOARDING_API_KEY=local-demo-key -e PET_BOARDING_STORAGE=memory petboarding:local

```



This command:



- Creates a container named `petboarding-local`.

- Publishes container port 8082 through localhost port 8085.

- Sets an API key for reservation requests.

- Configures in-memory reservation storage.



The example API key is intended for local testing only. Use a unique private key for an actual deployment.



### 4. Verify the Container



```powershell

docker ps

```



To view application logs:



```powershell

docker logs petboarding-local

```



The logs should confirm that the HTTP server is running on port 8082 and the TCP server is running on port 5050.



### 5. Access the Application



Open:



http://localhost:8085/resources



The response displays the available boarding capacity for dogs and cats.



To restart the existing container after stopping it:



```powershell

docker start petboarding-local

```



## Testing the HTTP API



### Retrieve Available Boarding Capacity



```powershell

Invoke-RestMethod -Uri "http://localhost:8085/resources"

```



### Retrieve Reservations



```powershell

Invoke-RestMethod -Uri "http://localhost:8085/reservations" -Method GET -Headers @{"X-API-Key"="local-demo-key"}

```



### Create a Reservation



```powershell

Invoke-RestMethod -Uri "http://localhost:8085/reservations" -Method POST -Headers @{"X-API-Key"="local-demo-key"} -ContentType "application/json" -Body '{"petName":"Buddy","petType":"DOG"}'

```



A successful reservation receives a confirmation response. The available boarding capacity is updated accordingly.



## Environment Variables



| Variable | Description |

|---|---|

| PET_BOARDING_API_KEY | Required API key for authenticated reservation operations |

| PET_BOARDING_STORAGE | Selects memory, csv, or json storage |

| PORT | Set to 8082 on Render to match the application's HTTP listening port |



The Dockerfile defaults to in-memory storage.



CSV and JSON storage use the application's `data` directory.



### Persistent JSON Storage



To save JSON reservation data using a Docker named volume:



```powershell

docker run -d --name petboarding-json -p 127.0.0.1:8098:8082 -e PET_BOARDING_API_KEY=local-demo-key -e PET_BOARDING_STORAGE=json -v petboarding-json-data:/app/data petboarding:local

```



The named volume stores reservation data independently of the container's writable filesystem.



In-memory reservations are lost when the application restarts. JSON reservations stored in a Docker volume can survive container restarts and replacement.



## Cloud Deployment with Render



The application is deployed using Render's Docker Web Service functionality.



### Deployment Procedure



1. Push the application source code and Dockerfile to GitHub.

2. Sign in to the Render dashboard.

3. Create a new Web Service.

4. Connect the PetBoardingReservationSystem GitHub repository.

5. Select the `main` branch.

6. Choose Docker as the runtime.

7. Select the Free compute plan.

8. Configure the required environment variables.

9. Create the Web Service and wait for deployment to finish.



### Render Environment Configuration



| Variable | Value |

|---|---|

| PORT | 8082 |

| PET_BOARDING_STORAGE | memory |

| PET_BOARDING_API_KEY | A unique private API key |



The API key must be configured through Render's environment settings and should not be committed to GitHub.



### Public Application URL



https://petboardingreservationsystem.onrender.com



### Verify Cloud Deployment



Check available boarding capacity:



https://petboardingreservationsystem.onrender.com/resources



Reservation requests require the `X-API-Key` header.



To test authenticated requests in PowerShell without displaying the API key:



```powershell

$secureRenderKey = Read-Host "Enter your Render API key" -AsSecureString

$renderApiKey = [System.Net.NetworkCredential]::new("", $secureRenderKey).Password

$renderHeaders = @{"X-API-Key" = $renderApiKey}

```



Retrieve reservations:



```powershell

Invoke-RestMethod -Uri "https://petboardingreservationsystem.onrender.com/reservations" -Method GET -Headers $renderHeaders

```



Create a reservation:



```powershell

Invoke-RestMethod -Uri "https://petboardingreservationsystem.onrender.com/reservations" -Method POST -Headers $renderHeaders -ContentType "application/json" -Body '{"petName":"CloudMilo","petType":"CAT"}'

```



## Container and Cloud Configuration Considerations



Docker requires explicit port publishing for host access to containerized services.



The application was tested with and without HTTP port publishing. Without a published port, the application continued running inside the container but could not be accessed through the tested host port.



Storage configuration also affects persistence. In-memory reservations were lost after a container restart, while JSON reservations stored using a Docker volume survived restarts and could be accessed by another container.



Render provides managed application hosting and a public HTTPS endpoint. Unlike local Docker, Render's Free Web Services do not support persistent disks.



The cloud demonstration uses in-memory storage, so reservation data may be lost when the service restarts or redeploys.



Render Free instances can also spin down after periods of inactivity, causing additional startup delay when the service is accessed again.



The TCP service on port 5050 is not publicly exposed through the Render Web Service.



## Virtual Machine Automation



The repository also contains Vagrant resources from the earlier virtualization and environment configuration assignment.



See [Vagrant Setup Instructions](vagrant/README.md) for the separate virtual machine provisioning documentation.



## Repository



https://github.com/CGadDev/PetBoardingReservationSystem



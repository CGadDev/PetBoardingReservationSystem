Pet Boarding Reservation System



Project Overview



The Pet Boarding Reservation System is a Java application that manages reservations for a pet boarding facility. The application provides an HTTP API and a TCP server for network communication.



The system supports creating, retrieving, and managing pet boarding reservations. It also tracks available boarding capacity for dogs and cats, including the combined total of available spaces.



The application supports CSV, JSON, and in-memory storage and uses an API key to authenticate reservation requests.





Technologies and Requirements



The application uses Java 17 and Apache Maven. Docker Desktop is used for local containerization, Git and GitHub are used for source code management, and Render is used for cloud deployment.



Docker packages the application and its runtime dependencies into a reproducible container image. Java and Maven are included in the Docker build environment, so they do not need to be installed separately on the host when building through Docker.



The application uses HTTP port 8082 and TCP port 5050. Only the HTTP service is published for the Docker and Render demonstrations.





Automated Build and Deployment Workflow



The repository includes an automated build and deployment workflow developed for CEN4025 Software Development II. The workflow uses Maven, JUnit 5, Docker, and PowerShell to build, test, package, deploy, and verify the Pet Boarding Reservation System through a single initiating command.



The automation resources are available on the automated-build-deployment branch.





Automation Requirements



The automated workflow requires Windows PowerShell, Docker Desktop with the Linux container engine running, and the application source code.



Java and Maven do not need to be installed separately because the Dockerfile provides the required build environment.



Host port 8085 must be available for the application container.





Clone the Assignment Branch



For a fresh copy of the project, open PowerShell and run:



git clone -b automated-build-deployment https://github.com/CGadDev/PetBoardingReservationSystem.git



cd PetBoardingReservationSystem





Configure the API Key



The application requires the PET\_BOARDING\_API\_KEY environment variable for authenticated reservation operations.



Configure the API key in the current PowerShell session:



$secureKey = Read-Host "Enter your API key" -AsSecureString



$env:PET\_BOARDING\_API\_KEY = \[System.Net.NetworkCredential]::new("", $secureKey).Password



The API key must not be committed to GitHub. The example allows a private key to be entered without displaying it in the terminal.





Execute the Automated Workflow



Ensure Docker Desktop is running and that host port 8085 is available.



From the project's root directory, execute:



powershell -NoProfile -ExecutionPolicy Bypass -File .\\deploy.ps1



The script automatically performs the following operations:



1\. Verifies that the required API key is configured.

2\. Builds the Docker image using Maven and executes the JUnit tests.

3\. Packages the application and required dependencies.

4\. Removes the previous application container if one exists.

5\. Starts a replacement container with JSON storage and a persistent Docker volume.

6\. Verifies the authenticated reservations API and the availability endpoint.

7\. Reports whether the deployment completed successfully.



The resulting Docker image is named petboarding-automation:latest, and the container is named petboarding-automation-app.



The application uses the Docker volume petboarding-automation-data to preserve JSON reservation data between container replacements.





Verify the Automated Deployment



After successful execution, the script displays DEPLOYMENT SUCCESSFUL and the HTTP API responses.



The application can be accessed locally at:



http://localhost:8085/resources



http://localhost:8085/reservations



The resources endpoint returns the available spaces for dogs and cats, including the totalAvailable field.



The reservations endpoint requires the X-API-Key header.



To retrieve reservations using the configured API key, run:



Invoke-RestMethod -Uri "http://localhost:8085/reservations" -Method Get -Headers @{"X-API-Key" = $env:PET\_BOARDING\_API\_KEY}



The response displays the existing reservations. An empty reservations collection is expected when no reservations have been created.





Repeatable Deployment



To deploy an updated application version, modify the source code and execute the same PowerShell command again:



powershell -NoProfile -ExecutionPolicy Bypass -File .\\deploy.ps1



The script rebuilds the application, executes its tests, replaces the existing container, and verifies the updated deployment without manually repeating the individual build or deployment operations.



The application update demonstrated for this assignment added a totalAvailable field to the resources endpoint. The field calculates the combined number of available dog and cat boarding spaces.



The workflow was successfully used to deploy both the original application and the updated version.





Automated Testing and Verification



The Docker build executes the application's JUnit tests through Maven.



The tests cover HTTP communication, TCP networking, concurrency, persistence, and reservation service functionality.



The HTTP integration tests also verify the totalAvailable field, including changes to availability when reservations are created or canceled.



The final automated build completed 31 tests with zero failures, zero errors, and zero skipped tests.



After deployment, the PowerShell script verifies the reservations and resources endpoints.



The availability verification checks that the response contains DOG, CAT, and totalAvailable and confirms that totalAvailable equals the combined available spaces for both pet categories.





Automation Failure Handling and Limitations



If the Docker build or required deployment operations fail, the script reports an error and stops execution. Maven compilation or test failures prevent the deployment stage from executing.



If the HTTP verification fails, the script does not report a successful deployment.



The workflow depends on Docker Desktop and requires host port 8085 to be available. Other containers using that port must be stopped before the automated deployment can start.



If the manually configured petboarding-local container is using port 8085, it can be stopped using:



docker stop petboarding-local



The automated deployment script handles replacement of its own petboarding-automation-app container.



The script removes the previous application container before verifying its replacement, so a failed deployment may temporarily leave the application unavailable.



The workflow is intended for local development and demonstration rather than production deployment. Production improvements could include health checks, automatic rollback, unique image tags, secure secrets management, and automated data backups.





Running the Application with Docker



Clone the Repository



Open PowerShell and run the following commands:



git clone https://github.com/CGadDev/PetBoardingReservationSystem.git



cd PetBoardingReservationSystem





Build the Docker Image



Run the following command:



docker build -t petboarding:local .



The Dockerfile uses a multi-stage build. Maven compiles and packages the application, and a Java 17 runtime image is used to execute it.





Start the Container



Run the following command:



docker run -d --name petboarding-local -p 127.0.0.1:8085:8082 -e PET\_BOARDING\_API\_KEY=local-demo-key -e PET\_BOARDING\_STORAGE=memory petboarding:local



This command creates a container named petboarding-local, publishes container port 8082 through localhost port 8085, configures an API key, and enables in-memory reservation storage.



The example API key is intended for local testing only. Use a unique private API key for an actual deployment.





Verify the Container



Check whether the container is running:



docker ps



View the application logs:



docker logs petboarding-local



The logs should confirm that the HTTP server is running on port 8082 and the TCP server is running on port 5050.





Access the Application



Open the following address in a browser:



http://localhost:8085/resources



The response displays the available boarding capacity for dogs and cats, including the combined total.



If the container has been stopped, it can be restarted using:



docker start petboarding-local





Testing the HTTP API



Retrieve Available Boarding Capacity



Run the following command:



Invoke-RestMethod -Uri "http://localhost:8085/resources"



The response displays the available spaces for dogs and cats, including totalAvailable.





Retrieve Reservations



Run the following command:



Invoke-RestMethod -Uri "http://localhost:8085/reservations" -Method GET -Headers @{"X-API-Key"="local-demo-key"}



The response displays existing reservations.





Create a Reservation



Run the following command:



Invoke-RestMethod -Uri "http://localhost:8085/reservations" -Method POST -Headers @{"X-API-Key"="local-demo-key"} -ContentType "application/json" -Body '{"petName":"Buddy","petType":"DOG"}'



A successful reservation receives a confirmation response containing the reservation information.



The available boarding capacity is updated accordingly.





Environment Variables



PET\_BOARDING\_API\_KEY



This variable defines the API key required to authenticate reservation operations.





PET\_BOARDING\_STORAGE



This variable determines the storage format used by the application. Supported values are memory, csv, and json.





PORT



This variable is set to 8082 on Render to match the HTTP listening port configured in the Java application.



The Dockerfile defaults to in-memory storage.



CSV and JSON storage use the application's data directory.





Persistent JSON Storage



Docker named volumes can be used to retain reservation data independently of an individual container.



To start the application with JSON storage and a named volume, run:



docker run -d --name petboarding-json -p 127.0.0.1:8098:8082 -e PET\_BOARDING\_API\_KEY=local-demo-key -e PET\_BOARDING\_STORAGE=json -v petboarding-json-data:/app/data petboarding:local



This configuration stores JSON reservation data in the petboarding-json-data volume.



In-memory reservations are lost when the application restarts.



JSON reservations stored using a Docker volume can survive container restarts and can be accessed by replacement containers that attach to the same volume.





Cloud Deployment with Render



The application is deployed using Render's Docker Web Service functionality.



Render provides managed cloud application hosting and a publicly accessible HTTPS endpoint.





Deployment Procedure



First, push the application source code and Dockerfile to GitHub.



Sign in to the Render dashboard at https://dashboard.render.com.



Create a new Web Service and connect the PetBoardingReservationSystem GitHub repository.



Select the main branch and choose Docker as the runtime.



Leave the Root Directory field blank.



Select the Free compute plan.



Configure the required environment variables.



Create the Web Service and wait for the Docker image to build and the application to deploy.





Render Environment Configuration



PORT is configured as 8082.



PET\_BOARDING\_STORAGE is configured as memory.



PET\_BOARDING\_API\_KEY is configured using a unique private API key.



The API key must be stored in Render's environment settings and should never be committed to GitHub.





Public Application URL



The deployed application is available at:



https://petboardingreservationsystem.onrender.com





Verify Cloud Deployment



To check available boarding capacity, open:



https://petboardingreservationsystem.onrender.com/resources



The response should display the available boarding spaces for dogs and cats.



Reservation requests require the X-API-Key HTTP header.





Testing Cloud Reservations



Open PowerShell and enter the following command:



$secureRenderKey = Read-Host "Enter your Render API key" -AsSecureString



Enter the private API key configured in Render.



Next, run:



$renderApiKey = \[System.Net.NetworkCredential]::new("", $secureRenderKey).Password



Then:



$renderHeaders = @{"X-API-Key" = $renderApiKey}



These commands allow authenticated requests without displaying the API key.





Retrieve Cloud Reservations



Run:



Invoke-RestMethod -Uri "https://petboardingreservationsystem.onrender.com/reservations" -Method GET -Headers $renderHeaders



The response displays existing cloud reservations.





Create a Cloud Reservation



Run:



Invoke-RestMethod -Uri "https://petboardingreservationsystem.onrender.com/reservations" -Method POST -Headers $renderHeaders -ContentType "application/json" -Body '{"petName":"CloudMilo","petType":"CAT"}'



The response should confirm that the reservation was created successfully.



The reservation can be verified by retrieving the reservations list again.



The available boarding capacity can also be checked through the resources endpoint to verify that the reservation affected the remaining capacity.





Container Configuration Investigations



Docker Port Publishing



Docker requires explicit port publishing for host access to services running on its default isolated container network.



The application was tested with and without an HTTP port mapping.



Without a published port, the HTTP server continued running inside the container, but the host could not access it through the tested host port.



Creating a new container with the correct port mapping restored network accessibility.





Storage Configuration and Persistence



The application was tested using in-memory storage and JSON storage.



When in-memory storage was enabled, a reservation was successfully created but disappeared after restarting the container.



When JSON storage was enabled with a Docker named volume, the reservation remained available after restarting the container.



The same reservation was also retrieved successfully from a newly created container that used the existing volume.



This demonstrated how Docker storage configuration affects data persistence and reproducibility.





Local Docker and Cloud Environment Considerations



Local Docker provides direct control over container creation, port mappings, storage volumes, and application configuration.



Render provides managed cloud hosting, automated Docker image builds, public HTTPS access, and deployment integration with GitHub.



The same Dockerfile and application source code were used for both environments.



Local Docker used host port 8085 to access the application's internal HTTP port 8082.



Render provided access through a public HTTPS URL while forwarding requests to the application's configured HTTP port.



The application operated correctly in both environments.





Cloud Hosting Limitations



The application uses Render's Free Web Service plan.



Free instances have limited CPU and memory resources and may spin down after periods of inactivity.



A request received after the service has become idle may experience additional startup delay.



Render's Free Web Services do not support persistent disks.



Because the cloud demonstration uses in-memory storage, reservation data may be lost when the service restarts or redeploys.



The TCP service on port 5050 is not publicly exposed through the Render Web Service.



These limitations are acceptable for the assignment demonstration but would require additional consideration for a production deployment.





Virtual Machine Automation



The repository also contains Vagrant resources from the earlier virtualization and environment configuration assignment.



The separate virtual machine provisioning instructions are available in:



vagrant/README.md





Repository



The complete project source code, Dockerfile, automation resources, and documentation are available at:



https://github.com/CGadDev/PetBoardingReservationSystem



The automated build and deployment assignment resources are available on the automated-build-deployment branch.


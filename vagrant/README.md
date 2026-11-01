Pet Boarding Reservation System - Vagrant Environment
This README.md contains the configuration files used to create and run the Pet Boarding Reservation System in an Ubuntu virtual machine with Vagrant and Oracle VirtualBox. The application source code is available at: https://github.com/CGadDev/PetBoardingReservationSystem.

Requirements:
-Windows 11 with Oracle VirtualBox installed
-Vagrant installed (tested with version 2.4.9)
-An internet connection to download the Ubuntu Vagrant box, application source, Ubuntu packages, and Maven dependencies
-Host TCP port 8084 available
Java and Maven do not need to be installed on Windows. The provisioning script installs them inside the virtual machine.

Files
-Vagrantfile: configures the Ubuntu virtual machine, its CPU and memory allocation, the network forwarding rule, and the provisioning script.

-provision.sh installs the application's dependencies, downloads its source code, builds it, configures its environment variables, and sets up automatic startup.
Keep Vagrantfile and provision.sh in the same directory.

Create and Start the Environment
1. Open Windows PowerShell and change to the directory containing both files. For the setup used in this assignment:
cd C:\Users\Owner\Documents\PetBoardingVagrant
Check the Vagrant configuration:
vagrant validate
Create the virtual machine and run the automated provisioning script:
vagrant up --provider=virtualbox
The first run may take several minutes while packages and Maven dependencies are downloaded. Provisioning is complete when Vagrant reports that the script has finished.

2. Verify the Application

Check that the virtual machine is running:
vagrant status

Access the application from the Windows host in a browser:
http://localhost:8084/resources

The response should contain the pet capacities DOG: 3 and CAT: 2. For example:
{
  "DOG": 3,
  "CAT": 2
}

You can also check the response from PowerShell:
curl.exe http://localhost:8084/resources

To confirm that the application starts automatically as a service inside Ubuntu:
vagrant ssh

Then, in the Ubuntu shell:
sudo systemctl status petboarding --no-pager

The service should show active (running). The HTTP server listens on guest port 8082, and the TCP server listens on guest port 5050. Type exit to return to PowerShell.

Provisioning Configuration
The Vagrantfile specifies:
-Ubuntu 24.04 using the bento/ubuntu-24.04 box (version 202510.26.0)
-2 virtual CPUs and 2048 MB of memory
-VirtualBox NAT forwarding from Windows 127.0.0.1:8084 to Ubuntu port 8082

The provisioning script installs OpenJDK 17, Maven, Git, and OpenSSL. It retrieves the application from GitHub, applies the HTTP binding correction needed for host-to-guest access, and runs mvn -B clean package to build and test the application. It then generates a random API key, sets PET_BOARDING_STORAGE=memory, and creates and enables the petboarding systemd service.
The API key is generated inside the VM and stored in /etc/petboarding/petboarding.env with restricted permissions. No fixed API key is included in the submitted files.

Stop or Restart the Virtual Machine
Run these commands from the directory containing the Vagrantfile:
vagrant halt

To start it again:
vagrant up

To rerun the provisioning script on an existing VM:
vagrant provision

Reproducibility Notes
The automated VM is separate from the Ubuntu 26.04.1 LTS VM that was initially configured by hand. The automated VM uses Ubuntu 24.04.3 LTS and does not rely on packages or files installed in the manual VM. Provisioning depends on access to the Vagrant box, Ubuntu package repositories, GitHub, and Maven Central. The in-memory storage configuration does not persist reservation data across application restarts.

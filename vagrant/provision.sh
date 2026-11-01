#!/usr/bin/env bash
set -euo pipefail

echo "Starting Pet Boarding environment provisioning..."

# Install application dependencies
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-17-jdk maven git openssl

# Create application directory
mkdir -p /opt/petboarding

# Retrieve application source
if [ ! -d /opt/petboarding/app/.git ]; then
  git clone https://github.com/CGadDev/PetBoardingReservationSystem.git /opt/petboarding/app
fi

cd /opt/petboarding/app

# Reproduce the network-binding correction
sed -i \
  's/InetAddress.getLoopbackAddress(), port/port/' \
  src/main/java/com/petboarding/api/ReservationHttpServer.java

# Build and test the application
mvn -B clean package

# Prepare application environment configuration
mkdir -p /etc/petboarding

if [ ! -f /etc/petboarding/petboarding.env ]; then
  api_key=$(openssl rand -hex 24)

  printf 'PET_BOARDING_API_KEY=%s\n' "$api_key" \
    > /etc/petboarding/petboarding.env

  printf 'PET_BOARDING_STORAGE=memory\n' \
    >> /etc/petboarding/petboarding.env

  chmod 600 /etc/petboarding/petboarding.env
fi

# Set permissions for the application files
chown -R vagrant:vagrant /opt/petboarding

# Configure automatic application startup
printf '%s\n' \
  '[Unit]' \
  'Description=Pet Boarding Reservation System' \
  'After=network-online.target' \
  'Wants=network-online.target' \
  '' \
  '[Service]' \
  'Type=simple' \
  'User=vagrant' \
  'WorkingDirectory=/opt/petboarding/app' \
  'EnvironmentFile=/etc/petboarding/petboarding.env' \
  'ExecStart=/usr/bin/mvn -q exec:java -Dexec.mainClass=com.petboarding.Main' \
  'Restart=on-failure' \
  'RestartSec=5' \
  '' \
  '[Install]' \
  'WantedBy=multi-user.target' \
  > /etc/systemd/system/petboarding.service

systemctl daemon-reload
systemctl enable petboarding
systemctl restart petboarding

echo "Pet Boarding provisioning completed."

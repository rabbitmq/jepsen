#!/bin/bash

set -ex

# script to provision and configure the Jepsen controller
# this controller will run the Jepsen tests

sudo apt-get update
sudo apt-get install -y -V --fix-missing --no-install-recommends wget git make gnuplot

# install Java
export JAVA_PATH="/usr/lib/jdk-21"
JAVA_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz"
wget --progress dot:giga --output-document jdk.tar.gz $JAVA_URL

sudo mkdir -p $JAVA_PATH
sudo tar --extract --file jdk.tar.gz --directory "$JAVA_PATH" --strip-components 1
rm jdk.tar.gz
sudo ln -s "$JAVA_PATH/bin/java" /usr/bin/java

# install lein (to compile and launch the Jepsen tests)
wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
sudo mv lein /usr/bin/lein
sudo chmod u+x /usr/bin/lein
lein -v

# configure SSH
touch ~/.ssh/known_hosts
chmod 600 jepsen-bot
echo "StrictHostKeyChecking no" >> ~/.ssh/config

git clone https://github.com/rabbitmq/jepsen.git
cd jepsen
echo $(git log -1 --pretty="%h %B")

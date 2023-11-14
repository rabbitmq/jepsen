#!/bin/bash

set -ex

# script to provision and configure the Jepsen controller
# this controller will run the Jepsen tests

# install Erlang a few utilities
sudo apt-get update
sudo apt-get install -y -V --fix-missing --no-install-recommends wget git make gnuplot

# install Java 8 (needed by Jepsen)
export JAVA_PATH="/usr/lib/jdk-8"
sudo wget --progress dot:giga --output-document "$JAVA_PATH.tar.gz" https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u392-b08/OpenJDK8U-jdk_x64_linux_hotspot_8u392b08.tar.gz
sudo mkdir $JAVA_PATH
sudo tar --extract --file "$JAVA_PATH.tar.gz" --directory "$JAVA_PATH" --strip-components 1
export JAVA_HOME=$JAVA_PATH
export PATH=$PATH:$JAVA_HOME/bin
echo "export JAVA_HOME=$JAVA_PATH" >> .profile
echo "export PATH=$PATH:$JAVA_PATH/bin" >> .profile

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


## The contents of this file are subject to the Mozilla Public License
## Version 1.1 (the "License"); you may not use this file except in
## compliance with the License. You may obtain a copy of the License
## at https://www.mozilla.org/MPL/
#
## Software distributed under the License is distributed on an "AS IS"
## basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
## the License for the specific language governing rights and
## limitations under the License.
#
# Copyright (c) 2023 Broadcom. All Rights Reserved. The term Broadcom refers to Broadcom Inc. and/or its subsidiaries.

FROM debian:buster

ENV LANG='C.UTF-8'
ENV TERRAFORM_VERSION='1.6.3'

RUN apt-get clean && \
    apt-get update && \
    apt-get -y upgrade && \
    apt-get install -y -V --no-install-recommends \
      ca-certificates \
      apt-transport-https \
      gnupg \
      wget

# Our own rabbitmq-erlang repository to provision Erlang.
RUN echo 'deb http://ppa1.novemberain.com/rabbitmq/rabbitmq-erlang/deb/debian buster main' > /etc/apt/sources.list.d/rabbitmq-erlang.list && \
    wget -O- https://dl.cloudsmith.io/public/rabbitmq/rabbitmq-erlang/gpg.E495BB49CC4BBE5B.key | apt-key add -

# We need to set an APT preference to make sure $ERLANG_VERSION is
# used for all erlang* packages. Without this, apt-get(1) would try to
# install dependencies using the latest version. This would conflict
# with the strict pinning in all packages, and thus fail.
RUN ERLANG_VERSION=1:26* && \
    echo 'Package: erlang*' > /etc/apt/preferences.d/erlang && \
    echo "Pin: version $ERLANG_VERSION" >> /etc/apt/preferences.d/erlang && \
    echo 'Pin-Priority: 1000' >> /etc/apt/preferences.d/erlang

# install a few utilities
RUN apt-get update && \
    apt-get install -y -V --fix-missing --no-install-recommends \
    openssh-client curl unzip lsb-release \
    erlang-nox \
    erlang-dev \
    erlang-common-test \
    make \
    git


RUN echo "deb http://packages.cloud.google.com/apt cloud-sdk-$(lsb_release -c -s) main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key add - && \
    apt-get update && \
    apt-get install -y -V --fix-missing --no-install-recommends google-cloud-sdk

RUN wget https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_amd64.zip && \
    unzip terraform_${TERRAFORM_VERSION}_linux_amd64.zip && \
    mv terraform /usr/bin && \
    chmod u+x /usr/bin/terraform && \
    rm terraform_${TERRAFORM_VERSION}_linux_amd64.zip

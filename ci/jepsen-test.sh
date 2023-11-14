#!/bin/bash

set -ex

VERSION=$RABBITMQ_VERSION
ARCHIVE=rabbitmq-server-generic-unix-${VERSION}.tar.xz

set +x
# create SSH key that will be used to connect to the Jepsen VMs
ssh-keygen -t rsa -m pem -f jepsen-bot -C jepsen-bot -N ''

# persist the GCP credentials in a file for Terraform
echo "$GCP_JEPSEN_CREDENTIALS" > jepsen-bot.json
set -x
gcloud auth activate-service-account --key-file=jepsen-bot.json

# destroy the VMs in they already exist (this cannot be done from Terraform unfortunately)
set +e
# the name of the VMs cannot be retrieved from Terraform, it must be hardcoded here
VMS=("jepsen-bot-qq-jepsen-controller-$RABBITMQ_BRANCH" "jepsen-bot-qq-jepsen-$RABBITMQ_BRANCH-0" "jepsen-bot-qq-jepsen-$RABBITMQ_BRANCH-1" "jepsen-bot-qq-jepsen-$RABBITMQ_BRANCH-2" "jepsen-bot-qq-jepsen-$RABBITMQ_BRANCH-3" "jepsen-bot-qq-jepsen-$RABBITMQ_BRANCH-4")
for vm in "${VMS[@]}"
do
	# the zone must be hardcoded as well
	list_vm=$(gcloud compute instances list --filter="name=('$vm') AND zone:(europe-west4-a)" --quiet)
	if [[ $list_vm == *$vm* ]]
	then
		gcloud compute instances delete $vm --delete-disks=all --quiet --zone=europe-west4-a
	fi
done
set -e

# copy Terraform configuration file in current directory
cp ./ci/rabbitmq-jepsen.tf .

# initialize Terraform (get plugins and so)
terraform init

# spin up the VMs
terraform apply -auto-approve -var="rabbitmq_branch=$RABBITMQ_BRANCH"

mkdir terraform-state
# save Terraform state and configuration to clean up even if the task fails
# this ensures the VMs will be destroyed whatever the outcomes of the job
cp jepsen-bot.json terraform-state
cp jepsen-bot terraform-state
cp jepsen-bot.pub terraform-state
cp -r .terraform terraform-state
cp terraform.tfstate terraform-state
cp rabbitmq-jepsen.tf terraform-state

# get the Jepsen controller IP
CONTROLLER_IP=$(terraform output -raw controller_ip)
# install dependencies and compile the RA KV store on the Jepsen controller
ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP 'bash -s' < ci/provision-jepsen-controller.sh
# makes sure the Jepsen test command line works
ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "source ~/.profile ; cd ~/jepsen/rabbitmq/ ; lein run test --help"
# download latest alpha on Jepsen controller
ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "wget https://github.com/rabbitmq/rabbitmq-server-binaries-dev/releases/download/v${VERSION}/rabbitmq-server-generic-unix-${VERSION}.tar.xz"

# copy the alpha on all the Jepsen workers
WORKERS=( $(terraform output -raw workers_hostname) )
for worker in "${WORKERS[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot \
    jepsen-bot@$CONTROLLER_IP \
    "scp -o StrictHostKeyChecking=no -i ~/jepsen-bot ~/${ARCHIVE} jepsen-bot@$worker:/tmp/${ARCHIVE}"
done

# create directory for broker /var directory archiving on all the Jepsen workers
WORKERS_IP=( $(terraform output -raw workers_ip) )
for worker_ip in "${WORKERS_IP[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$worker_ip "mkdir /tmp/rabbitmq-var"
done

# install some Jepsen dependencies on all the Jepsen workers
WORKERS_IP=( $(terraform output -raw workers_ip) )
for worker_ip in "${WORKERS_IP[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$worker_ip "sudo apt-get update"
  ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$worker_ip "sudo apt-get install -y build-essential bzip2 curl faketime iproute2 iptables iputils-ping libzip4 logrotate man man-db net-tools ntpdate psmisc python rsyslog tar unzip wget"
done

# build up some fixed parameters for the Jepsen tests
NODES=""
for worker in "${WORKERS[@]}"
do
  NODES="$NODES --node $worker"
done

SOURCE_AND_CD="source ~/.profile ; cd ~/jepsen/rabbitmq"
CREDENTIALS="--username jepsen-bot --ssh-private-key ~/jepsen-bot"
ARCHIVE_OPTION="--archive-url file:///tmp/${ARCHIVE}"

JEPSEN_TESTS_PARAMETERS=(
	"--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition random-partition-halves --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-halves --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-majorities-ring --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-random-node --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition random-partition-halves --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-halves --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-majorities-ring --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-random-node --net-ticktime 15 --consumer-type mixed"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-random-node --net-ticktime 15 --consumer-type asynchronous"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-random-node --net-ticktime 15 --consumer-type polling"
 #    "--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition random-partition-halves --net-ticktime 15 --consumer-type mixed --dead-letter true"
	# "--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-halves --net-ticktime 15 --consumer-type mixed --dead-letter true"
)

TESTS_COUNT=${#JEPSEN_TESTS_PARAMETERS[@]}
TEST_INDEX=1

failure=false

set +e

for jepsen_test_parameter in "${JEPSEN_TESTS_PARAMETERS[@]}"
do
  n=1
  until [ $n -ge 4 ]
  do
    echo "Running Jepsen test $TEST_INDEX / $TESTS_COUNT, attempt $n ($(date))"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "$SOURCE_AND_CD ; lein run test $NODES $CREDENTIALS $jepsen_test_parameter $ARCHIVE_OPTION" >/dev/null
    run_exit_code=$?
	for worker in "${WORKERS[@]}"
	do
    ssh -o StrictHostKeyChecking=no -i jepsen-bot \
        jepsen-bot@$CONTROLLER_IP \
        "ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot jepsen-bot@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl list_connections'"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		jepsen-bot@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot jepsen-bot@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl list_queues'"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		jepsen-bot@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot jepsen-bot@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmq-queues quorum_status jepsen.queue'"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		jepsen-bot@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot jepsen-bot@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl cluster_status'"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		jepsen-bot@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot jepsen-bot@$worker 'mkdir /tmp/rabbitmq-var/test-$TEST_INDEX-attempt-$n ; cp -R /tmp/rabbitmq-server/var/* /tmp/rabbitmq-var/test-$TEST_INDEX-attempt-$n'"
	done
    ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "$SOURCE_AND_CD ; head -n 50 store/current/jepsen.log"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "$SOURCE_AND_CD ; tail -n 100 store/current/jepsen.log"

	if [ $run_exit_code -eq 0 ]; then
	    # run returned 0, but checking the logs for some corner cases
	    ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "$SOURCE_AND_CD ; grep -q 'Set was never read' ./store/latest/jepsen.log"
	    if [ $? -eq 0 ]; then
	        # Could not read the final data structure, see if we can retry
			if [ $n -ge 3 ]; then
		        # It was the last attempt
		        echo "Test $TEST_INDEX / $TESTS_COUNT failed several times with unexpected errors or inappropriate results, moving on"
		        # We mark this run as failed
		        failure=true
		        break
		    else
		        echo "Final data structure could not be read, retrying"
		    fi
		else
		    # run succeeded, moving on
		    break
		fi
	else
		echo "Test has failed, checking whether it is an unexpected error or not"
		ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "$SOURCE_AND_CD ; grep -q 'Analysis invalid' ./store/latest/jepsen.log"
		if [ $? -eq 0 ]; then
			echo "Test $TEST_INDEX / $TESTS_COUNT failed, moving on"
			failure=true
			break
		else
		    if [ $n -ge 3 ]; then
		        # It was the last attempt
		        echo "Test $TEST_INDEX / $TESTS_COUNT failed several times with unexpected errors, moving on"
		        # We mark this run as failed
		        failure=true
		        break
		    fi
		    echo "Unexpected error, retrying"
		fi
	fi
	n=$[$n+1]
  done
  ((TEST_INDEX++))
done

the_date=$(date '+%Y%m%d-%H%M%S')
archive_name="qq-jepsen-$RABBITMQ_BRANCH-$the_date-jepsen-logs"
archive_file="$archive_name.tar.gz"
ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$CONTROLLER_IP "$SOURCE_AND_CD ; tar -zcf - store --transform='s/^store/${archive_name}/'" > $archive_file
gsutil cp $archive_file gs://jepsen-tests-logs

echo "Logs Download Link: https://storage.cloud.google.com/jepsen-tests-logs/$archive_file"

WORKER_INDEX=0
for worker_ip in "${WORKERS_IP[@]}"
do
	var_archive_name="qq-jepsen-$RABBITMQ_BRANCH-$the_date-var-node-$WORKER_INDEX"
	var_archive_file="$var_archive_name.tar.gz"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot jepsen-bot@$worker_ip \
		"cd /tmp ; tar -zcf - rabbitmq-var --transform='s/^rabbitmq-var/${var_archive_name}/'" > $var_archive_file
	gsutil cp $var_archive_file gs://jepsen-tests-logs
	((WORKER_INDEX++))
done

echo "Logs & Data Download Link: https://console.cloud.google.com/storage/browser/jepsen-tests-logs?prefix=qq-jepsen-$RABBITMQ_BRANCH-$the_date"

if [ "$failure" = true ]; then
  exit 1
else
  exit 0
fi

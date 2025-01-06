#!/bin/bash

set -ex

RABBITMQ_BRANCH=$(ci/extract-rabbitmq-branch-from-binary-url.sh $BINARY_URL)
ARCHIVE=$(basename $BINARY_URL)

set +x
# create SSH key that will be used to connect to the Jepsen VMs
ssh-keygen -t rsa -m pem -f jepsen-bot -C jepsen-bot -N ''

mkdir -p ~/.aws
echo "$AWS_CONFIG" > ~/.aws/config
echo "$AWS_CREDENTIALS" > ~/.aws/credentials
set -x

# destroy existing resources in they already exist
set +e
AWS_TAG="JepsenQq$RABBITMQ_BRANCH"
AWS_KEY_NAME="jepsen-qq-$RABBITMQ_BRANCH-key"
aws ec2 terminate-instances --no-cli-pager --instance-ids $(aws ec2 describe-instances --query 'Reservations[].Instances[].InstanceId' --filters "Name=tag:Name,Values=$AWS_TAG" --output text)
aws ec2 delete-key-pair --no-cli-pager --key-name $AWS_KEY_NAME
set -e

# copy Terraform configuration file in current directory
cp ./ci/rabbitmq-jepsen-aws.tf .

# initialize Terraform (get plugins and so)
terraform init

# spin up the VMs
terraform apply -auto-approve -var="rabbitmq_branch=$RABBITMQ_BRANCH"

mkdir terraform-state
# save Terraform state and configuration to clean up even if the task fails
# this ensures the VMs will be destroyed whatever the outcomes of the job
cp jepsen-bot terraform-state
cp jepsen-bot.pub terraform-state
cp -r .terraform terraform-state
cp terraform.tfstate terraform-state
cp rabbitmq-jepsen-aws.tf terraform-state

# get the Jepsen controller IP
CONTROLLER_IP=$(terraform output -raw controller_ip)
JEPSEN_USER="admin"
# install dependencies and compile the RA KV store on the Jepsen controller
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP 'bash -s' < ci/provision-jepsen-controller.sh
# makes sure the Jepsen test command line works
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "source ~/.profile ; cd ~/jepsen/rabbitmq/ ; lein run test --help"
# download latest alpha on Jepsen controller
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "wget $BINARY_URL"


# add the worker hostnames to /etc/hosts
WORKERS_HOSTS_ENTRIES=$(terraform output -raw workers_hosts_entries)
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "echo '$WORKERS_HOSTS_ENTRIES' | sudo tee --append /etc/hosts"

# copy the alpha on all the Jepsen workers
WORKERS=( $(terraform output -raw workers_hostname) )
for worker in "${WORKERS[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot \
    $JEPSEN_USER@$CONTROLLER_IP \
    "scp -o StrictHostKeyChecking=no -i ~/jepsen-bot ~/${ARCHIVE} $JEPSEN_USER@$worker:/tmp/${ARCHIVE}"
done

# create directory for broker /var directory archiving on all the Jepsen workers
WORKERS_IP=( $(terraform output -raw workers_ip) )
for worker_ip in "${WORKERS_IP[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip "mkdir /tmp/rabbitmq-var"
done

# miscellaneous configuration on all the Jepsen workers
for worker_ip in "${WORKERS_IP[@]}"
do
  ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip "sudo apt-get update"
  ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip "echo '$WORKERS_HOSTS_ENTRIES' | sudo tee --append /etc/hosts"
done

# build up some fixed parameters for the Jepsen tests
NODES=""
for worker in "${WORKERS[@]}"
do
  NODES="$NODES --node $worker"
done

SOURCE_AND_CD="source ~/.profile ; cd ~/jepsen/rabbitmq"
CREDENTIALS="--username $JEPSEN_USER --ssh-private-key ~/jepsen-bot"
ARCHIVE_OPTION="--archive-url file:///tmp/${ARCHIVE}"

JEPSEN_TESTS_PARAMETERS=(
	"--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition random-partition-halves --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-halves --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-majorities-ring --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-random-node --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition random-partition-halves --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-halves --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-majorities-ring --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-random-node --net-ticktime 15 --consumer-type mixed"
	"--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-random-node --net-ticktime 15 --consumer-type asynchronous"
	"--time-limit 180 --time-before-partition 20 --partition-duration 10 --network-partition partition-random-node --net-ticktime 15 --consumer-type polling"
	   "--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition random-partition-halves --net-ticktime 15 --consumer-type mixed --dead-letter true"
	"--time-limit 180 --time-before-partition 20 --partition-duration 30 --network-partition partition-halves --net-ticktime 15 --consumer-type mixed --dead-letter true"
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
    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; lein run test $NODES $CREDENTIALS $jepsen_test_parameter $ARCHIVE_OPTION" >/dev/null
    run_exit_code=$?
	for worker in "${WORKERS[@]}"
	do
    ssh -o StrictHostKeyChecking=no -i jepsen-bot \
        $JEPSEN_USER@$CONTROLLER_IP \
        "ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot $JEPSEN_USER@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl list_connections'"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot \
        $JEPSEN_USER@$CONTROLLER_IP \
        "ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot $JEPSEN_USER@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl list_consumers'"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		$JEPSEN_USER@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot $JEPSEN_USER@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl list_queues'"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		$JEPSEN_USER@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot $JEPSEN_USER@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmq-queues quorum_status jepsen.queue'"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		$JEPSEN_USER@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot $JEPSEN_USER@$worker 'sudo /tmp/rabbitmq-server/sbin/rabbitmqctl cluster_status'"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot \
		$JEPSEN_USER@$CONTROLLER_IP \
		"ssh -o StrictHostKeyChecking=no -i ~/jepsen-bot $JEPSEN_USER@$worker 'mkdir /tmp/rabbitmq-var/test-$TEST_INDEX-attempt-$n ; cp -R /tmp/rabbitmq-server/var/* /tmp/rabbitmq-var/test-$TEST_INDEX-attempt-$n'"
	done
    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; head -n 50 store/current/jepsen.log"
    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; tail -n 100 store/current/jepsen.log"

	if [ $run_exit_code -eq 0 ]; then
	    # run returned 0, but checking the logs for some corner cases
	    ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; grep -q 'Set was never read' ./store/latest/jepsen.log"
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
		ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; grep -q 'Analysis invalid' ./store/latest/jepsen.log"
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
ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$CONTROLLER_IP "$SOURCE_AND_CD ; tar -zcf - store --transform='s/^store/${archive_name}/'" > $archive_file
aws s3 cp $archive_file s3://jepsen-tests-logs/ --quiet

WORKER_INDEX=0
for worker_ip in "${WORKERS_IP[@]}"
do
	var_archive_name="qq-jepsen-$RABBITMQ_BRANCH-$the_date-var-node-$WORKER_INDEX"
	var_archive_file="$var_archive_name.tar.gz"
	ssh -o StrictHostKeyChecking=no -i jepsen-bot $JEPSEN_USER@$worker_ip \
		"cd /tmp ; tar -zcf - rabbitmq-var --transform='s/^rabbitmq-var/${var_archive_name}/'" > $var_archive_file
  aws s3 cp $var_archive_file s3://jepsen-tests-logs/ --quiet
	((WORKER_INDEX++))
done

echo "Download logs: aws s3 cp s3://jepsen-tests-logs/ . --recursive --exclude '*' --include 'qq-jepsen-$RABBITMQ_BRANCH-$the_date*'"

if [ "$failure" = true ]; then
  exit 1
else
  exit 0
fi

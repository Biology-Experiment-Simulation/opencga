#!/bin/bash

set -x
set -e
export DEBIAN_FRONTEND='noninteractive'
# Wait for network
sleep 5

## Nacho (6/12/2018)

MY_ID=$(($1+1))
SUBNET_PREFIX=$2
IP_FIRST=$3
ZK_HOSTS_NUM=$4
SOLR_VERSION=$5

DOCKER_NAME=opencga-solr-${SOLR_VERSION}

# Create docker

# Add OpenCGA Configuration Set 
tar zxfv OpenCGAConfSet_1.4.x.tar.gz

# create a directory to store the server/solr directory
mkdir /opt/solr-volume

# make sure its host owner matches the container's solr user
sudo chown 8983:8983 /opt/solr-volume

# copy the solr directory from a temporary container to the volume
docker run -it --rm -v /opt/solr-volume:/target solr:${SOLR_VERSION} cp -r server/solr /target/

cp -r OpenCGAConfSet /opt/solr-volume/solr/configsets/OpenCGAConfSet-1.4.x

docker run  solr:${SOLR_VERSION}  cat /opt/solr/bin/solr.in.sh > /opt/solr.in.sh

ZK_CLI=
if [[ $ZK_HOSTS_NUM -gt 0 ]]; then

    i=0
    while [ $i -lt $ZK_HOSTS_NUM ]
    do
        ZK_HOST=${ZK_HOST},${SUBNET_PREFIX}$(($i+$IP_FIRST))
        i=$(($i+1))
    done

    # Remove leading comma
    ZK_HOST=`echo $ZK_HOST | cut -c 2-`

    sed -i -e 's/#ZK_HOST=.*/ZK_HOST='$ZK_HOST'/' solr.in.sh
else
    ZK_CLI="-z localhost:9983"
fi

## Ensure always using cloud mode, even for the single server configurations.
echo 'SOLR_MODE="solrcloud"' >> solr.in.sh

docker run --name ${DOCKER_NAME} --restart always -p 8983:8983 -t -v /opt/solr-volume/solr:/opt/solr/server/solr -v /opt/solr.in.sh:/opt/solr/bin/solr.in.sh   solr:${SOLR_VERSION} docker-entrypoint.sh solr-foreground && /opt/solr/bin/solr zk upconfig -n OpenCGAConfSet-1.4.x -d /opt/solr/server/solr/configsets/OpenCGAConfSet-1.4.x $ZK_CLI
